package model;
import java.util.ArrayList;

public class OurObservable {
  ArrayList<OurObserver> observers = new ArrayList<>();
  
  public void addObserver(OurObserver anObserver) {
    observers.add(anObserver);
  }
 
  public void notifyObservers(OurObservable theObservable) {
    for(OurObserver obs : observers) {
      obs.update(theObservable);
    }
  }
}

