/**
 * Represents a Tic Tac Toe game.
 * This class uses an internal 2D char array to track the state of the board,
 * maintains a move count, and works with a ComputerPlayer for AI moves.
 * It extends OurObservable so that any registered views can update themselves
 * whenever the game state changes.
 *
 * @author Lex Castaneda
 */
package model;
public class TicTacToeGame extends OurObservable {

  // ---------------------------
  // Fields and Game Variables
  // ---------------------------

  private char[][] board;
  private int size;
  private int count = 0;
  private ComputerPlayer computerPlayer;

  // ---------------------------
  // Constructor and Initialization
  // ---------------------------

  /**
   * Constructs a new Tic Tac Toe game.
   *
   * Initializes a 3x3 board, creates a default ComputerPlayer with a RandomAI strategy,
   * and notifies all observers of the initial state.
   *
   */

  public TicTacToeGame() {
    size = 3;
    initializeBoard();
    computerPlayer = new ComputerPlayer();
    computerPlayer.setStrategy(new RandomAI());

    notifyObservers(this);
  }

  /**
   * Initializes the game board to its starting state.
   *
   * Called from the constructor and startNewGame().
   * Fills the board with '_' to denote empty spaces.
   *
   */
  private void initializeBoard() {
    board = new char[size][size];
    for (int r = 0; r < size; r++) {
      for (int c = 0; c < size; c++) {
        board[r][c] = '_';
      }
    }
  }

  /**
   * Starts a new game by reinitializing the board and notifying observers.
   */
  public void startNewGame() {
    initializeBoard();
    count = 0;
    notifyObservers(this);
  }

  // ---------------------------
  // Computer Player Methods
  // ---------------------------

  /**
   * Swaps the current strategy of the ComputerPlayer.
   *
   * @param AI the new TicTacToeStrategy to use.
   */
  public void setComputerPlayerStrategy(TicTacToeStrategy AI) {
    this.computerPlayer.setStrategy(AI);
  }

  /**
   * Returns the current ComputerPlayer.
   *
   * @return the ComputerPlayer instance.
   */
  public ComputerPlayer getComputerPlayer() {
    return computerPlayer;
  }

  // ---------------------------
  // Game Move Methods
  // ---------------------------

  /**
   * Processes a human move at the given row and column.
   *
   * If the move is valid (the cell is empty), the board is updated with 'X'.
   * Depending on the computer player's strategy (determined by its class name),
   * it may immediately trigger a computer move (for single-player mode) or simply update for
   * two-player mode.
   * <
   *
   * @param row     the row index (0 to 2).
   * @param col     the column index (0 to 2).
   * @param testing flag indicating whether the move is part of a test.
   */

  public void humanMove(int row, int col, boolean testing) {
    System.out.println(computerPlayer.getStrategy().getClass().getName());
    String aiName = computerPlayer.getStrategy().getClass().getName();
    if (board[row][col] != '_')
      return;
    if (computerPlayer.getStrategy() instanceof Player2) {
      board[row][col] = 'X';
      count++;
    }
    else {
      board[row][col] = 'X';
      count++;
      if (!testing && this.stillRunning()) {
        OurPoint move = computerPlayer.desiredMove(this);
        computerMove(move.getRow(), move.getCol());
      }
    }
    notifyObservers(this);
  }

  /**
   * Executes a computer move at the specified row and column.
   * This method is used by the computer player to update the board with 'O'.
   *
   * @param row the row index (0 to 2).
   * @param col the column index (0 to 2).
   */
  public void computerMove(int row, int col) {
    if (board[row][col] != '_')
      return;
    board[row][col] = 'O';
    count++;
    // setChanged(); // Java needs this or the next message does not happen
    notifyObservers(this); // Send update messages to all Observers
  }
  /**
   * Executes a move for player two at the specified row and column.
   * Essentially the same as computerMove, but kept separate for clarity in two-player mode.
   *
   * @param row the row index (0 to 2).
   * @param col the column index (0 to 2).
   */
  public void player2Move(int row, int col) {
    if (board[row][col] != '_')
      return;
    board[row][col] = 'O';
    count++;
    // setChanged(); // Java needs this or the next message does not happen
    notifyObservers(this); // Send update messages to all Observers
  }

