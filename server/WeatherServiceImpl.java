package server;

import common.WeatherService;
import java.rmi.server.UnicastRemoteObject;
import java.rmi.RemoteException;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.StringJoiner;

public class WeatherServiceImpl extends UnicastRemoteObject implements WeatherService {

    private final Map<String, WeatherRecord> store = new ConcurrentHashMap<>();
    private final Map<String, Deque<WeatherRecord>> history = new ConcurrentHashMap<>();
    private static final int MAX_HISTORY = 200;

    public WeatherServiceImpl() throws RemoteException {
        super();
    }

    @Override
    public synchronized void updateWeather(String location, double temperature, double humidity) throws RemoteException {
        if (location == null || location.trim().isEmpty()) {
            throw new RemoteException("location must not be empty");
        }
        String normalizedLocation = location.trim();
        WeatherRecord rec = new WeatherRecord(normalizedLocation, temperature, humidity, System.currentTimeMillis());
        store.put(normalizedLocation, rec);
        Deque<WeatherRecord> dq = history.computeIfAbsent(normalizedLocation, k -> new ArrayDeque<>());
        dq.addLast(rec);
        while (dq.size() > MAX_HISTORY) dq.removeFirst();
    }

    @Override
    public synchronized String getHistory(String location, int limit) throws RemoteException {
        if (location == null || location.trim().isEmpty()) {
            return jsonError("invalid_location", "Location must not be empty");
        }
        Deque<WeatherRecord> dq = history.get(location.trim());
        if (dq == null || dq.isEmpty()) {
            return "[]";
        }
        int n = (limit <= 0) ? dq.size() : Math.min(limit, dq.size());
        List<WeatherRecord> list = new ArrayList<>(dq);
        int start = Math.max(0, list.size() - n);
        StringJoiner joiner = new StringJoiner(",", "[", "]");
        for (int i = start; i < list.size(); i++) {
            WeatherRecord r = list.get(i);
            joiner.add("{" +
                "\"location\":" + quote(r.location) + "," +
                "\"timestamp\":" + r.timestamp + "," +
                "\"temperature\":" + fmt(r.temperature) + "," +
                "\"humidity\":" + fmt(r.humidity) +
                "}");
        }
        return joiner.toString();
    }

    @Override
    public synchronized String getSummary() throws RemoteException {
        int count = store.size();
        if (count == 0) return "{\"totalLocations\":0}";
        double sumT = 0.0, sumH = 0.0;
        String latestLoc = null;
        long latestTs = -1;
        for (Map.Entry<String, WeatherRecord> e : store.entrySet()) {
            WeatherRecord r = e.getValue();
            sumT += r.temperature;
            sumH += r.humidity;
            if (r.timestamp > latestTs) { latestTs = r.timestamp; latestLoc = e.getKey(); }
        }
        double avgT = sumT / count;
        double avgH = sumH / count;
        return "{" +
            "\"totalLocations\":" + count + "," +
            "\"avgTemperature\":" + fmt(avgT) + "," +
            "\"avgHumidity\":" + fmt(avgH) + "," +
            "\"latestLocation\":" + quote(latestLoc) + "," +
            "\"latestTimestamp\":" + latestTs +
            "}";
    }
    @Override
    public synchronized String getWeather(String location) throws RemoteException {
        if (location == null || location.trim().isEmpty()) {
            return jsonError("invalid_location", "Location must not be empty");
        }
        String normalizedLocation = location.trim();
        WeatherRecord record = store.get(normalizedLocation);
        if (record == null) {
            return jsonError("not_found", "No weather for location: " + normalizedLocation);
        }
        return record.toJson();
    }

    @Override
    public synchronized String getAllWeather() throws RemoteException {
        StringJoiner joiner = new StringJoiner(",", "[", "]");
        for (Map.Entry<String, WeatherRecord> e : store.entrySet()) {
            joiner.add(e.getValue().toJson());
        }
        return joiner.toString();
    }

    synchronized List<WeatherRecordSnapshot> getSnapshot() {
        List<WeatherRecordSnapshot> snapshot = new ArrayList<>(store.size());
        for (WeatherRecord record : store.values()) {
            snapshot.add(new WeatherRecordSnapshot(
                record.location, record.temperature, record.humidity, record.timestamp
            ));
        }
        return snapshot;
    }

    private static String jsonError(String code, String message) {
        return "{" +
            "\"error\":" + quote(code) + "," +
            "\"message\":" + quote(message) +
            "}";
    }

    private static String quote(String s) {
        return "\"" + escape(s) + "\"";
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String fmt(double d) {
        if (d == (long) d) {
            return Long.toString((long) d);
        } else {
            return Double.toString(d);
        }
    }

    private static class WeatherRecord {
        private final String location;
        private final double temperature;
        private final double humidity;
        private final long timestamp;

        WeatherRecord(String location, double temperature, double humidity, long timestamp) {
            this.location = location;
            this.temperature = temperature;
            this.humidity = humidity;
            this.timestamp = timestamp;
        }

        String toJson() {
            return "{" +
                "\"location\":" + quote(location) + "," +
                "\"temperature\":" + fmt(temperature) + "," +
                "\"humidity\":" + fmt(humidity) + "," +
                "\"timestamp\":" + timestamp +
                "}";
        }
    }

    static class WeatherRecordSnapshot {
        final String location;
        final double temperature;
        final double humidity;
        final long timestamp;

        WeatherRecordSnapshot(String location, double temperature, double humidity, long timestamp) {
            this.location = location;
            this.temperature = temperature;
            this.humidity = humidity;
            this.timestamp = timestamp;
        }
    }
}
