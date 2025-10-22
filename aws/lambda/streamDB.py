import os
import json
import utility  # Your existing layer
from boto3.dynamodb.types import TypeDeserializer
from botocore.exceptions import ClientError

deserializer = TypeDeserializer()

def lambda_handler(event, context):
    """
    Triggered by DynamoDB Streams. After processing record changes,
    we fetch the current queue and emit a 'queueUpdate' message
    to all connected clients so their UI updates in real-time.
    """

    utility.logger.info(f"Received DynamoDB stream event: {json.dumps(event)}")

    # 1) Process the stream records (INSERT, MODIFY, REMOVE)
    for record in event["Records"]:
        event_name = record["eventName"]  # e.g., "INSERT", "MODIFY", "REMOVE"
        utility.logger.info(f"Processing event type: {event_name}")

        if event_name in ["INSERT", "MODIFY"]:
            new_image = record["dynamodb"]["NewImage"]
            item = _dynamodb_record_to_dict(new_image)
            utility.logger.info(f"New/modified item: {item}")
        elif event_name == "REMOVE":
            old_image = record["dynamodb"]["OldImage"]
            item = _dynamodb_record_to_dict(old_image)
            utility.logger.info(f"Removed item: {item}")

    # 2) Build or fetch the live queue data
    updated_queue = _get_live_queue_data()  # Return both active/inactive

    # 3) Construct the message payload for the front end
    message_payload = {
        "action": "queueUpdate",
        "data": updated_queue
    }

    # 4) Emit message to all connected clients
    #    (You might store domainName/stage in environment variables or a separate table)
    utility.logger.info("entering emit mesagge to all")

    _emit_message_to_all(message_payload)
    utility.logger.info("left emit mesagge to all")

    return {"statusCode": 200, "body": json.dumps({"message": "Stream processed"})}


def _dynamodb_record_to_dict(dynamo_record):
    """
    Convert a DynamoDB record to a Python dict.
    Uses boto3's TypeDeserializer to handle various data types.
    """
    python_dict = {}
    for k, v in dynamo_record.items():
        python_dict[k] = deserializer.deserialize(v)
    return python_dict


def _get_live_queue_data():
    """
    Example: Return all items from the table sorted by joinedAt ascending.
    This ensures you show both active and inactive users in a single sorted list.
    """
    
    response = utility.table.scan()
    all_items = response.get("Items", [])

    # Sort them by joinedAt if it exists, otherwise treat them as earliest
    from datetime import datetime
    def parse_joined_at(item):
        joined_str = item.get("joinedAt", "")
        try:
            # Remove trailing 'Z' if present for fromisoformat
            joined_str = joined_str.replace("Z", "")
            return datetime.fromisoformat(joined_str)
        except:
            return datetime.min

    sorted_items = sorted(all_items, key=parse_joined_at)
    return sorted_items


def _emit_message_to_all(payload):
    """
    Scans the table for all connectionIds (or just active connections)
    and sends them the payload via utility.send_message().
    """
    # If you track all connections in the same table, do something like:
    utility.logger.info("get entered emit mesagge to all")

    response = utility.table.scan()
    items = response.get("Items", [])

    # For each item that has a connectionId, send the message
    domain_name = "wqritmruc9.execute-api.us-east-1.amazonaws.com"
    stage = os.environ.get("WEBSOCKET_STAGE", "production")
    utility.logger.info(f"domain_name from env: {domain_name}")
    utility.logger.info(f"stage from env: {stage}")

    for item in items:
        if "connectionId" in item:
            conn_id = item["connectionId"]
            try:
                utility.send_message(conn_id, domain_name, stage, payload)
            except ClientError as e:
                utility.logger.error(f"Failed to send message to {conn_id}: {e}")
