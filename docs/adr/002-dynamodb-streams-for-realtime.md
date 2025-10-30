# ADR-002: DynamoDB Streams for Real-Time Queue Broadcasting

## Status
Accepted

## Context

The multiplayer queue system requires all connected browsers to receive updates when:
- **New player joins:** User connects and enters queue
- **Player promoted:** Inactive user becomes active when slot opens
- **Player disconnects:** Active user leaves, triggering queue reshuffling
- **Username updated:** User sets display name after connecting
- **Game ends:** Winner stays active, loser re-enters queue

We needed a mechanism to **broadcast queue state to all WebSocket clients** whenever DynamoDB changes.

Options evaluated:
1. **Lambda-to-Lambda invocation:** Each game logic Lambda directly calls broadcast function
2. **DynamoDB Streams trigger:** Database writes automatically trigger broadcast Lambda
3. **EventBridge with DynamoDB as source:** Custom event bus for change notifications
4. **SNS topic:** Lambdas publish to SNS, broadcast Lambda subscribes

## Decision

**Use DynamoDB Streams** with the following architecture:
- Enable Streams on `TicTacToeUsers` table (`StreamViewType: NEW_AND_OLD_IMAGES`)
- Lambda function `streamDB.py` triggered by any INSERT/MODIFY/REMOVE event
- `streamDB.py` scans entire table, sorts by `joinedAt`, broadcasts to all WebSocket connections
- Game logic Lambdas (`disconnect.py`, `gameOver.py`, `updateDB.py`) simply write to DynamoDB, unaware of broadcasting

## Why This Approach

### Automatic Fan-Out (No Manual Tracking)

**DynamoDB Streams:**
```python
# aws/lambda/gameOver.py:15-25
# Just update DynamoDB - broadcasting happens automatically
table.update_item(
    Key={'connectionId': loser_id},
    UpdateExpression='SET #status = :inactive',
    ExpressionAttributeValues={':inactive': 'inactive'}
)
# DynamoDB Stream → streamDB.py → Broadcast to all clients
```

**Alternative (Manual Lambda Invoke):**
```python
# Would need to explicitly trigger broadcast
table.update_item(...)  # Update database

# Get all connection IDs
response = table.scan()
connection_ids = [user['connectionId'] for user in response['Items']]

# Manually broadcast
for conn_id in connection_ids:
    send_message(conn_id, queue_data)
```

**Why Streams Win:**
- ✅ **Decoupling:** Game logic Lambdas don't need broadcasting code
- ✅ **Single Responsibility:** Each Lambda focuses on one task
- ✅ **Automatic Retry:** Streams retry failed Lambda invocations
- ✅ **Ordered Processing:** Streams guarantee order per shard (prevents race conditions)

### Guaranteed Delivery with Retry

**DynamoDB Streams Behavior:**
- Lambda polls stream every second
- If `streamDB.py` fails (exception), API Gateway retries with exponential backoff
- Records retained in stream for 24 hours (ample retry window)
- Dead Letter Queue (DLQ) can capture failures after max retries

**Alternative (Direct Broadcast in gameOver.py):**
```python
try:
    for conn_id in connection_ids:
        send_message(conn_id, queue_data)
except Exception as e:
    # What now? User's update succeeded, but broadcast failed
    # Clients have stale data - no easy retry mechanism
    logger.error(f"Broadcast failed: {e}")
```

**Problem with Manual Broadcast:**
- ❌ If broadcast fails, DynamoDB already updated (inconsistent state)
- ❌ No automatic retry (would need custom error handling)
- ❌ Game logic Lambda timeout affects broadcast delivery

**Streams Advantage:**
- ✅ Database update and broadcast are separate transactions
- ✅ Broadcast failure doesn't affect game logic
- ✅ Automatic retry ensures eventual consistency

### Consistency: Single Source of Truth

**Current Flow:**
```
1. gameOver.py updates DynamoDB (loser → inactive)
   ↓
2. DynamoDB Streams emits MODIFY event
   ↓
3. streamDB.py triggered
   ↓
4. streamDB.py scans CURRENT table state
   ↓
5. Broadcasts fresh snapshot to all clients
```

