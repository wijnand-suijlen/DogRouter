# Routing engine comparison

> Design rationale for picking the cycling-routing backend for DogRouter.
> Decision is not yet final — this document captures the trade-offs so we
> can choose with eyes open.

## Constraints

- **Profile:** cycling, with cargo-bike characteristics (avoid stairs and
  very narrow ways, slower than a road bike, weight on the bike).
- **Operating area:** Meudon (92) and neighbouring communes — south-western
  Paris suburbs. Île-de-France regional OSM extract is the relevant unit.
- **Use:** distance and travel-time *estimates* between stops, not
  turn-by-turn navigation. The walker knows the way.
- **Offline-first:** must work without internet on the day. Online use is
  acceptable for planning the day at home.
- **Volume:** one user, a few hundred routing requests per day at peak.
- **Budget:** personal/hobby — minimise recurring cost; no Google.
- **License:** prefer permissive open source so embedding in the APK or
  caching results stays legal.

## Candidates considered

### OSRM
- Cycling profile is basic; cargo-bike specifics need Lua edits.
- Public demo endpoint is explicitly non-commercial, ~1 req/s, no SLA.
- Self-hostable (BSD-2), Docker image available. Île-de-France extract is a
  small regional file (well under 1 GB PBF); RAM during preprocessing is
  the bigger concern.
- No realistic on-device option — C++ engine, no Android port.

### GraphHopper
- **Best cycling/cargo story.** Ships a `bike` profile and an `ecargobike`
  vehicle, plus a `cargo-bike.json` custom model (tweaks for stairs,
  narrow ways, sharp corners) that can be extended in JSON without
  recompiling.
- Apache-2 core (Community Edition). Some optimisation features are paid.
- Directions API free tier: 500 credits/day, non-commercial, max 5
  waypoints per request. Covers our volume if we don't lean on matrix
  calls.
- Self-host: Java, easy on a small VPS, Île-de-France graph fits in
  ~1–2 GB RAM, builds in minutes.
- **Uniquely viable on-device.** Pure-Java core runs as a library inside an
  Android app. APK impact ~10–20 MB for the engine; an Île-de-France graph
  (`.ghz`) is a few hundred MB at most — small enough to bundle or
  download on first launch.

### Valhalla
- Strong bicycle costing (`bicycle_type`, `use_roads`, `use_hills`,
  `avoid_bad_surfaces`) but no explicit cargo profile; per-request costing
  options compensate partially.
- Hosted free tier via Stadia Maps (fair-use, non-commercial). Mapbox
  exposes Valhalla-derived routing through its Directions API.
- Self-host: easiest of the three — `nilsnolde/docker-valhalla` downloads
  the PBF and builds tiles automatically. Île-de-France tiles are small.
- On-device only via third-party JNI wrappers (`Rallista/valhalla-mobile`,
  CARTO Mobile SDK). Native C++ → noticeable APK bloat and build
  complexity.

### OpenRouteService (HeiGIT)
- Multiple cycling profiles out of the box (`cycling-regular`,
  `cycling-road`, `cycling-mountain`, `cycling-electric`). No cargo
  profile, no client-side customisation without self-hosting.
- Public API: stable, properly run by HeiGIT, generous free tier (caps
  live in the HeiGIT dashboard). Comfortable for our volume.
- Self-host is heavy (GPL-3 Java, 8–16 GB RAM even for a small country) —
  not realistic on a tiny VPS.
- No on-device option.

### Mapbox Directions API
- `cycling` profile (Valhalla-derived). Decent, no cargo customisation.
- 100k requests/month free; pricing scales gently after that.
- **TOS forbid long-term caching of routing results**, which conflicts
  with our offline-first / cached-fallback design. Eliminated.

### Google Directions / Routes API
- Listed for completeness only — the user has explicitly excluded Google.
- Also has caching restrictions (~30 days max).

## Recommendation

**GraphHopper everywhere.** It is the only mainstream option that ticks
all three boxes — strong cargo-bike profile, permissive license, and a
credible on-device story for a Kotlin/Compose app. The deployment shape
that matches our constraints best:

1. **On-device:** ship a pre-built Île-de-France graph (download on first
   launch to keep the APK small; refresh occasionally). The Java core
   library handles route computation locally. This is the primary path on
   walking days — fast, free, offline.
2. **Online fallback / planning at home:** optionally a self-hosted
   GraphHopper instance on a small VPS using the *same* engine and the
   *same* custom cargo-bike model. Useful for cross-checking the on-device
   graph or for richer features (matrix calls for multi-stop optimisation)
   if the on-device version turns out to be too slow.
3. **No third-party hosted dependency in the critical path.** The
   GraphHopper Directions API free tier could serve as an additional
   fallback during development, but production use stays self-contained.

Realistic alternatives if GraphHopper-on-Android turns out painful in
practice:
- **Valhalla via Stadia Maps for online + cached results on-device** —
  simpler operationally, but no first-class cargo profile and weaker
  offline story.
- **OpenRouteService API for online + nothing offline** — zero ops,
  abandons the offline-first goal. Not recommended given the scope.

## Open follow-ups

- Confirm the GraphHopper Java core still builds cleanly for modern
  Android targets (some Java APIs may need polyfills). The agent's
  research found a v1.0 official Android demo that was later dropped;
  the core JAR is reported to still work, but worth a small spike.
- Decide whether to bundle the Île-de-France graph in the APK or
  download it on first launch (UX vs install size).
- Decide on the map renderer (`osmdroid`, MapLibre, Mapbox) — a separate
  question, but worth pairing the decision with this one.

## Sources

The supporting research was gathered by a research subagent. Key sources:

- [OSRM API usage policy](https://github.com/Project-OSRM/osrm-backend/wiki/Api-usage-policy)
- [GraphHopper pricing](https://www.graphhopper.com/pricing/)
- [GraphHopper cargo-bike custom model](https://discuss.graphhopper.com/t/custom-model-urban-cycling-routing-issues-avoiding-sharp-corners-and-stiles-with-cargo-bikes/8267)
- [GraphHopper offline-Android thread](https://discuss.graphhopper.com/t/offlne-routing-on-android/9176)
- [OpenRouteService restrictions](https://openrouteservice.org/restrictions/)
- [Stadia Maps routing docs](https://docs.stadiamaps.com/routing/)
- [Mapbox pricing](https://www.mapbox.com/pricing)
- [Valhalla docker image](https://github.com/nilsnolde/docker-valhalla)
- [Rallista/valhalla-mobile (Android/iOS bindings)](https://github.com/Rallista/valhalla-mobile)
- [Geofabrik Île-de-France extract](https://download.geofabrik.de/europe/france/ile-de-france.html)
