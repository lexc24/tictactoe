/**
 * This strategy selects the first available move at random.  
 * It is easy to beat this strategy because it is totally random.
 * 
 * @throws 
 *    IGotNowhereToGoException whenever asked for a 
 *    move that is impossible to deliver.
 * 
 * @author Rick Mercer
 * 
 */

package model;

public class Player2 implements TicTacToeStrategy{
    public String letter = "O";
    @Override
    public OurPoint desiredMove(TicTacToeGame theGame) {
        // TODO Auto-generated method stub
        return null;
    }

}
