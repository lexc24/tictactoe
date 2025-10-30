# Game Logic Documentation

## Overview

The game logic is written in **JavaFX** using classic design patterns: **Observer** for UI reactivity and **Strategy** for pluggable AI opponents. The architecture cleanly separates model (game state), view (UI), and controller (event handlers).

This document focuses on **why design decisions were made** and **complex logic** that might be non-obvious when returning to the codebase.

## Class Architecture

```
┌────────────────────────────────────────────────────────────────┐
│                         views/                                 │
│  ┌──────────────┐  ┌─────────────────┐  ┌──────────────────┐  │
│  │    GUI       │  │  ButtonView     │  │  TextAreaView    │  │
│  │ (Entry Point)│  │  (Grid UI)      │  │  (Text Input UI) │  │
│  └──────┬───────┘  └────────┬────────┘  └────────┬─────────┘  │
│         │                   │                      │            │
│         │ implements        │ implements           │            │
│         │ OurObserver       │ OurObserver          │            │
│         └───────────────────┴──────────────────────┘            │
│                             │                                   │
└─────────────────────────────┼───────────────────────────────────┘
                              │ observes
                              ▼
┌────────────────────────────────────────────────────────────────┐
│                         model/                                 │
│  ┌─────────────────────────────────────────────────────────┐  │
│  │               TicTacToeGame                             │  │
│  │               extends OurObservable                     │  │
│  │  ┌──────────────────────────────────────────────────┐  │  │
│  │  │ - char[][] board                                 │  │  │
│  │  │ - int count (move counter)                       │  │  │
│  │  │ - ComputerPlayer computerPlayer                  │  │  │
│  │  │ + humanMove(row, col)                            │  │  │
│  │  │ + computerMove(row, col)                         │  │  │
│  │  │ + didWin(char), tied(), stillRunning()           │  │  │
│  │  └──────────────────────────────────────────────────┘  │  │
│  └───────────────────────┬─────────────────────────────────┘  │
│                          │ uses                                │
│                          ▼                                     │
│  ┌─────────────────────────────────────────────────────────┐  │
│  │            ComputerPlayer                               │  │
│  │  ┌──────────────────────────────────────────────────┐  │  │
│  │  │ - TicTacToeStrategy myStrategy                   │  │  │
│  │  │ + setStrategy(TicTacToeStrategy)                 │  │  │
│  │  │ + desiredMove(TicTacToeGame)                     │  │  │
│  │  └──────────────────────────────────────────────────┘  │  │
│  └───────────────────────┬─────────────────────────────────┘  │
│                          │ delegates to                        │
│                          ▼                                     │
│  ┌───────────────────────────────────────────────────┐        │
│  │      TicTacToeStrategy (interface)                │        │
│  │  + desiredMove(TicTacToeGame): OurPoint           │        │
│  └───────────┬───────────────────────┬───────────────┘        │
│              │                       │                         │
│      ┌───────▼────────┐   ┌─────────▼──────┐   ┌──────────┐  │
│      │   RandomAI     │   │ IntermediateAI │   │ Player2  │  │
│      │ (Easy)         │   │ (Medium)       │   │ (Human)  │  │
│      └────────────────┘   └────────────────┘   └──────────┘  │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```

---

## Pattern 1: Observer Pattern

### Why Observer Pattern?

**Problem:** Multiple views (ButtonView, TextAreaView) need to stay in sync with game state.

**Without Observer:**
```java
// Bad: Game logic knows about all views
public void humanMove(int row, int col) {
    board[row][col] = 'X';
    buttonView.refresh();      // Tight coupling
    textAreaView.refresh();
    scoreboardView.refresh();
}
```
- Adding new view requires changing game logic
- Violates Single Responsibility Principle

**With Observer:**
```java
// Good: Game logic decoupled from views
public void humanMove(int row, int col) {
    board[row][col] = 'X';
    notifyObservers(this);  // All registered views update automatically
}
```

### Implementation

**OurObservable.java** - Base class for observable objects

```java
// src/main/java/model/OurObservable.java:7-24
public class OurObservable {
    private ArrayList<OurObserver> observers;

    public OurObservable() {
        observers = new ArrayList<>();
    }

    public void addObserver(OurObserver observer) {
        observers.add(observer);
    }

    public void notifyObservers(OurObservable observedObject) {
        for (OurObserver observer : observers) {
            observer.update(observedObject);
        }
    }
}
```

