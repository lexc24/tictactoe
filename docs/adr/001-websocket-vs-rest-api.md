# ADR-001: WebSocket API Gateway vs REST API for Real-Time Multiplayer

## Status
Accepted

## Context

The Tic-Tac-Toe application requires real-time coordination between multiple browser clients for:
- **Queue management:** When a player disconnects, waiting players need instant notification that they can play
- **Player list updates:** All clients must see current active players and queue position
- **Game state synchronization:** When game ends, winner stays active, loser re-enters queue

We evaluated two AWS API Gateway types:
1. **REST API** (HTTP/HTTPS) with client-side polling
2. **WebSocket API** (WSS) with persistent bidirectional connections

## Decision

**Use API Gateway WebSocket API** with the following architecture:
- Persistent WebSocket connections from browser clients
- Lambda functions handle connection lifecycle (`$connect`, `$disconnect`) and custom routes
- DynamoDB stores connection state (active/inactive players)
- DynamoDB Streams trigger broadcasts to all connected clients

## Why This Approach

### Real-Time Server Push (vs Polling)

**WebSocket:**
```javascript
// Browser maintains single connection
ws.onmessage = (event) => {
    const update = JSON.parse(event.data);
    updateQueueUI(update.data);  // Instant update
};
```

**REST Alternative:**
```javascript
// Browser polls every 2 seconds
setInterval(() => {
    fetch('/queue')
        .then(res => res.json())
        .then(data => updateQueueUI(data));
}, 2000);
```

**WebSocket Advantages:**
- **Latency:** ~50ms (server push) vs ~2000ms average (polling interval)
- **Resource Efficient:** 1 connection vs 30 HTTP requests/minute
- **Battery Friendly:** No constant polling on mobile devices
- **Scalability:** API Gateway handles fan-out, not 100s of simultaneous polls

**Trade-off:**
- WebSocket connections cost $1.00/million messages vs REST $3.50/million requests
- But REST would require 30x more requests (1 poll/2sec vs 1 push/queue change)
- **Result:** WebSocket cheaper at scale

### Bidirectional Communication

**Required Flows:**
1. **Client → Server:** User updates username, game ends
2. **Server → Client:** Queue state changes, player promoted to active

**WebSocket:**
```
Single connection handles both directions:
Client: { action: "updateDB", username: "Player1" }  →  Server
Server: { action: "queueUpdate", data: [...] }        →  Client
```

**REST Alternative:**
```
Client → Server: POST /username
Server → Client: Requires polling GET /queue (can't push)
```

**Why WebSocket Wins:**
- REST requires polling for server-initiated updates
- WebSocket enables true server push (no polling overhead)

### Connection State Management

**WebSocket:**
- `$connect` route creates user in DynamoDB with unique `connectionId`
- `$disconnect` route automatically triggered on close/timeout
- API Gateway manages connection lifecycle (heartbeats, reconnection)

**REST Alternative:**
- Would need session cookies or JWT tokens for state
- Client must explicitly call `/leave` endpoint (unreliable if browser crashes)
- Timeout-based cleanup requires background job (Lambda + EventBridge)

**WebSocket Advantages:**
- **Automatic Cleanup:** `$disconnect` fires even if browser crashes
- **Built-in Session:** `connectionId` provided by API Gateway
- **No Token Management:** Connection itself is authentication (for this demo)

### API Gateway WebSocket Features Used

1. **Route Selection:**
   ```
   $connect        → connection.py (connection lifecycle)
   $disconnect     → disconnect.py (cleanup)
   joinQueue       → joinQueue.py (custom action)
   gameOVER        → gameOver.py (custom action)
   updateDB        → updateDB.py (custom action)
   ```

2. **Connection Management API:**
   ```python
   # Lambda can send messages to specific connectionId
   apigateway_client.post_to_connection(
       ConnectionId=connection_id,
       Data=json.dumps(message).encode('utf-8')
   )
   ```

3. **Integration with DynamoDB Streams:**
   - Any DynamoDB write triggers `streamDB.py`
   - Lambda broadcasts to all connections via Management API
   - Decouples game logic from broadcasting

## Integration Impact

### Frontend (app.js)

**WebSocket Client:**
```javascript
// src/main/resources/jpro/html/app.js:34-82
const ws = new WebSocket('wss://wqritmruc9.execute-api.us-east-1.amazonaws.com/production');

ws.onopen = () => console.log('Connected');
ws.onmessage = (event) => {
    const message = JSON.parse(event.data);
    if (message.action === 'queueUpdate') {
        updateQueueUI(message.data);
    }
};
```

**Impact:**
- Single connection for entire session
- No polling logic needed
- Real-time UI updates (queue position, active players)

### Backend (Lambda Functions)

**Connection Tracking:**
```python
# aws/lambda/connection.py:13-24
table.put_item(Item={
    'connectionId': connection_id,  # From API Gateway event
    'status': 'inactive',
    'joinedAt': datetime.now(timezone.utc).isoformat()
})
```

**Broadcasting:**
```python
# aws/lambda/streamDB.py:40-60
for user in queue_data:
    apigateway_client.post_to_connection(
        ConnectionId=user['connectionId'],
        Data=json.dumps({'action': 'queueUpdate', 'data': queue_data})
    )
```

**Impact:**
- Lambda functions don't track connection list (DynamoDB does)
- `streamDB.py` scans table to get all connectionIds for broadcast
- Automatic fan-out pattern (1 write → N clients)

