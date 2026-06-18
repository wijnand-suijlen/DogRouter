# DogRouter

Android app for a professional dog walker who works with a cargo bike
(*bakfiets*), used to plan each working day and the cycling routes between
pickup/drop-off addresses.

## Status

Greenfield. No code yet — the workspace is being set up. Structural decisions
(module layout, persistence, navigation graph, dependency injection) are still
open and should be proposed before being committed.

## Tech stack

- **Language / UI:** Kotlin + Jetpack Compose
- **Maps & routing:** OpenStreetMap data, with OSRM or GraphHopper for route
  computation. Specific client library and whether routing runs against a
  public endpoint or a self-hosted server is still to be decided.
- **Target:** Android (minSdk / targetSdk to be chosen at project init)

Avoid introducing additional frameworks, vendors, or paid APIs without first
checking with the user — in particular, do not pull in Google Maps, Firebase,
or other proprietary SDKs.

## Workspace boundary

This directory — `/Users/wijnand/Documents/src/DogRouter` — is the entire
workspace. Do **not** read, write, create, or delete anything outside it. This
applies to every tool (Read, Write, Edit, Bash, etc.). If a task seems to
require touching files outside this directory, stop and ask the user first.

This rule is also enforced via `.claude/settings.local.json` permissions.

## Language conventions

- **Conversation with the user:** Dutch. The user writes in Dutch; reply in
  Dutch unless they switch.
- **Source code, identifiers, code comments, commit messages, and primary
  documentation:** English.
- **User-facing documentation** (README and similar) ships in both English and
  Dutch. The English version is canonical; the Dutch translation lives
  alongside it with a `.nl.md` suffix (e.g. `README.md` + `README.nl.md`).
- This file (`CLAUDE.md`) stays English-only — it's instructions for the
  assistant, not user-facing documentation.

## Domain vocabulary

When discussing the problem space, prefer these terms in code and docs:

- *walker* — the dog walker / business operator (the app's user)
- *dog* — an individual dog being walked
- *client* — the dog's owner (billing / contact party)
- *stop* — a pickup or drop-off at a specific address
- *route* — an ordered sequence of stops for a single trip
- *day plan* — the full schedule for one working day, may contain multiple
  routes
- *cargo bike* / *bakfiets* — the vehicle; routing must use cycling profiles,
  not car or pedestrian
