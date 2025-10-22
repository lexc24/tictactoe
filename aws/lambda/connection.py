import uuid
import utility
import boto3
from boto3.dynamodb.conditions import Key
from datetime import datetime

from botocore.exceptions import ClientError  # <-- ADD THIS
client = boto3.client('lambda')

def handler(event, context):
    """
    This function is triggered on the $connect route. 
    It creates a user entry in DynamoDB with:
      - connectionId: from event
      - sessionId: newly generated
      - status: "inactive"
    Returns a successful response if everything goes well.
    """
    utility.logger.info(f"Event: {event}")

    # Extract connection information
    connection_id = event['requestContext']['connectionId']
    domain_name = event['requestContext']['domainName']
    stage = event['requestContext']['stage']
    
    utility.logger.info(f"domain_name from env: {domain_name}")
    utility.logger.info(f"stage from env: {stage}")
    timestamp = datetime.utcnow().isoformat()

    try:
        # Generate a sessionId (UUID)
        session_id = str(uuid.uuid4())

        # Create the user in DynamoDB
        utility.table.put_item(
            Item={
                'connectionId': connection_id,
                'sessionId': session_id,
                'status': 'inactive',
                'joinedAt': timestamp
            },
            ConditionExpression="attribute_not_exists(connectionId)"
        )
        
        #calls joinQueue lambda
        utility.logger.info(f"New connection: {connection_id} with sessionId: {session_id}")
        marker = get_marker_for_new_active_userr()
        client.invoke( 
            FunctionName = 'arn:aws:lambda:us-east-1:471112937699:function:joinQueue',
            InvocationType = 'Event',
            Payload = utility.json.dumps({
                "requestContext": 
                {
                "connectionId": connection_id,
                "sessionId": session_id,
                "domainName": domain_name,
                "stage": stage,
                "marker":marker
            }})
        )
        #calls send info lambda to send connectionID
        client.invoke(
            FunctionName = 'arn:aws:lambda:us-east-1:471112937699:function:sendInfo',
            InvocationType = 'Event',
            Payload = utility.json.dumps({
                "requestContext":
                {
                "connectionId": connection_id,
                "sessionId": session_id,
                "domainName": domain_name,
                "stage": stage
            }})
        )

        return {
            'statusCode': 200,
            'body': utility.json.dumps({"message": "Connected successfully"})
        }

    except ClientError as e:
        # If the item already exists or another DynamoDB error occurred
        utility.logger.error(f"DynamoDB error on connection: {str(e)}")
        return {
            'statusCode': 500,
            'body': utility.json.dumps({"error": str(e)})
        }
def get_marker_for_new_active_userr():
    """
    Checks existing active users to decide if the new user is 'X' or 'O'.
    If 0 active => 'X'.
    If 1 active => whichever marker is NOT in use by the existing user.
    """
    response = utility.table.query(
        IndexName="statusIndex",
        KeyConditionExpression=Key('status').eq('active')
    )
    active_players = response.get('Items', [])
    utility.logger.info("Current active players: " + str(active_players))

    if len(active_players) == 0:
        return "X"
    else:
        existing_marker = active_players[0].get("marker", "")
        utility.logger.info("Existing marker: " + existing_marker)
        return "O" if existing_marker == "X" else "X"


