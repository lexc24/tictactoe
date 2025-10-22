import utility
from boto3.dynamodb.conditions import Key
from botocore.exceptions import ClientError  # <-- ADD THIS
from datetime import datetime

def handler(event, context):
    """
    This function handles when the TTT game is over and the loser must change his status and rejoin the queue
    
    Steps:
     1) Mark the loser as inactive (return them to queue).
     2) Check if there's a waiting user who is inactive. If there's space, promote them to active.
     3) The winner remains active (we assume the client doesn't handle that here).
    """
    utility.logger.info(f"Event: {event}")

    connection_id = event['requestContext']['connectionId']
    domain_name = event['requestContext']['domainName']
    stage = event['requestContext']['stage']

    body = utility.json.loads(event.get('body', '{}')) 
    loser_id = body.get('loserId')

    # When the game ends in a Tie 
    if not loser_id:
        utility.logger.error("No loserId provided in the gameOver event.")
        return {
            'statusCode': 400,
            'body': utility.json.dumps({"error": "Missing loserId parameter"})
        }

    try:
        # Step 1: Mark the loser as inactive
        new_timestamp = datetime.utcnow().isoformat()
        utility.update_user(loser_id,'inactive',"",new_timestamp)
        utility.logger.info(f"Loser {loser_id} marked as inactive and re-queued")

        # Step 2: Ensure we have 2 active users if possible
        fill_active_slots()

        # Respond to the user who triggered the event (often the winner or the game controller)
        # utility.send_message(connection_id, domain_name, stage, {
        #     "action": "gameOver",
        #     "message": f"Loser {loser_id} has been re-queued and next user is activated if available."
        # })

        return {
            'statusCode': 200,
            'body': utility.json.dumps({"message": "gameOver processed"})
        }

    except ClientError as e:
        utility.logger.error(f"Error during gameOver: {str(e)}")
        return {
            'statusCode': 500,
            'body': utility.json.dumps({"error": str(e)})
        }


def fill_active_slots():
    """
    Ensure exactly 2 users have status='active' if possible.
    1) Count how many are active.
    2) If less than 2, query the GSI for users with status='inactive' ordered by joinedAt ascending.
    3) Promote as many as needed to reach 2 active users, in queue order.
    """
    active_count = utility.count_active_players()
    if active_count >= 2:
        return  # We already have enough active players

    needed = 2 - active_count

    # Query the GSI for 'inactive' users, sorted by joinedAt ascending
    response = utility.table.query(
        IndexName="statusIndex",                       # The name of your GSI
        KeyConditionExpression=Key('status').eq('inactive'),
        ScanIndexForward=True,                         # True => ascending order
        Limit=needed                                   # We only need 'needed' items
    )
    inactive_users = response.get("Items", [])

    for user in inactive_users:
        connection_id = user['connectionId']
        try:
            # Promote to active
            new_marker = utility.get_marker_for_new_active_user()
            utility.update_user(connection_id, 'active',new_marker)
            utility.logging.info(f"Promoted user {connection_id} to active.")
        except ClientError as e:
            utility.logger.error(f"Failed to promote user {connection_id} to active: {str(e)}")
            




