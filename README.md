# Weather-RMI

Distributed systems case-study project with:
- Java RMI service for weather operations
- Embedded HTTP API for browser integration
- Web dashboard for live monitoring

## Project Structure

- `common/WeatherService.java`: shared RMI contract
- `server/WeatherServiceImpl.java`: in-memory weather store + history + summary
- `server/WeatherServer.java`: RMI registry binding, HTTP API, static file hosting, simulation
- `client/WeatherClient.java`: CLI RMI client
- `web/`: dashboard UI assets (`index.html`, `script.js`, `styles.css`)

## Requirements

- JDK 11+ (tested with JDK 24)
- Ports available: `1099` (RMI), `8080` (HTTP)

## Compile

From project root:

```bash
javac -encoding UTF-8 common/WeatherService.java server/WeatherServiceImpl.java server/WeatherServer.java client/WeatherClient.java
```

## Run

Start server from the project root:

```bash
java server.WeatherServer
```

If ports are occupied, run on custom ports:

```bash
java -Dweather.http.port=8081 -Dweather.rmi.port=1100 server.WeatherServer
```

Run client:

```bash
java client.WeatherClient
java client.WeatherClient Pune
```

Optional remote host/port:

```bash
java -Dweather.host=<host> -Dweather.port=<port> client.WeatherClient Pune
```

For custom local RMI port from above:

```bash
java -Dweather.host=localhost -Dweather.port=1100 client.WeatherClient Pune
```

Open dashboard:

- [http://localhost:8080/](http://localhost:8080/)
  - If using custom HTTP port, open that port instead (example: `http://localhost:8081/`)

## HTTP API

- `GET /api/weather` -> all locations
- `GET /api/weather?location=<name>` -> one location
- `POST /api/weather` with JSON body:
  - `{"location":"Pune","temperature":31.2,"humidity":65}`
- `GET /api/history?location=<name>&limit=<n>`
- `GET /api/summary`