**Why Scan (Not Use Stream Record)?**

**Option A (Current): Scan Table**
```python
# aws/lambda/streamDB.py:20-28
for record in event['Records']:
    if record['eventName'] in ['INSERT', 'MODIFY', 'REMOVE']:
        # Ignore stream record content, scan for current state
        response = table.scan()
        queue_data = sorted(response['Items'], key=lambda x: x.get('joinedAt', ''))
        broadcast_to_all_clients(queue_data)
```

**Option B: Use Stream Record**
```python
# Alternative: Send only changed item
new_image = record['dynamodb']['NewImage']
changed_user = deserialize(new_image)

# Broadcast incremental update
broadcast_to_all_clients({
    'action': 'userUpdated',
    'user': changed_user
})
```

**Why Scan Wins:**
- ✅ **Simplicity:** Clients receive full state (no need to merge incremental updates)
- ✅ **Prevents Sync Issues:** Clients never have stale data (always get complete snapshot)
- ✅ **Handles Multiple Changes:** If 2 users disconnect simultaneously, single broadcast reflects both

**Trade-off:**
- ❌ Inefficient for large tables (scanning 10k+ users per update)
- ❌ Redundant data (sends unchanged users)

**When to Optimize:**
- If player count exceeds 1000, switch to incremental updates
- Clients maintain local state, apply deltas (`{action: 'userLeft', connectionId: 'abc'}`)

### Stream Event Types Used

**INSERT:**
```json
{
  "eventName": "INSERT",
  "dynamodb": {
    "NewImage": {
      "connectionId": {"S": "abc123"},
      "status": {"S": "inactive"},
      "joinedAt": {"S": "2025-01-15T10:30:00Z"}
    }
  }
}
```
- Triggered by: `connection.py` creates new user
- Broadcasts: Full queue (new user at end of inactive list)

