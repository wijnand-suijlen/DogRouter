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

| # | Screen | Primary use | Entry point |
|---|---|---|---|
| 1 | **Today** | View and fine-tune today's plan (or pick another day). | Default landing screen. |
| 2 | **Follow plan** | Cycling mode — current stop big, next stops listed, tick off as you go. | "Start trip" from Today. |
| 3 | **Week** | Read-only weekly grid: which dogs on which day. | Bottom tab. |
| 4 | **Dogs** | List + add/edit. Each dog bundles owner, address, quirks, schedule, weight, etc. | Bottom tab. |
| 5 | **History** | Past completed days, filterable by dog. Enough detail to support external invoicing. | Bottom tab. |
| 6 | **Settings** | Planning parameters + app prefs + data backup/import. | Bottom tab or overflow. |

## Per-screen detail

### 1. Today
- Date picker at top (default: today).
- Stops in proposed order, grouped by trip if more than one round is needed.
- Per stop: dog name, address, expected arrival, any quirks ("ring bell,
  wait ~3 min"), planner-estimated duration.
- Inline actions: reorder stops, move a dog between trips, override a leg's
  estimated duration, mark a stop skipped, add a temporary obstacle
  ("X-street closed today" — applies only to this day's plan).
- "Start trip" button → enters Follow plan.

### 2. Follow plan
- Full-screen, large text, designed to glance at while cycling.
- Current stop dominates: dog name + photo, address, quirks, ETA.
- Next 1–2 stops listed smaller below.
- Single tap: "done at this stop" → advances.
- Exit returns to Today (suspended state — resumable).

### 3. Week
- 7-column grid (Mon–Sun) × dogs as rows (or trips as rows — to be decided
  during build).
- Read-only overview. Tap a cell → opens that day in Today.

### 4. Dogs
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

### 5. History
- List of completed days, newest first.
- Per row: date, number of trips, number of dogs, total elapsed time.
- Tap a day → details: which dogs, in which order, start/finish times.
- Filters: by dog, by date range. Enough to count walks per client when
  preparing invoices in an external tool.

### 6. Settings
- **Planning parameters:** average cycling speed (km/h), cargo-bike weight
  capacity (kg, default 70), default per-stop time buffer (min).
- **App preferences:** theme, language.
- **Data:** export to file, import from file.

## Navigation sitemap

```
                            Bottom Navigation
   ┌──────────┬──────────┬──────────┬──────────┬──────────┐
   │  Today   │   Week   │   Dogs   │ History  │ Settings │
   └────┬─────┴────┬─────┴────┬─────┴────┬─────┴────┬─────┘
        │          │          │          │          │
        │          │          │          │          ├── Planning params
        │          │          │          │          ├── App preferences
        │          │          │          │          └── Data backup / import
        │          │          │          │
        │          │          │          └── Day detail (read-only)
        │          │          │
        │          │          ├── Dog detail / edit
        │          │          └── New dog
        │          │
        │          └── (tap cell → opens Today on that date)
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

Five top-level destinations sit comfortably in Material 3's bottom
navigation (max recommended is five). A drawer adds a tap and hides the
inventory.

### Why is Follow plan a separate screen from Today?

Different modes, different ergonomics. Today is for planning — many fields,
small text, edits. Follow plan is for execution on a bike — big text,
minimal taps, glanceable. Forcing one screen to do both compromises both.
