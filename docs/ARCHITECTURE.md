# Architecture Overview

## System at a Glance

This is a **real-time multiplayer Tic-Tac-Toe web application** that combines JavaFX desktop UI (compiled to web via JPro), AWS serverless backend, and WebSocket-based communication for live game coordination.

The architecture solves a unique problem: enabling multiple players to queue for matches, automatically pairing them, and providing real-time updates when games end and new players can join.

## Technology Stack

| Layer | Technology | Why It Exists |
|-------|-----------|---------------|
| **Frontend UI** | JavaFX + JPro | Reuses desktop Java code for web deployment without rewriting in JavaScript |
| **Frontend Integration** | Vanilla JavaScript + WebSocket | Handles queue management UI and WebSocket communication |
| **Backend API** | AWS API Gateway (WebSocket) | Persistent bidirectional connections for real-time multiplayer |
| **Backend Logic** | AWS Lambda (Python) | Serverless event processing for connection/game lifecycle |
| **Database** | DynamoDB + Streams | NoSQL storage with built-in change notifications for broadcasting |
| **Infrastructure** | EC2 + ALB | JPro application server with HTTPS termination |
| **IaC** | Terraform + CloudFormation | Reproducible infrastructure provisioning |

## High-Level Component Flow

```
┌─────────────────────────────────────────────────────────────────────┐
│                          USER'S BROWSER                              │
│  ┌────────────────┐  ┌──────────────────┐  ┌──────────────────┐    │
│  │  index.html    │  │  Embedded JPro   │  │  app.js          │    │
│  │  (Queue UI)    │◄─┤  JavaFX Game     │  │  (WebSocket)     │    │
│  └────────┬───────┘  └──────────────────┘  └─────────┬────────┘    │
│           │                                            │              │
└───────────┼────────────────────────────────────────────┼─────────────┘
            │                                            │
            │ HTTPS (Game Rendering)                     │ WSS (Real-time)
            ▼                                            ▼
┌───────────────────────────┐          ┌────────────────────────────────┐
│   Application Load        │          │   API Gateway WebSocket API    │
│   Balancer (ALB)          │          │   wss://xxx.execute-api...     │
│   - SSL Termination       │          └──────────┬─────────────────────┘
│   - HTTP → HTTPS redirect │                     │
└─────────┬─────────────────┘                     │ Route Events
          │                                        ▼
          │ Port 80                    ┌────────────────────────────┐
          ▼                            │   Lambda Functions (7)     │
┌──────────────────────────┐          │  - connection.py           │
│   EC2 Instance           │          │  - disconnect.py           │
│   Private Subnet         │          │  - joinQueue.py            │
│   JPro Server (Port 8080)│          │  - gameOver.py             │
│   + Nginx Reverse Proxy  │          │  - updateDB.py             │
│   + TTTG.jar             │          │  - sendInfo.py             │
└──────────────────────────┘          │  - streamDB.py (Stream)    │
                                      └──────────┬─────────────────┘
                                                 │ Read/Write
                                                 ▼
                                      ┌─────────────────────────────┐
                                      │   DynamoDB                  │
                                      │   TicTacToeUsers Table      │
                                      │   + Streams Enabled         │
                                      │   + statusIndex GSI         │
                                      └─────────────────────────────┘
```

## Data Flow: User Action → UI Update

### Scenario: Player Joins Queue

1. **Browser → WebSocket API**
   - User opens `index.html`, enters username
   - `app.js:connectWebSocket()` establishes WSS connection
   - API Gateway triggers `$connect` route

2. **Lambda → DynamoDB**
   - `connection.py:handler()` creates user record with `status='inactive'`
   - Invokes `joinQueue.py` asynchronously
   - `joinQueue.py:handler()` checks active player count via GSI query

3. **Queue Logic (Critical Path)**
   - If `< 2 active players`:
     - Update user `status='active'`, assign `marker='X'` or `'O'`
     - DynamoDB write triggers Stream event
   - If `>= 2 active players`:
     - Keep user `status='inactive'`, set `joinedAt` timestamp
     - User enters queue (FIFO ordering)

4. **DynamoDB Streams → Broadcast**
   - `streamDB.py:lambda_handler()` receives INSERT/MODIFY event
   - Scans entire table, sorts by `joinedAt`
   - Broadcasts `queueUpdate` message to **all connected WebSocket clients**

5. **WebSocket → Browser**
   - `app.js:onmessage()` receives queue data
   - `updateQueueUI()` splits players into active/inactive
   - Updates Player 1/2 display, queue list, and game interaction state
   - If user is active: game opacity 100%, clickable
   - If user is inactive: game opacity 20%, disabled

### Scenario: Game Ends

1. **JavaFX → JavaScript**
   - Player wins in ButtonView or TextAreaView
   - `views/ButtonView.java:156` calls `WebApi.alertEndGame(stage, loserId, winnerId)`
   - Java dispatches custom DOM event `gameOverEvent`

2. **JavaScript → WebSocket API**
   - `app.js:25` listens for `gameOverEvent`
   - Sends `gameOVER` WebSocket message with `loserId`

