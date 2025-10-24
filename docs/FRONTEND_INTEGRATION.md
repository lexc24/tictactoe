# Frontend Integration Guide

## Overview

The frontend consists of **two distinct layers** that work together:

1. **Java Game UI (JavaFX)** - Compiled to web via JPro, handles game board rendering and click interactions
2. **HTML/JavaScript Wrapper** - Manages WebSocket communication, queue UI, and player list display

The critical insight: **JavaFX handles game logic, JavaScript handles multiplayer coordination.**

## Architecture Diagram

```
┌──────────────────────────────────────────────────────────────┐
│                     USER'S BROWSER                           │
│                                                               │
│  ┌────────────────┐  ┌──────────────┐  ┌──────────────────┐ │
│  │ Username Modal │  │ Player Panel │  │ Queue Panel      │ │
│  │ (HTML)         │  │ (HTML/JS)    │  │ (HTML/JS)        │ │
│  └────────────────┘  └──────────────┘  └──────────────────┘ │
│                                                               │
│  ┌─────────────────────────────────────────────────────────┐ │
│  │           JPro Embedded Game (JavaFX)                   │ │
│  │  <jpro-app href="http://127.0.0.1:8080/app/TTT">       │ │
│  │  ┌────────────────────────────────────┐                │ │
│  │  │   ButtonView (3x3 Grid)            │                │ │
│  │  │   ┌───┬───┬───┐                    │                │ │
│  │  │   │ X │ O │   │                    │                │ │
│  │  │   ├───┼───┼───┤                    │                │ │
│  │  │   │   │ X │ O │                    │                │ │
│  │  │   ├───┼───┼───┤                    │                │ │
│  │  │   │   │   │ X │  ← Java handles   │                │ │
│  │  │   └───┴───┴───┘                    │                │ │
│  │  └────────────────────────────────────┘                │ │
│  └─────────────────────────────────────────────────────────┘ │
│                            │                                  │
│                            │ JPro WebAPI (Java → JS Bridge)  │
│                            ▼                                  │
│  ┌─────────────────────────────────────────────────────────┐ │
│  │               app.js (WebSocket Client)                 │ │
│  │  - Manages WebSocket connection                         │ │
│  │  - Listens for gameOverEvent from Java                  │ │
│  │  - Updates queue UI based on broadcasts                 │ │
│  └──────────────────────────┬──────────────────────────────┘ │
│                             │                                 │
└─────────────────────────────┼─────────────────────────────────┘
                              │
                              │ WebSocket (WSS)
                              ▼
                  ┌──────────────────────────┐
                  │ AWS API Gateway          │
                  │ WebSocket API            │
                  └──────────────────────────┘
```

## Component Breakdown

### 1. index.html - Page Structure

**File:** `src/main/resources/jpro/html/index.html`

#### Username Modal (Lines 19-30)

```html
<!-- src/main/resources/jpro/html/index.html:19-30 -->
<div id="username-modal" class="modal">
    <div class="modal-content">
        <h2>Welcome to Tic-Tac-Toe!</h2>
        <input type="text" id="username-input" placeholder="Enter your username" />
        <button id="join-button">Join Game</button>
    </div>
</div>
```

**Purpose:** Collect username before connecting to WebSocket

**Flow:**
1. Page loads → modal visible (`display: flex`)
2. User enters name, clicks "Join Game"
3. JavaScript validates input, hides modal
4. `app.js:connectWebSocket()` called

**Why Not URL Parameter?**
- Better UX (dedicated input field vs URL editing)
- Avoids logging usernames in server access logs
- Allows input validation before connection

#### Three-Column Layout (Lines 33-62)

