# Schermen (v1)

Schermen-inventarisatie en navigatie voor DogRouter v1. Afgeleid van
[`SCOPE.nl.md`](../SCOPE.nl.md). Werkafspraak, geen contract — schermen
mogen verschuiven tijdens de bouw.

> Vertaling van [`SCREENS.md`](SCREENS.md). De Engelse versie is canoniek.

## Ontwerp-constraints

- Eén gebruiker (de uitlater), kleine dataset (≤ 20 klanten, vrijwel
  allemaal één hond).
- De meeste klanten hebben exact één hond, verhuizen zelden, en hebben een
  vast weekrooster. Het datamodel klapt daarop in: **één `Dog`-entity
  bundelt eigenaar-info, adres en stop-eigenaardigheden.** In-place bewerken
  van zelden-veranderende velden is op deze schaal prima.
- De app moet bruikbaar zijn op een rijdende bakfiets (handschoenen,
  felle zon). Het uitvoer-scherm prioriteert een heldere "huidige stop /
  volgende stops"-weergave.
- Offline-first volgens `SCOPE.nl.md`.

## Schermen-overzicht

Status-legenda: **Gebouwd** = werkt in de app · **Stub** = in de navigatie
gekoppeld maar placeholder · **Gepland** = nog niet begonnen.

| # | Scherm | Hoofdgebruik | Toegang | Status |
|---|---|---|---|---|
| 1 | **Vandaag** | Plan van vandaag bekijken en finetunen (of een andere dag kiezen). | Standaard openings-scherm. | Gebouwd (read-only timeline; finetune-acties gepland) |
| 2 | **Plan volgen** | Fietsmodus — huidige stop groot, volgende stops eronder, afvinken onderweg. | "Start rit" vanuit Vandaag (volledig scherm). | Stub (gekoppeld; uitvoer-UI te bouwen) |
| 3 | **Honden** | Lijst + add/edit. Elke hond bundelt eigenaar, adres, eigenaardigheden, rooster, gewicht, etc. | Onderste tab. | Gebouwd |
| 4 | **Geschiedenis** | Afgesloten dagen uit het verleden, filterbaar op hond. Genoeg detail om externe facturatie te ondersteunen. | Onderste tab. | Stub |
| 5 | **Instellingen** | Planning-parameters + app-voorkeuren + data-backup/import. | Onderste tab of overflow. | Gebouwd |

