import utility
from botocore.exceptions import ClientError  # <-- ADD THIS

def handler(event, context):
    """
    This function is triggered on the $disconnect route.
    We remove the user from the table so they are no longer in queue or game.
    Then we fill the active slots if needed.
    """
    utility.logger.info(f"Event: {event}")

    connection_id = event['requestContext']['connectionId']
    domain_name = event['requestContext']['domainName']
    stage = event['requestContext']['stage']

    try:
        # Get the user's status before removing them
        user_item = utility.table.get_item(Key={'connectionId': connection_id}).get('Item')
        if not user_item:
            utility.logger.warning(f"No item found for connectionId {connection_id}. Nothing to remove.")
            return {'statusCode': 200, 'body': utility.json.dumps({"message": "No user record found."})}

        user_status = user_item['status']

        # Delete the user from the table
        utility.table.delete_item(Key={'connectionId': connection_id})
        utility.logger.info(f"User {connection_id} removed from table.")

        # If they were active, we might have only 1 or 0 active users left, so fill the slots.
        if user_status == 'active':
            fill_active_slots()

        return {
            'statusCode': 200,
            'body': utility.json.dumps({"message": f"Disconnected {connection_id}"})
        }

    except ClientError as e:
        utility.logger.error(f"Error during disconnect: {str(e)}")
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

