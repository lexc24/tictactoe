package views;

/*
 * Lex Castaneda
 * TextAreaView.java
 * CSC 335
 * 
 * This class contains the text area view option. This holds all GUI objects to build the view. 
 * As well as some methods to error check for incorrect row,col choices.
 */
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import model.OurObserver;
import model.TicTacToeGame;
import javafx.stage.Stage;

import static model.WebApi.alertEndGame;


public class TextAreaView extends BorderPane implements OurObserver {

  private TicTacToeGame theGame;
  private Stage curStage;

  
  private GridPane gridpane;
  private Text message;
  private TextField rowField = new TextField("");
  private TextField columnField = new TextField("");
  private Button button = new Button("Make Move");
  private Label row = new Label("row");
  private Label column = new Label("column");
  private Label bottomLabel = new Label("");


  
  public TextAreaView(TicTacToeGame theModel,Stage s) {
    theGame = theModel;
	curStage = s;
    initializePanel();
    registerhandlers();

  }

  private void registerhandlers() {
	// TODO Auto-generated method stub
	  button.setOnAction(new movePlayers());
}
  private class movePlayers implements EventHandler<ActionEvent> {

	@Override
	public void handle(ActionEvent event) {
		// TODO Auto-generated method stub
		if(valid(rowField.getText(),columnField.getText()) && theGame.stillRunning()) {
		 theGame.humanMove(Integer.parseInt(rowField.getText()), Integer.parseInt(columnField.getText()), false);
		 button.setText("Make Move");

		 checkGame();
		}
		else
			button.setText("Invalid Choice");
		
	}
  }
  private void checkGame() {
		// TODO Auto-generated method stub
	    if(!theGame.stillRunning()) {
	    	if(theGame.didWin('X')) {
				bottomLabel.setText("X wins");
				alertEndGame(curStage,"O","X");
			}
		    
		    else if(theGame.didWin('O')) {
				bottomLabel.setText("O wins");
				alertEndGame(curStage,"X","O");
			}
		    
		    else 
		    	bottomLabel.setText("Tie");
	    	
		    
	    }
	}

  public boolean valid(String rowStr, String colStr) {
	  if(rowStr == ""  || colStr == "")
		  return false;
	  if(!Character.isDigit(rowStr.charAt(0))  || !Character.isDigit(colStr.charAt(0)))
		  return false;
	
	 int row = Integer.parseInt(rowStr);
	 int col = Integer.parseInt(colStr);
	 
	 if (row>2 || row <0)
		return false;
	 if (col>2|| col <0)
		return false;
	 if( !theGame.available(row,col))
		return false;
	

	return true;
}

private void initializePanel() {
   
    message = new Text();
    message.setStyle("-fx-border-color: blue; border-width: 10;");
    Font font = new Font("Monospace", 50);
    message.setFont(font);  
    message.setText(theGame.toString());
    this.setCenter(message);
    
	gridpane = new GridPane();
	gridpane.add(rowField, 0, 0);
	gridpane.add(columnField, 0, 1);
	gridpane.add(row, 1, 0);
	gridpane.add(column, 1, 1);
	gridpane.add(button, 1, 2);
	
	this.setTop(gridpane);
	
	Font font2 = new Font("Arial", 20);
	bottomLabel.setFont(font2); 
	this.setBottom(bottomLabel);
	
  }

 
  // This method is called by Observable's notifyObservers()
  @Override
  public void update(Object observable) {
	  message.setText(observable.toString());
  }
  
}