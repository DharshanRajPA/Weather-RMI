package client;

import common.WeatherService;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;

public class WeatherClient {

    public static void main(String[] args) {
        try {
            Registry registry = LocateRegistry.getRegistry("localhost", 1099);
            WeatherService service = (WeatherService) registry.lookup("WeatherService");

            if (args.length > 0) {
                String location = args[0];
                String json = service.getWeather(location);
                if (json.contains("\"error\"")) {
                    System.out.println("Server response: " + json);
                } else {
                    String loc = extractString(json, "location");
                    double temp = extractDouble(json, "temperature");
                    double hum = extractDouble(json, "humidity");
                    System.out.println("Location: " + loc);
                    System.out.println("Temperature: " + temp + " °C");
                    System.out.println("Humidity: " + hum + " %");
                }
            } else {
                String json = service.getAllWeather();
                System.out.println("All Weather JSON: " + json);
                System.out.println("Hint: run with a location argument to see parsed output, e.g. 'java client.WeatherClient Pune'");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Minimal JSON helpers for flat object parsing
    private static String extractString(String json, String key) {
        String pattern = "\"" + key + "\":";
        int idx = json.indexOf(pattern);
        if (idx < 0) return null;
        int start = json.indexOf('"', idx + pattern.length());
        if (start < 0) return null;
        int end = json.indexOf('"', start + 1);
        if (end < 0) return null;
        return json.substring(start + 1, end);
    }

    private static double extractDouble(String json, String key) {
        String pattern = "\"" + key + "\":";
        int idx = json.indexOf(pattern);
        if (idx < 0) return Double.NaN;
        int start = idx + pattern.length();
        // read until comma or end brace
        int end = json.indexOf(',', start);
        if (end < 0) end = json.indexOf('}', start);
        String num = json.substring(start, end).trim();
        try {
            return Double.parseDouble(num);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
