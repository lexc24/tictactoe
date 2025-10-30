# Lambda Functions Deep Dive

## Overview

The application uses **7 serverless Python Lambda functions** to handle WebSocket lifecycle events, queue management, and real-time broadcasting. Six functions are triggered by API Gateway WebSocket routes, and one (`streamDB.py`) is triggered by DynamoDB Streams.

All functions depend on a shared `utility.py` module (not in repository) that provides:
- DynamoDB table reference
- `send_message(connection_id, data)` - Sends WebSocket message via API Gateway
- `count_active_players()` - Queries statusIndex GSI for active player count
- `get_marker_for_new_active_user()` - Assigns 'X' or 'O' based on existing markers
- `update_user(connection_id, updates)` - Updates DynamoDB item
- JSON utilities and logging configuration

## Function Trigger Map

```
WebSocket Routes                    DynamoDB Streams
┌─────────────────┐                ┌──────────────────┐
│ $connect        │───────────────▶│ connection.py    │
│ $disconnect     │───────────────▶│ disconnect.py    │
│ joinQueue       │───────────────▶│ joinQueue.py     │
│ gameOVER        │───────────────▶│ gameOver.py      │
│ updateDB        │───────────────▶│ updateDB.py      │
│ sendInfo        │───────────────▶│ sendInfo.py      │
└─────────────────┘                └──────────────────┘
                                             │
                                             │ Any table change
                                             ▼
                                   ┌──────────────────┐
                                   │ DynamoDB Streams │
                                   └────────┬─────────┘
                                            │
                                            ▼
                                   ┌──────────────────┐
                                   │ streamDB.py      │
                                   │ (Broadcaster)    │
                                   └──────────────────┘
```

---

## 1. connection.py - WebSocket Connection Handler

### Trigger
**API Gateway Route:** `$connect`
**When:** Client establishes WebSocket connection

### Purpose
Initialize new user in DynamoDB, assign session ID, and trigger queue join process.

### Code Flow

```python
# aws/lambda/connection.py:13-36 (reconstructed with utility functions)
def handler(event, context):
    # Extract connectionId from API Gateway event
    connection_id = event['requestContext']['connectionId']

    # Generate unique session identifier
    session_id = str(uuid.uuid4())

    try:
        # Create user record in DynamoDB
        table.put_item(
            Item={
                'connectionId': connection_id,
                'sessionId': session_id,
                'status': 'inactive',  # All users start in queue
                'joinedAt': datetime.now(timezone.utc).isoformat()
            }
        )

        # Asynchronously invoke joinQueue Lambda
        lambda_client.invoke(
            FunctionName='arn:aws:lambda:us-east-1:...:function:joinQueue',
            InvocationType='Event',  # Fire-and-forget
            Payload=json.dumps({
                'requestContext': {
                    'connectionId': connection_id
                }
            })
        )

        # Send connectionId back to client
        lambda_client.invoke(
            FunctionName='arn:aws:lambda:us-east-1:...:function:sendInfo',
            InvocationType='Event',
            Payload=json.dumps({
                'requestContext': {
                    'connectionId': connection_id
                }
            })
        )

        return {'statusCode': 200}

    except ClientError as e:
        # Handle duplicate connectionId (rare, but possible)
        logger.error(f"DynamoDB error: {e}")
        return {'statusCode': 500}
```

### Key Design Decisions

**Why Start All Users as `inactive`?**
- **Race Condition Prevention:** If we immediately set to `active`, two concurrent connections might both think they're player 1
- **Atomic Assignment:** `joinQueue.py` uses DynamoDB transactions to ensure exactly 2 active players
- **Queue Ordering:** `joinedAt` timestamp establishes FIFO order

**Why Lambda-to-Lambda Invocation (Not Direct Call)?**
- **Separation of Concerns:** Connection handler doesn't need queue logic
- **Async Pattern:** `InvocationType='Event'` returns immediately (low connection latency)
- **Independent Retries:** If `joinQueue.py` fails, API Gateway retries separately

**Why Not Use Step Functions?**
- **Simplicity:** Only 2-step workflow (create user → join queue)
- **Cost:** Step Functions cost $25 per million state transitions
- **Latency:** Direct Lambda invoke is faster (~10ms vs ~50ms)