  // ---------------------------
  // Game State Check Methods
  // ---------------------------

  /**
   * Returns whose turn it is.
   *
   * If the count is even, it's player X's turn.
   *
   *
   * @return true if it's player X's turn; false otherwise.
   */
  public boolean getTurn() {
    return count % 2 == 0;
  }

  /**
   * Returns a string representation of the current board.
   *
   * Each row is separated by a newline.
   *
   *
   * @return the string representation of the board.
   */
  @Override
  public String toString() {
    String result = "";
    for (int r = 0; r < size; r++) {
      for (int c = 0; c < size; c++) {
        result += " " + board[r][c] + " ";
      }
      if (r == 0 || r == 1)
        result += "\n";
    }
    return result;
  }

  /**
   * Provides the current board state as a 2D char array.
   *
   * Useful for AI strategies to analyze the game board.
   *
   *
   * @return the 2D array representing the board.
   */
  public char[][] getTicTacToeBoard() {
    return board;
  }

  /**
   * Checks if the game is tied.
   *
   * A game is tied if there are no moves left and neither player has won.
   *
   *
   * @return true if the game is a tie; false otherwise.
   */
  public boolean tied() {
    return maxMovesRemaining() == 0 && !didWin('X') && !didWin('O');
  }

  /**
   * Counts the number of moves still available.
   *
   * @return the number of unoccupied cells.
   */
  public int maxMovesRemaining() {
    int result = 0;
    for (int r = 0; r < size; r++) {
      for (int c = 0; c < size; c++) {
        if (board[r][c] == '_')
          result++;
      }
    }
    return result;
  }

  /**
   * Determines if a given cell is available.
   *
   * @param r the row index.
   * @param c the column index.
   * @return true if the cell is unoccupied ('_'); false otherwise.
   */
  public boolean available(int r, int c) {
    return board[r][c] == '_';
  }

  /**
   * Checks if the game is still running.

   * The game continues as long as there is no tie and neither player has won.
   *
   *
   * @return true if the game is active; false otherwise.
   */
  public boolean stillRunning() {
    return !tied() && !didWin('X') && !didWin('O');
  }

  /**
   * Determines if the specified player has won.
   *
   * @param playerChar the character representing the player ('X' or 'O').
   * @return true if the player has won; false otherwise.
   */
  public boolean didWin(char playerChar) {
    return wonByRow(playerChar) || wonByCol(playerChar) || wonByDiagonal(playerChar);
  }

  // ---------------------------
  // Winning Condition Helper Methods
  // ---------------------------

  /**
   * Checks if the specified player has a complete row.
   *
   * @param playerChar the player's character.
   * @return true if a row is completely filled with playerChar; false otherwise.
   */
  private boolean wonByRow(char playerChar) {
    for (int r = 0; r < size; r++) {
      int rowSum = 0;
      for (int c = 0; c < size; c++)
        if (board[r][c] == playerChar)
          rowSum++;
      if (rowSum == size)
        return true;
    }
    return false;
  }

  /**
   * Checks if the specified player has a complete column.
   *
   * @param playerChar the player's character.
   * @return true if a column is completely filled with playerChar; false otherwise.
   */
  private boolean wonByCol(char playerChar) {
    for (int c = 0; c < size; c++) {
      int colSum = 0;
      for (int r = 0; r < size; r++)
        if (board[r][c] == playerChar)
          colSum++;
      if (colSum == size)
        return true;
    }
    return false;
  }

  /**
   * Checks if the specified player has a winning diagonal.

   * Both the primary (top-left to bottom-right) and secondary (top-right to bottom-left)
   * diagonals are checked.
   *
   * @param playerChar the player's character.
   * @return true if a diagonal is completely filled with playerChar; false otherwise.
   */
  private boolean wonByDiagonal(char playerChar) {
    // Check primary diagonal (upper left to lower right)
    int sum = 0;
    for (int r = 0; r < size; r++)
      if (board[r][r] == playerChar)
        sum++;
    if (sum == size)
      return true;

    // Check secondary diagonal (upper right to lower left)
    sum = 0;
    for (int r = size - 1; r >= 0; r--)
      if (board[size - r - 1][r] == playerChar)
        sum++;
    if (sum == size)
      return true;

    // No winning diagonal found
    return false;
  }
}
