# Debrief

Notes on the design decisions behind this implementation, and what I'd revisit given more time.
Written to accompany the PR/submission — see the root [README](README.md) for the task itself and
each service's own README for its specific API.

## Data cleaning & dedup strategy (Stage 1)

**Normalization** (casing, padding, double spaces, boolean flags, province spelling variants) is
mostly mechanical — trim, collapse whitespace, map through a canonical-form lookup. The one
judgment call there was `unknown`/`N/A`/blank values on the `active` column: I treat these as
genuinely ambiguous (`null`) rather than guessing `true` or `false`. Silently defaulting an unknown
value to `false` would make an active hub look shut down; defaulting to `true` risks the opposite.
Leaving it `null` pushes that decision to whoever consumes the field, with the ambiguity visible
instead of hidden.

**Dedup** was the harder call, since the brief is explicit that there's no single right answer —
what matters is that the reasoning is sound and articulable. My approach:

- **Group by normalized sorting-center name**, not by any similarity heuristic on the hub ID. In
  this dataset, hub ID and province are exactly the fields that vary or go missing across
  duplicate rows for the same physical place (see `H-508`, which has a blank province but shares
  a sorting center with `H-502`); the sorting-center name is the one field that stays a reliable
  identity signal. This also means the grouping key is cheap and deterministic — no fuzzy string
  matching, no thresholds to tune.
- **Canonical ID = lowest numeric suffix** in the group. Arbitrary, but stable and predictable —
  the "first-seen" ID (by numbering, not by row order) is the one downstream services see, and
  the choice doesn't depend on which row happened to appear first in the file.
- **Missing province backfilled from a sibling** in the same group. If any duplicate row has a
  usable province, a hub in the output shouldn't have a `null` one just because the specific row
  that happened to be picked as canonical was incomplete.
- **`active` resolves via "any true wins."** A duplicate row confirming a hub is active is a
  stronger signal than a stale "inactive" snapshot elsewhere in the export — an inactive hub is
  unlikely to have a duplicate row mistakenly asserting it's active. This is the one rule I'd
  flag as most debatable: it assumes newer/positive signals should win over older/negative ones
  with no timestamp to actually confirm recency. A real system would resolve this with an
  ingestion timestamp per row instead of an assumption.

Verified against the real `hubs-global.csv`: 18 raw rows collapse to 10 records across 4 clusters,
one of which (`H-502`/`H-508`, Pretoria North) isn't obvious from a first read of the file — it only
surfaces once you group by sorting center rather than skimming for matching hub IDs.

## REST vs. MQ, at each stage

Stage 2 uses synchronous REST because at that point every call is a **read** that needs an
immediate answer to satisfy the request in front of it — transit-service can't compute an ETA
without knowing the hub's location and current stage *right now*. A request/response call is the
right shape for "I need this value before I can continue."

Stage 3 replaces exactly one of those links — delay-stage-service → transit-service — with the MQ
topic, and deliberately not the others (transit-service still calls hub-service synchronously).
The delay-stage link is the one whose *pattern* changes: a delay stage isn't something transit-
service needs to ask about per-request, it's a fact that occasionally changes and every interested
party should hear about as it happens. That's a publish/subscribe problem, not a request/response
one. Moving it to MQ means transit-service is no longer coupled to delay-stage-service's uptime on
every single request — it holds the last-known value and updates it opportunistically — and it's
what makes Stage 4 (alertbot) nearly free: alertbot just becomes another subscriber to a feed that
already exists, with no changes needed anywhere else.

I did not move the hub-service link to MQ. Hub/location data changes rarely if ever compared to
delay stage, so the cost of a synchronous call per request is low, and there's no natural "event"
to publish (nothing about a hub *changes* on a schedule the way a delay stage does). Forcing every
service link onto MQ "for consistency" would have added indirection without solving a real problem.

## AlertBot threshold (Stage 4, stretch)

Chose stage ≥ 6 (of 0–8) as "severe enough to be worth a public post" — roughly the top third of
the scale, reserved for something like a weather shutdown rather than routine sorting delays.
Alerts fire only on **crossing upward** through the threshold (tracked per hub), not on every
message that happens to still be above it — otherwise a hub sitting at stage 8 would spam an alert
on every unrelated update. It re-fires if a hub drops back below the threshold and crosses again
later, since that's a genuinely new event worth telling someone about.

## What I'd do differently with more time

- **Persistence.** Every service holds its state in memory (`ConcurrentHashMap`s, an in-process
  list). Fine for a scaffold/exercise; a restart loses all delay-stage history and every alert
  ever fired. A real version needs a database, or at minimum for delay-stage-service to persist
  its map and for alertbot's history to survive a restart.
- **Shared code.** `Hub`, `PackageStatusMessage`, and `MqConfig` are hand-duplicated across
  services because there's no parent pom, per the brief's stated constraint. If that constraint
  were lifted, a small shared library (even just the model classes) would remove real drift risk —
  right now nothing enforces that the duplicated `Hub` record in `hub-service` and
  `transit-service` stay in sync if one changes.
- **MQ resilience.** Both the producer and consumers connect once at startup and retry lazily
  (producer) or not at all (consumers — a dropped broker connection isn't detected or
  reconnected). A production version needs a proper reconnect/backoff strategy, and probably a
  durable subscription so a consumer that's briefly down doesn't just miss messages.
- **Ingestion timestamp per row**, to make the `active` dedup conflict-resolution rule
  evidence-based (last-write-wins) instead of assumption-based (true-wins). This is the single
  change I'd make first if the exercise allowed the CSV format itself to change.
- **Tests beyond ingestion-service.** `HubCsvCleaner` has the one genuinely complex piece of logic
  in this repo and is covered; the REST/MQ handlers in the other four services are thin enough
  that I prioritized end-to-end manual verification over unit tests for them, but a real project
  would want at least integration tests around the ETA formula and the delay-stage validation
  logic.