```html
<!-- src/main/resources/jpro/html/index.html:33-62 -->
<div class="container">
    <!-- Left Column: Active Players -->
    <div class="players-panel">
        <h2>Players</h2>
        <div id="player1-display" class="player-card">
            <span id="player1-name">Waiting...</span>
            <span id="player1-marker" class="marker"></span>
        </div>
        <div id="player2-display" class="player-card">
            <span id="player2-name">Waiting...</span>
            <span id="player2-marker" class="marker"></span>
        </div>
    </div>

    <!-- Center Column: Game Board -->
    <div class="game-container">
        <div id="game-embed">
            <jpro-app href="http://127.0.0.1:8080/app/TTT?singleton=true"></jpro-app>
        </div>
    </div>

    <!-- Right Column: Queue List -->
    <div class="queue-panel">
        <h2>Queue</h2>
        <ul id="queue-list">
            <li>Waiting for players...</li>
        </ul>
    </div>
</div>
```

**Layout Proportions:**
- Players: 22% width
- Game: 56% width
- Queue: 22% width

**Why Center the Game?**
- Visual focus on gameplay
- Balanced layout with context (players + queue) on sides
- Responsive: stacks vertically on mobile (<992px)

#### JPro Embedding

```html
<!-- src/main/resources/jpro/html/index.html:48 -->
<jpro-app href="http://127.0.0.1:8080/app/TTT?singleton=true"></jpro-app>
```

**JPro Custom Element:** `<jpro-app>` is web component from `jpro.js`

**Parameters:**
- `href`: JPro application URL (defined in `jpro.conf`)
- `singleton=true`: Single shared instance across all browser tabs

**Why Singleton Mode?**
- **Original Intent:** Likely meant to share game state across tabs
- **Current Reality:** Each WebSocket connection is independent
- **Effect:** Minimal (could be removed without breaking functionality)

**Alternative (Non-Singleton):**
```html
<jpro-app href="http://127.0.0.1:8080/app/TTT"></jpro-app>
```
- Each tab gets independent JavaFX instance
- Higher memory usage on EC2 server

---

### 2. app.js - WebSocket Client Logic

**File:** `src/main/resources/jpro/html/app.js`

#### Global State

```javascript
// src/main/resources/jpro/html/app.js:1-3
let ws;              // WebSocket connection
let connectionId;    // Client's unique identifier from API Gateway
let username;        // Player's display name
```

**Why Global Variables?**
- Multiple functions need WebSocket reference (`connectWebSocket`, `send`)
- `connectionId` used in event listeners (`gameOverEvent`, `updateQueueUI`)
- `username` stored for potential reconnection logic

**Production Improvement:**
- Use module pattern or ES6 class to encapsulate state
- Avoid polluting global scope

#### DOMContentLoaded - Username Modal Handler

```javascript
// src/main/resources/jpro/html/app.js:5-18
document.addEventListener('DOMContentLoaded', () => {
    const modal = document.getElementById('username-modal');
    const input = document.getElementById('username-input');
    const button = document.getElementById('join-button');

    button.addEventListener('click', () => {
        const enteredUsername = input.value.trim();

        if (enteredUsername) {
            username = enteredUsername;
            modal.style.display = 'none';  // Hide modal
            connectWebSocket();            // Establish WebSocket connection
        } else {
            alert('Please enter a valid username.');
        }
    });
});
```

**Flow:**
1. User enters username, clicks button
2. Validate non-empty input
3. Hide modal (CSS transition)
4. Call `connectWebSocket()` to start multiplayer session

**Enhancement Opportunity:**
- Enter key should submit (not just button click)
- Better validation (length limits, special characters)
- Remember username in `localStorage`

#### gameOverEvent Listener - Java→JavaScript Bridge

```javascript
// src/main/resources/jpro/html/app.js:20-32
document.addEventListener('gameOverEvent', (event) => {
    const { loserId, winnerId } = event.detail;

    console.log(`Game over! Winner: ${winnerId}, Loser: ${loserId}`);

    // Send gameOVER message to WebSocket
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({
            action: 'gameOVER',
            loserId: loserId
        }));
    }
});
```

**Triggered By:** `views/ButtonView.java:156` or `views/TextAreaView.java` (similar line)

**Java Side:**
```java
// src/main/java/model/WebApi.java:13-23
var window = WebAPI.getWebAPI(stage).getWindow();
window.executeScript(String.format(
    "window.dispatchEvent(new CustomEvent('gameOverEvent', { " +
    "detail: { loserId: '%s', winnerId: '%s' } }));",
    loserId, winnerId
));
```

