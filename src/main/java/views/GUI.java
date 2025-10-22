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
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import model.*;



public class GUI extends Application {

  public static void main(String[] args) {
    launch(args);
  }

  private TicTacToeGame theGame;

  private OurObserver currentView;
  private OurObserver buttonView;
  private OurObserver textAreaView;
  
  private boolean curView = true;
  //private boolean curAI = true;

  private BorderPane window;
  public static final int width = 254;
  public static final int height = 360;
  
  //Menu Bar objects
  private final MenuBar mainMenu = new MenuBar();
  private final Menu StrategiesMenu = new Menu("Strategies");
  private final Menu ViewsMenu = new Menu("Views");
  
  private final Menu options = new Menu("Options");
  private final MenuItem newGame = new MenuItem("New Game");
  private final MenuItem random = new MenuItem("RandomAI");
  private final MenuItem intermediate = new MenuItem("IntermediateAI");
  private final MenuItem player2 = new MenuItem("Player 2");

  private final MenuItem buttonMenu = new MenuItem("Button");
  private final MenuItem textareaMenu = new MenuItem("TextArea");


  public static Label label = new Label("Click to Make a Move");
  private Stage globalS;

  public void start(Stage stage) {
//	stage = new Stage(); // this needs to happen first!

    stage.setTitle("Tic Tac Toe");
    stage.setMinHeight(400);
    stage.setMinWidth(600);
    //stage.setMaximized(true);

    window = new BorderPane();
    Scene scene = new Scene(window, width, height);
    theGame = new TicTacToeGame();
    
    // Set up the views
    buttonView = new ButtonView(theGame,stage);
    textAreaView = new TextAreaView(theGame,stage);
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
	  player2.setOnAction(new changeAI());

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

      if( ((MenuItem)event.getSource()).getText()=="Player 2"){
				theGame.setComputerPlayerStrategy(new Player2());

      }
      if( ((MenuItem)event.getSource()).getText()=="IntermediateAI"){
				theGame.setComputerPlayerStrategy(new IntermediateAI());

      }
      if( ((MenuItem)event.getSource()).getText()=="RandomAI"){
				theGame.setComputerPlayerStrategy(new RandomAI());

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
  StrategiesMenu.getItems().add(player2);

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
    GUI.label.setText("Click to Make a Move");

	  
  }

}