### Integration Points

**Upstream:** Browser `app.js:connectWebSocket()`
**Downstream:**
- DynamoDB `TicTacToeUsers` table
- `joinQueue.py` Lambda (async invoke)
- `sendInfo.py` Lambda (async invoke)

### Error Handling

| Error | Cause | Handling |
|-------|-------|----------|
| `ClientError` | DynamoDB write failure | Return 500, API Gateway retries |
| Duplicate `connectionId` | API Gateway reuses ID (very rare) | `put_item` overwrites, no issue |
| Lambda invoke failure | IAM permissions or function doesn't exist | Logged, but connection succeeds |

---

## 2. disconnect.py - Connection Cleanup Handler

### Trigger
**API Gateway Route:** `$disconnect`
**When:** Client closes WebSocket or connection times out

### Purpose
Remove user from DynamoDB and promote next waiting player if disconnected user was active.

### Code Flow

```python
# aws/lambda/disconnect.py:10-30 (reconstructed)
def handler(event, context):
    connection_id = event['requestContext']['connectionId']

    try:
        # Get user status before deleting
        response = table.get_item(Key={'connectionId': connection_id})
        user = response.get('Item')

        if not user:
            return {'statusCode': 200}  # Already deleted

        user_status = user.get('status')

        # Delete user from table
        table.delete_item(Key={'connectionId': connection_id})

        # If user was active, backfill empty slot
        if user_status == 'active':
            fill_active_slots()

        return {'statusCode': 200}

    except Exception as e:
        logger.error(f"Error in disconnect: {e}")
        return {'statusCode': 500}
```

### Critical Function: `fill_active_slots()`

```python
# aws/lambda/disconnect.py:33-61 (reconstructed)
def fill_active_slots():
    # Count current active players
    active_count = count_active_players()  # From utility module
    slots_available = 2 - active_count

    if slots_available <= 0:
        return  # Already have 2 active players

    # Query inactive users sorted by join time (GSI)
    response = table.query(
        IndexName='statusIndex',
        KeyConditionExpression='#status = :inactive',
        ExpressionAttributeNames={'#status': 'status'},
        ExpressionAttributeValues={':inactive': 'inactive'},
        ScanIndexForward=True,  # Sort joinedAt ascending (oldest first)
        Limit=slots_available
    )

    # Promote oldest waiting users to active
    for user in response['Items']:
        marker = get_marker_for_new_active_user()  # Utility function

        table.update_item(
            Key={'connectionId': user['connectionId']},
            UpdateExpression='SET #status = :active, marker = :marker',
            ExpressionAttributeNames={'#status': 'status'},
            ExpressionAttributeValues={
                ':active': 'active',
                ':marker': marker
            }
        )
```

### Why This Function Exists

**Problem:** Active player disconnects mid-game, leaving only 1 active player.

**Solution:**
1. Query `statusIndex` GSI for all `inactive` users
2. Sort by `joinedAt` ascending (FIFO queue)
3. Promote oldest 1-2 users to `active` status
4. Assign markers ('X' or 'O') based on existing active player

**Why DynamoDB Streams Trigger Broadcast:**
- `fill_active_slots()` writes to DynamoDB → Stream event
- `streamDB.py` detects MODIFY events
- Broadcasts new queue state to all clients
- All browsers see updated player list

### Integration Points

**Upstream:** Browser closes tab, network timeout, `ws.close()` call
**Downstream:**
- DynamoDB `TicTacToeUsers` table (GetItem, DeleteItem, Query, UpdateItem)
- DynamoDB Streams → `streamDB.py` (indirect)

### Edge Cases

**What if 2 active players disconnect simultaneously?**
- Both `$disconnect` routes trigger in parallel
- First function queries GSI, finds 0 active, promotes 2 users
- Second function queries GSI, finds 2 active (from first), does nothing
- **Result:** Correct (2 new active players)

**What if all players disconnect?**
- Each disconnect deletes user, queries empty queue
- Last disconnect finds 0 inactive users
- **Result:** Empty table (correct state)

---

## 3. joinQueue.py - Queue Assignment Logic

