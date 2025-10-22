/**
 * This class allows a tic tac to player to play games
 * against a variety of AIs. It completely relies on 
 * the TicTacToeStrategy for it's next move with the  
 * desiredMove method that can "see" the game.
 * 
 * @author Lex Castaneda
 */
package model;

public class ComputerPlayer {

  private TicTacToeStrategy myStrategy;

  public ComputerPlayer() {
    // This default TicTacToeStrategy can be changed with setStrategy
    myStrategy = new RandomAI();
  }

  /**
   * Change the AI for this ComputerPlayer.
   * 
   * @param strategy  Any type that implements TicTacToeStrategy
   */
  public void setStrategy(TicTacToeStrategy strategy) {
    myStrategy = strategy;
  }
  public TicTacToeStrategy getStrategy() {
    return this.myStrategy;
  }

 
  /**
   * Delegate to my strategy, which can "see" the game for my next move
   * 
   * @param theGame The current state of the game when asked for a move
   * 
   * @return A point that store two ints: an row and a colim
   */
  public OurPoint desiredMove(TicTacToeGame theGame) {
    return myStrategy.desiredMove(theGame);
  }
}