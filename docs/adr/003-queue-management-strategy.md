# ADR-003: Queue Management Strategy (Active/Inactive Status System)

## Status
Accepted

## Context

The Tic-Tac-Toe game allows exactly **2 players to be active** (playing) at any time, with additional users waiting in a queue. We needed a system to:

1. **Assign players:** Promote users from queue when slots open (first-in, first-out)
2. **Track state:** Distinguish between active players and waiting users
3. **Handle disconnections:** Automatically fill empty slots when players leave
4. **Persist markers:** Assign 'X' and 'O' to active players, clear when they go inactive
5. **Maintain fairness:** Ensure consistent ordering (oldest waiter gets next slot)

Options evaluated:
1. **Active/Inactive Status System** - Single DynamoDB table with `status` attribute and GSI
2. **Separate Tables** - `ActivePlayers` and `QueuedPlayers` tables
3. **SQS Queue** - FIFO queue for waiting users
4. **Redis Sorted Set** - In-memory sorted queue with TTL

## Decision

**Use Active/Inactive Status System** with the following design:

**DynamoDB Table: `TicTacToeUsers`**
```yaml
Primary Key: connectionId (String)
Attributes:
  - sessionId: UUID
  - username: String
  - status: 'active' | 'inactive'  # Key attribute for queue logic
  - marker: 'X' | 'O' | null       # Only set when active
  - joinedAt: ISO 8601 timestamp   # Queue ordering

Global Secondary Index: statusIndex
  Partition Key: status
  Sort Key: joinedAt
  Projection: ALL
```

**Promotion Algorithm (FIFO):**
```python
# Query inactive users sorted by oldest join time
response = table.query(
    IndexName='statusIndex',
    KeyConditionExpression='#status = :inactive',
    ExpressionAttributeValues={':inactive': 'inactive'},
    ScanIndexForward=True,  # Ascending order (oldest first)
    Limit=slots_available
)

# Promote oldest user(s)
for user in response['Items']:
    table.update_item(
        Key={'connectionId': user['connectionId']},
        UpdateExpression='SET #status = :active, marker = :marker'
    )
```

## Why This Approach

### Single Table for Simplicity

**Current Design:**
```
TicTacToeUsers
├── connectionId: abc (status='active', marker='X')
├── connectionId: def (status='active', marker='O')
├── connectionId: ghi (status='inactive', marker=null)
└── connectionId: jkl (status='inactive', marker=null)
```

**Alternative (Separate Tables):**
```
ActivePlayers                  QueuedPlayers
├── connectionId: abc (X)      ├── connectionId: ghi
└── connectionId: def (O)      └── connectionId: jkl
```

**Why Single Table Wins:**
- ✅ **Atomic Operations:** Promoting user = single `UpdateItem` (change status)
- ✅ **Consistent View:** DynamoDB Streams broadcast shows all users in one scan
- ✅ **Simpler Code:** No cross-table transactions or synchronization
- ✅ **Cost:** One table, one GSI (vs two tables + coordination logic)

**Trade-off:**
- ❌ Slightly larger table (but negligible for <10k users)
- ❌ Cannot independently scale active vs inactive queries (not a concern)

### Global Secondary Index for Fast Queue Queries

**Why GSI (Not Scan + Filter)?**

**With GSI (Current):**
```python
# O(log n) + O(k) where k = inactive users
response = table.query(
    IndexName='statusIndex',
    KeyConditionExpression='#status = :inactive',
    ScanIndexForward=True,
    Limit=1  # Get oldest waiting user
)
```
- **Read Capacity:** 1 RCU (reads only inactive items)
- **Latency:** ~10-20ms

**Without GSI (Scan Alternative):**
```python
# O(n) where n = total users
response = table.scan()
inactive_users = [u for u in response['Items'] if u['status'] == 'inactive']
inactive_users.sort(key=lambda x: x['joinedAt'])
next_user = inactive_users[0]
```
- **Read Capacity:** 10+ RCUs (reads entire table)
- **Latency:** ~50-100ms (scans all items)

**Interview Insight:**
- GSI creates second "view" of table partitioned by `status`
- DynamoDB maintains GSI automatically on writes (no manual sync)
- Query GSI by `status='inactive'` only reads inactive partition (efficient)