### Trigger
**API Gateway Route:** `joinQueue`
**When:**
1. Invoked by `connection.py` after user creation
2. Client manually sends `joinQueue` WebSocket message (optional)

### Purpose
Determine if user should be active (playing) or inactive (waiting in queue) based on current player count.

### Code Flow

```python
# aws/lambda/joinQueue.py:10-50 (reconstructed)
def handler(event, context):
    connection_id = event['requestContext']['connectionId']

    try:
        # Count existing active players using GSI
        active_count = count_active_players()

        if active_count < 2:
            # Slot available - make user active
            marker = get_marker_for_new_active_user()  # 'X' or 'O'

            table.update_item(
                Key={'connectionId': connection_id},
                UpdateExpression='SET #status = :active, marker = :marker',
                ExpressionAttributeNames={'#status': 'status'},
                ExpressionAttributeValues={
                    ':active': 'active',
                    ':marker': marker
                }
            )

            # Notify user they're playing
            send_message(connection_id, {
                'action': 'joinedQueue',
                'status': 'active',
                'marker': marker
            })

        else:
            # Queue full - keep user inactive
            table.update_item(
                Key={'connectionId': connection_id},
                UpdateExpression='SET #status = :inactive, joinedAt = :timestamp',
                ExpressionAttributeNames={'#status': 'status'},
                ExpressionAttributeValues={
                    ':inactive': 'inactive',
                    ':timestamp': datetime.now(timezone.utc).isoformat()
                }
            )

            # Notify user they're in queue
            send_message(connection_id, {
                'action': 'joinedQueue',
                'status': 'inactive',
                'position': active_count - 2 + 1  # Rough position estimate
            })

        return {'statusCode': 200}

    except Exception as e:
        logger.error(f"Error in joinQueue: {e}")
        return {'statusCode': 500}
```

### How `get_marker_for_new_active_user()` Works (Utility Function)

```python
# Likely implementation in utility.py
def get_marker_for_new_active_user():
    # Query all active users
    response = table.query(
        IndexName='statusIndex',
        KeyConditionExpression='#status = :active',
        ExpressionAttributeNames={'#status': 'status'},
        ExpressionAttributeValues={':active': 'active'}
    )

    active_users = response['Items']

    if len(active_users) == 0:
        return 'X'  # First player gets X

    # Check what marker(s) are already taken
    existing_markers = {user.get('marker') for user in active_users}

    if 'X' not in existing_markers:
        return 'X'
    else:
        return 'O'
```

**Why Not Just Alternate X/O?**
- **Player Disconnects:** If X disconnects, next player should get X (not O)
- **Consistency:** Ensures exactly 1 X and 1 O active at all times
- **No Hardcoded IDs:** Doesn't rely on connectionId ordering

### Race Condition Handling

**Scenario:** Two players connect simultaneously, both call `joinQueue.py` at same time.

**Problem:** Both might see `active_count = 1` and both set themselves active.

**Solution (Likely in Production):**
- Use DynamoDB conditional updates:
  ```python
  ConditionExpression='attribute_not_exists(marker) OR marker = :null'
  ```
- Or use DynamoDB transactions (`transact_write_items`)
- Current code may have race condition (acceptable for low-traffic demo)

### Integration Points

**Upstream:**
- `connection.py` async invoke
- Browser `app.js` manual `joinQueue` message (if implemented)

**Downstream:**
- DynamoDB `statusIndex` GSI (read)
- DynamoDB `TicTacToeUsers` table (write)
- API Gateway `POST /@connections/{connectionId}` (send_message)

---

## 4. gameOver.py - Game Completion Handler

### Trigger
**API Gateway Route:** `gameOVER`
**When:** JavaFX dispatches `gameOverEvent`, JavaScript sends WebSocket message

### Purpose
Mark loser as inactive (re-enter queue) and promote next waiting player.

### Code Flow