**Why Custom DOM Event?**
- **JPro Limitation:** No direct Java→JavaScript function call
- **Web Standard:** `CustomEvent` works in all browsers
- **Decoupling:** Java doesn't need to know WebSocket details

**Data Flow:**
```
Java: game.didWin('X') returns true
   ↓
ButtonView: Determines loserId (connectionId of losing player)
   ↓
WebApi.alertEndGame(stage, loserId, winnerId)
   ↓
Dispatch 'gameOverEvent' in browser DOM
   ↓
app.js listener catches event
   ↓
Send WebSocket message to gameOver.py Lambda
```

**Critical Question:** How does Java know connectionIds?

**Answer:** It doesn't! The current code has a gap:
- Java doesn't have access to `connectionId` variable
- `loserId` and `winnerId` would be undefined in Java
- **Likely Fix:** Pass connectionIds to JavaFX via URL parameters or JPro WebAPI

**Inferred Fix (Not in Code):**
```javascript
// When loading JPro app, pass connectionId:
const jproApp = document.querySelector('jpro-app');
jproApp.setAttribute('href', `http://127.0.0.1:8080/app/TTT?userId=${connectionId}`);

// Java reads from command-line arguments or jpro.conf
```

#### connectWebSocket() - Establish Connection

```javascript
// src/main/resources/jpro/html/app.js:34-82
function connectWebSocket() {
    const wsUrl = 'wss://wqritmruc9.execute-api.us-east-1.amazonaws.com/production';
    ws = new WebSocket(wsUrl);

    ws.onopen = () => {
        console.log('WebSocket connection established');
    };

    // Listen for sendInfo message (contains connectionId)
    ws.addEventListener('message', (event) => {
        const data = JSON.parse(event.data);

        if (data.connectionId && !connectionId) {
            connectionId = data.connectionId;
            console.log(`Received connectionId: ${connectionId}`);

            // Send username to backend
            ws.send(JSON.stringify({
                action: 'updateDB',
                connectionId: connectionId,
                username: username
            }));
        }
    });

    ws.onmessage = (event) => {
        const message = JSON.parse(event.data);

        if (message.action === 'queueUpdate') {
            updateQueueUI(message.data);
        }
    };

    ws.onclose = () => {
        console.log('WebSocket connection closed');
        alert('Connection lost. Please refresh the page.');
    };

    ws.onerror = (error) => {
        console.error('WebSocket error:', error);
    };
}
```

**Event Handlers:**

**1. `onopen` (Connection Established)**
- Logs success
- At this point: `connection.py` Lambda has executed
- User record created in DynamoDB with `status='inactive'`

**2. `addEventListener('message')` (First Message Only)**
- Purpose: Capture `connectionId` from `sendInfo.py`
- Condition: `if (data.connectionId && !connectionId)` ensures only runs once
- **After Receiving ID:** Send `updateDB` message with username

**Why Two Message Handlers?**
```javascript
ws.addEventListener('message', ...)  // Fires first (captures connectionId)
ws.onmessage = (event) => { ... }    // Fires second (handles queueUpdate)
```

**Issue:** Both handlers fire for every message!

**Better Approach:**
```javascript
ws.onmessage = (event) => {
    const data = JSON.parse(event.data);

    if (data.connectionId && !connectionId) {
        // Handle sendInfo
        connectionId = data.connectionId;
        ws.send(JSON.stringify({
            action: 'updateDB',
            connectionId: connectionId,
            username: username
        }));
    } else if (data.action === 'queueUpdate') {
        // Handle queue broadcasts
        updateQueueUI(data.data);
    }
};
```

**3. `onmessage` (Queue Updates)**
- Receives broadcasts from `streamDB.py`
- Message format:
  ```json
  {
    "action": "queueUpdate",
    "data": [
      {"connectionId": "abc", "username": "Player1", "status": "active", "marker": "X"},
      {"connectionId": "def", "username": "Player2", "status": "inactive", "marker": null}
    ]
  }
  ```
- Calls `updateQueueUI()` to refresh UI

**4. `onclose` (Connection Lost)**
- Alerts user (could auto-reconnect instead)
- No cleanup needed (browser garbage collects WebSocket)

**5. `onerror` (Connection Failed)**
- Logs to console
- Could show user-friendly error message

**Hardcoded WebSocket URL:**
```javascript
const wsUrl = 'wss://wqritmruc9.execute-api.us-east-1.amazonaws.com/production';
```

**Production Fix:**
- Environment variable or config file
- Use same domain as main site (e.g., `wss://api.tttlexc24.it.com/ws`)

