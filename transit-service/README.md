# TransitServiceApp

## Overview

Calculates estimated arrival windows based on hub and delay stage.

Part of the [LogisticsConnect](../README.md) project. Independent Maven module, no
parent pom.

MQ: this service subscribes to the ActiveMQ topic `package-status-topic` — see [`../common/`](../common). Broker URL and topic name come from the common `co.wethinkcode.logisticsconnect.mq.MqConfig` class alongside it in this module.

## API

- `GET /eta/{hubId}` — calls hub-service for location, reads the current delay stage from a local
  cache kept up to date by the `package-status-topic` subscription (unseen hubs default to stage
  `0`), and returns `{ hubId, province, sortingCenter, delayStage, etaWindowStart, etaWindowEnd }`.
  ETA formula: a 24h baseline, plus a window that widens by 6h per delay stage (4h base
  uncertainty at stage 0). `404` if hub-service doesn't know the hub, `502` if hub-service is
  unreachable.

## Project structure

```
transit-service/
├── pom.xml
└── src/main/java/co/wethinkcode/logisticsconnect/
    ├── Hub.java
    ├── TransitServiceApp.java
    └── mq/
        └── MqConfig.java
```

## Build

```
mvn package
```

## Run

```
java -jar target/transit-service.jar
```

Listens on port `7053`.

## Test

No automated tests yet. Manually verify it's up:

```
curl http://localhost:7053/health   # -> OK
```

To add real tests, add JUnit 5 + the Surefire plugin to `pom.xml`, put tests under
`src/test/java/co/wethinkcode/logisticsconnect/`, and run `mvn test`.
