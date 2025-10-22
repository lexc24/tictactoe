package model;
import com.jpro.webapi.WebAPI;
import javafx.stage.Stage;

public class WebApi {

    public static void alertEndGame(Stage yourStage, String loserId, String winnerId){
        WebAPI webAPI = WebAPI.getWebAPI(yourStage); // 'yourStage' should be your current Stage
        String script = String.format("document.dispatchEvent(new CustomEvent('gameOverEvent', { " +
                "detail: { loserId: '%s', winnerId: '%s' } }));", loserId, winnerId);
        webAPI.js().eval("console.log('Hello from Java, using js().eval()')");
        webAPI.js().eval(script);
        webAPI.js().eval("console.log('Just executed the gameOverEvent, using js().eval()')");

    }


}
