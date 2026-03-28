package common;

import java.rmi.Remote;
import java.rmi.RemoteException;

public interface WeatherService extends Remote {
    // Returns a JSON object of weather for a given location
    String getWeather(String location) throws RemoteException;

    // Returns a JSON array of all stored weather records
    String getAllWeather() throws RemoteException;

    // Updates/inserts weather data for a location on the central server
    void updateWeather(String location, double temperature, double humidity) throws RemoteException;

    // Returns a JSON array of recent records for a location
    String getHistory(String location, int limit) throws RemoteException;

    // Returns a JSON object summarizing current dataset
    String getSummary() throws RemoteException;
}