**Week** (een read-only week-raster) stond eerder als v1-scherm én onderste
tab. Het is uit v1 geschrapt — zie
[Overwogen, niet in v1](#overwogen-niet-in-v1) — en uit de navigatiecode
verwijderd, dus de onderste balk heeft nu vier tabs.

## Detail per scherm

### 1. Vandaag *(Gebouwd — finetune-acties nog gepland)*
- Datum-kiezer bovenaan, met vorige/volgende/vandaag-knoppen (standaard:
  vandaag).
- Stops in voorgestelde volgorde, gegroepeerd per rit als er meer dan één
  ronde nodig is. *Nu is dit een PDPTW-event-timeline (thuis-start, ophalen,
  wandelingen, brengen, thuis-eind) met een samenvattingskaart en een
  conflictpaneel voor onplanbare wandelingen.*
- Per stop: hondnaam, adres, verwachte aankomsttijd, eventuele
  eigenaardigheden ("aanbellen, ~3 min wachten"), planner-schatting van de
  duur.
- Inline acties *(gepland, niet gebouwd)*: stops herordenen, een hond naar
  een andere rit verplaatsen, geschatte duur van een leg overschrijven, een
  stop overslaan, een tijdelijke obstructie toevoegen ("X-straat vandaag
  dicht" — alleen voor het plan van vandaag).
- Knop "Start rit" → opent Plan volgen *(gepland)*.

### 2. Plan volgen *(Stub — uitvoer-UI te bouwen)*
- Nu al bereikbaar: een "Start rit"-knop op Vandaag opent een volledig-scherm
  placeholder die de onderste balk verbergt en terugkeert naar Vandaag. De
  glanceable uitvoer-indeling hieronder is het doel, nog niet gebouwd.
- Volledig scherm, grote tekst, ontworpen om in één blik tijdens het
  fietsen te lezen.
- Huidige stop domineert: hondnaam + foto, adres, eigenaardigheden,
  verwachte aankomsttijd.
- Volgende 1–2 stops kleiner eronder.
- Eén tik: "klaar bij deze stop" → ga naar volgende.
- Afsluiten gaat terug naar Vandaag (gesuspendeerde staat — hervatbaar).

### 3. Honden *(Gebouwd)*
- Lijst met foto, naam, eigenaar; zoek/filter.
- Tik → Hond-detail / bewerken:
  - Foto, naam, ras, gewicht (kg).
  - Eigenaar-naam + telefoonnummer.
  - Ophaal-/breng-adres + stop-eigenaardigheden (vrije tekst + optionele
    vaste tijdscorrectie).
  - **Transport-staat:** *bakfiets* en *rugzak*, elk een keuze uit
    *ja / nee / nog niet getest*. Twee onafhankelijke velden; nieuwe honden
    staan standaard op "nog niet getest" voor beide.
  - Incompatibiliteiten: kies uit lijst van andere honden (symmetrisch).
  - Weekrooster: per weekdag, aan/uit + optioneel tijdvenster.
  - Opmerkingen (vrije tekst).

### 4. Geschiedenis *(Stub)*
- Lijst van voltooide dagen, nieuwste eerst.
- Per rij: datum, aantal ritten, aantal honden, totale verstreken tijd.
- Tik op een dag → details: welke honden, in welke volgorde, start- en
  eindtijden.
- Filters: op hond, op datumbereik. Genoeg om wandelingen per klant te
  tellen wanneer je facturen maakt in een externe tool.

### 5. Instellingen *(Gebouwd)*
- **Planning-parameters:** gemiddelde fietssnelheid (km/u), bakfiets-
  gewichtscapaciteit (kg, standaard 70), standaard tijdmarge per stop (min).
- **App-voorkeuren:** thema, taal.
- **Data:** export naar bestand, import uit bestand.

## Navigatie-sitemap

```
                       Onderste navigatie
   ┌──────────┬──────────┬──────────┬──────────┐
   │ Vandaag  │  Honden  │ Geschied │Instelling│
   └────┬─────┴────┬─────┴────┬─────┴────┬─────┘
        │          │          │          │
        │          │          │          ├── Planning-parameters
        │          │          │          ├── App-voorkeuren
        │          │          │          └── Data backup / import
        │          │          │
        │          │          └── Dag-detail (read-only)
        │          │
        │          ├── Hond-detail / bewerken
        │          └── Nieuwe hond
        │
        └── Start rit → Plan volgen (volledig-scherm-bestemming)
```

## Ontwerp-rationale

### Waarom één `Dog`-entity in plaats van aparte Klant / Adres / Hond?

Op deze schaal — ~20 klanten, vrijwel allemaal één hond, zelden verhuizend
— voegt het scheiden van eigenaar en adres in eigen entiteiten in 95% van
de gevallen bewerk-overhead toe voor theoretisch voordeel in 5%. Als een
klant écht twee honden heeft, voer je de eigenaar-velden twee keer in;
mild irritant, misschien één keer per jaar. Als een klant verhuist, wordt
het adres één keer per hond aangepast (waarschijnlijk één hond).

Geaccepteerde trade-off: kleine data-duplicatie in het zeldzame
meerdere-honden-geval, plus een kleine migratie als we `Client` later toch
naar eerste-klas willen promoveren. Op deze schaal is die migratie klein.

### Waarom onderste tabs en geen drawer?

De top-level bestemmingen (Vandaag, Honden, Geschiedenis, Instellingen) passen
comfortabel in Material 3's onderste navigatie (aanbevolen max is vijf). Een
drawer voegt een extra tik toe en verbergt het overzicht.

## Overwogen, niet in v1

### Week (read-only week-raster)

Een 7-koloms raster (ma–zo) van welke honden op welke dag komen, bereikbaar
als eigen onderste tab. Geschrapt uit v1 omdat:

- Het toont **puur afgeleide data**: welke hond op welke dag komt rechtstreeks
  uit de weekrooster-regels per hond, die je al onder Honden bewerkt. Het
  raster is een visualisatie, geen eigen bron van waarheid.
- De enige navigatiewaarde — "tik op een cel om die dag te openen" — wordt al
  gedekt door de datum-kiezer van Vandaag (vorige/volgende/vandaag).
- Het verdient geen plek op een werkdag, waar de echte behoefte uitvoer is
  (Plan volgen), niet een read-only weekoverzicht.

Kan later terugkeren als nice-to-have om overvolle dagen te spotten of een
nieuwe klant in te werken, maar is in v1 geen tab waard.

### Waarom is Plan volgen een apart scherm van Vandaag?

Andere modi, andere ergonomie. Vandaag is voor plannen — veel velden,
kleine tekst, bewerkingen. Plan volgen is voor uitvoer op de fiets — grote
tekst, minimale tikken, glanceable. Eén scherm beide laten doen
compromitteert beide.