### FIFO Ordering with Timestamp Sort Key

**Why `joinedAt` as Sort Key?**

**Timestamp (ISO 8601 String):**
```python
joinedAt = datetime.now(timezone.utc).isoformat()
# Example: "2025-01-15T10:30:45.123456+00:00"
```

**Alternative: Epoch Milliseconds (Number):**
```python
joinedAt = int(datetime.now().timestamp() * 1000)
# Example: 1705318245123
```

**Why ISO String Chosen:**
- ✅ **Human Readable:** Easy to debug in DynamoDB console (`2025-01-15T10:30:45` vs `1705318245123`)
- ✅ **String Sorting:** Lexicographic sort works correctly for ISO 8601 (YYYY-MM-DDTHH:MM:SS)
- ✅ **Timezone Explicit:** `+00:00` suffix prevents confusion

**Trade-off:**
- ❌ Larger storage: ~27 bytes vs 8 bytes (number)
- ❌ Slightly slower comparison (string vs int)
- ✅ But difference negligible for <10k users

**Why ScanIndexForward=True?**
```python
ScanIndexForward=True  # Ascending order (oldest → newest)
```
- Returns oldest user first (FIFO queue behavior)
- `ScanIndexForward=False` would give newest first (LIFO stack behavior)

### Marker Assignment Logic

**Current Algorithm:**
```python
# aws/lambda utility.py (inferred)
def get_marker_for_new_active_user():
    # Query all active users
    response = table.query(
        IndexName='statusIndex',
        KeyConditionExpression='#status = :active'
    )

    active_users = response['Items']
    existing_markers = {user.get('marker') for user in active_users}

    if 'X' not in existing_markers:
        return 'X'
    else:
        return 'O'
```

**Why This Logic?**

**Scenario 1: Empty Game**
- 0 active users
- `existing_markers = set()`
- `'X' not in existing_markers` → Return `'X'`
- First player gets X

**Scenario 2: One Active Player (X)**
- 1 active user with marker='X'
- `existing_markers = {'X'}`
- `'X' not in existing_markers` → False
- Return `'O'`
- Second player gets O

**Scenario 3: Player X Disconnects**
- Player X disconnects → `status='inactive'`, `marker=null`
- New player promoted
- 1 active user with marker='O'
- `existing_markers = {'O'}`
- `'X' not in existing_markers` → True
- New player gets X (refills empty slot)

**Why Not Just Alternate?**
```python
# Bad: Breaks when players disconnect
def get_marker_alternating():
    active_count = count_active_players()
    return 'X' if active_count == 0 else 'O'
```
- **Problem:** If both players disconnect, next user gets X, second gets O (correct)
- **But:** If only X disconnects, next user should get X (not O)
- **Alternating fails** in partial disconnect scenarios

### Winner-Stays-Active Policy

**Game End Logic:**
```python
# aws/lambda/gameOver.py:15-30
loser_id = body.get('loserId')

# Mark loser as inactive (re-enters queue)
table.update_item(
    Key={'connectionId': loser_id},
    UpdateExpression='SET #status = :inactive, marker = :null, joinedAt = :now',
    ExpressionAttributeValues={
        ':inactive': 'inactive',
        ':null': None,
        ':now': datetime.now(timezone.utc).isoformat()
    }
)

# Winner stays active (no database update needed)
```

**Why Only Update Loser?**
- ✅ **Continuous Play:** Winner doesn't wait in queue again (rewards skill)
- ✅ **Efficiency:** One write instead of two (winner + loser)
- ✅ **Simpler Logic:** No need to track who was winner

**Alternative (Both Go Inactive):**
```python
# Mark both players inactive
table.update_item(Key={'connectionId': loser_id}, ...)
table.update_item(Key={'connectionId': winner_id}, ...)
fill_active_slots()  # Promotes next 2 in queue
```

**Why Rejected:**
- ❌ Winners penalized (must wait in queue after winning)
- ❌ Higher churn (more promotions per game)
- ❌ Two database writes per game (vs one)

