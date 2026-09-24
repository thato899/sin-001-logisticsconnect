# HubServiceApp

## Overview

Serves provinces and sorting centers (place-name source of truth).

Part of the [LogisticsConnect](../README.md) project. Independent Maven module, no
parent pom.

## API

- `GET /hubs` — proxies/caches ingestion-service's `GET :7050/hubs`.
- `GET /hubs/{hubId}` — a single hub (case-insensitive ID match), or `404`.
- Both return `502` if ingestion-service can't be reached and no cached data exists yet. Once
  cached, the cache is refreshed lazily on the next request after a miss — hub-service recovers
  on its own once ingestion-service comes back up, no restart needed.

## Project structure

```
hub-service/
├── pom.xml
└── src/main/java/co/wethinkcode/logisticsconnect/
    ├── Hub.java
    └── HubServiceApp.java
```

## Build

```
mvn package
```

## Run

```
java -jar target/hub-service.jar
```

Listens on port `7051`.

## Test

Unit tests cover case-insensitive hub ID matching. Manually verify it's up:

```
curl http://localhost:7051/health   # -> OK
```

To add real tests, add JUnit 5 + the Surefire plugin to `pom.xml`, put tests under
`src/test/java/co/wethinkcode/logisticsconnect/`, and run `mvn test`.
