import json
import utility
import boto3
from boto3.dynamodb.conditions import Key
from botocore.exceptions import ClientError  # Make sure to import ClientError

def lambda_handler(event, context):
    utility.logger.info(f"Event: {event}")

    if "username" in event:
        try:
            connection_id = event["connectionId"]
            username = event["username"]
            
            # Update the existing DynamoDB item with the username.
            response = utility.table.update_item(
                Key={'connectionId': connection_id},
                UpdateExpression="set username = :u",
                ExpressionAttributeValues={":u": username},
                ReturnValues="UPDATED_NEW"
            )
            return response
        except ClientError as e:
            # Log or handle the ClientError as needed.
            utility.logger.error(f"ClientError updating connection: {e}")
            raise e
        except Exception as e:
            utility.logger.error(f"Error parsing body: {e}")
            return {
                'statusCode': 400,
                'body': json.dumps({"error": "Error parsing body"})
            }

    return {
        'statusCode': 200,
        'body': json.dumps('Hello from Lambda!')
    }
