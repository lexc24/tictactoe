package views;

/*
 * Lex Castaneda
 * ButtonView.java
 * CSC 335
 * 
 * This class contains the button view option. This holds all GUI objects to build the view. 
 * This class uses a hashmap to make for easy access to update button texts whenever the observable 
 * object is called.
 */

import java.util.Hashtable;
import java.util.Map;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
//import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.text.Font;
import model.OurObserver;
import model.OurPoint;
import model.TicTacToeGame;
import javafx.stage.Stage;

import static model.WebApi.alertEndGame;


//import model.TicTacToeStrategy;

public class ButtonView extends BorderPane implements OurObserver{

	private TicTacToeGame theGame;
	private Stage curStage;

	private Button button1;
	private Button button2;
	private Button button3;
	private Button button4;
	private Button button5;
	private Button button6;
	private Button button7;
	private Button button8;
	private Button button9;
	private GridPane gridpane;	

	
	private Hashtable<OurPoint, Button> hashtable = new Hashtable<>();
	 
	
	ButtonView(TicTacToeGame theModel, Stage s){
		theGame = theModel;
		curStage = s;
	    initializeButtons();
	    initializePanel();
	    registerhandlers();
	}
	
	
	private void registerhandlers() {
		// TODO Auto-generated method stub
		button1.setOnAction(new whichButton());
		button2.setOnAction(new whichButton());
		button3.setOnAction(new whichButton());
		button4.setOnAction(new whichButton());
		button5.setOnAction(new whichButton());
		button6.setOnAction(new whichButton());
		button7.setOnAction(new whichButton());
		button8.setOnAction(new whichButton());
		button9.setOnAction(new whichButton());

	}
	private class whichButton implements EventHandler<ActionEvent> {
		@Override
		public void handle(ActionEvent event) {
			// selected button
			if(theGame.stillRunning()) {
				Button buttonClicked = (Button) event.getSource();
				//human move
				for(Map.Entry<OurPoint, Button> b : hashtable.entrySet() ) {
					  if (b.getValue() == buttonClicked) {
						if(theGame.getTurn()==true)
						  theGame.humanMove(b.getKey().getRow(), b.getKey().getCol(), false);
						else
							theGame.player2Move(b.getKey().getRow(), b.getKey().getCol());
						  break;
					  }
				}
				checkGame();
				
			}
			
		}
	}
	 private void checkGame() {
			// TODO Auto-generated method stub
		    if(!theGame.stillRunning()) {
		    	if(theGame.didWin('X')) {
					GUI.label.setText("X wins");
					alertEndGame(curStage,"O","X");

				}
			    
			    else if(theGame.didWin('O')) {
					GUI.label.setText("O wins");
					alertEndGame(curStage,"X","O");
				}
			    
			    else 
			    	GUI.label.setText("Tie");
			    
		    }
		}

	private void initializePanel() {
		// TODO Auto-generated method stub
		gridpane = new GridPane();
		gridpane.setAlignment(Pos.CENTER);

		gridpane.add(button1, 0, 0);
		gridpane.add(button2, 1, 0);
		gridpane.add(button3, 2, 0);
		gridpane.add(button4, 0, 1);
		gridpane.add(button5, 1, 1);
		gridpane.add(button6, 2, 1);
		gridpane.add(button7, 0, 2);
		gridpane.add(button8, 1, 2);
		gridpane.add(button9, 2, 2);
		
		
		Font font = new Font("Arial", 20);
		GUI.label.setFont(font); 
		this.setCenter(gridpane);
		this.setBottom(GUI.label);

	}
	
	private void initializeButtons() {
		//Create Buttons
		button1 = new Button("_");
		button2 = new Button("_");
		button3 = new Button("_");
		button4 = new Button("_");
		button5 = new Button("_");
		button6 = new Button("_");
		button7 = new Button("_");
		button8 = new Button("_");
		button9 = new Button("_");
		
		
		//Set Up button looks
		button1.setAlignment(Pos.CENTER);
		button2.setAlignment(Pos.CENTER);
		button3.setAlignment(Pos.CENTER);
		button4.setAlignment(Pos.CENTER);
		button5.setAlignment(Pos.CENTER);
		button6.setAlignment(Pos.CENTER);
		button7.setAlignment(Pos.CENTER);
		button8.setAlignment(Pos.CENTER);
		button9.setAlignment(Pos.CENTER);
		
		button1.setPrefSize(50, 50);
		button2.setPrefSize(50, 50);
		button3.setPrefSize(50, 50);
		button4.setPrefSize(50, 50);
		button5.setPrefSize(50, 50);
		button6.setPrefSize(50, 50);
		button7.setPrefSize(50, 50);
		button8.setPrefSize(50, 50);
		button9.setPrefSize(50, 50);
		
		//Set Up button hashtable
		hashtable.put(new OurPoint(0,0), button1);
		hashtable.put(new OurPoint(0,1), button2);
		hashtable.put(new OurPoint(0,2), button3);
		hashtable.put(new OurPoint(1,0), button4);
		hashtable.put(new OurPoint(1,1), button5);
		hashtable.put(new OurPoint(1,2), button6);
		hashtable.put(new OurPoint(2,0), button7);
		hashtable.put(new OurPoint(2,1), button8);
		hashtable.put(new OurPoint(2,2), button9);

	}

	@Override
	public void update(Object theObserved) {
		char[][] board = ((TicTacToeGame) theObserved).getTicTacToeBoard();
		for(int i = 0; i < board.length; i++) {
			  for(int j = 0; j < board[i].length; j++) {
				  for(Map.Entry<OurPoint, Button> b : hashtable.entrySet() ) {
					  if ((b.getKey().getRow() == i)&& (b.getKey().getCol() == j)) 
						  b.getValue().setText(String.valueOf(board[i][j]));
				  }	
					  	
				  
			}
		}
	}
	
}