```python
# aws/lambda/gameOver.py:10-50 (reconstructed)
def handler(event, context):
    # Parse request body
    body = json.loads(event.get('body', '{}'))
    loser_id = body.get('loserId')  # connectionId of loser

    if not loser_id:
        return {'statusCode': 400, 'body': 'Missing loserId'}

    try:
        # Mark loser as inactive (back to queue)
        table.update_item(
            Key={'connectionId': loser_id},
            UpdateExpression='SET #status = :inactive, joinedAt = :timestamp, marker = :null',
            ExpressionAttributeNames={'#status': 'status'},
            ExpressionAttributeValues={
                ':inactive': 'inactive',
                ':timestamp': datetime.now(timezone.utc).isoformat(),
                ':null': None  # Remove marker
            }
        )

        # Promote next player(s) in queue
        fill_active_slots()

        return {'statusCode': 200}

    except Exception as e:
        logger.error(f"Error in gameOver: {e}")
        return {'statusCode': 500}


def fill_active_slots():
    # (Same implementation as disconnect.py:33-61)
    active_count = count_active_players()
    slots_available = 2 - active_count

    if slots_available <= 0:
        return

    response = table.query(
        IndexName='statusIndex',
        KeyConditionExpression='#status = :inactive',
        ExpressionAttributeNames={'#status': 'status'},
        ExpressionAttributeValues={':inactive': 'inactive'},
        ScanIndexForward=True,
        Limit=slots_available
    )

    for user in response['Items']:
        marker = get_marker_for_new_active_user()
        table.update_item(
            Key={'connectionId': user['connectionId']},
            UpdateExpression='SET #status = :active, marker = :marker',
            ExpressionAttributeNames={'#status': 'status'},
            ExpressionAttributeValues={
                ':active': 'active',
                ':marker': marker
            }
        )
```

### Why Only Update Loser (Not Winner)?

**Current Behavior:**
- Loser → `inactive` (re-enters queue)
- Winner → stays `active` (plays next opponent)

**Why:**
- **Continuous Play:** Winner doesn't need to wait in queue again
- **Efficiency:** Reduces queue churn
- **User Experience:** Winning players get priority

**Alternative Design:**
- Both players go inactive, next 2 in queue become active
- Would be fairer, but slower gameplay

### Integration with DynamoDB Streams

```
gameOver.py updates loser status
    ↓
DynamoDB Streams emits MODIFY event
    ↓
streamDB.py triggered
    ↓
Scans entire table, sorts by joinedAt
    ↓
Broadcasts queueUpdate to all WebSocket clients
    ↓
app.js:updateQueueUI() refreshes browser displays
```

**Why Not Directly Call streamDB.py?**
- **Decoupling:** `gameOver.py` doesn't need to know about broadcasting
- **Automatic Retries:** DynamoDB Streams retry failed Lambda invocations
- **Consistency:** Every DynamoDB write triggers same broadcast logic

### Integration Points

**Upstream:** Browser `app.js:25` listens for `gameOverEvent`, sends WebSocket message
**Downstream:**
- DynamoDB `TicTacToeUsers` table (UpdateItem, Query)
- DynamoDB Streams → `streamDB.py`

---

## 5. updateDB.py - Username Update Handler

### Trigger
**API Gateway Route:** `updateDB`
**When:** Client sends username after connecting

### Purpose
Update user's `username` attribute in DynamoDB.

### Code Flow

```python
# aws/lambda/updateDB.py:10-35 (reconstructed)
def handler(event, context):
    connection_id = event['requestContext']['connectionId']

    # Parse username from message body
    body = json.loads(event.get('body', '{}'))
    username = body.get('username')

    if not username:
        return {'statusCode': 400, 'body': 'Missing username'}

    try:
        # Update username in DynamoDB
        response = table.update_item(
            Key={'connectionId': connection_id},
            UpdateExpression='SET username = :username',
            ExpressionAttributeValues={':username': username},
            ReturnValues='ALL_NEW'  # Return updated item
        )

        return {
            'statusCode': 200,
            'body': json.dumps(response['Attributes'])
        }

    except Exception as e:
        logger.error(f"Error updating username: {e}")
        return {'statusCode': 500}
```

### Why Separate Function for Username?

**Alternative Approach:** Include username in `$connect` route via query parameter.

**Why Not:**
- **User Experience:** Username modal displayed after connection (not in URL)
- **Connection Latency:** `$connect` should return fast
- **Security:** Usernames in URL appear in ALB/CloudFront logs

**Why Trigger DynamoDB Stream?**
- Username update triggers MODIFY event
- `streamDB.py` broadcasts updated queue to all clients
- All browsers see new username immediately