**Why Custom Implementation (Not java.util.Observable)?**
- `java.util.Observable` deprecated since Java 9
- Custom version is simpler (no `setChanged()` ceremony)
- Educational: demonstrates pattern clearly

**OurObserver.java** - Interface for observers

```java
// src/main/java/model/OurObserver.java:3-5
public interface OurObserver {
    void update(Object theObserved);
}
```

**Usage in TicTacToeGame:**

```java
// src/main/java/model/TicTacToeGame.java:25-30 (constructor)
public TicTacToeGame() {
    board = new char[size][size];
    count = 0;
    initializeBoard();
    computerPlayer = new ComputerPlayer();
    notifyObservers(this);  // Initial state notification
}

// src/main/java/model/TicTacToeGame.java:92-103 (humanMove)
public void humanMove(int row, int col, boolean testing) {
    if (available(row, col)) {
        board[row][col] = 'X';
        count++;
        notifyObservers(this);  // Trigger view updates

        if (!testing && stillRunning() && computerPlayer.getStrategy().getClass() != Player2.class) {
            OurPoint point = computerPlayer.desiredMove(this);
            computerMove(point.getRow(), point.getCol());
        }
    }
}
```

**View Registration (GUI.java):**

```java
// src/main/java/views/GUI.java:45-50 (start method)
game = new TicTacToeGame();
buttonView = new ButtonView(game, stage);
textAreaView = new TextAreaView(game, stage);

game.addObserver(buttonView);    // Register observers
game.addObserver(textAreaView);
```

**View Update (ButtonView.java):**

```java
// src/main/java/views/ButtonView.java:100-115 (update method)
@Override
public void update(Object theObserved) {
    TicTacToeGame game = (TicTacToeGame) theObserved;

    // Update all button labels based on board state
    for (int row = 0; row < 3; row++) {
        for (int col = 0; col < 3; col++) {
            OurPoint point = new OurPoint(row, col);
            Button button = buttonMap.get(point);
            char cell = game.getTicTacToeBoard()[row][col];
            button.setText(cell == '_' ? "" : String.valueOf(cell));
        }
    }

    checkGame();  // Check for win/tie
}
```

**Key Insight:**
- Game calls `notifyObservers(this)` → All views' `update()` methods called
- Views read current game state (via `getTicTacToeBoard()`) and refresh UI
- No hardcoded references to specific views in game logic

---

## Pattern 2: Strategy Pattern

### Why Strategy Pattern?

**Problem:** Need to swap AI difficulty (Easy, Medium) and switch to two-player mode at runtime.

**Without Strategy:**
```java
// Bad: AI logic embedded in game
public OurPoint getComputerMove() {
    if (difficulty == EASY) {
        // Random move logic here
    } else if (difficulty == MEDIUM) {
        // Intermediate AI logic here
    } else if (difficulty == TWO_PLAYER) {
        // Wait for human input
    }
}
```
- Violates Open/Closed Principle (adding AI requires modifying game)
- All AI logic in one giant method

**With Strategy:**
```java
// Good: AI algorithms encapsulated in separate classes
computerPlayer.setStrategy(new IntermediateAI());
OurPoint move = computerPlayer.desiredMove(game);
```

### Implementation

**TicTacToeStrategy.java** - Interface defining AI contract

```java
// src/main/java/model/TicTacToeStrategy.java:3-5
public interface TicTacToeStrategy {
    OurPoint desiredMove(TicTacToeGame theGame);
}
```

**ComputerPlayer.java** - Context class holding current strategy

```java
// src/main/java/model/ComputerPlayer.java:7-20
public class ComputerPlayer {
    private TicTacToeStrategy myStrategy;

    public void setStrategy(TicTacToeStrategy newStrategy) {
        myStrategy = newStrategy;
    }

    public TicTacToeStrategy getStrategy() {
        return myStrategy;
    }

    public OurPoint desiredMove(TicTacToeGame theGame) {
        return myStrategy.desiredMove(theGame);
    }
}
```

**Strategy Selection (GUI.java):**

```java
// src/main/java/views/GUI.java:93-110 (registerHandlers)
randomAIItem.setOnAction(e -> {
    game.setComputerPlayerStrategy(new RandomAI());
    game.startNewGame();
});

intermediateAIItem.setOnAction(e -> {
    game.setComputerPlayerStrategy(new IntermediateAI());
    game.startNewGame();
});

player2Item.setOnAction(e -> {
    game.setComputerPlayerStrategy(new Player2());
    game.startNewGame();
});
```

