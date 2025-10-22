# Tic Tac Toe Game

> A full-stack Tic Tac Toe application with JavaFX desktop UI, web interface, and cloud-based multiplayer functionality powered by AWS.

![Java](https://img.shields.io/badge/Java-JavaFX-orange)
![Python](https://img.shields.io/badge/Python-AWS%20Lambda-blue)
![AWS](https://img.shields.io/badge/AWS-DynamoDB%20%7C%20API%20Gateway%20%7C%20Lambda-yellow)
![Terraform](https://img.shields.io/badge/IaC-Terraform-purple)

---

## Overview

This project is a sophisticated implementation of the classic Tic Tac Toe game that demonstrates modern software architecture patterns and cloud-native development. It features a rich JavaFX desktop application that can also be deployed as a web application using JPro, combined with a serverless AWS backend for multiplayer queue management.

The application showcases the **Observer design pattern** for reactive UI updates, **Strategy pattern** for pluggable AI difficulty levels, and implements a complete cloud infrastructure using **Infrastructure as Code (Terraform)**. Players can enjoy single-player games against AI opponents of varying difficulty, local two-player matches, or join an online queue system for web-based multiplayer gaming.

Whether you're learning about design patterns, exploring JavaFX development, or studying cloud-native architectures, this project provides a comprehensive example of integrating desktop applications with modern web and cloud technologies.

---

## Key Features

- **Multiple AI Difficulty Levels**
  - **Random AI**: Makes random valid moves (easy mode)
  - **Intermediate AI**: Strategic gameplay with win/block detection, corner and center preferences

- **Flexible Viewing Options**
  - **Button View**: Interactive 3x3 grid of clickable buttons
  - **TextArea View**: Text-based board representation

- **Multiple Game Modes**
  - Single-player vs Computer AI
  - Local two-player mode
  - Web-based multiplayer with queue system

- **Observer Pattern Implementation**
  - Reactive UI updates across multiple views
  - Clean separation between game logic and presentation

- **Cloud-Native Architecture**
  - WebSocket-based real-time communication via AWS API Gateway
  - Serverless queue management with AWS Lambda (Python)
  - DynamoDB for player state and queue persistence
  - DynamoDB Streams for real-time queue updates across all clients
  - Auto-scaling infrastructure with Application Load Balancer

- **Infrastructure as Code**
  - Complete AWS infrastructure defined in Terraform
  - Reproducible deployments with VPC, EC2, ALB, and security groups

---

## Tech Stack

### Frontend
- **JavaFX** - Desktop UI framework
- **JPro** - JavaFX-to-Web compilation framework
- **HTML5/CSS3/JavaScript** - Web interface and WebSocket client

### Backend
- **Java** - Core game logic and business rules
- **Python** - AWS Lambda functions for queue management and event handling
- **AWS Lambda** - Serverless compute for WebSocket routes and game events
- **AWS DynamoDB** - NoSQL database for player state and queue management
- **AWS DynamoDB Streams** - Real-time change data capture for queue updates
- **AWS API Gateway** - WebSocket API endpoint for bi-directional communication

### Infrastructure
- **Terraform** - Infrastructure as Code for AWS resources
- **AWS VPC** - Network isolation and security
- **AWS EC2** - JPro application server
- **AWS Application Load Balancer** - Traffic distribution and SSL termination
- **AWS CloudFormation** - DynamoDB table provisioning

### Design Patterns
- **Observer Pattern** - For model-view updates
- **Strategy Pattern** - For AI difficulty levels

---

## Prerequisites

### For Desktop Application
- **Java Development Kit (JDK) 11 or higher**
- **JavaFX SDK** (if not bundled with your JDK)
- **JPro** (for web deployment)

### For AWS Infrastructure
- **AWS CLI** configured with appropriate credentials
- **Terraform** 1.0+
- **Python 3.8+** (for Lambda functions)
- **boto3** Python library

### System Requirements
- **OS**: Windows, macOS, or Linux
- **RAM**: 2GB minimum
- **Disk Space**: 500MB

---

## Installation

### Desktop Application Setup

Since this project uses JPro for JavaFX-to-Web compilation, you'll need to set up JPro:

1. **Clone the repository**
   ```bash
   git clone <repository-url>
   cd tictactoe
   ```

2. **Install JPro**

   Follow the [JPro installation guide](https://www.jpro.one/) to install JPro on your system.

3. **Configure JPro**

   The project includes a JPro configuration file at `src/main/resources/jpro.conf`:
   ```
   jpro.applications {
       "TTT" = views.GUI
   }
   ```

4. **Build the application**
   ```bash
   # Use your JPro build command (typically via Gradle or Maven)
   jpro run
   ```

5. **Run locally**
   ```bash
   jpro run
   ```

### AWS Infrastructure Setup

1. **Navigate to the AWS directory**
   ```bash
   cd aws
   ```

2. **Deploy DynamoDB table**
   ```bash
   aws cloudformation create-stack \
     --stack-name tictactoe-dynamodb \
     --template-body file://template.yaml
   ```

3. **Deploy VPC infrastructure**
   ```bash
   cd vpc/mod1
   terraform init
   terraform plan
   terraform apply
   ```

4. **Deploy Security Groups**
   ```bash
   cd ../mod2
   terraform init
   terraform apply
   ```

5. **Deploy EC2 and ALB**
   ```bash
   cd ../mod3
   terraform init
   terraform apply
   ```

6. **Deploy Lambda functions**

   Package and deploy all Lambda functions using AWS SAM or the AWS Console:
   ```bash
   cd ../../lambda

   # Package all Lambda functions (ensure utility.py is included in each)
   for func in connection disconnect joinQueue gameOver sendInfo streamDB updateDB; do
     zip -r ${func}.zip ${func}.py utility.py
   done

   # Deploy via AWS CLI (repeat for each function)
   # Example for connection handler:
   aws lambda create-function \
     --function-name connection \
     --runtime python3.8 \
     --handler connection.handler \
     --zip-file fileb://connection.zip \
     --role <your-lambda-execution-role-arn>

   # Configure API Gateway WebSocket routes:
   # - $connect -> connection.handler
   # - $disconnect -> disconnect.handler
   # - joinQueue -> joinQueue.handler
   # - gameOVER -> gameOver.handler
   # - updateDB -> updateDB.lambda_handler
   # - sendInfo -> sendInfo.lambda_handler

   # Enable DynamoDB Streams on TicTacToeUsers table
   # and configure streamDB.lambda_handler as the stream processor
   ```

---

## Usage

### Running the Desktop Application

```bash
# From the project root
jpro run

# The JavaFX application will launch with a menu bar offering:
# - Strategies Menu: Choose AI difficulty (RandomAI, IntermediateAI, Player 2)
# - Views Menu: Toggle between Button and TextArea views
# - Options Menu: Start a new game
```

### Playing the Game

1. **Single Player Mode**
   - Select an AI strategy from the **Strategies** menu (RandomAI or IntermediateAI)
   - Click on any empty cell to make your move (you play as 'X')
   - The computer will automatically respond (plays as 'O')
   - The game announces the winner or tie

2. **Two Player Mode**
   - Select **Player 2** from the **Strategies** menu
   - Players alternate clicking cells to make moves
   - First player is 'X', second player is 'O'

3. **Changing Views**
   - Select **Button** or **TextArea** from the **Views** menu
   - The game board updates in real-time across all views

### Running the Web Application

1. **Access the web interface**

   After deploying to AWS, navigate to your Application Load Balancer URL or configured domain.

2. **Enter username**

   A modal will prompt for your username when first connecting.

3. **Join the queue**

   The application automatically connects to the WebSocket API Gateway endpoint:
   ```javascript
   wss://wqritmruc9.execute-api.us-east-1.amazonaws.com/production
   ```

4. **Queue system**
   - First two players are marked as "active" and matched together
   - Additional players are placed in an "inactive" queue with timestamps
   - When a game ends, the next player in queue is activated

---

## Project Structure

```
tictactoe/
├── aws/                              # AWS Infrastructure
│   ├── lambda/                       # Serverless functions
│   │   ├── connection.py            # WebSocket $connect handler
│   │   ├── disconnect.py            # WebSocket $disconnect handler
│   │   ├── joinQueue.py             # Manages player queue, assigns markers
│   │   ├── gameOver.py              # Handles game completion, queue progression
│   │   ├── sendInfo.py              # Sends connectionId to newly connected clients
│   │   ├── streamDB.py              # DynamoDB Stream processor, broadcasts queue updates
│   │   └── updateDB.py              # Updates player username in database
│   ├── vpc/                         # Terraform modules
│   │   ├── mod1/vpct.tf            # VPC, subnets, route tables, IGW, NAT
│   │   ├── mod2/sgt.tf             # Security groups for ALB and EC2
│   │   └── mod3/
│   │       ├── ec2.tf              # EC2 instances, ALB, IAM roles
│   │       └── user_data.sh        # EC2 bootstrap script
│   └── template.yaml                # CloudFormation template for DynamoDB
│
├── src/
│   └── main/
│       ├── java/
│       │   ├── model/               # Core game logic
│       │   │   ├── TicTacToeGame.java       # Main game state and logic
│       │   │   ├── ComputerPlayer.java      # AI player wrapper
│       │   │   ├── TicTacToeStrategy.java   # Strategy interface
│       │   │   ├── RandomAI.java            # Random move AI
│       │   │   ├── IntermediateAI.java      # Strategic AI
│       │   │   ├── Player2.java             # Two-player mode
│       │   │   ├── OurObservable.java       # Observable base class
│       │   │   ├── OurObserver.java         # Observer interface
│       │   │   ├── OurPoint.java            # Coordinate class (row, col)
│       │   │   ├── WebApi.java              # Web API integration
│       │   │   └── NoWhereToGoExcep.java    # Custom exception
│       │   │
│       │   └── views/               # UI components
│       │       ├── GUI.java                 # Main JavaFX application
│       │       ├── ButtonView.java          # Button-based board view
│       │       └── TextAreaView.java        # Text-based board view
│       │
│       └── resources/
│           ├── jpro.conf            # JPro application configuration
│           └── jpro/html/
│               ├── index.html       # Web UI entry point
│               ├── app.js           # WebSocket client logic
│               ├── styles.css       # Web UI styling
│               └── updated-css.css  # Additional styles
│
└── .gitignore                       # Git ignore rules
```

### Key Components

#### Java Application
- **`model/TicTacToeGame.java`** - Core game engine with 3x3 board, move validation, win detection
- **`model/ComputerPlayer.java`** - Delegates to strategy implementations
- **`model/TicTacToeStrategy.java`** - Interface for AI strategies (Strategy Pattern)
- **`views/GUI.java`** - Main application with menu bar and view management
- **`views/ButtonView.java`** - Observable view with clickable button grid
- **`views/TextAreaView.java`** - Observable view with text representation

#### AWS Lambda Functions
- **`connection.py`** - Handles WebSocket $connect route, creates user in DynamoDB, invokes joinQueue and sendInfo
- **`disconnect.py`** - Handles WebSocket $disconnect route, removes user from queue, fills active slots
- **`joinQueue.py`** - Processes queue join requests, assigns X/O markers, sets active/inactive status
- **`gameOver.py`** - Processes game completion events, marks players inactive, advances queue
- **`sendInfo.py`** - Sends connectionId and session information to newly connected clients
- **`streamDB.py`** - DynamoDB Stream processor, broadcasts real-time queue updates to all connected clients
- **`updateDB.py`** - Updates player username in DynamoDB when submitted via client

---

## API Documentation

### WebSocket API

The application uses AWS API Gateway WebSocket API for real-time bi-directional communication.

**Endpoint**: `wss://wqritmruc9.execute-api.us-east-1.amazonaws.com/production`

### WebSocket Routes

| Route | Lambda Handler | Description |
|-------|---------------|-------------|
| `$connect` | `connection.handler` | Establishes WebSocket connection, creates user record |
| `$disconnect` | `disconnect.handler` | Cleans up on disconnect, refills active slots |
| `updateDB` | `updateDB.lambda_handler` | Updates username in DynamoDB |
| `joinQueue` | `joinQueue.handler` | Adds player to queue or activates them |
| `gameOVER` | `gameOver.handler` | Handles game completion, advances queue |
| `sendInfo` | `sendInfo.lambda_handler` | Sends connection info to client |

### Events

#### 1. Connection Flow ($connect)

**Trigger**: WebSocket connection established

**Process**:
1. `connection.handler` creates user in DynamoDB with status='inactive'
2. Generates unique `sessionId` (UUID)
3. Invokes `joinQueue` lambda to assign marker and set status
4. Invokes `sendInfo` lambda to return connectionId to client

**Server -> Client (via sendInfo)**:
```javascript
{
  "message": "Hello from Lambda!",
  "connectionId": "abc123xyz"
}
```

#### 2. Update Username (updateDB)

**Client -> Server**:
```javascript
{
  "action": "updateDB",
  "connectionId": "abc123xyz",
  "username": "Player1"
}
```

**Response**:
- Updates DynamoDB item with username
- Triggers DynamoDB Stream event

#### 3. Join Queue (joinQueue)

**Trigger**: Invoked automatically by `connection.handler` or manually

**Logic**:
- Count active players (status='active')
- If < 2 active: Set user to 'active', assign marker (X or O)
- If >= 2 active: Set user to 'inactive' with joinedAt timestamp

**Server -> Client (Active)**:
```javascript
{
  "action": "joinQueue",
  "status": "active",
  "marker": "X",
  "message": "You are now active in the game with marker 'X'."
}
```

**Server -> Client (Queued)**:
```javascript
{
  "action": "joinQueue",
  "status": "inactive",
  "message": "You have joined the queue. Please wait for your turn."
}
```

#### 4. Queue Updates (DynamoDB Streams)

**Trigger**: Any INSERT, MODIFY, or REMOVE event in DynamoDB

**Process**:
1. `streamDB.lambda_handler` processes stream records
2. Fetches current queue state (all users sorted by joinedAt)
3. Broadcasts to all connected clients

**Server -> All Clients**:
```javascript
{
  "action": "queueUpdate",
  "data": [
    {
      "connectionId": "conn1",
      "username": "Player1",
      "status": "active",
      "marker": "X",
      "joinedAt": "2025-01-15T10:30:00"
    },
    {
      "connectionId": "conn2",
      "username": "Player2",
      "status": "active",
      "marker": "O",
      "joinedAt": "2025-01-15T10:30:05"
    },
    {
      "connectionId": "conn3",
      "username": "Player3",
      "status": "inactive",
      "marker": null,
      "joinedAt": "2025-01-15T10:30:10"
    }
  ]
}
```

#### 5. Game Over (gameOVER)

**Client -> Server**:
```javascript
{
  "action": "gameOVER",
  "data": {
    "winner": "Player1",
    "loser": "Player2"
  }
}
```

**Process**:
1. `gameOver.handler` receives event
2. Marks both players as 'inactive'
3. Queries queue for next 2 inactive players (by joinedAt ascending)
4. Promotes them to 'active' with assigned markers
5. DynamoDB Stream triggers queue update broadcast

#### 6. Disconnect Flow ($disconnect)

**Trigger**: WebSocket disconnection

**Process**:
1. `disconnect.handler` retrieves user status
2. Deletes user from DynamoDB
3. If user was 'active', calls `fill_active_slots()`:
   - Queries inactive users by joinedAt ascending
   - Promotes up to 2 users to active status
4. DynamoDB Stream broadcasts updated queue

### DynamoDB Schema

**Table Name**: `TicTacToeUsers`

**Primary Key**: `connectionId` (String)

**Attributes**:
- `connectionId` (String) - WebSocket connection identifier (Primary Key)
- `sessionId` (String) - Unique session identifier (UUID)
- `username` (String) - Player's display name
- `status` (String) - Player state: "active" or "inactive"
- `marker` (String) - Game marker: "X" or "O" (null for inactive players)
- `joinedAt` (String) - ISO 8601 timestamp for queue ordering

**Global Secondary Index**: `statusIndex`
- **Partition Key**: `status`
- **Sort Key**: `joinedAt`
- **Projection**: ALL attributes

**Query Example** (Python boto3):
```python
import boto3
from boto3.dynamodb.conditions import Key

dynamodb = boto3.resource('dynamodb')
table = dynamodb.Table('TicTacToeUsers')

# Get all active players
response = table.query(
    IndexName='statusIndex',
    KeyConditionExpression=Key('status').eq('active')
)
active_players = response['Items']

# Get queued players (sorted by join time)
response = table.query(
    IndexName='statusIndex',
    KeyConditionExpression=Key('status').eq('inactive'),
    ScanIndexForward=True  # Ascending order by joinedAt
)
queue = response['Items']
```

---

## Configuration

### JPro Configuration

Located at `src/main/resources/jpro.conf`:

```
jpro.applications {
    "TTT" = views.GUI
}
```

This maps the JPro application name "TTT" to the main GUI class `views.GUI`.

### AWS Configuration

#### DynamoDB Table

Defined in `aws/template.yaml`:
- **Table Name**: TicTacToeUsers
- **Billing Mode**: PAY_PER_REQUEST (on-demand)
- **Encryption**: Server-side encryption enabled
- **GSI**: statusIndex on (status, joinedAt)

#### WebSocket Endpoint

Update the WebSocket URL in `src/main/resources/jpro/html/app.js`:

```javascript
ws = new WebSocket('wss://YOUR_API_GATEWAY_ID.execute-api.REGION.amazonaws.com/STAGE');
```

#### Infrastructure Variables

Terraform modules in `aws/vpc/` may require variables for:
- AWS region
- VPC CIDR blocks
- Availability zones
- EC2 instance types
- AMI IDs

Consult the `.tf` files for specific variable requirements.

---

## Testing

**Note**: This repository currently does not include automated tests.

To add tests, consider:

1. **Unit Tests** for game logic
   ```java
   // Example using JUnit
   @Test
   public void testWinCondition() {
       TicTacToeGame game = new TicTacToeGame();
       game.humanTurn(0, 0); // X
       game.humanTurn(1, 0); // X
       game.humanTurn(2, 0); // X
       assertTrue(game.didWin('X'));
   }
   ```

2. **Integration Tests** for Lambda functions
   ```python
   # Example using pytest and moto
   import pytest
   from moto import mock_dynamodb
   from joinQueue import handler

   @mock_dynamodb
   def test_join_queue_when_less_than_two_active():
       # Setup mock DynamoDB
       # Invoke handler
       # Assert response
       pass
   ```

3. **UI Tests** for JavaFX components
   - Use TestFX framework for UI testing

### Manual Testing

Run the desktop application and verify:
- [ ] Single-player mode against RandomAI
- [ ] Single-player mode against IntermediateAI
- [ ] Two-player local mode
- [ ] Switching between Button and TextArea views
- [ ] Starting a new game
- [ ] Win detection for rows, columns, diagonals
- [ ] Tie detection when board is full

---

## Contributing

Contributions are welcome! Please follow these guidelines:

### Getting Started

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Make your changes
4. Commit your changes (`git commit -m 'Add some amazing feature'`)
5. Push to the branch (`git push origin feature/amazing-feature`)
6. Open a Pull Request

### Code Style

- **Java**: Follow standard Java naming conventions
  - Classes: `PascalCase`
  - Methods/Variables: `camelCase`
  - Constants: `UPPER_SNAKE_CASE`

- **Python**: Follow PEP 8 style guide
  - Use 4 spaces for indentation
  - Maximum line length: 100 characters

- **JavaScript**: Use ES6+ syntax
  - Use `const` and `let`, avoid `var`
  - Use arrow functions where appropriate

### Commit Messages

- Use present tense ("Add feature" not "Added feature")
- Use imperative mood ("Move cursor to..." not "Moves cursor to...")
- Reference issues and pull requests liberally

### Suggested Improvements

- [ ] Add comprehensive unit tests
- [ ] Implement additional AI strategies (Expert mode)
- [ ] Add game statistics and leaderboards
- [ ] Implement tournament bracket system
- [ ] Add sound effects and animations
- [ ] Create mobile-responsive design
- [ ] Add chat functionality for multiplayer
- [ ] Implement replay/spectator mode
- [ ] Add CI/CD pipeline (GitHub Actions)
- [ ] Create Docker containerization

---

## License

This project is available for educational purposes. No specific license has been declared.

For production use, consider adding an appropriate open-source license such as:
- **MIT License** - Permissive, allows commercial use
- **Apache 2.0** - Permissive with patent grant
- **GPL v3** - Copyleft, requires derivative works to be open source

To add a license, create a `LICENSE` file in the repository root and update this section.

---

## Contact / Author Information

**Authors**:
- **Lex Castaneda** - Primary developer
- **Rick Mercer** - Contributor

**Course**: CSC 335 (Software Engineering)

**Project**: Tic Tac Toe Game with JavaFX and AWS Integration

### Support

For questions, issues, or suggestions:
- Open an issue in the GitHub repository
- Contact the authors through the course portal

### Acknowledgments

- **JPro** - For enabling JavaFX-to-Web deployment
- **AWS** - For cloud infrastructure services
- **Terraform** - For Infrastructure as Code capabilities

---

## Architecture Diagram

```
┌──────────────────────────────────────────────────────────────────┐
│                         Client Layer                             │
├──────────────────────────────────────────────────────────────────┤
│   Desktop (JavaFX)          │      Web (HTML/CSS/JS)             │
│   - ButtonView              │      - index.html                  │
│   - TextAreaView            │      - app.js (WebSocket client)   │
│   - GUI (Menu)              │      - styles.css                  │
└─────────────┬───────────────┴──────────────┬─────────────────────┘
              │                              │
              │ (JPro)                       │ (WebSocket)
              ▼                              ▼
   ┌────────────────────┐        ┌────────────────────────┐
   │   JPro Server      │        │   API Gateway          │
   │   (EC2 + ALB)      │        │   (WebSocket Routes)   │
   └────────────────────┘        └──────────┬─────────────┘
                                            │
              ┌─────────────────────────────┼─────────────────────┐
              │                             │                     │
              ▼                             ▼                     ▼
    ┌──────────────────┐        ┌──────────────────┐  ┌──────────────────┐
    │  connection.py   │        │  disconnect.py   │  │   updateDB.py    │
    │  ($connect)      │        │  ($disconnect)   │  │   (route)        │
    └────────┬─────────┘        └─────────┬────────┘  └────────┬─────────┘
             │                            │                     │
             │ invokes                    │                     │
             ├──────────┬─────────────────┘                     │
             ▼          ▼                                       │
    ┌──────────────┐ ┌──────────────┐                          │
    │ joinQueue.py │ │ sendInfo.py  │                          │
    │   (route)    │ │   (route)    │                          │
    └──────┬───────┘ └──────────────┘                          │
           │                                                    │
           │         ┌─────────────────────────────────────────┘
           │         │
           │         │                    ┌──────────────────┐
           │         │                    │   gameOver.py    │
           │         │                    │     (route)      │
           │         │                    └────────┬─────────┘
           │         │                             │
           └─────────┼─────────────────────────────┘
                     ▼
          ┌────────────────────────┐
          │   DynamoDB Table       │
          │   TicTacToeUsers       │
          │   + statusIndex GSI    │
          │   + Streams ENABLED    │
          └───────────┬────────────┘
                      │
                      │ Stream (INSERT/MODIFY/REMOVE)
                      ▼
          ┌────────────────────────┐
          │   streamDB.py          │
          │   (Stream Processor)   │
          └───────────┬────────────┘
                      │
                      │ Broadcast queueUpdate
                      ▼
          ┌────────────────────────┐
          │   All Connected        │
          │   WebSocket Clients    │
          │   (Real-time updates)  │
          └────────────────────────┘
```

### Data Flow

**Connection Flow**:
1. Client connects → API Gateway `$connect` → `connection.py`
2. `connection.py` creates user in DynamoDB
3. `connection.py` invokes `joinQueue.py` and `sendInfo.py`
4. DynamoDB change triggers Stream → `streamDB.py` → Broadcast to all clients

**Game Over Flow**:
1. Client sends gameOVER event → `gameOver.py`
2. `gameOver.py` marks players inactive, promotes queue
3. DynamoDB changes trigger Stream → `streamDB.py` → Broadcast queue update

**Disconnect Flow**:
1. Client disconnects → `disconnect.py`
2. User removed from DynamoDB, active slots refilled
3. DynamoDB changes trigger Stream → `streamDB.py` → Broadcast queue update

---

**Happy Gaming!** Enjoy playing Tic Tac Toe and exploring the codebase.