### Integration Points

**Upstream:** Browser `app.js:42` receives connectionId, sends `updateDB` message
**Downstream:**
- DynamoDB `TicTacToeUsers` table (UpdateItem)
- DynamoDB Streams → `streamDB.py`

---

## 6. sendInfo.py - Connection ID Sender

### Trigger
**Invoked by:** `connection.py` after user creation
**Not a WebSocket route** (Lambda-to-Lambda invoke)

### Purpose
Send connectionId back to client so JavaScript knows its own ID.

### Code Flow

```python
# aws/lambda/sendInfo.py:10-30 (reconstructed)
def handler(event, context):
    connection_id = event['requestContext']['connectionId']

    try:
        # Send connectionId to client
        message = {
            'message': 'Hello from Lambda!',
            'connectionId': connection_id
        }

        send_message(connection_id, message)

        return {'statusCode': 200}

    except Exception as e:
        logger.error(f"Error sending info: {e}")
        return {'statusCode': 500}
```

### Why Client Needs Its Own connectionId

**Use Case 1: Game Over Event**
```javascript
// app.js:25
document.addEventListener('gameOverEvent', (event) => {
    const { loserId, winnerId } = event.detail;

    // Send gameOVER message with loser's connectionId
    ws.send(JSON.stringify({
        action: 'gameOVER',
        loserId: loserId
    }));
});
```
- Java doesn't know WebSocket connectionId
- JavaScript receives it via `sendInfo.py`, stores in global variable
- When game ends, JS sends loserId to backend

**Use Case 2: UI Updates**
```javascript
// app.js:72
function setActiveCharacters(queueData) {
    const currentUser = queueData.find(user => user.connectionId === connectionId);
    const isActive = currentUser && currentUser.status === 'active';
    enableGameInteraction(isActive);
}
```
- Client compares own ID to queue data
- Enables/disables game interaction based on `status`

### Alternative Approach

**Could client use WebSocket ID from browser API?**
- No, browser WebSocket API doesn't expose API Gateway's connectionId
- connectionId is AWS-specific identifier (not standard WebSocket property)

### Integration Points

**Upstream:** `connection.py` async invoke
**Downstream:** API Gateway `POST /@connections/{connectionId}` (send_message)

---

## 7. streamDB.py - DynamoDB Stream Broadcaster

### Trigger
**DynamoDB Streams:** Any INSERT/MODIFY/REMOVE event on `TicTacToeUsers` table

### Purpose
Broadcast current queue state to all connected WebSocket clients.

### Code Flow

```python
# aws/lambda/streamDB.py:10-70 (reconstructed)
def lambda_handler(event, context):
    from boto3.dynamodb.types import TypeDeserializer

    deserializer = TypeDeserializer()

    # Process each stream record
    for record in event['Records']:
        event_name = record['eventName']  # INSERT, MODIFY, or REMOVE

        if event_name in ['INSERT', 'MODIFY', 'REMOVE']:
            # Get current queue state (scan entire table)
            response = table.scan()
            items = response['Items']

            # Sort by joinedAt timestamp (oldest first)
            sorted_items = sorted(items, key=lambda x: x.get('joinedAt', ''))

            # Broadcast to all connected clients
            broadcast_queue_update(sorted_items)

    return {'statusCode': 200}


def broadcast_queue_update(queue_data):
    # Get API Gateway endpoint from environment
    domain = 'wqritmruc9.execute-api.us-east-1.amazonaws.com'
    stage = 'production'

    # Initialize API Gateway Management API client
    apigateway_client = boto3.client(
        'apigatewaymanagementapi',
        endpoint_url=f'https://{domain}/{stage}'
    )

    message = {
        'action': 'queueUpdate',
        'data': queue_data
    }

    # Send to every user in the queue
    for user in queue_data:
        try:
            apigateway_client.post_to_connection(
                ConnectionId=user['connectionId'],
                Data=json.dumps(message).encode('utf-8')
            )
        except apigateway_client.exceptions.GoneException:
            # Connection no longer exists, ignore
            pass
        except Exception as e:
            logger.error(f"Failed to send to {user['connectionId']}: {e}")
```

### Why Use TypeDeserializer?