#### updateQueueUI() - Render Queue State

```javascript
// src/main/resources/jpro/html/app.js:84-118
function updateQueueUI(queueData) {
    // Separate active and inactive players
    const activePlayers = queueData.filter(user => user.status === 'active');
    const inactivePlayers = queueData.filter(user => user.status === 'inactive');

    // Update active players display
    changeActivePlayersUI(activePlayers);

    // Enable/disable game interaction
    setActiveCharacters(queueData);

    // Update queue list
    const queueList = document.getElementById('queue-list');
    queueList.innerHTML = '';  // Clear existing

    if (inactivePlayers.length === 0) {
        queueList.innerHTML = '<li>No players in queue</li>';
    } else {
        inactivePlayers.forEach(player => {
            const li = document.createElement('li');
            li.textContent = player.username || 'Anonymous';
            queueList.appendChild(li);
        });
    }
}
```

**Why Filter by Status?**
- Active players (≤2) shown in left panel
- Inactive players shown in right queue panel
- Ensures UI reflects backend state

**XSS Vulnerability:**
```javascript
li.textContent = player.username || 'Anonymous';
```
- ✅ `textContent` is safe (escapes HTML)
- ❌ If using `innerHTML`, malicious username could inject scripts

#### changeActivePlayersUI() - Update Player Cards

```javascript
// src/main/resources/jpro/html/app.js:120-152
function changeActivePlayersUI(activePlayers) {
    const player1Name = document.getElementById('player1-name');
    const player1Marker = document.getElementById('player1-marker');
    const player2Name = document.getElementById('player2-name');
    const player2Marker = document.getElementById('player2-marker');

    if (activePlayers.length >= 1) {
        player1Name.textContent = activePlayers[0].username || 'Anonymous';
        player1Marker.textContent = activePlayers[0].marker || '';
    } else {
        player1Name.textContent = 'Waiting...';
        player1Marker.textContent = '';
    }

    if (activePlayers.length >= 2) {
        // Avoid duplicate display (don't show same player twice)
        const player2 = activePlayers.find(p => p.connectionId !== activePlayers[0].connectionId);
        if (player2) {
            player2Name.textContent = player2.username || 'Anonymous';
            player2Marker.textContent = player2.marker || '';
        }
    } else {
        player2Name.textContent = 'Waiting...';
        player2Marker.textContent = '';
    }
}
```

**Edge Case Handling:**
- `activePlayers.length === 0`: Both players show "Waiting..."
- `activePlayers.length === 1`: Player 1 filled, Player 2 "Waiting..."
- `activePlayers.length === 2`: Both players filled

**Duplicate Prevention:**
```javascript
const player2 = activePlayers.find(p => p.connectionId !== activePlayers[0].connectionId);
```
- Ensures Player 1 and Player 2 are different users
- Shouldn't happen in normal flow, but defensive programming

#### setActiveCharacters() - Enable/Disable Game

```javascript
// src/main/resources/jpro/html/app.js:154-167
function setActiveCharacters(queueData) {
    const currentUser = queueData.find(user => user.connectionId === connectionId);

    if (currentUser) {
        const isActive = currentUser.status === 'active';
        enableGameInteraction(isActive);
    }
}

function enableGameInteraction(isEnabled) {
    const gameEmbed = document.getElementById('game-embed');

    if (isEnabled) {
        gameEmbed.style.opacity = '1.0';
        gameEmbed.style.pointerEvents = 'auto';
    } else {
        gameEmbed.style.opacity = '0.2';
        gameEmbed.style.pointerEvents = 'none';
    }
}
```

