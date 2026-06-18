# DogRouter — Scope (v1)

> Vertaling van [`SCOPE.md`](SCOPE.md). De Engelse versie is de canonieke
> bron; bij verschillen geldt die.

Dit document legt vast wat versie 1 van DogRouter wel en niet doet. Het is
een werkafspraak, geen contract — onderdelen mogen verschuiven tussen *in
scope* en *buiten scope* zolang dat expliciet gebeurt.

## Doel

Een persoonlijk Android-hulpmiddel waarmee de uitlater elke werkdag plant:
welke honden mee, in welke volgorde, gegroepeerd in één of meer
bakfiets-ritten, met respect voor capaciteit, tijdvensters en
honden-incompatibiliteiten.

## Gebruikers

Eén gebruiker: de uitlater (de eigenaar van de app). Geen accounts, geen
multi-user, geen onboarding. De uitlater is ook de beheerder en de enige die
data bewerkt.

## In scope (v1)

- **Adresboek van klanten en honden.** Een klant heeft één of meer honden;
  een hond hoort bij één klant en heeft een gewicht, optionele
  incompatibiliteiten met andere honden, en een standaard ophaal-/breng-adres.
- **Dagplan.** Voor een gekozen datum aanvinken welke honden mee gaan. De
  app groepeert ze in één of meer *ritten* (een rit is één ronde met de
  bakfiets) en stelt een volgorde van stops per rit voor.
- **Capaciteit-bewuste groepering.** Ritten respecteren de gewichtsgrens van
  de bakfiets (~70 kg, instelbaar). Twee grote honden passen misschien,
  drie kleine ook; de app beslist op basis van het gewicht per hond.
- **Tijdvensters.** Per klant (of per hond) kan de uitlater de vroegste
  ophaal- en laatste breng-tijd opgeven. De planner weigert of waarschuwt
  bij niet-haalbare plannen.
- **Honden-incompatibiliteiten.** Hondparen die als onverenigbaar gemarkeerd
  zijn, komen nooit in dezelfde rit.
- **Stop-eigenaardigheden als eerste-klas data.** Per stop kan de uitlater
  notities vastleggen die de timing of route beïnvloeden — bv. "aanbellen,
  ~3 min wachten", "doorsteekje via X alleen ma–vr 09:00–17:00". Deze
  verschijnen op het dagplan-scherm en tellen mee in de tijdschatting.
- **Afstand-/duur-schattingen via routeplanner.** OSM-gebaseerde routing
  (OSRM of GraphHopper, fietsprofiel) levert afstanden en tijden per
  leg zodat de planner opties kan vergelijken. Resultaten zijn schattingen
   — de uitlater kent de buurt beter dan de kaart.
- **Handmatig overschrijven van het voorgestelde plan.** De uitlater kan
  stops verplaatsen, honden tussen ritten schuiven, een specifieke volgorde
  vastpinnen, of de geschatte duur van een leg overschrijven om bekende
  eigenaardigheden te verwerken. Handmatige aanpassingen blijven staan.
- **Kaartweergave van stops en ritten.** Visueel overzicht van de dag op een
  kaart. Geen turn-by-turn-navigatie; de uitlater kent de weg.
- **Offline werken.** Alle data staat in een lokale database op het toestel.
  De app werkt zonder internet — routing-schattingen vallen offline terug op
  gecachte resultaten of hemelsbreed-afstand.
- **Backup via export/import.** Handmatig exporteren van de database naar
  een bestand (en terug-importeren) zodat de uitlater na een telefoonwissel
  of -verlies kan herstellen.
- **Geschiedenis van voltooide dagen.** Elk dagplan dat daadwerkelijk
  uitgevoerd wordt, laat een blijvend record achter: datum, welke honden
  mee waren, in welke volgorde, wanneer de uitlater begon en eindigde. Een
  Geschiedenis-scherm ontsluit dit zodat de uitlater eerdere dagen kan
  terugzien en wandelingen per hond/klant kan optellen voor *externe*
  facturatie. Het record is op boekhouding-niveau, geen logboek — zie de
  buiten-scope-lijst voor wat bewust níet wordt vastgelegd.

## Buiten scope (v1)

- Meerdere uitlaters, teams, gedeelde schema's.
- Accounts, authenticatie, cloud-sync, een backend-server.
- Turn-by-turn-navigatie, gesproken instructies, rijbaan-aanwijzingen.
- Klantgerichte functies: eigenaar-portaal, push-notificaties naar
  eigenaren, aankomsttijden delen met klanten.
- Facturering, declaraties, betalingen, urenregistratie.
- Foto-logs, wandelverslagen, GPS-tracks van afgeronde wandelingen.
- Terugkerende-schema-sjablonen (bv. "elke ma/wo/vr deze vijf honden"). Kan
  in v2 komen als de handmatige flow te omslachtig blijkt.
- Integratie met agenda-apps, contacten of externe diensten.
- Wear OS / smartwatch-app.

## Domein-constraints

- **Bakfiets-capaciteit:** op basis van gewicht, standaard ~70 kg, instelbaar
  in instellingen. Niet een aantal honden.
- **Hondgewicht** is een verplicht veld op elke hond.
- **Incompatibiliteiten** zijn symmetrische paren van honden; de planner
  groepeert ze nooit in dezelfde rit.
- **Tijdvensters** zijn per klant (standaard) met optionele override per hond.
- **Stop-eigenaardigheden** zijn vrije tekst plus een optionele vaste
  tijdscorrectie in minuten (positief of negatief) op de leg die aan die
  stop aankomt.

## Open vragen

Deze hoeven niet beantwoord vóór de bouw begint, maar moeten beslist zijn
voordat de bijbehorende feature gebouwd wordt:

- **Routing-backend:** publieke OSRM/GraphHopper demo-endpoint, een betaalde
  hosted dienst, of zelf-gehost op een kleine VPS? Beïnvloedt offline-gedrag
  en kosten.
- **Routing-client:** native OSRM Android-binding, GraphHopper Android, of
  gewoon HTTP-calls naar een routing-server?
- **Kaart-rendering:** osmdroid, MapLibre of Mapbox SDK (met OSM-tiles)?
- **Optimalisatie-algoritme:** brute-force voor kleine ritten (≤ 8 stops),
  heuristiek voor grotere? Of alleen nearest-neighbor met handmatige
  herordening?
- **Android min/target SDK** — nog niet gekozen.
- **Terugkerende schema's** — uitstellen naar v2 of nu meenemen als het
  weinig werk is?