**Why Restart Game on Strategy Change?**
- Prevents mid-game confusion (player expects consistent opponent)
- Resets board to clean state
- Alternative: Could allow mid-game switch, but UX is unclear

---

## Strategy Implementations

### RandomAI - Easy Opponent

```java
// src/main/java/model/RandomAI.java:10-30
public class RandomAI implements TicTacToeStrategy {
    @Override
    public OurPoint desiredMove(TicTacToeGame theGame) {
        Random rand = new Random();
        int row, col;

        while (true) {
            row = rand.nextInt(3);
            col = rand.nextInt(3);

            if (theGame.available(row, col)) {
                return new OurPoint(row, col);
            }

            if (theGame.maxMovesRemaining() == 0) {
                throw new NoWhereToGoExcep("Board full");
            }
        }
    }
}
```

**Algorithm:**
1. Generate random (row, col) in range [0, 2]
2. If cell available, return coordinates
3. If not, retry (loop)
4. If board full, throw exception

**Why Infinite Loop?**
- Keeps retrying until valid move found
- `maxMovesRemaining()` check prevents infinite loop on full board
- Simple but inefficient (could take many iterations near end of game)

**Optimization Opportunity:**
```java
// Better: Collect all empty cells, pick randomly
List<OurPoint> emptyCells = new ArrayList<>();
for (int r = 0; r < 3; r++) {
    for (int c = 0; c < 3; c++) {
        if (theGame.available(r, c)) {
            emptyCells.add(new OurPoint(r, c));
        }
    }
}
return emptyCells.get(rand.nextInt(emptyCells.size()));
```

### IntermediateAI - Medium Opponent

**Algorithm Priority (High to Low):**

1. **Win if possible** - If AI can win this turn, take it
2. **Block opponent win** - If human can win next turn, block
3. **Strategic opening** - Try corners, then center
4. **Random fallback** - Pick any available cell

```java
// src/main/java/model/IntermediateAI.java:10-60 (simplified)
@Override
public OurPoint desiredMove(TicTacToeGame theGame) {
    char[][] board = theGame.getTicTacToeBoard();

    // Priority 1: Can AI win this turn?
    OurPoint winMove = toWin(board, 'O');
    if (winMove != null) {
        return winMove;
    }

    // Priority 2: Can human win next turn? Block them!
    OurPoint blockMove = toWin(board, 'X');
    if (blockMove != null) {
        return blockMove;
    }

    // Priority 3: Strategic opening moves (corners, center)
    OurPoint strategicMove = initialMoves(theGame);
    if (strategicMove != null) {
        return strategicMove;
    }

    // Priority 4: Random available cell
    return randomMove(theGame);
}
```

#### Win/Block Detection Logic

```java
// src/main/java/model/IntermediateAI.java:62-110 (toWin method)
private OurPoint toWin(char[][] board, char symbol) {
    // Check all rows for 2-in-a-row + 1 empty
    for (int row = 0; row < 3; row++) {
        int count = 0, emptyCol = -1;
        for (int col = 0; col < 3; col++) {
            if (board[row][col] == symbol) count++;
            if (board[row][col] == '_') emptyCol = col;
        }
        if (count == 2 && emptyCol != -1) {
            return new OurPoint(row, emptyCol);
        }
    }

    // Check all columns (similar logic)
    // Check both diagonals (similar logic)

    return null;  // No winning/blocking move found
}
```

**Example Scenario (Win Detection):**
```
Board:
O | O | _
─────────
X | X | O
─────────
_ | X | _

toWin(board, 'O') checks row 0:
- count = 2 (two O's)
- emptyCol = 2
- Returns OurPoint(0, 2) → AI wins!
```

**Example Scenario (Block Detection):**
```
Board:
X | X | _
─────────
O | _ | _
─────────
_ | _ | O

toWin(board, 'X') checks row 0:
- count = 2 (two X's)
- emptyCol = 2
- Returns OurPoint(0, 2) → AI blocks human win
```

**Why Check Opponent Win Separately?**
- Could combine into single method, but logic clearer when separated
- Explicit priority: "Win > Block" is self-documenting

#### Strategic Opening Moves

