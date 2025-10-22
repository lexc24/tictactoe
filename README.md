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
- **Python** - AWS Lambda functions for queue management
- **AWS Lambda** - Serverless compute for game events
- **AWS DynamoDB** - NoSQL database for player state
- **AWS API Gateway** - WebSocket API endpoint

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

   Package and deploy the Lambda functions using AWS SAM or the AWS Console:
   ```bash
   cd ../../lambda
   # Package your Lambda function
   zip -r joinQueue.zip joinQueue.py utility.py
   zip -r gameOver.zip gameOver.py utility.py

   # Deploy via AWS CLI or Console
   aws lambda create-function \
     --function-name joinQueue \
     --runtime python3.8 \
     --handler joinQueue.handler \
     --zip-file fileb://joinQueue.zip \
     --role <your-lambda-execution-role-arn>
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
│   │   ├── gameOver.py              # Handles game completion, queue progression
│   │   └── joinQueue.py             # Manages player queue join events
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

- **`model/TicTacToeGame.java`** - Core game engine with 3x3 board, move validation, win detection
- **`model/ComputerPlayer.java`** - Delegates to strategy implementations
- **`model/TicTacToeStrategy.java`** - Interface for AI strategies (Strategy Pattern)
- **`views/GUI.java`** - Main application with menu bar and view management
- **`views/ButtonView.java`** - Observable view with clickable button grid
- **`views/TextAreaView.java`** - Observable view with text representation
- **`aws/lambda/joinQueue.py`** - Manages player queue, assigns markers (X/O)
- **`aws/lambda/gameOver.py`** - Handles game completion, advances queue

---

## API Documentation

### WebSocket API

The application uses AWS API Gateway WebSocket API for real-time communication.

**Endpoint**: `wss://wqritmruc9.execute-api.us-east-1.amazonaws.com/production`

#### Events

**1. Connection Establishment**
```javascript
// Client connects and receives connectionId
{
  "connectionId": "abc123xyz",
  "message": "Connected"
}
```

**2. Update Database (Join)**
```javascript
// Client -> Server
{
  "action": "updateDB",
  "connectionId": "abc123xyz",
  "username": "Player1"
}
```

**3. Queue Update**
```javascript
// Server -> Client
{
  "action": "queueUpdate",
  "data": {
    "activePlayer": ["player1", "player2"],
    "queue": ["player3", "player4"]
  }
}
```

**4. Join Queue Response**
```javascript
// Server -> Client (Active)
{
  "action": "joinQueue",
  "status": "active",
  "marker": "X",
  "message": "You are now active in the game with marker 'X'."
}

// Server -> Client (Queued)
{
  "action": "joinQueue",
  "status": "inactive",
  "message": "You have joined the queue. Please wait for your turn."
}
```

**5. Game Over**
```javascript
// Client -> Server
{
  "action": "gameOVER",
  "data": {
    "winner": "Player1",
    "loser": "Player2"
  }
}
```

### DynamoDB Schema

**Table Name**: `TicTacToeUsers`

**Primary Key**: `connectionId` (String)

**Attributes**:
- `connectionId` (String) - WebSocket connection identifier
- `username` (String) - Player's display name
- `status` (String) - Player state: "active" or "inactive"
- `marker` (String) - Game marker: "X" or "O" (null for inactive)
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
┌─────────────────────────────────────────────────────────────┐
│                      Client Layer                           │
├─────────────────────────────────────────────────────────────┤
│  Desktop (JavaFX)          │      Web (HTML/CSS/JS)         │
│  - ButtonView              │      - index.html              │
│  - TextAreaView            │      - app.js (WebSocket)      │
│  - GUI (Menu)              │      - styles.css              │
└──────────────┬─────────────┴────────────────┬───────────────┘
               │                              │
               │                              │
               │                              ▼
               │                    ┌──────────────────┐
               │                    │  API Gateway     │
               │                    │  (WebSocket)     │
               │                    └────────┬─────────┘
               │                             │
               ▼                             ▼
    ┌──────────────────┐         ┌──────────────────────┐
    │   JPro Server    │         │   Lambda Functions   │
    │   (EC2 + ALB)    │         │   - joinQueue.py     │
    └──────────────────┘         │   - gameOver.py      │
                                 └──────────┬───────────┘
                                            │
                                            ▼
                                 ┌──────────────────────┐
                                 │   DynamoDB Table     │
                                 │   TicTacToeUsers     │
                                 │   + statusIndex GSI  │
                                 └──────────────────────┘
```

---

**Happy Gaming!** Enjoy playing Tic Tac Toe and exploring the codebase.
