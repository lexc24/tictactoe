package views;

/*
 * Lex Castaneda
 * TicTacToeGUI.java
 * CSC 335
 * 
 * This class contains the GUI for TicTacToeGame.java file. This GUI has multiple options for viewing the 
 * game, ButtonView and TextArea view. It also has multiple options of computer AI difficulty, easy(random)
 * and intermediate. There is also an option to start new games. This class utlizes the observable TicTacToeGame.java
 * to change and update the observer views.
 */

import javafx.application.Application;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import model.IntermediateAI;
import model.OurObserver;
import model.RandomAI;
import model.TicTacToeGame;

public class TicTacToeGUI extends Application {

  public static void main(String[] args) {
    launch(args);
  }

  private TicTacToeGame theGame;

  private OurObserver currentView;
  private OurObserver buttonView;
  private OurObserver textAreaView;
  
  private boolean curView = true;
  private boolean curAI = true;

  private BorderPane window;
  public static final int width = 254;
  public static final int height = 360;
  
  //Menu Bar objects
  private MenuBar mainMenu = new MenuBar();
  private Menu StrategiesMenu = new Menu("Strategies");
  private Menu ViewsMenu = new Menu("Views");
  
  private Menu options = new Menu("Options");
  private MenuItem newGame = new MenuItem("New Game");
  private MenuItem random = new MenuItem("RandomAI");
  private MenuItem intermediate = new MenuItem("IntermediateAI");
  private MenuItem buttonMenu = new MenuItem("Button");
  private MenuItem textareaMenu = new MenuItem("TextArea");

  public void start(Stage stage) {
    stage.setTitle("Tic Tac Toe");
    window = new BorderPane();
    Scene scene = new Scene(window, width, height);
    theGame = new TicTacToeGame();
    
    // Set up the views
    buttonView = new ButtonView(theGame);
    textAreaView = new TextAreaView(theGame);
    theGame.addObserver(buttonView);
    theGame.addObserver(textAreaView);
    
    registerHandlers();
    setUpOptionsBar();
    initializeGameForTheFirstTime();

    stage.setScene(scene);
    stage.show();
  }
  
  
  private void registerHandlers() {
	// TODO Auto-generated method stub
	  buttonMenu.setOnAction(new changeView());
	  textareaMenu.setOnAction(new changeView());
	  
	  random.setOnAction(new changeAI());
	  intermediate.setOnAction(new changeAI());
	  
	  newGame.setOnAction(new createNewGame());

  }
  private class createNewGame implements EventHandler<ActionEvent>{
		@Override
		public void handle(ActionEvent event) {
			// TODO Auto-generated method stub
			startNewGame();
		}
	}

  private class changeAI implements EventHandler<ActionEvent>{
		@Override
		public void handle(ActionEvent event) {
			// TODO Auto-generated method stub
			if (curAI) {
				theGame.setComputerPlayerStrategy(new IntermediateAI());
				curAI = false;
			}
			else {
				theGame.setComputerPlayerStrategy(new RandomAI());
				curAI = true;
			}
		}
	}

  private class changeView implements EventHandler<ActionEvent>{
		@Override
		public void handle(ActionEvent event) {
			// TODO Auto-generated method stub
			if (curView) {
				setViewTo(buttonView);
				curView = false;
			}
			else {
				setViewTo(textAreaView);
				curView = true;
			}
		}
		
	}

  private void setUpOptionsBar() {
	//options bar
	mainMenu.getMenus().add(options);
	//menu options
	options.getItems().add(newGame);
	options.getItems().add(StrategiesMenu);
	options.getItems().add(ViewsMenu);
	//strategies submenu
	StrategiesMenu.getItems().add(random);
	StrategiesMenu.getItems().add(intermediate);
	//views submenu
	ViewsMenu.getItems().add(buttonMenu);
	ViewsMenu.getItems().add(textareaMenu);
	
	window.setTop(mainMenu);
	
}

/**
   * Set the game to the default of an empty board and the random AI.
   */
  public void initializeGameForTheFirstTime() {
    // This event driven program will always have
    // a computer player who takes the second turn
    setViewTo(textAreaView);
  }

  private void setViewTo(OurObserver newView) {
    window.setCenter(null);
    currentView = newView;
    window.setCenter((Node) currentView);
  }
  
  
  private void startNewGame() {
	  theGame.startNewGame();
	  
  }

}