```java
// src/main/java/model/IntermediateAI.java:150-170
private OurPoint initialMoves(TicTacToeGame theGame) {
    // Try corners first (best opening strategy)
    int[][] corners = {{0, 0}, {0, 2}, {2, 0}, {2, 2}};
    for (int[] corner : corners) {
        if (theGame.available(corner[0], corner[1])) {
            return new OurPoint(corner[0], corner[1]);
        }
    }

    // Try center
    if (theGame.available(1, 1)) {
        return new OurPoint(1, 1);
    }

    return null;  // No strategic moves available
}
```

**Why Corners Before Center?**
- **Tic-Tac-Toe Theory:** Corners give more winning combinations (3 lines) vs edges (2 lines)
- **Center:** Also strong, but prioritizing corners makes AI less predictable
- **Not Optimal:** True optimal strategy is minimax (evaluates all future moves)

**Why Not Minimax?**
- IntermediateAI intended as "medium" difficulty
- Minimax would make AI unbeatable (not fun for casual players)
- Heuristic approach is "good enough" and simpler to understand

### Player2 - Two-Player Mode

```java
// src/main/java/model/Player2.java:7-15
public class Player2 implements TicTacToeStrategy {
    private final String letter = "O";

    @Override
    public OurPoint desiredMove(TicTacToeGame theGame) {
        return null;  // Not used - human input handled by view
    }

    public String getLetter() {
        return letter;
    }
}
```

**Why Return Null?**
- Player2 moves come from UI (ButtonView or TextAreaView)
- `desiredMove()` never called in two-player mode
- Class exists to satisfy TicTacToeStrategy interface

**How Two-Player Mode Works:**

```java
// src/main/java/model/TicTacToeGame.java:92-103
public void humanMove(int row, int col, boolean testing) {
    if (available(row, col)) {
        board[row][col] = 'X';
        count++;
        notifyObservers(this);

        // Only trigger computer move if NOT Player2
        if (!testing && stillRunning() &&
            computerPlayer.getStrategy().getClass() != Player2.class) {
            OurPoint point = computerPlayer.desiredMove(this);
            computerMove(point.getRow(), point.getCol());
        }
    }
}
```

**Alternative Design:**
- Could have separate `player2Move()` method (which exists: `TicTacToeGame.java:121`)
- Current approach: Check strategy class type (`!= Player2.class`)

---

## Game State Management

### Board Representation

```java
// src/main/java/model/TicTacToeGame.java:14-17
private char[][] board;
private int count;       // Move counter (even = X's turn, odd = O's turn)
private final int size = 3;
```

**Why `char[][]` (Not `String[]` or Enum)?**
- **Simple:** Single character per cell ('X', 'O', '_')
- **Memory Efficient:** char is 2 bytes vs String overhead
- **Easy Rendering:** Convert to string for display (`String.valueOf(cell)`)

**Why `count` for Turn Tracking?**
```java
// src/main/java/model/TicTacToeGame.java:61-63
public boolean getTurn() {
    return count % 2 == 0;  // Even count = X's turn
}
```
- Simpler than boolean flag
- Naturally increments with each move
- Useful for detecting ties (`count == 9` and no winner)

### Win Detection Algorithm

```java
// src/main/java/model/TicTacToeGame.java:139-145
public boolean didWin(char playerChar) {
    return wonByRow(playerChar) ||
           wonByCol(playerChar) ||
           wonByDiagonal(playerChar);
}
```

**Row Check:**
```java
// src/main/java/model/TicTacToeGame.java:152-161
private boolean wonByRow(char playerChar) {
    for (int row = 0; row < size; row++) {
        int matches = 0;
        for (int col = 0; col < size; col++) {
            if (board[row][col] == playerChar) {
                matches++;
            }
        }
        if (matches == size) return true;
    }
    return false;
}
```

**Why Count Matches (Not Early Exit)?**
- Could optimize with early exit:
  ```java
  if (board[row][0] == playerChar &&
      board[row][1] == playerChar &&
      board[row][2] == playerChar) {
      return true;
  }
  ```
- Current approach more readable, minimal performance difference (3x3 board)

**Diagonal Check:**
```java
// src/main/java/model/TicTacToeGame.java:181-200
private boolean wonByDiagonal(char playerChar) {
    // Top-left to bottom-right
    int diagonal1 = 0;
    for (int i = 0; i < size; i++) {
        if (board[i][i] == playerChar) diagonal1++;
    }
    if (diagonal1 == size) return true;

    // Top-right to bottom-left
    int diagonal2 = 0;
    for (int i = 0; i < size; i++) {
        if (board[i][size - 1 - i] == playerChar) diagonal2++;
    }
    return diagonal2 == size;
}
```

