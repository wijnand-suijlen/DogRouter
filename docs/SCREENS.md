# Screens (v1)

Screen inventory and navigation for DogRouter v1. Derived from
[`SCOPE.md`](../SCOPE.md). Working agreement, not a contract — screens may
move as we learn during build.

> Dutch translation: [`SCREENS.nl.md`](SCREENS.nl.md). English is canonical.

## Design constraints

- Single user (the walker), small dataset (≤ 20 clients, mostly one dog each).
- Most clients have exactly one dog, rarely move, and have a fixed weekly
  schedule. The data model is collapsed accordingly: **one `Dog` entity
  bundles owner info, address, and stop quirks.** Editing the rarely-changing
  fields in place is acceptable at this scale.
- The app must be usable on a moving cargo bike (gloves, sun glare). The
  execution screen prioritises a clear "current stop / next stops" view.
- Offline-first per `SCOPE.md`.

## Screen inventory

Status legend: **Built** = working in the app · **Stub** = wired in nav but
placeholder · **Planned** = not yet started.

| # | Screen | Primary use | Entry point | Status |
|---|---|---|---|---|
| 1 | **Today** | View and fine-tune today's plan (or pick another day). | Default landing screen. | Built (read-only timeline; fine-tune actions planned) |
| 2 | **Follow plan** | Cycling mode — current stop big, next stops listed, tick off as you go. | "Start trip" from Today (full-screen). | Built (photo + resume-on-exit to come) |
| 3 | **Dogs** | List + add/edit. Each dog bundles owner, address, quirks, schedule, weight, etc. | Bottom tab. | Built |
| 4 | **History** | Past completed days, filterable by dog. Enough detail to support external invoicing. | Bottom tab. | Stub |
| 5 | **Settings** | Planning parameters + app prefs + data backup/import. | Bottom tab or overflow. | Built |

**Week** (a read-only weekly grid) was previously listed as a v1 screen and a
bottom tab. It has been dropped from v1 — see
[Considered, not in v1](#considered-not-in-v1) — and removed from the
navigation code, so the bottom bar now has four tabs.

## Per-screen detail

### 1. Today *(Built — fine-tune actions still planned)*
- Date picker at top, with prev/next/today controls (default: today).
- Stops in proposed order, grouped by trip if more than one round is needed.
  *Today this is a PDPTW event timeline (home-start, pickups, walks, drop-offs,
  home-end) with a summary card and a conflict panel for unschedulable walks.*
- Per stop: dog name, address, expected arrival, any quirks ("ring bell,
  wait ~3 min"), planner-estimated duration.
- Inline actions *(planned, not built)*: reorder stops, move a dog between
  trips, override a leg's estimated duration, mark a stop skipped, add a
  temporary obstacle ("X-street closed today" — applies only to this day's
  plan).
- "Start trip" button → enters Follow plan *(planned)*.

### 2. Follow plan *(Built — photo + resume-on-exit to come)*
- Full-screen, large text, designed to glance at while cycling; hides the
  bottom bar.
- Current stop dominates: big ETA, title (e.g. "Pickup Rex"), address,
  owner phone, and quirks in a highlighted note. Dog photo is not shown
  yet (no image loader in the project).
- Next 1–2 stops listed smaller below.
- Single tap on the large "Done — next stop" button advances; "Back"
  corrects a mis-tap. A progress bar and "Stop n of N" show position; a
  "Trip complete" state ends the run.
- Exit returns to Today. Progress currently survives rotation but not
  leaving the screen — a resumable suspended trip is a follow-up.

### 3. Dogs *(Built)*
- List with photo, name, owner; search/filter.
- Tap → Dog detail / edit:
  - Photo, name, breed, weight (kg).
  - Owner name + phone.
  - Pickup/drop-off address + stop quirks (free text + optional fixed time
    adjustment).
  - **Transport state:** *cargo bike* and *backpack*, each one of
    *yes / no / not yet tested*. Two independent fields; new dogs default to
    "not yet tested" for both.
  - Incompatibilities: pick from list of other dogs (symmetric).
  - Weekly schedule: per weekday, on/off + optional time window.
  - Notes (free text).

### 4. History *(Stub)*
- List of completed days, newest first.
- Per row: date, number of trips, number of dogs, total elapsed time.
- Tap a day → details: which dogs, in which order, start/finish times.
- Filters: by dog, by date range. Enough to count walks per client when
  preparing invoices in an external tool.

### 5. Settings *(Built)*
- **Planning parameters:** average cycling speed (km/h), cargo-bike weight
  capacity (kg, default 70), default per-stop time buffer (min).
- **App preferences:** theme, language.
- **Data:** export to file, import from file.

## Navigation sitemap

```
                       Bottom Navigation
   ┌──────────┬──────────┬──────────┬──────────┐
   │  Today   │   Dogs   │ History  │ Settings │
   └────┬─────┴────┬─────┴────┬─────┴────┬─────┘
        │          │          │          │
        │          │          │          ├── Planning params
        │          │          │          ├── App preferences
        │          │          │          └── Data backup / import
        │          │          │
        │          │          └── Day detail (read-only)
        │          │
        │          ├── Dog detail / edit
        │          └── New dog
        │
        └── Start trip → Follow plan (full-screen destination)
```

## Design rationale

### Why one `Dog` entity instead of separate Client / Address / Dog?

At this scale — ~20 clients, mostly one dog each, rarely moving — separating
owner and address into their own entities adds editing overhead in 95% of
cases for theoretical benefit in 5%. If a client genuinely has two dogs the
owner fields are entered twice; mildly annoying maybe once a year. If a
client moves, the address is edited once per dog (probably one dog).

Accepted trade-off: small data duplication in the rare multi-dog case, and a
small migration if we later promote `Client` to a first-class entity. On
this scale the migration is small.

### Why bottom tabs and not a drawer?

The top-level destinations (Today, Dogs, History, Settings) sit comfortably
in Material 3's bottom navigation (max recommended is five). A drawer adds a
tap and hides the inventory.

## Considered, not in v1

### Week (read-only weekly grid)

A 7-column (Mon–Sun) grid of which dogs come on which day, reachable as its
own bottom tab. Dropped from v1 because:

- It shows **purely derived data**: which dog on which day comes straight
  from each dog's weekly schedule rules, already editable under Dogs. The grid
  is a visualisation, not its own source of truth.
- Its only navigation value — "tap a cell to open that day" — is already
  covered by Today's date picker (prev/next/today).
- It earns no place on a working day, where the real need is execution
  (Follow plan), not a read-only weekly overview.

May return later as a nice-to-have for spotting overloaded days or onboarding
a new client, but it is not worth a tab in v1.

### Why is Follow plan a separate screen from Today?

Different modes, different ergonomics. Today is for planning — many fields,
small text, edits. Follow plan is for execution on a bike — big text,
minimal taps, glanceable. Forcing one screen to do both compromises both.
