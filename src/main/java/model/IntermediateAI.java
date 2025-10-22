package model;

/**
 * IntermediateAI implements the TicTacToeStrategy interface and provides
 * an intermediate level AI for TicTacToe.
 *
 * The strategy is as follows:
 * 1. If the AI ('O') can win in one move or if the opponent ('X') is about to win,
 *    then make the winning/blocking move.
 * 2. Otherwise, attempt to place a move in one of the corners.
 * 3. If no corner is available, try the center.
 * 4. If none of these are available, choose a random move.
 *
 * This version uses the initialMoves method to determine if a corner or the center is free.
 */
public class IntermediateAI implements TicTacToeStrategy {

    public IntermediateAI() {
    }

    @Override
    public OurPoint desiredMove(TicTacToeGame theGame) {
        // Ensure there is at least one move available
        if (theGame.maxMovesRemaining() == 0)
            throw new NoWhereToGoExcep(" -- Hey there programmer, the board is filled");

        int[] coordinate = makeMove(theGame.getTicTacToeBoard(), theGame);
        return new OurPoint(coordinate[0], coordinate[1]);
    }

    /**
     * Determines the move for the AI based on a prioritized strategy:
     * 1. Winning move for AI or blocking move against the opponent.
     * 2. If none, then check for an initial move: corners first, then center.
     * 3. If neither of the above is available, choose a random move.
     *
     * @param board the current game board as a 2D character array.
     * @param theGame the current TicTacToeGame instance.
     * @return an integer array where index 0 is the row and index 1 is the column.
     */
    private int[] makeMove(char[][] board, TicTacToeGame theGame) {
        // First, check if the AI can win in the next move.
        int[] winMove = toWin(board, 'O');
        if (winMove[0] != -1) {
            return winMove;
        }

        // Next, check if the opponent is about to win; block their move.
        int[] blockMove = toWin(board, 'X');
        if (blockMove[0] != -1) {
            return blockMove;
        }

        // Now, try to take one of the initial strategic positions.
        int[] initial = initialMoves(board);
        if (initial[0] != -1) {
            return initial;
        }

        // If none of the above strategies apply, choose a random move.
        RandomAI randomAi = new RandomAI();
        OurPoint newCoor = randomAi.desiredMove(theGame);
        return new int[]{newCoor.getRow(), newCoor.getCol()};
    }

    /**
     * Checks if there is a winning (or blocking) move available for the given symbol.
     * Iterates through rows, columns, and both diagonals looking for a line where
     * the symbol appears twice and the third cell is empty.
     *
     * @param board the current game board as a 2D character array.
     * @param symbol the symbol to check for ('X' for opponent, 'O' for AI).
     * @return an integer array with the row and column of the move,
     *         or {-1, -1} if no such move exists.
     */
    private int[] toWin(char[][] board, char symbol) {
        // Check each row for a winning/blocking move
        for (int i = 0; i < 3; i++) {
            int count = 0;
            int emptyCol = -1;
            for (int j = 0; j < 3; j++) {
                if (board[i][j] == symbol)
                    count++;
                else if (board[i][j] == ' ')
                    emptyCol = j;
            }
            if (count == 2 && emptyCol != -1)
                return new int[]{i, emptyCol};
        }

        // Check each column for a winning/blocking move
        for (int j = 0; j < 3; j++) {
            int count = 0;
            int emptyRow = -1;
            for (int i = 0; i < 3; i++) {
                if (board[i][j] == symbol)
                    count++;
                else if (board[i][j] == ' ')
                    emptyRow = i;
            }
            if (count == 2 && emptyRow != -1)
                return new int[]{emptyRow, j};
        }

        // Check the primary diagonal (top-left to bottom-right)
        int count = 0;
        int emptyIndex = -1;
        for (int i = 0; i < 3; i++) {
            if (board[i][i] == symbol)
                count++;
            else if (board[i][i] == ' ')
                emptyIndex = i;
        }
        if (count == 2 && emptyIndex != -1)
            return new int[]{emptyIndex, emptyIndex};

        // Check the secondary diagonal (top-right to bottom-left)
        count = 0;
        emptyIndex = -1;
        for (int i = 0; i < 3; i++) {
            if (board[i][2 - i] == symbol)
                count++;
            else if (board[i][2 - i] == ' ')
                emptyIndex = i;
        }
        if (count == 2 && emptyIndex != -1)
            return new int[]{emptyIndex, 2 - emptyIndex};

        // No winning or blocking move found
        return new int[]{-1, -1};
    }

    /**
     * Checks for available initial moves by looking for an empty corner first.
     * If no corner is available, then checks the center.
     *
     * Corners: (0,0), (0,2), (2,0), (2,2)
     * Center: (1,1)
     *
     * @param board the current game board as a 2D character array.
     * @return an integer array with the row and column of the chosen initial move,
     *         or {-1, -1} if neither a corner nor the center is available.
     */
    private int[] initialMoves(char[][] board) {
        // Define the corner coordinates
        int[][] corners = { {0, 0}, {0, 2}, {2, 0}, {2, 2} };

        // Try to find an available corner
        for (int[] corner : corners) {
            if (board[corner[0]][corner[1]] == ' ')
                return new int[]{corner[0], corner[1]};
        }

        // If no corner is free, check if the center is available
        if (board[1][1] == ' ')
            return new int[]{1, 1};

        // Return an invalid coordinate if neither a corner nor the center is free
        return new int[]{-1, -1};
    }
}
