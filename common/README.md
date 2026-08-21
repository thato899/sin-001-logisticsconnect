# common — Asynchronous Decoupling (MQ)

## Overview

Topic: `package-status-topic`

Package status updates move from latency-driven RPC to bandwidth-driven messaging.

Part of the [LogisticsConnect](../README.md) project. Holds the ActiveMQ broker shared
by the services below — not a service itself, so it has no port of its own.

- Producer: `delay-stage-service` (`../delay-stage-service`)
- Consumer(s): `transit-service` (required, stage 3); `alertbot` (stretch, stage 4 —
  reacts to stage changes to decide when to raise an alert)

Broker URL and topic name are shared via a common `co.wethinkcode.logisticsconnect.mq.MqConfig` class
(`BROKER_URL`, `TOPIC`). It's identical in every participating service's own source
tree — each service here is an independent Maven project with no shared parent pom,
so the common package is duplicated rather than imported from one place.

## Project structure

```
common/
├── docker-compose.yml
└── README.md
```

This folder holds the broker config and notes only — the actual publish/subscribe
code belongs in the producer/consumer services listed above (their poms already
depend on `activemq-client`, and each already has
`src/main/java/co/wethinkcode/logisticsconnect/mq/MqConfig.java`).

## Build

Nothing to build here directly — this folder just brings up the broker used by the
services listed above.

## Run

```
docker compose up -d
```

- Broker URL for clients: `tcp://localhost:61616`
- Web console: http://localhost:8161 (default admin/admin)

Then start the producer/consumer services as usual (`mvn package && java -jar ...`
from their own directories at the project root).

## Test

```
docker compose ps          # confirm the broker container is healthy
```

Verify end-to-end by publishing a message from `delay-stage-service` (`POST
:7052/delay-stage/{hubId}`) and confirming the consumer(s) receive it:

```
curl -X POST localhost:7052/delay-stage/H-501 -H "Content-Type: application/json" -d '{"stage":7}'
```

`transit-service` and `alertbot` (if running) both log a line on receipt, e.g. `Received stage
update: H-501 -> stage 7`. The ActiveMQ web console at `localhost:8161` is also fine evidence.

## Status

Both producer (`delay-stage-service`) and consumers (`transit-service`, and `alertbot` as the
stretch goal) are implemented — see each service's own README for its `MqConfig`-based
publish/subscribe details.