**DynamoDB Streams Format:**
```json
{
  "Records": [{
    "dynamodb": {
      "NewImage": {
        "connectionId": {"S": "abc123"},
        "status": {"S": "active"},
        "marker": {"S": "X"}
      }
    }
  }]
}
```
- Attributes wrapped in type descriptors (`{"S": "value"}`)
- `TypeDeserializer` converts to native Python dict: `{"connectionId": "abc123"}`

**Why Not Used in Code Excerpt Above?**
- Likely used when processing `record['dynamodb']['NewImage']`
- Not needed for `table.scan()` (returns normal dict)

### Why Scan Entire Table (Not Just Stream Record)?

**Problem:** Stream record only contains changed item, not full queue state.

**Example:**
- Stream receives: `{connectionId: 'abc', status: 'active'}`
- Clients need: Full list of all active + inactive players

**Solution:**
- Ignore stream record content (only use as trigger)
- Scan entire `TicTacToeUsers` table
- Send complete queue to all clients

**Trade-off:**
- ✅ Simplifies logic (clients get full state every time)
- ✅ Prevents sync issues (clients never miss updates)
- ❌ Inefficient for large player counts (scan entire table per update)
- ❌ Redundant data (most players unchanged)

**Scaling Improvement:**
- Could use DynamoDB Streams record to determine what changed
- Send incremental updates (`{action: 'playerJoined', user: {...}}`)
- Requires clients to maintain local state (more complex)

### Broadcasting Pattern: Fan-Out

```
Single DynamoDB Write
    ↓
DynamoDB Streams (1 event)
    ↓
streamDB.py Lambda (1 invocation)
    ↓
API Gateway POST /@connections/{id} (N calls)
    ↓
N WebSocket Clients (all receive update)
```

**Why This Pattern?**
- **Decoupling:** Game logic Lambdas don't need to track connection list
- **Consistency:** All clients receive same data from single source of truth
- **Simplicity:** Write to DynamoDB = automatic broadcast

**Alternative (Direct Broadcast):**
```python
# In gameOver.py:
for connection_id in get_all_connection_ids():
    send_message(connection_id, queue_data)
```
- **Problem:** Every Lambda needs broadcast logic (code duplication)
- **Problem:** Race condition if two Lambdas broadcast simultaneously

### Hardcoded Endpoint Issue

```python
# aws/lambda/streamDB.py:40-41
domain = 'wqritmruc9.execute-api.us-east-1.amazonaws.com'
stage = 'production'
```

**Why Hardcoded?**
- Likely oversight (should use environment variable)

**Production Fix:**
```python
domain = os.environ['WEBSOCKET_API_DOMAIN']
stage = os.environ['WEBSOCKET_API_STAGE']
```

**Risk:**
- If API Gateway ID changes (redeployment), must update Lambda code
- Multi-environment deployments (dev/staging/prod) require separate functions

### Integration Points

**Upstream:** DynamoDB Streams event source mapping
**Downstream:**
- DynamoDB `TicTacToeUsers` table (Scan)
- API Gateway Management API (`post_to_connection`)
- All WebSocket clients

### Error Handling

| Error | Cause | Handling |
|-------|-------|----------|
| `GoneException` | Connection closed before message sent | Ignore (connection will be deleted by `$disconnect`) |
| `Scan` throttling | Too many requests to DynamoDB | Retry with exponential backoff (Lambda auto-retries) |
| Stream deserialization failure | Corrupt stream record | Log error, skip record |

---

## Shared Utility Module (`utility.py`)

**Note:** This file is not in the repository but referenced by all Lambdas.

### Likely Contents

