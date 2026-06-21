# Screens (v1)

Screen inventory and navigation for DogRouter v1. Derived from
[`SCOPE.md`](../SCOPE.md). Working agreement, not a contract — screens move as we
learn during build.

> Dutch translation: [`SCREENS.nl.md`](SCREENS.nl.md). English is canonical.

## Design constraints

- Single user (the walker), small dataset (≤ 20 clients, mostly one dog each).
- The app must be usable on a moving cargo bike (gloves, sun glare). The
  execution screen prioritises a clear "current stop / next stops" view.
- Offline-first per `SCOPE.md`.
- **Owners are a first-class entity.** Billing rolls up done walks per owner, so
  an `Owner` (name, billing address, phone, email) is referenced by each `Dog`.
  (Early v1 bundled owner info onto the dog; promoting it was a small migration —
  see [Design rationale](#design-rationale).)

## Screen inventory

Status legend: **Built** = working in the app · **Stub** = wired in nav but
placeholder · **Planned** = not yet started.

| # | Screen | Primary use | Entry point | Status |
|---|---|---|---|---|
| 1 | **Today** | View, hand-edit (drag & drop) and commit a day's plan. | Default landing screen. | Built |
| 2 | **Follow plan** | Cycling mode — current stop big, next stops listed, tick off as you go. | "Start trip" from Today (full-screen). | Built (photo + resume-on-exit to come) |
| 3 | **Dogs** | List + add/edit; each dog links to an owner. | Bottom tab. | Built |
| 3b | **Owners** | Shared list of owners (name, billing address, phone, email, employer/test flags). | Person icon on Dogs, or "Add owner" from a dog. | Built |
| 4 | **Billing** | Running accounts per owner; invoices, payments, URSSAF export. | Bottom tab (replaces the old History tab). | Built |
| 5 | **Settings** | Planning parameters, invoice issuer profile, data backup/import + URSSAF export. | Bottom tab. | Built |

The bottom bar has four tabs: **Today · Dogs · Billing · Settings.**

## Per-screen detail

### 1. Today *(Built)*
- Date picker at top, with prev/next/today controls (default: today).
- Read-only mode: a PDPTW event timeline (home-start, pickups, walks, drop-offs,
  home-end) with travel legs, wait rows, a summary card and a conflict panel.
- Each cycling/foot leg has a map icon; tap to open a full-screen street map of
  that leg.
- **Edit mode** (pencil): the plan explodes into draggable **chips** where
  *position = execution order*. Long-press the drag handle to reorder
  (pickup ≤ walk ≤ dropoff is enforced); a leg chip toggles foot/bike; tap a walk
  to set duration, a pickup to pin its start time; a walk splits/merges; a
  pickup drops the dog for the day; the FAB adds a walk or a forced appointment.
  Re-times after each change (impossible plans are shown red and warned); undo;
  Done finalises.
- **Commit** (receipt icon): adds the day's walks to the owners' running accounts
  at the current prices, after a confirmation. A committed day shows a check and
  can't be committed twice.
- "Start trip" → Follow plan.

### 2. Follow plan *(Built — photo + resume-on-exit to come)*
- Full-screen, large text, glanceable while cycling; hides the bottom bar.
- Current stop dominates (ETA, title, address, owner phone, quirks). Next 1–2
  stops below. Single big "Done — next stop"; "Back" corrects a mis-tap.
- Inline leg map; tap for full-screen. Exit returns to Today.

### 3. Dogs *(Built)*
- List with name, owner, weight; pause/resume toggle.
- Tap → dog edit: photo, name, breed, weight; **owner dropdown + "Add owner"**;
  pickup/drop-off address (+ quirks, time adjustment); transport state (cargo
  bike / backpack: yes/no/not tested); incompatibilities; weekly schedule with,
  **per walk rule, an editable price** (default tariff pre-filled); notes.

### 3b. Owners *(Built)*
- Reachable from the Dogs list (person icon) or "Add owner" on a dog form.
- List of owners with employer/test chips. Add/edit: first/last name, billing
  address, phone, email, **Employer** (employeur particulier — only monthly hours
  matter, no invoices) and **Test** (excluded from URSSAF turnover, invoices
  watermarked) switches. Owners aren't deleted once they have billed services.

### 4. Billing *(Built — replaces History)*
- **Overview:** owners with their outstanding balance; employer owners show this
  month's hours instead. A "committed days" entry (top bar) lists every committed
  day; tapping one shows the full plan as it was committed (a snapshot).
- **Owner account:** balance (or hours-per-month for employers); the service list
  (paid/unpaid badges); add a manual item; remove an unpaid service; the TEST
  status read-only. Tick unpaid services → **Invoice** (proof facture) or
  **Register payment** (facture acquittée — marks paid, lowers the balance). A
  paid service offers **Correct** → the credit-note (avoir) wizard.
- **Invoices** (per owner): every facture / acquittée / avoir with a share action
  that re-renders the PDF from its frozen snapshot (works after a restore too).
- **Credit-note wizard:** a 3-step tutorial to issue a negative correction
  (facture d'avoir) for an already-paid service.
- Invoices are French micro-entrepreneur (BNC, non-TVA) PDFs via the built-in
  `PdfDocument`; test owners use a separate `TEST-` number series + watermark;
  shared via the system share sheet (email/print).

### 5. Settings *(Built)*
- **Planning parameters:** cycling/walking speed, capacity, buffers, weights,
  LNS iterations; home base; breaks & appointments.
- **Invoice issuer:** your name (incl. EI), address, SIRET, email, phone, invoice
  number prefix, editable French legal mentions. Stored locally only.
- **Data:** export/import a full backup (JSON).
- **URSSAF:** export a `.zip` with `wandelingen.csv` + `ontvangsten.csv` (test
  owners excluded, quarter column) and a full `backup.json`.

## Navigation sitemap

```
                       Bottom Navigation
   ┌──────────┬──────────┬──────────┬──────────┐
   │  Today   │   Dogs   │ Billing  │ Settings │
   └────┬─────┴────┬─────┴────┬─────┴────┬─────┘
        │          │          │          ├── Planning params
        │          │          │          ├── Invoice issuer
        │          │          │          └── Data backup / import · URSSAF export
        │          │          │
        │          │          ├── Owner account ── Invoices ── (share/re-render)
        │          │          │                └── Correct → credit-note wizard
        │          │          └── Committed days ── Committed day (plan snapshot)
        │          │
        │          ├── Dog detail / edit ── Add owner
        │          ├── New dog
        │          └── Owners (list) ── Owner edit
        │
        └── Start trip → Follow plan (full-screen destination)
```

## Design rationale

### Owners promoted from bundled fields to a first-class entity

Early v1 bundled owner name/phone onto the `Dog` to save editing overhead at a
~20-client scale. Billing changed the calculus: running accounts, invoices and
the second-dog discount are all *per owner*, so an `Owner` entity earns its keep.
The migration seeds one owner per distinct existing owner name and links the
dogs; the legacy `ownerName`/`ownerPhone` columns stay as a denormalised cache.

### Why bottom tabs and not a drawer?

The top-level destinations (Today, Dogs, Billing, Settings) sit comfortably in
Material 3's bottom navigation. A drawer adds a tap and hides the inventory.

### Why is Follow plan a separate screen from Today?

Different modes, different ergonomics. Today is for planning and editing — many
fields, small text. Follow plan is for execution on a bike — big text, minimal
taps, glanceable. One screen doing both compromises both.

## Considered, not in v1

### Week (read-only weekly grid)

A 7-column grid of which dogs come on which day, as its own tab. Dropped from v1:
it shows purely derived data (each dog's weekly schedule), its "tap a day"
navigation is covered by Today's date picker, and a working day needs execution
(Follow plan), not a read-only overview. May return as a nice-to-have.
