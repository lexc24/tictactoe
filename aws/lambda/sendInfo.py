import json
import utility
import boto3

def lambda_handler(event, context):
    # Extract connection information
    connection_id = event['requestContext']['connectionId']
    domain_name = event['requestContext']['domainName']
    stage = event['requestContext']['stage']


    utility.send_message(connection_id, domain_name, stage, {
        "message": "Hello from Lambda!",
        "connectionId": connection_id
    })

    return {
        'statusCode': 200,
        'body': json.dumps('Hello from Lambda!')
    }