**Visual Feedback:**
- **Active:** Game board full opacity, clickable
- **Inactive:** Game board grayed out (20% opacity), not clickable

**Why `pointerEvents: 'none'`?**
- Prevents clicks from reaching JavaFX buttons
- User cannot make moves while in queue
- Alternative: Could hide game entirely, but this way user sees ongoing match

**User Experience:**
1. User joins queue → game grayed out
2. Player disconnects → user promoted to active
3. `streamDB.py` broadcasts update
4. `updateQueueUI()` → `setActiveCharacters()` → game enabled
5. User can now click buttons

---

### 3. JPro Configuration

**File:** `src/main/resources/jpro.conf`

```
jpro.applications {
    "TTT" = views.GUI
}
```

**Purpose:** Maps URL path to JavaFX Application class

**URL Mapping:**
```
http://127.0.0.1:8080/app/TTT  →  views.GUI.main()
```

**JPro Compilation:**
1. JavaFX bytecode compiled to JavaScript + WebSocket protocol
2. JPro server renders JavaFX scenegraph in browser
3. User clicks button → WebSocket message → JPro server → Java button handler

**Alternative Approaches:**
- **GluonHQ:** Compiles JavaFX to native mobile apps
- **Vaadin:** Java framework rendering to HTML/JS (no JavaFX)
- **Pure JavaScript:** Rewrite game in React/Vue (loses Java codebase)

---

### 4. CSS Styling

**File:** `src/main/resources/jpro/html/updated-css.css`

#### Theme Colors

```css
/* Cardinal Red & Navy Blue Color Scheme */
--cardinal-red: #8C1515;
--navy-blue: #002147;
--light-gray: #f8f8f8;
```

**Why These Colors?**
- Cardinal red: Button accents, headers
- Navy blue: Player/queue panels
- Light gray: Container background

#### Responsive Layout

```css
/* src/main/resources/jpro/html/updated-css.css:50-60 */
@media (max-width: 992px) {
    .container {
        flex-direction: column;  /* Stack vertically */
    }

    .players-panel, .game-container, .queue-panel {
        width: 100%;  /* Full width on mobile */
    }
}
```

**Breakpoint:** 992px (tablet width)

**Desktop (>992px):**
```
┌─────────┬──────────────┬─────────┐
│ Players │     Game     │  Queue  │
│  22%    │     56%      │   22%   │
└─────────┴──────────────┴─────────┘
```

**Mobile (<992px):**
```
┌───────────────┐
│    Players    │
├───────────────┤
│     Game      │
├───────────────┤
│     Queue     │
└───────────────┘
```

#### Game Embed Styling

```css
/* src/main/resources/jpro/html/updated-css.css:90-95 */
#game-embed {
    width: 100%;
    height: 600px;
    transition: opacity 0.3s ease;  /* Smooth fade when disabled */
}
```

**Height:** Fixed 600px (JPro app renders at this size)

**Transition:** Smooth opacity change when toggling `enableGameInteraction()`

---

## Critical Integration Points

### 1. Java → JavaScript Communication

**Challenge:** JavaFX runs on server, needs to trigger JavaScript in browser.

**Solution:** JPro WebAPI + CustomEvent

```java
// src/main/java/model/WebApi.java:18-21
var window = WebAPI.getWebAPI(stage).getWindow();
window.executeScript(
    "window.dispatchEvent(new CustomEvent('gameOverEvent', { detail: {...} }));"
);
```

**JavaScript Listener:**
```javascript
// src/main/resources/jpro/html/app.js:20
document.addEventListener('gameOverEvent', (event) => {
    const { loserId, winnerId } = event.detail;
    ws.send(JSON.stringify({ action: 'gameOVER', loserId }));
});
```

**Why Not Direct WebSocket from Java?**
- JavaFX runs on server, doesn't have browser's WebSocket instance
- JavaScript owns WebSocket connection (`app.js:ws` variable)
- JPro allows JavaScript execution, not direct variable access

