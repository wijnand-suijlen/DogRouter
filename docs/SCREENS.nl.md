# Schermen (v1)

Scherm-inventaris en navigatie voor DogRouter v1. Afgeleid van
[`SCOPE.md`](../SCOPE.md). Werkafspraak, geen contract — schermen verschuiven
naarmate we tijdens het bouwen leren.

> Dit is de Nederlandse vertaling van [`SCREENS.md`](SCREENS.md). De Engelse
> versie is leidend.

## Ontwerpbeperkingen

- Eén gebruiker (de uitlaatster), kleine dataset (≤ 20 klanten, meestal één hond).
- De app moet bruikbaar zijn op een rijdende bakfiets (handschoenen, zonlicht).
  Het uitvoerscherm zet "huidige stop / volgende stops" voorop.
- Offline-first volgens `SCOPE.md`.
- **Eigenaren zijn een eigen entiteit.** Facturatie telt gedane wandelingen per
  eigenaar op, dus elke `Dog` verwijst naar een `Owner` (naam, factuuradres,
  telefoon, e-mail). (Vroeg in v1 zaten de eigenaargegevens op de hond; dat
  promoveren was een kleine migratie — zie [Ontwerpkeuzes](#ontwerpkeuzes).)

## Scherm-inventaris

Statuslegenda: **Gebouwd** = werkt in de app · **Stub** = in navigatie maar
placeholder · **Gepland** = nog niet begonnen.

| # | Scherm | Hoofdgebruik | Ingang | Status |
|---|---|---|---|---|
| 1 | **Today** | Dagplan bekijken, handmatig bewerken (drag & drop) en committen. | Startscherm. | Gebouwd |
| 2 | **Follow plan** | Fietsmodus — huidige stop groot, volgende eronder, afvinken. | "Start trip" vanuit Today (volledig scherm). | Gebouwd (foto + hervatten volgt) |
| 3 | **Dogs** | Lijst + toevoegen/bewerken; elke hond koppelt aan een eigenaar. | Onderste tab. | Gebouwd |
| 3b | **Owners** | Gedeelde eigenarenlijst (naam, factuuradres, telefoon, e-mail, werkgever/test-vlaggen). | Personen-icoon op Dogs, of "Add owner" bij een hond. | Gebouwd |
| 4 | **Billing** | Lopende rekeningen per eigenaar; facturen, betalingen, URSSAF-export. | Onderste tab (vervangt de oude History-tab). | Gebouwd |
| 5 | **Settings** | Planningsparameters, uitgever-profiel, backup/import + URSSAF-export. | Onderste tab. | Gebouwd |

De onderbalk heeft vier tabs: **Today · Dogs · Billing · Settings.**

## Per-scherm detail

### 1. Today *(Gebouwd)*
- Datumkiezer bovenaan met vorige/volgende/vandaag (standaard: vandaag).
- Leesmodus: een PDPTW-tijdlijn (thuis-start, pickups, wandelingen, dropoffs,
  thuis-eind) met reis-legs, wachtrijen, een samenvattingskaart en een
  conflictpaneel.
- Elke fiets/voet-leg heeft een kaart-icoon; tik voor een volledig stratenkaartje.
- **Bewerkmodus** (potlood): het plan splitst in versleepbare **chips** waarbij
  *positie = uitvoeringsvolgorde*. Long-press de sleep-handle om te herordenen
  (pickup ≤ walk ≤ dropoff afgedwongen); een leg-chip togglet voet/fiets; tik een
  walk voor de duur, een pickup voor de starttijd; een walk split/merge; een
  pickup zet de hond op niet-vandaag; de FAB voegt een walk of afspraak toe.
  Hertimet na elke wijziging (onmogelijke plannen worden rood getoond en
  gewaarschuwd); undo; Done finaliseert.
- **Commit** (bonnetje-icoon): zet na bevestiging de wandelingen van de dag tegen
  de huidige prijzen op de lopende rekeningen. Een gecommitte dag toont een
  vinkje en kan niet dubbel.
- "Start trip" → Follow plan.

### 2. Follow plan *(Gebouwd — foto + hervatten volgt)*
- Volledig scherm, grote tekst, te overzien tijdens het fietsen; verbergt de
  onderbalk.
- Huidige stop domineert (ETA, titel, adres, telefoon eigenaar, bijzonderheden).
  Volgende 1–2 stops eronder. Eén grote "Done — next stop"; "Back" corrigeert.
- Inline leg-kaart; tik voor volledig scherm. Afsluiten gaat terug naar Today.

### 3. Dogs *(Gebouwd)*
- Lijst met naam, eigenaar, gewicht; pauzeer/hervat-schakelaar.
- Tik → hond bewerken: foto, naam, ras, gewicht; **eigenaar-dropdown + "Add
  owner"**; ophaal/afzet-adres (+ bijzonderheden, tijdcorrectie); transportstatus
  (bak / rugzak: ja/nee/niet getest); incompatibiliteiten; weekschema met **per
  uitlaatregel een bewerkbare prijs** (standaardtarief voorgevuld); notities.

### 3b. Owners *(Gebouwd)*
- Bereikbaar via de Dogs-lijst (personen-icoon) of "Add owner" op een hondformulier.
- Eigarenlijst met werkgever/test-chips. Toevoegen/bewerken: voor/achternaam,
  factuuradres, telefoon, e-mail, schakelaars **Employer** (employeur particulier
  — alleen maanduren tellen, geen facturen) en **Test** (buiten de URSSAF-omzet,
  facturen met watermerk). Eigenaren worden niet verwijderd zodra ze diensten
  hebben.

### 4. Billing *(Gebouwd — vervangt History)*
- **Overzicht:** eigenaren met hun openstaand saldo; werkgever-eigenaren tonen in
  plaats daarvan de uren deze maand. Een "committed days"-ingang (bovenbalk) toont
  elke gecommitte dag; tik erop voor het volledige plan zoals het gecommit was
  (een momentopname).
- **Eigenaar-rekening:** saldo (of uren-per-maand voor werkgevers); de
  dienstenlijst (betaald/onbetaald-badges); handmatig item toevoegen; onbetaalde
  dienst verwijderen; de TEST-status read-only. Vink onbetaalde diensten →
  **Invoice** (proef-factuur) of **Register payment** (facture acquittée — markeert
  betaald, verlaagt saldo). Een betaalde dienst biedt **Correct** → de avoir-wizard.
- **Facturen** (per eigenaar): elke facture / acquittée / avoir met een deel-actie
  die de PDF opnieuw rendert uit de bevroren momentopname (werkt ook na herstel).
- **Avoir-wizard:** een 3-staps tutorial om een negatieve correctie (facture
  d'avoir) te maken voor een al-betaalde dienst.
- Facturen zijn Franse micro-entrepreneur (BNC, niet-TVA) PDF's via het ingebouwde
  `PdfDocument`; test-eigenaren krijgen een aparte `TEST-`-nummerreeks + watermerk;
  delen via de systeem-deel-sheet (e-mail/print).

### 5. Settings *(Gebouwd)*
- **Planningsparameters:** fiets/loopsnelheid, capaciteit, buffers, gewichten,
  LNS-iteraties; thuisbasis; pauzes & afspraken.
- **Uitgever (Invoice issuer):** je naam (incl. EI), adres, SIRET, e-mail,
  telefoon, factuurnummer-prefix, bewerkbare Franse wettelijke vermeldingen.
  Alleen lokaal opgeslagen.
- **Data:** volledige backup exporteren/importeren (JSON).
- **URSSAF:** een `.zip` met `wandelingen.csv` + `ontvangsten.csv` (test-eigenaren
  uitgesloten, kwartaal-kolom) en een volledige `backup.json`.

## Navigatie-sitemap

```
                       Onderste navigatie
   ┌──────────┬──────────┬──────────┬──────────┐
   │  Today   │   Dogs   │ Billing  │ Settings │
   └────┬─────┴────┬─────┴────┬─────┴────┬─────┘
        │          │          │          ├── Planningsparameters
        │          │          │          ├── Uitgever-profiel
        │          │          │          └── Backup/import · URSSAF-export
        │          │          │
        │          │          ├── Eigenaar-rekening ── Facturen ── (delen/her-renderen)
        │          │          │                    └── Correct → avoir-wizard
        │          │          └── Committed days ── Gecommitte dag (plan-momentopname)
        │          │
        │          ├── Hond bewerken ── Add owner
        │          ├── Nieuwe hond
        │          └── Owners (lijst) ── Eigenaar bewerken
        │
        └── Start trip → Follow plan (volledig-scherm bestemming)
```

## Ontwerpkeuzes

### Eigenaren gepromoveerd van losse velden naar eigen entiteit

Vroeg in v1 zaten naam/telefoon van de eigenaar op de `Dog` om bewerk-overhead te
sparen bij ~20 klanten. Facturatie veranderde dat: lopende rekeningen, facturen en
de tweede-hond-korting zijn allemaal *per eigenaar*, dus een `Owner`-entiteit
verdient z'n plek. De migratie seedt één eigenaar per bestaande eigenaarsnaam en
koppelt de honden; de oude `ownerName`/`ownerPhone`-kolommen blijven als cache.

### Waarom onderste tabs en geen drawer?

De hoofdbestemmingen (Today, Dogs, Billing, Settings) passen prima in Material 3's
onderste navigatie. Een drawer kost een tik extra en verbergt de inventaris.

### Waarom is Follow plan een apart scherm van Today?

Andere modus, andere ergonomie. Today is voor plannen en bewerken — veel velden,
kleine tekst. Follow plan is voor uitvoering op de fiets — grote tekst, weinig
taps, in één oogopslag. Eén scherm voor beide doet beide tekort.

## Overwogen, niet in v1

### Week (alleen-lezen weekraster)

Een 7-koloms raster van welke honden op welke dag komen, als eigen tab. Uit v1
geschrapt: het toont puur afgeleide data (het weekschema per hond), de "tik een
dag"-navigatie zit al in de datumkiezer van Today, en een werkdag vraagt
uitvoering (Follow plan), geen alleen-lezen overzicht. Komt misschien terug.