### DynamoDB Schema

**Table: TicTacToeUsers**
```yaml
Primary Key: connectionId  # WebSocket connection ID from API Gateway
Attributes:
  - sessionId: UUID
  - username: String
  - status: 'active' | 'inactive'
  - marker: 'X' | 'O' | null
  - joinedAt: ISO timestamp
```

**Impact:**
- `connectionId` as primary key (unique per WebSocket connection)
- When connection closes, `$disconnect` deletes record (automatic cleanup)

## Alternative Approaches Considered

### 1. REST API + Long Polling

**Approach:** Client makes `/queue` request, server holds connection open until update.

**Rejected Because:**
- API Gateway Lambda timeout: 30 seconds max
- Client must reconnect every 30s (not truly real-time)
- Harder to implement than WebSocket (no built-in support)

### 2. REST API + Server-Sent Events (SSE)

**Approach:** Unidirectional stream from server to client.

**Rejected Because:**
- API Gateway doesn't support SSE natively
- Would need CloudFront + Lambda@Edge (complex)
- Still requires REST calls for client → server messages

### 3. AWS AppSync (GraphQL Subscriptions)

**Approach:** GraphQL with real-time subscriptions over WebSocket.

**Rejected Because:**
- Overkill for simple queue management
- Requires learning GraphQL schema definition
- Higher cost ($4.00/million requests + data transfer)
- Would require rewriting existing Lambda functions

### 4. Third-Party Service (Pusher, Ably)

**Approach:** SaaS WebSocket provider.

**Rejected Because:**
- External dependency (vendor lock-in)
- Monthly costs ($49-99 for production tier)
- Data leaves AWS (compliance concerns)
- Goal was to learn AWS services

## Trade-Offs Accepted

### ❌ Harder to Debug
- WebSocket messages not visible in browser Network tab (need to manually log)
- Can't use tools like Postman (requires WebSocket-specific clients)

**Mitigation:**
- Use browser console: `ws.send(JSON.stringify({action: 'updateDB', username: 'test'}))`
- Use `wscat` CLI tool: `wscat -c wss://xxx.execute-api.us-east-1.amazonaws.com/production`

### ❌ Stateful Connections
- API Gateway maintains connection state (unlike stateless REST)
- If API Gateway restarts, all connections drop (clients must reconnect)

**Mitigation:**
- Implement reconnection logic:
  ```javascript
  ws.onclose = () => {
      setTimeout(() => connectWebSocket(), 1000);  // Retry after 1s
  };
  ```

### ❌ Connection Limits
- API Gateway WebSocket limit: 100,000 concurrent connections
- Each connected browser counts as 1 connection

**Mitigation:**
- For Tic-Tac-Toe demo, unlikely to hit limit
- If scaling: Use multiple API Gateway endpoints with load balancing

### ❌ Cost Unpredictability
- WebSocket costs based on message count + connection minutes
- Unlike REST (predictable cost per request)

**Mitigation:**
- Message costs still cheaper than polling REST ($1.00/million vs $3.50/million requests)
- Connection minutes: First 1 billion minutes free (likely never exceeded)

## Interview-Worthy Insights

### Why Not Just Use HTTP/2 Server Push?

**HTTP/2 Server Push:**
- Server can push resources (CSS, JS) before client requests
- **Not for dynamic data:** Can't push arbitrary messages after page load
- **WebSocket still needed** for real-time bidirectional communication

### How API Gateway WebSocket Scales

1. **Connections:** Distributed across multiple availability zones
2. **Lambda Integration:** Each route invokes separate Lambda (parallel scaling)
3. **Broadcasting:** `post_to_connection` is HTTP POST (API Gateway handles fan-out)

**Bottleneck:** If 10,000 concurrent users, `streamDB.py` makes 10,000 HTTP calls to send messages.

**Optimization:**
- Use AWS SNS to fan-out (Lambda → SNS → N Lambdas → clients)
- Use AWS IoT Core (purpose-built for millions of connections)

### When REST Would Be Better

**Use REST if:**
- Updates infrequent (e.g., once per hour)
- Client-initiated requests only (no server push)
- Stateless architecture preferred (easier to scale horizontally)
- Debugging/monitoring tooling more important

**Use WebSocket if:**
- Real-time updates critical (< 1 second latency)
- Server-initiated push required
- Bidirectional communication (chat, multiplayer games)

## Code References

- **WebSocket Connection:** `src/main/resources/jpro/html/app.js:34-82`
- **Connection Handler:** `aws/lambda/connection.py:13-36`
- **Disconnect Handler:** `aws/lambda/disconnect.py:10-61`
- **Broadcasting Logic:** `aws/lambda/streamDB.py:40-70`
- **API Gateway Management API Usage:** `utility.py` (inferred, sends messages via `post_to_connection`)

## Related ADRs

- **ADR-002:** DynamoDB Streams for Real-Time Broadcasting
- **ADR-003:** Queue Management Strategy (active/inactive status)

## Future Considerations

If traffic grows significantly:
1. **Connection Pooling:** Group users into rooms, broadcast only to relevant subset
2. **Redis for Connection Tracking:** Faster than DynamoDB scan for 10k+ connections
3. **Multi-Region:** Deploy WebSocket APIs in multiple regions for lower latency
4. **GraphQL Subscriptions:** If adding complex data fetching patterns
