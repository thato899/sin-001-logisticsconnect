# DelayStageServiceApp

## Overview

Tracks the Transit Delay Stage (0-8, e.g. weather shutdowns).

Part of the [LogisticsConnect](../README.md) project. Independent Maven module, no
parent pom.

MQ: this service publishes to the ActiveMQ topic `package-status-topic` — see [`../common/`](../common). Broker URL and topic name come from the common `co.wethinkcode.logisticsconnect.mq.MqConfig` class alongside it in this module.

## API

- `GET /delay-stage/{hubId}` — `{ hubId, stage }`. Unseen hubs default to stage `0`.
- `POST /delay-stage/{hubId}` with `{ "stage": 0-8 }` — updates the stage, `400` on a
  malformed body or an out-of-range value, `200` with the updated record on success. On success
  this also publishes `{ hubId, stage, timestamp }` to `package-status-topic` (best-effort — a
  broker outage never fails the REST call itself).

## Project structure

```
delay-stage-service/
├── pom.xml
└── src/main/java/co/wethinkcode/logisticsconnect/
    ├── DelayStageServiceApp.java
    └── mq/
        └── MqConfig.java
```

## Build

```
mvn package
```

## Run

```
java -jar target/delay-stage-service.jar
```

Listens on port `7052`.

## Test

Unit tests cover stage validation. State is persisted to `STATE_FILE` (default
`data/delay-stages.json`). Manually verify it's up:

```
curl http://localhost:7052/health   # -> OK
```

To add real tests, add JUnit 5 + the Surefire plugin to `pom.xml`, put tests under
`src/test/java/co/wethinkcode/logisticsconnect/`, and run `mvn test`.
