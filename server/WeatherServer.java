package server;

import common.WeatherService;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class WeatherServer {

    public static void main(String[] args) {
        int rmiPort = Integer.getInteger("weather.rmi.port", 1099);
        int httpPort = Integer.getInteger("weather.http.port", 8080);
        try {
            WeatherServiceImpl service = new WeatherServiceImpl();

            // Prepopulate sample weather data on the central server
            service.updateWeather("Pune", 31, 65);
            service.updateWeather("Mumbai", 29, 70);
            service.updateWeather("Delhi", 12, 50);
            service.updateWeather("London", 14, 75);
            service.updateWeather("New York", 18, 55);
            service.updateWeather("Tokyo", 22, 60);
            service.updateWeather("Paris", 16, 68);
            service.updateWeather("Berlin", 13, 72);
            service.updateWeather("Sydney", 24, 62);
            service.updateWeather("Singapore", 30, 85);
            service.updateWeather("Dubai", 38, 40);
            service.updateWeather("Rome", 20, 58);
            service.updateWeather("Amsterdam", 11, 80);
            service.updateWeather("Toronto", 15, 52);
            service.updateWeather("San Francisco", 19, 64);
            service.updateWeather("Bangkok", 34, 78);
            service.updateWeather("Seoul", 21, 59);
            service.updateWeather("Cairo", 32, 45);
            service.updateWeather("Madrid", 23, 50);
            service.updateWeather("Moscow", 5, 82);

            Registry registry;
            try {
                registry = LocateRegistry.createRegistry(rmiPort);
            } catch (java.rmi.server.ExportException alreadyRunning) {
                registry = LocateRegistry.getRegistry(rmiPort);
            }
            registry.rebind("WeatherService", service);

            // Start embedded HTTP server for frontend integration
            HttpServer http = HttpServer.create(new InetSocketAddress(httpPort), 0);
            http.createContext("/api/weather", new ApiWeatherHandler(service));
            http.createContext("/api/history", new ApiHistoryHandler(service));
            http.createContext("/api/summary", new ApiSummaryHandler(service));
            http.createContext("/", new StaticHandler("web/index.html"));
            http.createContext("/script.js", new StaticHandler("web/script.js"));
            http.createContext("/styles.css", new StaticHandler("web/styles.css"));
            http.setExecutor(null);
            http.start();

            System.out.println("Weather RMI Server started on RMI:" + rmiPort + " and HTTP:" + httpPort + "...");
            System.out.println("Frontend URL: http://localhost:" + httpPort + "/");

            // Start background simulation thread to jitter weather data
            Thread simulator = new Thread(() -> {
                java.util.Random rnd = new java.util.Random();
                while (true) {
                    try {
                        Thread.sleep(3000);
                        List<WeatherServiceImpl.WeatherRecordSnapshot> snapshot = service.getSnapshot();
                        for (WeatherServiceImpl.WeatherRecordSnapshot node : snapshot) {
                            // Jitter temperature by ±0.5 and humidity by ±1
                            double newT = Math.round((node.temperature + (rnd.nextDouble() - 0.5)) * 10.0) / 10.0;
                            double newH = Math.max(0, Math.min(100, Math.round(node.humidity + (rnd.nextInt(3) - 1))));
                            service.updateWeather(node.location, newT, newH);
                        }
                    } catch (Exception e) {
                        System.err.println("Simulation error: " + e.getMessage());
                    }
                }
            }, "weather-simulator");
            simulator.setDaemon(true);
            simulator.start();
        } catch (java.net.BindException be) {
            System.err.println("Port bind failed: " + be.getMessage());
            System.err.println("Tip: use custom ports, e.g. java -Dweather.http.port=8081 -Dweather.rmi.port=1100 server.WeatherServer");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    static class ApiHistoryHandler implements HttpHandler {
        private final WeatherService service;
        ApiHistoryHandler(WeatherService service) { this.service = service; }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    send(exchange, 405, jsonError("method_not_allowed", "Use GET"));
                    return;
                }
                Map<String, String> params = parseQuery(exchange.getRequestURI().getRawQuery());
                String loc = params.get("location");
                int limit = 50;
                try { String l = params.get("limit"); if (l != null) limit = Integer.parseInt(l); } catch (Exception ignored) {}
                if (loc == null || loc.isEmpty()) {
                    send(exchange, 400, jsonError("invalid_location", "Provide location"));
                    return;
                }
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                String json = service.getHistory(loc, limit);
                send(exchange, 200, json);
            } catch (Exception e) {
                send(exchange, 500, jsonError("server_error", safe(e.getMessage())));
            } finally {
                exchange.close();
            }
        }
    }

    static class ApiSummaryHandler implements HttpHandler {
        private final WeatherService service;
        ApiSummaryHandler(WeatherService service) { this.service = service; }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    send(exchange, 405, jsonError("method_not_allowed", "Use GET"));
                    return;
                }
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                String json = service.getSummary();
                send(exchange, 200, json);
            } catch (Exception e) {
                send(exchange, 500, jsonError("server_error", safe(e.getMessage())));
            } finally {
                exchange.close();
            }
        }
    }
    static class ApiWeatherHandler implements HttpHandler {
        private final WeatherService service;
        ApiWeatherHandler(WeatherService service) { this.service = service; }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                String method = exchange.getRequestMethod();
                URI uri = exchange.getRequestURI();
                Map<String, String> params = parseQuery(uri.getRawQuery());
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");

                if ("GET".equalsIgnoreCase(method)) {
                    String location = params.get("location");
                    String json = (location == null || location.isEmpty())
                        ? service.getAllWeather()
                        : service.getWeather(location);
                    send(exchange, 200, json);
                } else if ("POST".equalsIgnoreCase(method)) {
                    String body = new String(readAll(exchange.getRequestBody()), StandardCharsets.UTF_8);
                    Map<String, String> fields = extractJsonFields(body);
                    String loc = fields.get("location");
                    Double temp = parseDouble(fields.get("temperature"));
                    Double hum = parseDouble(fields.get("humidity"));
                    if (loc == null || loc.trim().isEmpty() || temp == null || hum == null) {
                        send(exchange, 400, jsonError("bad_request", "Expected JSON with location, temperature, humidity"));
                        return;
                    }
                    try {
                        service.updateWeather(loc, temp, hum);
                        send(exchange, 200, "{\"status\":\"ok\"}");
                    } catch (Exception ex) {
                        send(exchange, 500, jsonError("server_error", safe(ex.getMessage())));
                    }
                } else {
                    send(exchange, 405, jsonError("method_not_allowed", "Use GET or POST"));
                }
            } catch (Exception e) {
                send(exchange, 500, jsonError("server_error", safe(e.getMessage())));
            } finally {
                exchange.close();
            }
        }
    }

    static class StaticHandler implements HttpHandler {
        private final String filePath;
        StaticHandler(String filePath) { this.filePath = filePath; }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                byte[] data = FilesUtil.readFile(filePath);
                String ct = contentType(filePath);
                exchange.getResponseHeaders().set("Content-Type", ct);
                exchange.sendResponseHeaders(200, data.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(data);
                }
            } catch (IOException ex) {
                byte[] msg = ("Not found: " + filePath).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                exchange.sendResponseHeaders(404, msg.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(msg); }
            } finally {
                exchange.close();
            }
        }
    }

    private static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, data.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(data); }
    }

    private static byte[] readAll(InputStream is) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
        return baos.toByteArray();
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> out = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) return out;
        for (String part : query.split("&")) {
            int idx = part.indexOf('=');
            if (idx >= 0) out.put(urlDecode(part.substring(0, idx)), urlDecode(part.substring(idx + 1)));
            else out.put(urlDecode(part), "");
        }
        return out;
    }

    private static String urlDecode(String s) {
        try { return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8.name()); }
        catch (Exception e) { return s; }
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static String jsonError(String code, String message) {
        return "{" +
            "\"error\":" + quote(code) + "," +
            "\"message\":" + quote(message) +
            "}";
    }

    private static String quote(String s) {
        String t = safe(s);
        return "\"" + t.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static Double parseDouble(String s) {
        if (s == null) return null;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return null; }
    }

    // Small JSON extractor for flat objects {"k":"v","n":1} with quoted-commas support.
    private static Map<String, String> extractJsonFields(String json) {
        Map<String, String> map = new LinkedHashMap<>();
        if (json == null) return map;
        String trimmed = json.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return map;
        String inner = trimmed.substring(1, trimmed.length() - 1);
        for (String pair : splitTopLevel(inner)) {
            int colon = pair.indexOf(':');
            if (colon < 0) continue;
            String k = stripQuotes(pair.substring(0, colon).trim());
            String vraw = pair.substring(colon + 1).trim();
            String v = vraw.startsWith("\"") ? stripQuotes(vraw) : vraw;
            map.put(k, v);
        }
        return map;
    }

    private static String stripQuotes(String s) {
        String t = s.trim();
        if (t.startsWith("\"") && t.endsWith("\"")) return unescapeJsonString(t.substring(1, t.length() - 1));
        return t;
    }

    private static String unescapeJsonString(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean escaped = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escaped) {
                switch (c) {
                    case 'n': out.append('\n'); break;
                    case 'r': out.append('\r'); break;
                    case 't': out.append('\t'); break;
                    case '"':
                    case '\\':
                    case '/': out.append(c); break;
                    default: out.append(c); break;
                }
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else {
                out.append(c);
            }
        }
        if (escaped) out.append('\\');
        return out.toString();
    }

    private static java.util.List<String> splitTopLevel(String input) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (escaped) {
                current.append(c);
                escaped = false;
                continue;
            }
            if (c == '\\') {
                current.append(c);
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                current.append(c);
                continue;
            }
            if (c == ',' && !inString) {
                parts.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        if (!current.isEmpty()) {
            parts.add(current.toString());
        }
        return parts;
    }

    private static String contentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
        return "application/octet-stream";
    }

    // Small file utility
    static class FilesUtil {
        private static final Path WORKDIR = Paths.get(System.getProperty("user.dir")).toAbsolutePath();

        static byte[] readFile(String relPath) throws IOException {
            Path requested = Paths.get(relPath).normalize();
            Path fromWorkdir = WORKDIR.resolve(requested).normalize();
            if (java.nio.file.Files.exists(fromWorkdir)) {
                return java.nio.file.Files.readAllBytes(fromWorkdir);
            }
            Path fromRepoRoot = WORKDIR.resolve("Weather-RMI").resolve(requested).normalize();
            if (java.nio.file.Files.exists(fromRepoRoot)) {
                return java.nio.file.Files.readAllBytes(fromRepoRoot);
            }
            java.net.URL resource = WeatherServer.class.getClassLoader().getResource(relPath.replace("\\", "/"));
            if (resource != null) {
                try (InputStream is = resource.openStream()) {
                    return readAll(is);
                }
            }
            throw new IOException("Missing file: " + relPath);
        }
    }
}
