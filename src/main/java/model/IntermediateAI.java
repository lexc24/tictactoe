package model;
/*
 * Lex Castaneda
 * IntermediateAI.java
 * CSC 335
 * 
 * This class contains the intermediate difficulty for the TicTacToe game.IntermediateAI increases
 * difficulty by looking for a way to win and then a way to block the user to win. If it fails in finding those
 * options then the AI will choose a random place.
 */

public class IntermediateAI  implements TicTacToeStrategy {
	 
	 public IntermediateAI() {
		  }	

  @Override
  public OurPoint desiredMove(TicTacToeGame theGame) {
	  boolean set = false;
	    while (!set) {
	      if (theGame.maxMovesRemaining() == 0)
	        throw new NoWhereToGoExcep(" -- Hey there programmer, the board is filled");

	     int[] coor = makeMove(theGame.getTicTacToeBoard(),theGame);
	     set = true;
	     return new OurPoint(coor[0],coor[1]);
	     
	    }
	    return null; // Avoid a compile-time error

  }
  
  private int [] makeMove(char[][] board,TicTacToeGame theGame) {
	  
      int [] coordinate = new int[2];
      boolean choiceMade = false;

      char user = 'X';
      char ai = 'O';


      int[] bestMove = toWin(board, user);
      if (bestMove[0] != -1) {
          choiceMade = true;
          coordinate = bestMove;
      }
      
      if (!choiceMade) {
          bestMove = toWin(board, ai);
          if (bestMove[0] != -1) {
              choiceMade = true;
              coordinate = bestMove;
          }
      }
      if (!choiceMade) {
    	  RandomAI randomAi = new RandomAI();
    	  OurPoint newCoor= randomAi.desiredMove(theGame);
    	  coordinate[0] = newCoor.getRow();
    	  coordinate[1] = newCoor.getCol();

      }
	return coordinate; 
  }
  
  

  private int[] toWin(char[][] board, char symbol) {
	  int row = -1;
      int col = -1;

      if ((board[0][0] == symbol && board[2][2] == symbol && board[1][1] == ' ') || (board[2][0] == symbol && board[0][2] == symbol && board[1][1] == ' ')) {
          row = 1;
          col = 1;
      } else if (board[1][1] == symbol) {
          if (board[0][0] == symbol && board[2][2] == ' ') {
              row = 2;
              col = 2;
          } else if (board[2][2] == symbol && board[0][0] == ' ') {
              row = 0;
              col = 0;
          } else if (board[0][2] == symbol && board[2][0] == ' ') {
              row = 2;
              col = 0;
          } else if (board[2][0] == symbol && board[0][2] == ' ') {
              row = 0;
              col = 2;
          }
      }

          for (int i = 0; i < 3; i++) {
              if (board[i][0] == symbol && board[i][1] == symbol && board[i][2] == ' ') {
                  row = i;
                  col = 2;
              } else if (board[i][1] == symbol && board[i][2] == symbol && board[i][0] == ' ') {
                  row = i;
                  col = 0;
              } else if (board[i][0] == symbol && board[i][2] == symbol && board[i][1] == ' ') {
                  row = i;
                  col = 1;
              } else if (board[0][i] == symbol && board[1][i] == symbol && board[2][i] == ' ') {
                  row = 2;
                  col = i;
              } else if (board[1][i] == symbol && board[2][i] == symbol && board[0][i] == ' ') {
                  row = 0;
                  col = i;
              } else if (board[0][i] == symbol && board[2][i] == symbol && board[1][i] == ' ') {
                  row = 1;
                  col = i;
              }
          }

      return new int[] {row, col};
  
  }
  
  
}
