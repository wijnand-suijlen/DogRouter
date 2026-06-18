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

| # | Scherm | Hoofdgebruik | Toegang |
|---|---|---|---|
| 1 | **Vandaag** | Plan van vandaag bekijken en finetunen (of een andere dag kiezen). | Standaard openings-scherm. |
| 2 | **Plan volgen** | Fietsmodus — huidige stop groot, volgende stops eronder, afvinken onderweg. | "Start rit" vanuit Vandaag. |
| 3 | **Week** | Read-only week-raster: welke honden op welke dag. | Onderste tab. |
| 4 | **Honden** | Lijst + add/edit. Elke hond bundelt eigenaar, adres, eigenaardigheden, rooster, gewicht, etc. | Onderste tab. |
| 5 | **Geschiedenis** | Afgesloten dagen uit het verleden, filterbaar op hond. Genoeg detail om externe facturatie te ondersteunen. | Onderste tab. |
| 6 | **Instellingen** | Planning-parameters + app-voorkeuren + data-backup/import. | Onderste tab of overflow. |

## Detail per scherm

### 1. Vandaag
- Datum-kiezer bovenaan (standaard: vandaag).
- Stops in voorgestelde volgorde, gegroepeerd per rit als er meer dan één
  ronde nodig is.
- Per stop: hondnaam, adres, verwachte aankomsttijd, eventuele
  eigenaardigheden ("aanbellen, ~3 min wachten"), planner-schatting van de
  duur.
- Inline acties: stops herordenen, een hond naar een andere rit verplaatsen,
  geschatte duur van een leg overschrijven, een stop overslaan, een
  tijdelijke obstructie toevoegen ("X-straat vandaag dicht" — alleen voor
  het plan van vandaag).
- Knop "Start rit" → opent Plan volgen.

### 2. Plan volgen
- Volledig scherm, grote tekst, ontworpen om in één blik tijdens het
  fietsen te lezen.
- Huidige stop domineert: hondnaam + foto, adres, eigenaardigheden,
  verwachte aankomsttijd.
- Volgende 1–2 stops kleiner eronder.
- Eén tik: "klaar bij deze stop" → ga naar volgende.
- Afsluiten gaat terug naar Vandaag (gesuspendeerde staat — hervatbaar).

### 3. Week
- 7-koloms raster (ma–zo) × honden als rijen (of ritten als rijen — beslis
  tijdens de bouw).
- Read-only overzicht. Tik op een cel → opent die dag in Vandaag.

### 4. Honden
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

### 5. Geschiedenis
- Lijst van voltooide dagen, nieuwste eerst.
- Per rij: datum, aantal ritten, aantal honden, totale verstreken tijd.
- Tik op een dag → details: welke honden, in welke volgorde, start- en
  eindtijden.
- Filters: op hond, op datumbereik. Genoeg om wandelingen per klant te
  tellen wanneer je facturen maakt in een externe tool.

### 6. Instellingen
- **Planning-parameters:** gemiddelde fietssnelheid (km/u), bakfiets-
  gewichtscapaciteit (kg, standaard 70), standaard tijdmarge per stop (min).
- **App-voorkeuren:** thema, taal.
- **Data:** export naar bestand, import uit bestand.

## Navigatie-sitemap

```
                            Onderste navigatie
   ┌──────────┬──────────┬──────────┬──────────┬──────────┐
   │ Vandaag  │   Week   │  Honden  │ Geschied │Instelling│
   └────┬─────┴────┬─────┴────┬─────┴────┬─────┴────┬─────┘
        │          │          │          │          │
        │          │          │          │          ├── Planning-parameters
        │          │          │          │          ├── App-voorkeuren
        │          │          │          │          └── Data backup / import
        │          │          │          │
        │          │          │          └── Dag-detail (read-only)
        │          │          │
        │          │          ├── Hond-detail / bewerken
        │          │          └── Nieuwe hond
        │          │
        │          └── (tik op cel → opent Vandaag op die datum)
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

Vijf top-level bestemmingen passen comfortabel in Material 3's onderste
navigatie (aanbevolen max is vijf). Een drawer voegt een extra tik toe en
verbergt het overzicht.

### Waarom is Plan volgen een apart scherm van Vandaag?

Andere modi, andere ergonomie. Vandaag is voor plannen — veel velden,
kleine tekst, bewerkingen. Plan volgen is voor uitvoer op de fiets — grote
tekst, minimale tikken, glanceable. Eén scherm beide laten doen
compromitteert beide.
