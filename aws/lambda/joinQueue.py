import utility
from botocore.exceptions import ClientError  # <-- ADD THIS
from datetime import datetime


def handler(event, context):
    """
    This functions handles when a connected user is joining the Queue
    
    Steps:
      1) Count how many are 'active'.
      2) If < 2, set user to 'active'.
      3) Otherwise, set user to 'inactive' with a joinedAt timestamp.
    """
    utility.logger.info(f"Event: {event}")

    connection_id = event['requestContext']['connectionId']
    domain_name = event['requestContext']['domainName']
    stage = event['requestContext']['stage']

    try:
        active_count = utility.count_active_players()
        if active_count < 2:
            # Mark user as active and get marker for new user
            marker = utility.get_marker_for_new_active_user()
            utility.update_user(connection_id,'active', marker)

            message = {
                "action": "joinQueue",
                "status": "active",
                "marker": marker,
                "message": f"You are now active in the game with marker '{marker}'."
            }
            utility.logger.info(f"User {connection_id} set to active with marker {marker}")
        else:
            # Mark user as inactive and update joinedAt
            # (Preserving queue order)
            new_timestamp = datetime.utcnow().isoformat()
            marker = None  # Explicitly define marker for inactive users

            utility.update_user(connection_id,'inactive', marker,new_timestamp)
            message = {
                "action": "joinQueue",
                "status": "inactive",
                "message": "You have joined the queue. Please wait for your turn."
            }
            utility.logger.info(f"User {connection_id} is queued (inactive)")

        # # Send a message back to the user
        # utility.send_message(connection_id, domain_name, stage, message)

        return {
            'statusCode': 200,
            'body': utility.json.dumps({"message": message})
        }

    except ClientError as e:
        utility.logger.error(f"Error during joinQueue: {str(e)}")
        return {
            'statusCode': 500,
            'body': utility.json.dumps({"error": str(e)})
        }