### 2. JavaScript → Java Communication

**Challenge:** How does Java know which marker (X/O) current user has?

**Current Gap:** Java doesn't receive connectionId or marker from JavaScript.

**Inferred Solution (Not Visible in Code):**

**Option A: URL Parameters**
```javascript
// app.js (after receiving connectionId and marker):
const jproApp = document.querySelector('jpro-app');
jproApp.setAttribute('href',
    `http://127.0.0.1:8080/app/TTT?userId=${connectionId}&marker=${marker}`
);
```

```java
// views/GUI.java (read parameters):
Parameters params = getParameters();
String userId = params.getRaw().get(0);  // connectionId
String userMarker = params.getRaw().get(1);  // 'X' or 'O'
```

**Option B: JPro WebAPI (Reverse)**
```java
// Periodically query JavaScript variable:
var window = WebAPI.getWebAPI(stage).getWindow();
String connectionId = (String) window.executeScript("return window.connectionId;");
```

**Option C: Hidden Input Field**
```html
<input type="hidden" id="connection-id" value="">
```

```javascript
// After receiving connectionId:
document.getElementById('connection-id').value = connectionId;
```

```java
// Java reads from HTML element:
var elem = document.getElementById("connection-id");
String connectionId = elem.getAttribute("value");
```

### 3. WebSocket Message Flow

**User Connects:**
```
1. index.html loads → Username modal shown
   ↓
2. User enters name → app.js:connectWebSocket()
   ↓
3. new WebSocket(wsUrl) → API Gateway $connect route
   ↓
4. connection.py creates DynamoDB record
   ↓
5. connection.py invokes sendInfo.py
   ↓
6. sendInfo.py sends { connectionId: 'abc123' } to client
   ↓
7. app.js receives message, stores connectionId
   ↓
8. app.js sends updateDB with username
   ↓
9. updateDB.py updates DynamoDB → Stream event
   ↓
10. streamDB.py broadcasts queueUpdate to all clients
    ↓
11. app.js:updateQueueUI() updates UI
```

**Game Ends:**
```
1. User clicks button in JavaFX → ButtonView.java:whichButton handler
   ↓
2. ButtonView calls game.humanMove(row, col)
   ↓
3. TicTacToeGame updates board, notifies observers
   ↓
4. ButtonView.update() refreshes button text
   ↓
5. ButtonView.checkGame() detects win/tie
   ↓
6. WebApi.alertEndGame(stage, loserId, winnerId)
   ↓
7. JavaScript gameOverEvent dispatched
   ↓
8. app.js sends WebSocket message { action: 'gameOVER', loserId }
   ↓
9. gameOver.py updates loser to inactive, promotes next player
   ↓
10. DynamoDB Stream → streamDB.py → broadcast
    ↓
11. All clients receive new queue state
    ↓
12. Winner's game stays enabled, loser's game grayed out
```

---

## Debugging Tips

### Browser Developer Console

**Check WebSocket Status:**
```javascript
// In browser console:
ws.readyState
// 0 = CONNECTING, 1 = OPEN, 2 = CLOSING, 3 = CLOSED
```

**Inspect Global State:**
```javascript
console.log({ connectionId, username, ws });
```

**Test Message Sending:**
```javascript
ws.send(JSON.stringify({ action: 'updateDB', username: 'TestUser' }));
```

### Common Issues

**Issue 1: Game Not Loading**
- **Check:** Network tab for `http://127.0.0.1:8080/app/TTT` request
- **Likely Cause:** EC2 instance down, Nginx/JPro not running
- **Fix:** SSH to EC2, `sudo systemctl status jpro nginx`

**Issue 2: WebSocket Connection Fails**
- **Check:** Console for `WebSocket error` log
- **Likely Cause:** API Gateway endpoint changed, CORS issue
- **Fix:** Verify `wsUrl` in `app.js` matches deployed API Gateway

