# IngestionServiceApp

## Overview

Parses and cleans `hubs-global.csv`, a messy legacy export of hubs, sorting centers, and regional districts data, and is the
first stop in the LogisticsConnect pipeline. Independent Maven module, no parent pom.

Part of the [LogisticsConnect](../README.md) project.

## Known data issues

`hubs-global.csv` is deliberately messy — cleaning it is the point of this service. Look
out for (and handle) at least:

- **Inconsistent casing** in IDs, names, and status/category values (`Active` /
  `active` / `ACTIVE`)
- **Padding** — leading/trailing spaces, and the occasional double space, inside
  fields
- **Duplicate records** for the same real-world entity, written with a different ID
  casing/format and/or slightly different field values
- **Inconsistent date formats** (`YYYY-MM-DD`, `MM/DD/YYYY`, `DD-MM-YYYY`, one- and
  two-digit months/days) and outright invalid dates
- **Missing / placeholder values** — blank fields, `N/A`, `n/a`, `TBD`, `unknown`,
  `-`, `NaN`
- **Invalid or non-numeric values** in numeric columns (negative counts, spelled-out
  numbers, unrealistic values)
- **Inconsistent boolean/flag representations** (`Y`/`N`, `yes`/`no`, `1`/`0`,
  `true`/`FALSE`)
- **Naming/spelling variants** for the same thing (e.g. regional spelling
  differences, synonyms)

## Worked example

Two raw rows from `hubs-global.csv`:

```
hub_id, Province ,sorting_center,active
H-502 ,gauteng,Pretoria North,0
H-505,Western Cape ,Cape Town  Port,TRUE
```

A reasonable cleaned shape for those same two rows:

| hub_id | province | sorting_center | active |
|---|---|---|---|
| H-502 | Gauteng | Pretoria North | false |
| H-505 | Western Cape | Cape Town Port | true |

That covers padding (`H-502 ` → `H-502`), casing (`gauteng` → `Gauteng`), a
collapsed double space (`Cape Town  Port` → `Cape Town Port`), and boolean
normalization (`0`/`TRUE` → `false`/`true`). Column names, exact casing
convention, and boolean representation are up to you — just be consistent.

This doesn't cover deduplication: rows like `H-500`, `H-504`, `H-510`, and `H-515`
all describe "Johannesburg Central" in "Gauteng" under different hub IDs, with
conflicting `active` values between them. How you resolve that (which one wins,
how you detect they're duplicates in the first place) is part of the exercise —
there's no single correct answer, but be ready to explain your reasoning.

## API

- `GET /hubs` — the cleaned, deduplicated hub list, as a JSON array.
- `GET /hubs/{hubId}` — a single cleaned record (case-insensitive ID match), or `404`.

Cleaning is done once at startup by `HubCsvCleaner` and cached in memory. See
[HubCsvCleaner.java](src/main/java/co/wethinkcode/logisticsconnect/HubCsvCleaner.java) for the
normalization rules and the dedup strategy (grouped by normalized sorting-center name; lowest
numeric hub ID wins as canonical; missing province backfilled from a duplicate sibling; `active`
resolved via "any true wins"). Against the real `hubs-global.csv`, 18 raw rows collapse to 10
records across 4 duplicate clusters.

## Project structure

```
ingestion-service/
├── pom.xml
└── src/main/
    ├── java/co/wethinkcode/logisticsconnect/
    │   ├── Hub.java
    │   ├── HubCsvCleaner.java
    │   └── IngestionServiceApp.java
    └── resources/hubs-global.csv
```

## Build

```
mvn package
```

## Run

```
java -jar target/ingestion-service.jar
```

Listens on port `7050`. Cleans and caches `hubs-global.csv` at startup, then serves it via
`/hubs` and `/hubs/{hubId}` (see [API](#api) below) alongside `/health`.

## Test

```
mvn test
```

Runs [HubCsvCleanerTest](src/test/java/co/wethinkcode/logisticsconnect/HubCsvCleanerTest.java) — 15
tests covering each normalization rule in isolation (casing, padding, province spelling variants,
boolean flags, unknown/placeholder handling) plus the dedup behavior (merge, canonical ID
selection, active resolution, missing-province backfill), and 3 tests run against the real
`hubs-global.csv` confirming the 18-rows-to-10-records result.

To sanity-check the running service instead:

```
curl http://localhost:7050/health   # -> OK
```