**Fairness Consideration:**
- Winner might dominate for hours (plays multiple games in a row)
- Could implement "max wins" rule (after 5 wins, go inactive)
- Current approach prioritizes winner engagement over strict fairness

## Integration Impact

### joinQueue.py - Initial Queue Assignment

```python
# aws/lambda/joinQueue.py:15-40
active_count = count_active_players()  # Query statusIndex GSI

if active_count < 2:
    # Slot available - promote immediately
    marker = get_marker_for_new_active_user()
    table.update_item(
        Key={'connectionId': connection_id},
        UpdateExpression='SET #status = :active, marker = :marker'
    )
else:
    # Queue full - keep inactive
    table.update_item(
        Key={'connectionId': connection_id},
        UpdateExpression='SET #status = :inactive, joinedAt = :timestamp'
    )
```

**Why Set `joinedAt` Only When Inactive?**
- Users who immediately become active don't need queue timestamp
- If they later lose, `gameOver.py` sets fresh `joinedAt` (re-enter queue at back)

### disconnect.py - Slot Backfilling

```python
# aws/lambda/disconnect.py:33-61
def fill_active_slots():
    active_count = count_active_players()
    slots_available = 2 - active_count

    if slots_available <= 0:
        return  # Already full

    # Query oldest inactive users
    response = table.query(
        IndexName='statusIndex',
        KeyConditionExpression='#status = :inactive',
        ScanIndexForward=True,
        Limit=slots_available
    )

    for user in response['Items']:
        marker = get_marker_for_new_active_user()
        table.update_item(
            Key={'connectionId': user['connectionId']},
            UpdateExpression='SET #status = :active, marker = :marker'
        )
```

**When Called:**
- `disconnect.py:handler()` - After deleting disconnected user
- `gameOver.py:handler()` - After marking loser inactive

**Why `Limit=slots_available`?**
- If 1 slot open, only promote 1 user
- If 2 slots open (both players disconnected), promote 2 users
- Prevents over-promotion (never >2 active players)

### Frontend - Active/Inactive UI Rendering

```javascript
// src/main/resources/jpro/html/app.js:84-118
function updateQueueUI(queueData) {
    const activePlayers = queueData.filter(u => u.status === 'active');
    const inactivePlayers = queueData.filter(u => u.status === 'inactive');

    // Display active players in left panel
    changeActivePlayersUI(activePlayers);

    // Display inactive players in right queue panel
    const queueList = document.getElementById('queue-list');
    inactivePlayers.forEach(player => {
        const li = document.createElement('li');
        li.textContent = player.username;
        queueList.appendChild(li);
    });

    // Enable/disable game interaction
    const currentUser = queueData.find(u => u.connectionId === connectionId);
    enableGameInteraction(currentUser && currentUser.status === 'active');
}
```

**Visual States:**
- **Active:** User's name in "Players" panel, game board clickable
- **Inactive:** User's name in "Queue" panel, game board grayed out

**Why Filter Client-Side (Not Server)?**
- `streamDB.py` broadcasts full queue (active + inactive)
- Clients filter locally (simpler backend, flexible frontend)
- Alternative: Broadcast two separate lists (more network overhead)

## Alternative Approaches Considered

### 1. Separate Tables (ActivePlayers + QueuedPlayers)

**Schema:**
```yaml
ActivePlayers:
  Primary Key: connectionId
  Attributes: marker, username

QueuedPlayers:
  Primary Key: connectionId
  Sort Key: joinedAt  # Native table sort (no GSI needed)
  Attributes: username
```

**Promotion Logic:**
```python
# Query oldest queued player
response = queued_table.query(
    Limit=1,
    ScanIndexForward=True  # Oldest first
)

oldest = response['Items'][0]

# Move from QueuedPlayers to ActivePlayers (2 operations)
active_table.put_item(Item={'connectionId': oldest['connectionId'], 'marker': 'X'})
queued_table.delete_item(Key={'connectionId': oldest['connectionId']})
```

**Rejected Because:**
- ❌ **Not Atomic:** Two separate writes (could fail between operations)
- ❌ **Transaction Overhead:** Would need DynamoDB transactions (extra cost)
- ❌ **Complex Cleanup:** Disconnection requires checking both tables
- ❌ **Broadcast Logic:** Need to scan both tables and merge (slower)