**MODIFY:**
```json
{
  "eventName": "MODIFY",
  "dynamodb": {
    "NewImage": {"connectionId": {"S": "abc123"}, "status": {"S": "active"}},
    "OldImage": {"connectionId": {"S": "abc123"}, "status": {"S": "inactive"}}
  }
}
```
- Triggered by: `joinQueue.py` promotes user to active, `updateDB.py` sets username
- Broadcasts: Full queue (user's status or username changed)

**REMOVE:**
```json
{
  "eventName": "REMOVE",
  "dynamodb": {
    "OldImage": {"connectionId": {"S": "abc123"}, "status": {"S": "active"}}
  }
}
```
- Triggered by: `disconnect.py` deletes user on WebSocket close
- Broadcasts: Full queue (user removed, possibly triggering promotions)

## Integration Impact

### DynamoDB Table Configuration

**CloudFormation Template:**
```yaml
# aws/template.yaml:40-43
StreamSpecification:
  StreamViewType: NEW_AND_OLD_IMAGES
```

**Why `NEW_AND_OLD_IMAGES`?**
- Provides both old and new item state
- Useful for debugging (see what changed)
- Could use `NEW_IMAGE_ONLY` to save stream storage (minor cost)

**Stream Retention:** 24 hours (DynamoDB default, not configurable)

### Lambda Event Source Mapping

**Configuration (Inferred, Not in Repo):**
```python
# Terraform or AWS Console
aws lambda create-event-source-mapping \
  --function-name streamDB \
  --event-source-arn arn:aws:dynamodb:us-east-1:ACCOUNT:table/TicTacToeUsers/stream/2025-01-15T10:00:00.000 \
  --starting-position LATEST \
  --batch-size 10 \
  --maximum-batching-window-in-seconds 1
```

**Parameters:**
- `LATEST`: Only process new records (not historical)
- `batch-size: 10`: Process up to 10 stream records per Lambda invocation
- `maximum-batching-window-in-seconds: 1`: Wait max 1 second before invoking (low latency)

**Why Batch Size 10?**
- Balance between latency and efficiency
- If 10 users disconnect simultaneously, single Lambda invocation handles all
- Each invocation scans table once, broadcasts once (efficient)

### streamDB.py Implementation

**Deserialization (boto3 TypeDeserializer):**
```python
# aws/lambda/streamDB.py:12-18
from boto3.dynamodb.types import TypeDeserializer

deserializer = TypeDeserializer()

for record in event['Records']:
    # Convert DynamoDB format to Python dict
    new_image = record['dynamodb'].get('NewImage', {})
    user = {k: deserializer.deserialize(v) for k, v in new_image.items()}
```

**Why Needed:**
- DynamoDB Streams use low-level format: `{"connectionId": {"S": "abc"}}`
- `TypeDeserializer` converts to: `{"connectionId": "abc"}`
- Alternative: Manually parse (error-prone)

**Broadcasting Logic:**
```python
# aws/lambda/streamDB.py:40-70
def broadcast_queue_update(queue_data):
    domain = 'wqritmruc9.execute-api.us-east-1.amazonaws.com'
    stage = 'production'

    apigateway_client = boto3.client(
        'apigatewaymanagementapi',
        endpoint_url=f'https://{domain}/{stage}'
    )

    message = {'action': 'queueUpdate', 'data': queue_data}

    for user in queue_data:
        try:
            apigateway_client.post_to_connection(
                ConnectionId=user['connectionId'],
                Data=json.dumps(message).encode('utf-8')
            )
        except apigateway_client.exceptions.GoneException:
            # Connection closed, ignore (will be removed by $disconnect)
            pass
```

**Why Ignore `GoneException`?**
- Connection may close between table scan and message send
- `$disconnect` Lambda will delete user from table soon
- Next stream event will broadcast updated queue (without stale connection)

### Game Logic Lambda Simplification

**Before (Hypothetical Manual Broadcast):**
```python
# aws/lambda/gameOver.py (if we didn't use Streams)
def handler(event, context):
    loser_id = json.loads(event['body'])['loserId']

    # Update database
    table.update_item(...)

    # Manually get all connections
    response = table.scan()
    queue_data = sorted(response['Items'], ...)

    # Manually broadcast
    for user in queue_data:
        try:
            send_message(user['connectionId'], {'action': 'queueUpdate', ...})
        except Exception as e:
            # Handle errors, implement retry logic, etc.
            pass

    return {'statusCode': 200}
```
- **Lines of Code:** ~40-50
- **Concerns:** Database update + broadcasting + error handling

**After (With Streams):**
```python
# aws/lambda/gameOver.py:10-50 (actual)
def handler(event, context):
    loser_id = json.loads(event['body'])['loserId']

    table.update_item(
        Key={'connectionId': loser_id},
        UpdateExpression='SET #status = :inactive',
        ...
    )

    fill_active_slots()  # Promote next players

    return {'statusCode': 200}
    # Broadcasting happens automatically via DynamoDB Stream
```
- **Lines of Code:** ~20
- **Concerns:** Database update only (single responsibility)

**Benefits:**
- ✅ Simpler code (easier to test, maintain)
- ✅ No broadcasting bugs in game logic
- ✅ Consistent pattern across all Lambdas

## Alternative Approaches Considered

### 1. SNS Topic for Broadcasting

**Approach:** Lambdas publish to SNS topic, `streamDB.py` subscribes.

**Implementation:**
```python
# aws/lambda/gameOver.py
table.update_item(...)
sns_client.publish(
    TopicArn='arn:aws:sns:us-east-1:ACCOUNT:queue-updates',
    Message=json.dumps({'action': 'broadcastQueue'})
)

# aws/lambda/streamDB.py (SNS subscriber)
def handler(event, context):
    # Triggered by SNS, not DynamoDB Streams
    response = table.scan()
    broadcast_to_all_clients(response['Items'])
```

**Rejected Because:**
- ❌ **Manual Publish:** Every Lambda must remember to publish to SNS
- ❌ **No Ordering Guarantee:** SNS doesn't preserve message order (could broadcast stale state)
- ❌ **Extra Service:** Adds SNS cost + complexity
- ❌ **Race Conditions:** If two Lambdas publish simultaneously, which state is current?

**When SNS Would Be Better:**
- If broadcasting to multiple services (e.g., queue updates + analytics + audit log)
- DynamoDB Streams → SNS → [streamDB, analyticsDB, auditLog]

### 2. EventBridge with DynamoDB as Source

**Approach:** Enable EventBridge on DynamoDB, create rules to trigger Lambda.

**Implementation:**
```json
{
  "source": ["aws.dynamodb"],
  "detail-type": ["DynamoDB Stream Record"],
  "detail": {
    "eventName": ["INSERT", "MODIFY", "REMOVE"]
  }
}
```

**Rejected Because:**
- ❌ **Higher Latency:** EventBridge adds ~500ms delay vs DynamoDB Streams (~100ms)
- ❌ **Extra Cost:** EventBridge events cost $1.00/million (Streams are free)
- ❌ **Overkill:** EventBridge better for cross-account/cross-region routing

**When EventBridge Would Be Better:**
- Multiple AWS accounts need queue updates
- Complex filtering rules (e.g., only broadcast if status changed to 'active')

### 3. Step Functions for Orchestration

**Approach:** Game logic Lambda starts Step Functions workflow, which broadcasts.

**Implementation:**
```json
{
  "StartAt": "UpdateDatabase",
  "States": {
    "UpdateDatabase": {
      "Type": "Task",
      "Resource": "arn:aws:lambda:...:gameOver",
      "Next": "BroadcastQueue"
    },
    "BroadcastQueue": {
      "Type": "Task",
      "Resource": "arn:aws:lambda:...:streamDB",
      "End": true
    }
  }
}
```

**Rejected Because:**
- ❌ **High Cost:** $25 per million state transitions (vs Streams free)
- ❌ **Higher Latency:** Step Functions add 50-100ms overhead
- ❌ **Unnecessary Complexity:** Simple trigger (DB write → broadcast) doesn't need workflow

**When Step Functions Would Be Better:**
- Complex multi-step logic (e.g., wait 30 seconds, check if user reconnected, then remove)
- Human approval workflows (e.g., admin approves player ban before updating DB)

### 4. Lambda-to-Lambda Direct Invocation

**Approach:** Each game logic Lambda directly invokes `streamDB.py`.

**Implementation:**
```python
# aws/lambda/gameOver.py
table.update_item(...)

lambda_client.invoke(
    FunctionName='streamDB',
    InvocationType='Event',  # Async
    Payload=json.dumps({})
)
```

**Rejected Because:**
- ❌ **Tight Coupling:** Game logic knows about broadcast function
- ❌ **No Automatic Retry:** If invocation fails, no broadcast (inconsistent state)
- ❌ **Duplicate Invocations:** If two Lambdas invoke simultaneously, double broadcast

**When Direct Invocation Would Be Better:**
- One-off operations (e.g., `connection.py` → `sendInfo.py` to send connectionId)
- Synchronous workflows where caller needs response

## Trade-Offs Accepted

### ❌ Scan Overhead (Scaling Concern)

**Current:** Every stream event triggers full table scan.

**Impact:**
- **10 users:** ~1-2ms scan time (negligible)
- **1000 users:** ~50-100ms scan time (noticeable)
- **10,000 users:** ~500ms-1s scan time (problematic)

**Mitigation (Future):**
1. **Incremental Updates:** Send only changed user to clients
2. **Secondary Index Query:** Use statusIndex GSI instead of scan
3. **Caching:** Store queue state in ElastiCache, invalidate on change

### ❌ Broadcast Storms (Many Simultaneous Updates)

**Scenario:** 100 users disconnect in 1 second.

**Current Behavior:**
- 100 DynamoDB writes → 100 Stream events
- 100 `streamDB.py` invocations (might batch into 10 invocations of 10 records)
- Each invocation scans table, broadcasts to remaining ~900 users
- **Total:** ~10 scans, ~9000 WebSocket messages

**Mitigation:**
- Use batch window (1 second) to group events
- Deduplicate broadcasts (only send once per batch)
- If very high traffic: Introduce rate limiting or debouncing

### ❌ Stream Processing Delay (Not Truly Synchronous)

**Latency Breakdown:**
```
DynamoDB write:          ~10ms
Stream propagation:      ~100ms  (eventual consistency)
Lambda cold start:       ~500ms  (first invocation)
Lambda warm execution:   ~50ms
Broadcast to N clients:  ~50ms * N connections
────────────────────────────────
Total (warm):            ~210ms + (50ms * N)
Total (cold):            ~710ms + (50ms * N)
```

**Why Not Synchronous?**
- Could make `gameOver.py` wait for broadcast confirmation
- But would increase user-facing latency (game ends → response delayed)

**Accepted Trade-off:**
- Users may see ~200ms delay before queue updates
- Acceptable for demo/casual game (not real-time twitch shooter)

## Interview-Worthy Insights

### Why Streams Are "Eventually Consistent"

**DynamoDB Guarantee:**
- Write to table → acknowledged immediately
- Stream record appears within ~100ms (not instant)

**Why Not Instant?**
- DynamoDB replicates to multiple AZs (availability over speed)
- Stream is separate system (reads from replicas, not primary)

**Implication:**
- `gameOver.py` returns 200 OK before broadcast completes
- Client might briefly see stale state (acceptable for this use case)

### How Streams Preserve Ordering

**DynamoDB Streams Guarantee:**
- Records from same **partition key** are ordered
- Records from different partition keys may be out of order

**Example:**
```
User A (connectionId=abc): UPDATE status='active'  (t=0)
User B (connectionId=def): UPDATE status='active'  (t=1)
User A (connectionId=abc): UPDATE username='Alice' (t=2)
```

**Stream Processing:**
- Shard 1 (connectionId=abc): Records at t=0 and t=2 processed in order
- Shard 2 (connectionId=def): Record at t=1 processed independently
- **Result:** A's updates ordered, but A and B might process out of chronological order

**Why This Matters:**
- If relying on stream records (not scanning), could broadcast stale state
- Current approach (scan table) avoids issue (always shows current truth)

### DynamoDB Streams vs Kinesis Streams

**Similarities:**
- Both provide ordered event streams
- Both integrate with Lambda

**Differences:**

| Feature | DynamoDB Streams | Kinesis Streams |
|---------|------------------|-----------------|
| **Retention** | 24 hours | 1-365 days (configurable) |
| **Cost** | Free | $0.015/shard-hour + data ingress |
| **Use Case** | Database change notifications | General event streaming |
| **Ordering** | Per partition key | Per shard |
| **Consumer** | Lambda, Kinesis Client Library | Lambda, KCL, custom consumers |

**When to Use Kinesis:**
- Need longer retention (audit logs, analytics)
- Multiple independent consumers (each tracks own position)
- Non-DynamoDB data sources (application logs, IoT sensors)

## Code References

- **Stream Configuration:** `aws/template.yaml:40-43`
- **Stream Processor:** `aws/lambda/streamDB.py:10-70`
- **Game Logic (No Broadcasting):** `aws/lambda/gameOver.py:10-50`
- **Disconnect Cleanup:** `aws/lambda/disconnect.py:33-61`

## Related ADRs

- **ADR-001:** WebSocket vs REST API (why real-time broadcasting needed)
- **ADR-003:** Queue Management Strategy (what data gets broadcasted)

## Future Considerations

If queue grows beyond 1000 players:
1. **Pagination:** Clients request queue in chunks (GET /queue?page=1)
2. **Incremental Updates:** Send deltas instead of full state
3. **Redis Pub/Sub:** Faster than DynamoDB scan for broadcasting
4. **GraphQL Subscriptions:** AWS AppSync manages subscriptions automatically