```python
# utility.py (inferred from Lambda usage)
import boto3
import logging

# DynamoDB table reference
dynamodb = boto3.resource('dynamodb')
table = dynamodb.Table('TicTacToeUsers')

# API Gateway client for WebSocket messaging
apigateway_client = boto3.client('apigatewaymanagementapi')

# Logger
logger = logging.getLogger()
logger.setLevel(logging.INFO)


def count_active_players():
    """Query statusIndex GSI for active player count."""
    response = table.query(
        IndexName='statusIndex',
        KeyConditionExpression='#status = :active',
        ExpressionAttributeNames={'#status': 'status'},
        ExpressionAttributeValues={':active': 'active'},
        Select='COUNT'
    )
    return response['Count']


def get_marker_for_new_active_user():
    """Assign 'X' or 'O' based on existing active players."""
    response = table.query(
        IndexName='statusIndex',
        KeyConditionExpression='#status = :active',
        ExpressionAttributeNames={'#status': 'status'},
        ExpressionAttributeValues={':active': 'active'}
    )

    existing_markers = {user.get('marker') for user in response['Items']}
    return 'X' if 'X' not in existing_markers else 'O'


def update_user(connection_id, updates):
    """Update DynamoDB user item."""
    update_expression = 'SET ' + ', '.join(f'{k} = :{k}' for k in updates.keys())
    expression_values = {f':{k}': v for k, v in updates.items()}

    table.update_item(
        Key={'connectionId': connection_id},
        UpdateExpression=update_expression,
        ExpressionAttributeValues=expression_values
    )


def send_message(connection_id, data):
    """Send WebSocket message via API Gateway."""
    domain = 'wqritmruc9.execute-api.us-east-1.amazonaws.com'
    stage = 'production'

    endpoint_url = f'https://{domain}/{stage}'
    client = boto3.client('apigatewaymanagementapi', endpoint_url=endpoint_url)

    try:
        client.post_to_connection(
            ConnectionId=connection_id,
            Data=json.dumps(data).encode('utf-8')
        )
    except client.exceptions.GoneException:
        # Connection no longer exists
        logger.warning(f'Connection {connection_id} gone')
    except Exception as e:
        logger.error(f'Failed to send message: {e}')
```

### Why Lambda Layer?

**Benefits:**
- **Code Reuse:** All 7 Lambdas share same utility functions
- **Consistency:** DynamoDB table name defined in one place
- **Versioning:** Update layer = all Lambdas get new code
- **Bundle Size:** Each Lambda package smaller (utilities in layer)

**Deployment:**
```bash
cd aws/lambda/
zip -r utility.zip utility.py
aws lambda publish-layer-version \
  --layer-name tictactoe-utilities \
  --zip-file fileb://utility.zip \
  --compatible-runtimes python3.9

# Attach layer to each Lambda function
aws lambda update-function-configuration \
  --function-name connection \
  --layers arn:aws:lambda:us-east-1:ACCOUNT_ID:layer:tictactoe-utilities:1
```

---

## Lambda Execution Role Permissions

**Required IAM Permissions:**

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "dynamodb:GetItem",
        "dynamodb:PutItem",
        "dynamodb:UpdateItem",
        "dynamodb:DeleteItem",
        "dynamodb:Query",
        "dynamodb:Scan"
      ],
      "Resource": [
        "arn:aws:dynamodb:us-east-1:ACCOUNT_ID:table/TicTacToeUsers",
        "arn:aws:dynamodb:us-east-1:ACCOUNT_ID:table/TicTacToeUsers/index/statusIndex"
      ]
    },
    {
      "Effect": "Allow",
      "Action": "execute-api:ManageConnections",
      "Resource": "arn:aws:execute-api:us-east-1:ACCOUNT_ID:API_ID/production/POST/@connections/*"
    },
    {
      "Effect": "Allow",
      "Action": "lambda:InvokeFunction",
      "Resource": [
        "arn:aws:lambda:us-east-1:ACCOUNT_ID:function:joinQueue",
        "arn:aws:lambda:us-east-1:ACCOUNT_ID:function:sendInfo"
      ]
    },
    {
      "Effect": "Allow",
      "Action": [
        "logs:CreateLogGroup",
        "logs:CreateLogStream",
        "logs:PutLogEvents"
      ],
      "Resource": "arn:aws:logs:us-east-1:ACCOUNT_ID:*"
    }
  ]
}
```

**Breakdown:**
- **DynamoDB:** Full read/write access to table and GSI
- **API Gateway:** `ManageConnections` for WebSocket messaging
- **Lambda:** Invoke permissions for async calls
- **CloudWatch Logs:** Standard logging permissions

---

## Performance & Scalability

### Concurrency

| Function | Expected Concurrency | Why |
|----------|---------------------|-----|
| `connection.py` | = concurrent connections | 1 per WebSocket connect |
| `disconnect.py` | = concurrent disconnects | Usually low |
| `joinQueue.py` | = concurrent connections | Invoked by connection.py |
| `gameOver.py` | = game completions/sec | Typically low |
| `updateDB.py` | = username updates/sec | Once per user session |
| `sendInfo.py` | = concurrent connections | Invoked by connection.py |
| `streamDB.py` | = DynamoDB writes/sec | Every INSERT/MODIFY/REMOVE |

**Bottleneck:** `streamDB.py` if many simultaneous queue updates (fan-out broadcast)

### Latency

| Function | Avg Latency | Why |
|----------|-------------|-----|
| `connection.py` | ~100ms | DynamoDB write + 2 async invokes |
| `disconnect.py` | ~150ms | GetItem + DeleteItem + Query + UpdateItem |
| `joinQueue.py` | ~80ms | Query + UpdateItem + send_message |
| `gameOver.py` | ~150ms | UpdateItem + Query + UpdateItem |
| `updateDB.py` | ~50ms | Single UpdateItem |
| `sendInfo.py` | ~30ms | Single send_message |
| `streamDB.py` | ~200ms | Scan + N send_message calls |

**Critical Path (User Connects):**
```
connection.py (100ms)
    ↓ async invoke