3. **Lambda → Queue Promotion**
   - `gameOver.py:handler()` updates loser to `status='inactive'`
   - `fill_active_slots()` queries inactive players sorted by `joinedAt ASC`
   - Promotes next 1-2 players to active status
   - DynamoDB Stream triggers broadcast (back to step 4 above)

4. **All Browsers Update**
   - Every connected client receives new queue state
   - Winners stay active, losers re-enter queue at back
   - Next waiting players become active and can play

## Key Architectural Decisions

### Why JPro for Game UI?
- **Reusability:** Desktop JavaFX code compiles to web without rewrite
- **Observer Pattern:** Existing `OurObservable`/`OurObserver` works seamlessly
- **Strategy Pattern:** AI switching (`RandomAI`, `IntermediateAI`) preserved
- **Trade-off:** Requires EC2 server (not pure serverless), adds JAR compilation step

### Why Separate JavaScript + JavaFX?
- **JPro Limitation:** JPro renders JavaFX, but doesn't handle WebSocket lifecycle natively
- **Division of Labor:**
  - JavaFX: Game board rendering, click handling, win detection
  - JavaScript: Queue UI, WebSocket connection, player list management
- **Bridge:** `WebApi.alertEndGame()` dispatches DOM events from Java → JS

### Why DynamoDB Streams Instead of Lambda-to-Lambda?
- **Fan-out Pattern:** One write triggers broadcast to all clients automatically
- **Decoupling:** Game logic Lambdas don't need to know about all connected clients
- **Consistency:** Single source of truth (DynamoDB) ensures all clients see same queue state
- See `docs/adr/002-dynamodb-streams-for-realtime.md` for deep dive

### Why WebSocket Instead of REST + Polling?
- **Real-time Updates:** When player disconnects, all clients instantly see updated queue
- **Server Push:** No need for clients to poll for queue changes every N seconds
- **Efficiency:** Single persistent connection vs. repeated HTTP requests
- See `docs/adr/001-websocket-vs-rest-api.md` for trade-offs

## Component Responsibilities

### Frontend (`src/main/resources/jpro/html/`)
- `index.html`: Page structure, username modal, three-column layout
- `app.js`: WebSocket client, queue state management, DOM updates
- `updated-css.css`: Cardinal red/navy blue theme, responsive layout

### Java Game (`src/main/java/`)
- `model/TicTacToeGame.java`: Game state, win detection, move validation
- `model/TicTacToeStrategy.java`: Interface for AI implementations
- `model/RandomAI.java`, `IntermediateAI.java`: Pluggable computer strategies
- `model/WebApi.java`: Bridge to JavaScript via JPro WebAPI
- `views/ButtonView.java`: 3x3 button grid, click handlers
- `views/TextAreaView.java`: Alternative text input view
- `views/GUI.java`: Application entry point, menu bar, view switching

### AWS Lambda (`aws/lambda/`)
- `connection.py`: WebSocket connection handler
- `disconnect.py`: Connection cleanup + queue slot backfill
- `joinQueue.py`: Active/inactive status assignment
- `gameOver.py`: Winner stays active, loser re-queues
- `updateDB.py`: Username updates
- `sendInfo.py`: Returns connectionId to client
- `streamDB.py`: Broadcasts DynamoDB changes to all WebSocket clients

### Infrastructure (`aws/vpc/`)
- `mod1/vpct.tf`: VPC, subnets, NAT gateways, route tables
- `mod2/sgt.tf`: Security groups for ALB and EC2
- `mod3/ec2.tf`: EC2 instance, ALB, Route 53, ACM certificate
- `aws/template.yaml`: DynamoDB table with Streams and GSI

## Critical Integration Points

### 1. WebSocket Connection Lifecycle
```python
# aws/lambda/connection.py:13-24
def handler(event, context):
    connection_id = event['requestContext']['connectionId']
    session_id = str(uuid.uuid4())

    # Create user in DynamoDB
    table.put_item(Item={
        'connectionId': connection_id,
        'sessionId': session_id,
        'status': 'inactive',
        'joinedAt': datetime.now(timezone.utc).isoformat()
    })
```
- Every WebSocket connection gets unique `connectionId` from API Gateway
- `sessionId` is UUID for additional tracking
- All users start `inactive` until queue slot available

### 2. Queue Ordering via GSI
```yaml
# aws/template.yaml:32-38
GlobalSecondaryIndexes:
  - IndexName: statusIndex
    KeySchema:
      - AttributeName: status      # Partition key: 'active' or 'inactive'
        KeyType: HASH
      - AttributeName: joinedAt    # Sort key: ISO timestamp
        KeyType: RANGE
```
- Enables fast queries: "Get all inactive users sorted by join time"
- Used in `disconnect.py:56` and `gameOver.py:45` for FIFO queue promotion
- Without this: Would need to scan entire table and filter in Python

