# AlertBotApp

## Overview

Posts proactive delay notifications to public transit social media pages (simulated).

Part of the [LogisticsConnect](../README.md) project — its alerting service.
Independent Maven module, no parent pom.

Mechanism: Outbound webhook, simulated social post

MQ (stretch goal): this service subscribes to the ActiveMQ topic
`package-status-topic` — see [`../common/`](../common). Broker URL and topic name
come from the common `co.wethinkcode.logisticsconnect.mq.MqConfig` class alongside
it in this module. Use the stage in each message to decide when to raise an alert
(e.g. above a threshold you choose).

## API

- Chosen threshold: stage `>= 6` (of 0-8) — severe enough to be worth telling the public about.
- Fires only when a hub's stage *crosses upward* through the threshold (tracked per hub), not on
  every message that's still above it — re-fires if the hub drops back below and crosses again.
- "Posting" is simulated: a log line plus an in-memory record.
- `GET /alerts` — every simulated alert fired so far, as a JSON array of
  `{ hubId, stage, postedAt, message }`.

## Project structure

```
alertbot/
├── pom.xml
└── src/main/java/co/wethinkcode/logisticsconnect/
    ├── AlertBotApp.java
    └── mq/
        └── MqConfig.java
```

## Build

```
mvn package
```

## Run

```
java -jar target/alertbot.jar
```

Listens on port `7054`.

## Test

No automated tests yet. Manually verify it's up:

```
curl http://localhost:7054/health   # -> OK
```

To add real tests, add JUnit 5 + the Surefire plugin to `pom.xml`, put tests under
`src/test/java/co/wethinkcode/logisticsconnect/`, and run `mvn test`.