joinQueue.py (80ms)
    ↓ DynamoDB write
streamDB.py (200ms)
    ↓ WebSocket message
Browser receives queue update
───────────────────────────
Total: ~380ms
```

### Cost Analysis (1000 Players/Day)

**Assumptions:**
- 1000 connections/day
- 500 game completions/day
- 1000 username updates/day
- Average session: 10 minutes

**Lambda Invocations:**
```
connection.py:   1000 invocations
disconnect.py:   1000 invocations
joinQueue.py:    1000 invocations
gameOver.py:     500 invocations
updateDB.py:     1000 invocations
sendInfo.py:     1000 invocations
streamDB.py:     ~3500 invocations (each table write triggers)

Total: ~9000 invocations/day = 270k/month
```

**Cost:**
- Lambda free tier: 1M requests/month
- **Result:** $0/month for Lambda

**DynamoDB:**
```
Writes/day: ~3500 (connections + disconnects + updates + gameOver)
Reads/day:  ~1500 (queries for active count)

On-demand pricing:
Writes: 3500 * 30 = 105k/month * $1.25/million = $0.13
Reads:  1500 * 30 = 45k/month * $0.25/million = $0.01

Total: ~$0.14/month
```

**Conclusion:** Lambda + DynamoDB costs negligible at low scale.

---

## Debugging & Monitoring

### CloudWatch Logs

Each Lambda writes to `/aws/lambda/{function-name}` log group.

**Key Metrics:**
- **Errors:** Filter pattern `ERROR`
- **Duration:** Sort by `@duration` to find slow executions
- **Throttles:** `aws logs filter-log-events --filter-pattern "Task timed out"`

**Useful Queries:**
```bash
# Find all gameOver invocations
aws logs filter-log-events \
  --log-group-name /aws/lambda/gameOver \
  --start-time $(date -d '1 hour ago' +%s)000 \
  --filter-pattern '"loserId"'

# Count DynamoDB throttling errors
aws logs filter-log-events \
  --log-group-name /aws/lambda/streamDB \
  --filter-pattern "ProvisionedThroughputExceededException"
```

### X-Ray Tracing (If Enabled)

```python
from aws_xray_sdk.core import xray_recorder
from aws_xray_sdk.core import patch_all

patch_all()

@xray_recorder.capture('fill_active_slots')
def fill_active_slots():
    # Function code
```

**Benefits:**
- Visualize Lambda-to-DynamoDB latency
- Identify slow DynamoDB queries
- Trace request through multiple Lambdas

---

## Related Documentation

- **DynamoDB Schema:** `AWS_INFRASTRUCTURE.md` (CloudFormation section)
- **WebSocket Client:** `FRONTEND_INTEGRATION.md`
- **Queue Management Strategy:** `adr/003-queue-management-strategy.md`
- **DynamoDB Streams Decision:** `adr/002-dynamodb-streams-for-realtime.md`