### 3. Java-to-JavaScript Event Bridge
```java
// src/main/java/model/WebApi.java:13-23
public static void alertEndGame(Stage yourStage, String loserId, String winnerId) {
    var window = WebAPI.getWebAPI(yourStage).getWindow();
    window.executeScript(String.format(
        "window.dispatchEvent(new CustomEvent('gameOverEvent', { " +
        "detail: { loserId: '%s', winnerId: '%s' } }));",
        loserId, winnerId
    ));
}
```
- JPro provides `WebAPI.getWindow()` to access browser DOM
- Java dispatches custom event that JavaScript listens for
- Alternative would be HTTP POST to backend, but this is instant (no network latency)

### 4. Broadcasting via DynamoDB Streams
```python
# aws/lambda/streamDB.py:20-28
for record in event['Records']:
    # Any INSERT/MODIFY/REMOVE triggers this
    if record['eventName'] in ['INSERT', 'MODIFY', 'REMOVE']:
        # Scan entire table to get current queue state
        response = table.scan()
        items = sorted(response['Items'], key=lambda x: x.get('joinedAt', ''))

        # Send to ALL connected WebSocket clients
        broadcast_to_all_clients(items)
```
- **Pattern:** Write-triggered fan-out
- **Trade-off:** Every DynamoDB write triggers broadcast (could be optimized for large scale)
- **Benefit:** Simplifies game logic Lambdas (no need to track connection list)

## Local Development vs Production

### Local Mode (Desktop JavaFX)
- Run `views.GUI` as standard Java application
- No WebSocket, no queue system
- Single player vs AI or two-player local mode
- Menu bar for strategy/view switching

### Production Mode (Web)
- JPro compiles JavaFX to web application
- EC2 instance serves on port 8080, Nginx reverse proxy on port 80
- ALB terminates HTTPS, forwards to Nginx
- Browser embeds JPro app via `<jpro-app>` tag
- JavaScript manages WebSocket for multiplayer queue

## Security Considerations

### Network Isolation
- EC2 instance in **private subnet** (no direct internet access)
- NAT gateway for outbound traffic (software updates, S3 access)
- ALB in **public subnet** accepts HTTPS traffic
- Security groups enforce least privilege (ALB → EC2 port 80 only)

### HTTPS Everywhere
- ACM certificate for `api.tttlexc24.it.com`
- ALB redirects HTTP → HTTPS automatically
- WebSocket uses WSS (encrypted WebSocket)

### DynamoDB Access
- Lambda execution role has `dynamodb:PutItem`, `Query`, `UpdateItem` permissions
- No public DynamoDB endpoint exposure
- VPC S3 endpoint for private subnet EC2 to download JAR

## Performance Characteristics

### Scalability
- **Lambda:** Auto-scales per concurrent WebSocket connection
- **DynamoDB:** On-demand billing, auto-scales with traffic
- **Bottleneck:** Single EC2 instance for JPro server (could use Auto Scaling Group)

### Latency
- **WebSocket Message:** ~50-100ms (API Gateway → Lambda → DynamoDB → Stream → Broadcast)
- **Game Move:** <10ms (local JavaFX rendering)
- **Queue Update:** ~200ms (DynamoDB write → Stream trigger → broadcast to all clients)

### Cost Optimization
- NAT Gateway disabled by default (`enable_nat_gateways = false` in `vpct.tf:12`)
- DynamoDB on-demand billing (only pay for actual requests)
- Lambda pay-per-invocation (no idle server costs)

## What Happens When...

### A Player Disconnects Mid-Game?
1. WebSocket `$disconnect` route triggers `disconnect.py`
2. User deleted from DynamoDB
3. If user was active, `fill_active_slots()` promotes next in queue
4. DynamoDB Stream broadcasts new queue state
5. All clients see updated player list

### Two Players Win Simultaneously?
- Not possible in Tic-Tac-Toe (turn-based)
- Each game instance runs in separate browser (JPro singleton per user)
- JavaFX logic prevents simultaneous moves

### DynamoDB Stream Lambda Fails?
- API Gateway retries Lambda invocations automatically
- DynamoDB Stream keeps records for 24 hours
- Clients might not receive broadcast, but next write will trigger new broadcast

### EC2 Instance Crashes?
- JPro server becomes unavailable
- Game UI doesn't load, but WebSocket queue system still works
- Would need Auto Scaling Group + health checks for high availability

## Future Scaling Considerations

If expanding beyond proof-of-concept:

- **Multi-Region:** Deploy API Gateway + Lambda + DynamoDB globally
- **EC2 Auto Scaling:** Multiple JPro instances behind ALB for redundancy
- **Connection Limit:** API Gateway supports 100k concurrent WebSocket connections (likely sufficient)
- **Database Sharding:** If > 100k players, partition by region or game room ID
- **CDN:** CloudFront for static assets (index.html, CSS, JS)

## Related Documentation

- **AWS Infrastructure Details:** `AWS_INFRASTRUCTURE.md`
- **Lambda Function Deep Dive:** `LAMBDA_FUNCTIONS.md`
- **Frontend Integration Guide:** `FRONTEND_INTEGRATION.md`
- **Game Logic Patterns:** `GAME_LOGIC.md`
- **Architecture Decisions:** `adr/*.md`
