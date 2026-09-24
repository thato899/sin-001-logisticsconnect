# Debrief

## Data cleaning and deduplication

`HubCsvCleaner` trims and normalizes casing, whitespace, provinces, boolean values, and
placeholders. Duplicate rows are grouped by normalized sorting-center name. The canonical ID is
the lowest numeric suffix; missing provinces are backfilled from siblings; and `active` uses an
"any true wins" rule. The real dataset reduces from 18 rows to 10 records.

## Service boundaries

Hub location data remains synchronous because it is relatively static and required immediately by
an ETA request. Delay stages are events: delay-stage-service publishes persistent messages to
`package-status-topic`, while transit-service and AlertBot consume them through durable ActiveMQ
subscriptions. ActiveMQ failover reconnect is enabled for brief broker interruptions.

## Persistence and operations

Delay stages, alert history, and AlertBot's last-seen stage map are persisted with atomic JSON file
replacement. Compose mounts named volumes for these files. A multi-replica deployment should use a
shared transactional database instead of local files, and should add external metrics, tracing,
backups, secret management, and a managed broker.

The root Maven reactor, GitHub Actions workflow, Dockerfile, Compose health checks, and unit tests
provide repeatable build and deployment verification. The live Compose path was verified by sending
a stage update through ActiveMQ, observing the ETA update, and observing the threshold alert.