**When This Would Be Better:**
- If active and inactive have vastly different access patterns
- If scaling to millions of users (independent table scaling)

### 2. SQS FIFO Queue

**Schema:**
```yaml
DynamoDB TicTacToeUsers:
  - connectionId, marker (only active players)

SQS Queue: waiting-players.fifo
  - Messages: {"connectionId": "abc", "username": "Player1"}
```

**Promotion Logic:**
```python
# Receive oldest message from queue
response = sqs.receive_message(
    QueueUrl='https://sqs.us-east-1.amazonaws.com/.../waiting-players.fifo',
    MaxNumberOfMessages=1
)

message = response['Messages'][0]
connection_id = json.loads(message['Body'])['connectionId']

# Add to active players
table.put_item(Item={'connectionId': connection_id, 'marker': 'X'})

# Delete message from queue
sqs.delete_message(ReceiptHandle=message['ReceiptHandle'])
```

**Rejected Because:**
- ❌ **Two Systems:** DynamoDB + SQS (more complexity)
- ❌ **Visibility Timeout:** Message invisible for 30s after receive (could delay promotion)
- ❌ **Cost:** SQS costs $0.40/million requests (DynamoDB queries free in this tier)
- ❌ **Broadcast:** Still need DynamoDB to store all users for queue display

**When SQS Would Be Better:**
- High-volume queueing (millions of users)
- Need dead-letter queues for failed promotions
- Decoupling multiple consumers (e.g., game servers in different regions)

### 3. Redis Sorted Set

**Schema:**
```redis
ZADD waiting_players 1705318245 "abc"  # Score = timestamp, Value = connectionId
ZADD waiting_players 1705318250 "def"
```

**Promotion Logic:**
```python
# Get oldest user
oldest = redis.zrange('waiting_players', 0, 0)[0]

# Remove from queue
redis.zrem('waiting_players', oldest)

# Add to DynamoDB active players
table.put_item(Item={'connectionId': oldest, 'marker': 'X'})
```

**Rejected Because:**
- ❌ **Extra Infrastructure:** Need ElastiCache cluster (cost + ops overhead)
- ❌ **Persistence:** Redis is in-memory (data lost on crash without AOF/RDB)
- ❌ **Complexity:** Syncing DynamoDB + Redis state (eventual consistency issues)
- ❌ **Overkill:** DynamoDB GSI queries fast enough (<20ms)

**When Redis Would Be Better:**
- Sub-millisecond queue queries needed (DynamoDB too slow)
- Very high read throughput (>10k queries/sec)
- Already using Redis for session storage

### 4. In-Memory Queue (Lambda Global Variable)

**Implementation:**
```python
# aws/lambda/joinQueue.py
waiting_queue = []  # Global variable (persists across invocations)

def handler(event, context):
    if active_count < 2:
        promote_immediately()
    else:
        waiting_queue.append(connection_id)
```

**Rejected Because:**
- ❌ **Lambda Stateless:** Global variables lost when Lambda scales or recycles
- ❌ **Concurrency:** Multiple Lambda instances have separate queues (inconsistent)
- ❌ **No Persistence:** Server restart = lost queue

**When This Would Be Better:**
- Never (anti-pattern for serverless)

## Trade-Offs Accepted

### ❌ GSI Eventual Consistency

**Scenario:**
1. User promoted: `status='active'` written to table
2. `fill_active_slots()` queries GSI 10ms later
3. GSI not yet updated → Shows 1 active user (should be 2)
4. Function promotes another user → 3 active users (bug!)

**Why This Hasn't Happened:**
- `fill_active_slots()` only called after disconnect/gameOver (rare race window)
- DynamoDB GSI typically updates within 100ms (usually consistent)

**Mitigation:**
- Use strongly consistent read (not possible for GSI - always eventual)
- Add conditional update:
  ```python
  table.update_item(
      ...,
      ConditionExpression='attribute_not_exists(marker)'  # Only update if no marker
  )
  ```

### ❌ No Transaction Guarantees (Promotion)

