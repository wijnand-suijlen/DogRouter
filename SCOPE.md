# DogRouter — Scope (v1)

This document defines what version 1 of DogRouter does and does not do. It's
a working agreement, not a contract — entries can move between *in scope* and
*out of scope* as we learn, but changes should be explicit.

## Purpose

A personal Android tool that helps the walker plan each working day:
which dogs to pick up, in which order, grouped into one or more cargo-bike
trips, while respecting capacity, time windows, and dog-pair restrictions.

## Users

Single user: the walker (the app's owner). No accounts, no multi-user, no
onboarding flow. The walker is also the administrator and the only person who
edits data.

## In scope (v1)

- **Address book of clients and dogs.** A client has one or more dogs; a dog
  belongs to one client and has a weight, optional incompatibilities with
  other dogs, and a default pickup/drop-off address.
- **Day plan.** For a given date, select which dogs go out. The app groups
  them into one or more *trips* (each trip is one round on the cargo bike)
  and proposes an order of stops per trip.
- **Capacity-aware grouping.** Trips respect the cargo bike's weight limit
  (~70 kg, configurable). Two large dogs may fit, three small ones may fit;
  the app decides based on per-dog weight.
- **Time-window constraints.** Per client (or per dog) the walker can specify
  earliest pickup and latest drop-off times. The planner refuses or warns on
  infeasible plans.
- **Dog-pair restrictions.** Pairs of dogs marked incompatible are never
  placed in the same trip.
- **Stop quirks as first-class data.** Per stop the walker can record
  notes that affect timing or routing — e.g. "ring the bell, wait ~3 min", "shortcut via X only Mon–Fri 09:00–17:00". These appear on the day-plan
  screen and feed the time estimate.
- **Distance / duration estimates via routing engine.** OSM-based routing
  (OSRM or GraphHopper, cycling profile) provides leg distances and times so
  the planner can compare options. Results are estimates — the walker is
  expected to know the area better than the map.
- **Manual override of the proposed plan.** The walker can reorder stops,
  move dogs between trips, pin a specific order, or override a leg's
  estimated duration to reflect known quirks. Manual edits stick.
- **Map view of stops and trips.** Visual overview of the day on a map. No
  turn-by-turn navigation; the walker knows the way.
- **Offline operation.** All data lives in a local database on the device.
  The app is usable without internet — routing estimates fall back to cached
  results or straight-line distance when offline.
- **Backup via export/import.** Manual export of the database to a file (and
  re-import) so the walker can recover after a phone change or loss.
- **Completed-day history.** Each day plan that is actually executed leaves
  a durable record: date, which dogs went out, in which order, when the
  walker started and finished. A History screen surfaces this so the
  walker can review past work and tally walks per dog/client for *external*
  invoicing. The record is bookkeeping-grade, not journaling — see the
  out-of-scope list for what is deliberately not captured.

## Out of scope (v1)

- Multiple walkers, teams, shared schedules.
- User accounts, authentication, cloud sync, a backend server.
- Turn-by-turn navigation, voice guidance, lane assistance.
- Client-facing features: owner portal, push notifications to owners,
  arrival ETAs sent to clients.
- Billing, invoicing, payments, time tracking for payroll.
- Photo logs, walk reports, GPS tracks of completed walks.
- Recurring-schedule templates (e.g. "every Mon/Wed these five dogs"). May
  arrive in v2 if the manual flow proves tedious.
- Integration with calendar apps, contacts, or third-party services.
- Wear OS / smartwatch companion.

## Domain constraints

- **Cargo bike capacity:** weight-based, default ~70 kg, adjustable in
  settings. Not a count of dogs.
- **Dog weight** is a required field on every dog.
- **Incompatibilities** are symmetric pairs of dogs; the planner never groups
  them into the same trip.
- **Time windows** are per client (default) with optional per-dog overrides.
- **Stop quirks** are free-text notes plus an optional fixed time adjustment
  in minutes (positive or negative) applied to the leg arriving at that stop.

## Open questions

These do not need to be answered before coding starts but should be resolved
before the relevant feature is built:

- **Routing backend:** public OSRM/GraphHopper demo endpoint, a paid hosted
  service, or self-hosted on a small VPS? Affects offline behavior and cost.
- **Routing client library:** native OSRM Android binding, GraphHopper
  Android, or just HTTP calls to a routing server?
- **Map rendering:** osmdroid, MapLibre, or Mapbox SDK (with OSM tiles)?
- **Optimization algorithm:** brute-force for small trips (≤ 8 stops),
  heuristic for larger? Or just nearest-neighbor with manual reordering?
- **Android min/target SDK** — not yet chosen.
- **Recurring schedules** — defer to v2 or accept now if low-effort?