**Diagonal Indexing:**
- Main diagonal: `(0,0), (1,1), (2,2)` → `board[i][i]`
- Anti-diagonal: `(0,2), (1,1), (2,0)` → `board[i][size-1-i]`

### Tie Detection

```java
// src/main/java/model/TicTacToeGame.java:71-73
public boolean tied() {
    return maxMovesRemaining() == 0 && !didWin('X') && !didWin('O');
}
```

**Logic:**
- Board full (`maxMovesRemaining() == 0`)
- AND neither player won
- = Tie (cat's game)

**Why Check Both `!didWin('X')` and `!didWin('O')`?**
- Defensive programming (both players can't win, but explicit is clear)
- Could simplify to `!didWin('X')` since last move determines winner

---

## View Implementations

### ButtonView - Grid UI

**Key Data Structure:**

```java
// src/main/java/views/ButtonView.java:18-20
private Hashtable<OurPoint, Button> buttonMap;
```

**Why Hashtable (Not ArrayList)?**
- **Lookup:** Get button at (row, col) in O(1) vs O(n) scan
- **Mapping:** Natural row/col → Button relationship
- **Alternative:** 2D array `Button[][] buttons` would also work

**Button Initialization:**

```java
// src/main/java/views/ButtonView.java:40-58
private void initializeButtons() {
    buttonMap = new Hashtable<>();
    char[][] board = game.getTicTacToeBoard();

    for (int row = 0; row < 3; row++) {
        for (int col = 0; col < 3; col++) {
            Button button = new Button();
            button.setPrefSize(100, 100);
            button.setFont(Font.font("Arial", FontWeight.BOLD, 24));

            char cell = board[row][col];
            button.setText(cell == '_' ? "" : String.valueOf(cell));

            OurPoint point = new OurPoint(row, col);
            buttonMap.put(point, button);
        }
    }
}
```

**Button Click Handler:**

```java
// src/main/java/views/ButtonView.java:75-98
private class whichButton implements EventHandler<ActionEvent> {
    private OurPoint point;

    public whichButton(OurPoint point) {
        this.point = point;
    }

    @Override
    public void handle(ActionEvent event) {
        if (game.getTurn()) {
            game.humanMove(point.getRow(), point.getCol(), false);
        } else if (game.getComputerPlayer().getStrategy().getClass() == Player2.class) {
            game.player2Move(point.getRow(), point.getCol());
        }

        update(game);
    }
}
```

**Why Inner Class for Handler?**
- **Closure:** Each button gets its own handler with specific (row, col)
- **Alternative:** Lambda expression would be cleaner:
  ```java
  button.setOnAction(e -> {
      if (game.getTurn()) {
          game.humanMove(row, col, false);
      }
  });
  ```

**Turn Logic:**
- `getTurn() == true`: X's turn (human)
- `getTurn() == false`: O's turn (computer OR Player2)
- Check if Player2 mode to allow second human move

### TextAreaView - Text Input UI

**Input Validation:**

```java
// src/main/java/views/TextAreaView.java:85-105
private boolean valid(String rowStr, String colStr) {
    try {
        int row = Integer.parseInt(rowStr);
        int col = Integer.parseInt(colStr);

        if (row < 0 || row > 2 || col < 0 || col > 2) {
            validationLabel.setText("Row and column must be 0-2");
            return false;
        }

        if (!game.available(row, col)) {
            validationLabel.setText("Cell already occupied");
            return false;
        }

        validationLabel.setText("");  // Clear error
        return true;

    } catch (NumberFormatException e) {
        validationLabel.setText("Please enter valid numbers");
        return false;
    }
}
```

**Why Three Validation Checks?**
1. **Numeric:** Reject non-integers ("a", "1.5")
2. **Range:** Reject out-of-bounds (3, -1)
3. **Availability:** Reject occupied cells

**User Experience:**
- Show specific error message for each failure type
- Clear message on successful input

---

## Bridge to JavaScript: WebApi

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

**Why `WebAPI.getWebAPI(yourStage)`?**
- JPro provides `WebAPI` class to access browser context
- `getWindow()` returns JavaScript `window` object
- `executeScript()` runs JavaScript code from Java

**Security Risk:**
```java
window.executeScript(String.format("...", loserId, winnerId));
```
- If `loserId` contains `'; alert("XSS"); //`, code injection possible
- **Mitigation:** Sanitize inputs or use JSON serialization:
  ```java
  String json = new JSONObject()
      .put("loserId", loserId)
      .put("winnerId", winnerId)
      .toString();
  window.executeScript("window.dispatchEvent(new CustomEvent('gameOverEvent', { detail: " + json + " }));");
  ```

---

## Common Operations

### Starting New Game

```java
// src/main/java/model/TicTacToeGame.java:33-38
public void startNewGame() {
    count = 0;
    initializeBoard();  // Fill board with '_'
    notifyObservers(this);  // Trigger views to refresh
}
```

**Called When:**
- User selects new strategy (RandomAI, IntermediateAI, Player2)
- User clicks "New Game" menu option
- Application first starts

### Checking Cell Availability

```java
// src/main/java/model/TicTacToeGame.java:65-69
public boolean available(int r, int c) {
    return board[r][c] == '_';
}
```

**Simple but Critical:**
- Prevents overwriting existing moves
- Used by AI strategies to find valid moves
- Used by views to validate user input

### Counting Empty Cells

```java
// src/main/java/model/TicTacToeGame.java:75-85
public int maxMovesRemaining() {
    int count = 0;
    for (int row = 0; row < size; row++) {
        for (int col = 0; col < size; col++) {
            if (board[row][col] == '_') {
                count++;
            }
        }
    }
    return count;
}
```

**Used For:**
- Tie detection (`maxMovesRemaining() == 0`)
- RandomAI exception handling (prevent infinite loop)
- Could be optimized: Decrement counter on each move instead of scanning

---

## Testing & Debugging

### Testing Mode Flag

```java
// src/main/java/model/TicTacToeGame.java:92
public void humanMove(int row, int col, boolean testing)
```

**Purpose of `testing` Parameter:**
- When `true`: Don't trigger computer move (for unit tests)
- When `false`: Normal gameplay (computer moves after human)

**Example Test:**
```java
@Test
public void testWinCondition() {
    TicTacToeGame game = new TicTacToeGame();
    game.humanMove(0, 0, true);  // X
    game.computerMove(1, 0);      // O
    game.humanMove(0, 1, true);  // X
    game.computerMove(1, 1);      // O
    game.humanMove(0, 2, true);  // X wins!

    assertTrue(game.didWin('X'));
}
```

**Without `testing` Flag:**
- Each `humanMove()` would trigger `computerMove()`
- Hard to set up specific board states

### Debug Output

```java
// src/main/java/model/TicTacToeGame.java:206-220
@Override
public String toString() {
    String result = "";
    for (int row = 0; row < size; row++) {
        for (int col = 0; col < size; col++) {
            result += board[row][col];
            if (col < size - 1) result += " | ";
        }
        if (row < size - 1) result += "\n─────────\n";
    }
    return result;
}
```

**Output Example:**
```
X | O | _
─────────
_ | X | O
─────────
_ | _ | X
```

**Useful For:**
- Console debugging: `System.out.println(game)`
- Logging game states
- Unit test assertions

---

## Performance Considerations

### Observer Notification Overhead

**Every Move Triggers:**
1. `notifyObservers(this)` calls all observers' `update()` methods
2. Each view re-renders entire board (9 buttons or text area)

**Why Not Incremental Updates?**
- 3x3 board is small (negligible performance impact)
- Full refresh simpler than tracking changed cells
- If scaling to larger boards (chess, Go), would optimize

### AI Performance

**RandomAI:**
- Worst case: O(n) iterations where n = empty cells
- Average: ~2-3 iterations (random usually hits quickly)

**IntermediateAI:**
- Checks 3 rows + 3 cols + 2 diagonals = 8 checks for win
- 8 checks for block
- 4 corners + 1 center = 5 checks for strategy
- **Total:** O(1) - fixed number of operations

**Minimax (Not Implemented):**
- Would be O(9!) worst case (evaluate all move sequences)
- With alpha-beta pruning: ~O(b^(d/2)) where b=branching factor, d=depth

---

## Related Documentation

- **Frontend Integration:** `FRONTEND_INTEGRATION.md` (Java→JavaScript bridge)
- **Architecture Overview:** `ARCHITECTURE.md` (how game fits in overall system)
- **Observer Pattern Motivation:** Classic GoF Design Patterns book