**Current Code:**
```python
marker = get_marker_for_new_active_user()  # Query active users

table.update_item(
    Key={'connectionId': user['connectionId']},
    UpdateExpression='SET marker = :marker'  # Assign marker
)
```

**Race Condition:**
1. Lambda A queries: 1 active user (marker=X)
2. Lambda B queries: 1 active user (marker=X)  ← Same time!
3. Lambda A assigns: marker=O
4. Lambda B assigns: marker=O  ← Duplicate marker!

**Why Unlikely:**
- `fill_active_slots()` triggered by DynamoDB Streams (serialized)
- Streams process records in order per partition
- Concurrent promotions rare (requires simultaneous disconnects)

**Fix (Production):**
```python
# Use DynamoDB transactions
dynamodb.transact_write_items(
    TransactItems=[
        {'Update': {'Key': {'connectionId': user_id}, 'UpdateExpression': 'SET marker = :X'}},
        {'ConditionCheck': {'Key': {'connectionId': other_user}, 'ConditionExpression': 'marker <> :X'}}
    ]
)
```

### ❌ No Position Feedback

**Current:**
- Users know they're in queue (game grayed out)
- Don't know position (#3 in queue vs #100)

**Fix:**
```javascript
// app.js:updateQueueUI()
const currentUser = queueData.find(u => u.connectionId === connectionId);
const inactiveUsers = queueData.filter(u => u.status === 'inactive');
const position = inactiveUsers.findIndex(u => u.connectionId === connectionId) + 1;

document.getElementById('queue-position').textContent = `Position: ${position}`;
```

**Why Not Implemented:**
- Scope creep (demo focused on core functionality)
- Adds minimal value for small queues (<10 users)

## Interview-Worthy Insights

### Why GSI (Not LSI)?

**GSI (Global Secondary Index):**
- Different partition key than base table
- Can query by `status` (not just `connectionId`)
- Eventually consistent

**LSI (Local Secondary Index):**
- Same partition key as base table
- Can only query by `connectionId` + different sort key
- Strongly consistent

**Why GSI Needed:**
- We query by `status='inactive'` (different partition key)
- LSI can't support this query (would still need to scan all connectionIds)

### How DynamoDB Maintains GSI

**Behind the Scenes:**
1. Write to base table: `table.update_item(...)`
2. DynamoDB asynchronously updates GSI (separate storage)
3. GSI update within ~100ms (eventually consistent)

**Cost:**
- GSI storage: Billed separately (stores duplicate data)
- GSI writes: Count toward table WCUs (1 WCU = 1 KB write)

**Example:**
- Update user (1 KB): 1 WCU for base table + 1 WCU for GSI = 2 WCUs total
- On-demand billing: $1.25/million WCUs (negligible for this app)

### Why Timestamp String (Not Auto-Increment ID)?

**Auto-Increment Alternative:**
```python
# Get max ID, increment
response = table.scan()
max_id = max([user.get('queuePosition', 0) for user in response['Items']])
new_position = max_id + 1
```

**Problems:**
- ❌ Requires scan to find max (slow)
- ❌ Race condition (two users get same ID)
- ❌ Gaps when users disconnect (position 1, 3, 5... confusing)

**Timestamp Wins:**
- ✅ No coordination needed (each Lambda generates independently)
- ✅ Collision-free (down to microsecond precision)
- ✅ Sortable without conversion

## Code References

- **Queue Promotion:** `aws/lambda/disconnect.py:33-61`, `aws/lambda/gameOver.py:45-70`
- **GSI Definition:** `aws/template.yaml:32-38`
- **Marker Assignment:** `utility.py:get_marker_for_new_active_user()` (inferred)
- **Frontend Filtering:** `src/main/resources/jpro/html/app.js:84-118`

## Related ADRs

- **ADR-001:** WebSocket vs REST API (why real-time queue updates matter)
- **ADR-002:** DynamoDB Streams (how queue changes broadcast to clients)

## Future Considerations

If scaling beyond demo:
1. **Matchmaking:** Group users by skill level (separate queues per tier)
2. **Timeouts:** Auto-remove inactive users after 5 minutes
3. **Priority Queues:** Premium users jump to front
4. **Multi-Game Support:** Separate queues per game type (classic, speed, ranked)
