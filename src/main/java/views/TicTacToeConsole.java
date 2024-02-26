package views;

/*
 * Lex Castaneda
 * TicTacToeConsole.java
 * CSC 335
 * 
 * This class contains the simple console implementation of the TicTacToeGame.java class
 */
import java.util.Scanner;  


import model.TicTacToeGame;

public class TicTacToeConsole {

	public static void main(String[] args) {
		// TODO Auto-generated method stub
		Scanner scanner = new Scanner(System.in); 
	    TicTacToeGame b = new TicTacToeGame();
	    while(b.stillRunning()) {
		    System.out.println("Enter row and column: ");
		    String coor = scanner.nextLine();  // Read user input
		    String[] coorStr = coor.split(" ");
		    b.humanMove(Integer.parseInt(coorStr[0]), Integer.parseInt(coorStr[1]), false);
		    System.out.println(b.toString());
	    }
	    
	    if(b.didWin('X')) {
	    	System.out.println("X wins");
	    }
	    else if(b.didWin('O')) {
	    	System.out.println("O wins");
	    }
	    else {
	    	System.out.println("Tie");
	    }
	    
	    

		
		
	}

}