**Issue 3: Queue Not Updating**
- **Check:** Console for `queueUpdate` messages
- **Likely Cause:** `streamDB.py` not triggered, DynamoDB Streams disabled
- **Fix:** Verify DynamoDB table has Streams enabled in AWS console

**Issue 4: Game Interaction Disabled**
- **Check:** `connectionId` variable populated
- **Likely Cause:** `sendInfo.py` not sending connectionId
- **Debug:**
  ```javascript
  console.log('Current user:', queueData.find(u => u.connectionId === connectionId));
  ```

---

## Performance Considerations

### Latency Breakdown

**User Joins Queue:**
```
WebSocket connect:          ~50ms   (TLS handshake)
connection.py:              ~100ms  (DynamoDB write + Lambda invokes)
joinQueue.py:               ~80ms   (Query + UpdateItem)
streamDB.py:                ~200ms  (Scan + broadcast)
Browser receives update:    ~30ms   (WebSocket message)
────────────────────────────────────
Total:                      ~460ms
```

**Game Move (Local):**
```
Button click:               <1ms    (JavaScript event)
JPro WebSocket:             ~10ms   (Browser → EC2)
JavaFX handler:             <5ms    (Game logic)
JPro render update:         ~10ms   (EC2 → Browser)
────────────────────────────────────
Total:                      ~26ms
```

**Why Game Feels Responsive:**
- JPro maintains persistent WebSocket (no HTTP overhead)
- Game logic runs server-side (no network latency for moves)
- Only multiplayer coordination (queue updates) has ~460ms delay

### Bandwidth Usage

**Initial Page Load:**
```
index.html:         ~5 KB
updated-css.css:    ~10 KB
app.js:             ~5 KB
jpro.js:            ~200 KB  (JPro runtime library)
JavaFX assets:      ~500 KB  (buttons, scenegraph)
────────────────────────────
Total:              ~720 KB
```

**Ongoing:**
```
WebSocket messages: ~500 bytes per queueUpdate
JPro rendering:     ~1-5 KB per user interaction
```

**Optimization Opportunity:**
- JPro assets cacheable (set long `Cache-Control` headers)
- Compress WebSocket messages (gzip)
- Use binary WebSocket format instead of JSON

---

## Security Considerations

### XSS Prevention

**Safe:**
```javascript
li.textContent = player.username;  // Escapes HTML
```

**Unsafe:**
```javascript
li.innerHTML = `<strong>${player.username}</strong>`;  // Vulnerable to XSS
```

**Example Attack:**
```
Username: <script>alert(document.cookie)</script>
Result: Script executes in all browsers viewing queue
```

**Mitigation:** Always use `textContent` for user-generated content.

### WebSocket Security

**Current State:**
- ✅ WSS (encrypted WebSocket)
- ✅ HTTPS for page load
- ⚠️ No authentication (anyone can connect)
- ⚠️ No authorization (any client can send any action)

**Production Fixes:**
1. **Authentication:** Require OAuth token in WebSocket `$connect`
2. **Authorization:** Validate user can only update their own record
3. **Rate Limiting:** Prevent spam via API Gateway throttling
4. **Input Validation:** Sanitize usernames, reject special characters

### CORS Configuration

**Nginx Config (user_data.sh):**
```nginx
add_header Access-Control-Allow-Origin "https://tttlexc24.it.com";
```

**Why Needed:**
- JPro app served from `api.tttlexc24.it.com`
- If assets loaded from different domain, browser blocks (CORS policy)

**Current Issue:**
- Hardcoded to single domain (doesn't work on `localhost` for development)

**Better Approach:**
```nginx
if ($http_origin ~* "^https://(tttlexc24\.it\.com|localhost:8080)$") {
    add_header Access-Control-Allow-Origin "$http_origin";
}
```

---

## Related Documentation

- **JavaFX Game Logic:** `GAME_LOGIC.md`
- **Lambda WebSocket Handlers:** `LAMBDA_FUNCTIONS.md`
- **WebSocket vs REST Decision:** `adr/001-websocket-vs-rest-api.md`
- **DynamoDB Broadcasting:** `adr/002-dynamodb-streams-for-realtime.md`
