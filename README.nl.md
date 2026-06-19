# DogRouter

Android-app voor het plannen van de werkdag van een hondenuitlater en de
bakfiets-routes tussen ophaal- en breng-adressen. Zie [`SCOPE.nl.md`](SCOPE.nl.md)
voor wat v1 wel en niet doet, en
[`docs/ROUTING_ENGINES.md`](docs/ROUTING_ENGINES.md) (alleen Engels) voor de
afweging tussen routing-engines.

> Vertaling van [`README.md`](README.md). De Engelse versie is canoniek; bij
> verschillen geldt die.

## Tech stack

- Kotlin + Jetpack Compose (Material 3)
- `minSdk=35`, `compileSdk=35`, `targetSdk=35`
- Gradle 8.10.2 + AGP 8.7.2 + Kotlin 2.1.0
- JDK 17 source/target (via de JBR 21 die met Android Studio meekomt)

Versies staan vast in [`gradle/libs.versions.toml`](gradle/libs.versions.toml).

## Eerste setup

### GitHub Packages-credentials (verplicht)

De routing-engine BRouter (`org.btools:brouter-core`) wordt alleen via
**GitHub Packages** gepubliceerd — Maven Central heeft 'm niet. Gradle
heeft een GitHub personal access token nodig om het artefact te
downloaden.

1. Open <https://github.com/settings/tokens?type=beta> en maak een
   fine-grained PAT aan (of een classic) met de `read:packages` scope.
2. Voeg de credentials toe aan `~/.gradle/gradle.properties` (NIET aan
   die in dit project — secrets blijven buiten de repo):
   ```properties
   gpr.user=<je-github-gebruikersnaam>
   gpr.key=<de-token-uit-stap-1>
   ```
3. Doe een nieuwe Gradle-sync.

CI-alternatief: zet de env-vars `GITHUB_ACTOR` en `GITHUB_TOKEN`.

### Gradle-wrapper

De Gradle-wrapper-jar (`gradle/wrapper/gradle-wrapper.jar`) staat **nog
niet** in de repo — genereer 'm eenmalig via een van deze paden:

1. **Open het project in Android Studio.** `File → Open` → selecteer deze
   map. Studio merkt op dat de wrapper ontbreekt, downloadt Gradle 8.10.2
   volgens `gradle/wrapper/gradle-wrapper.properties`, en maakt de wrapper
   aan. Na de eerste sync werkt `./gradlew`.
2. **Of installeer Gradle eenmalig via Homebrew** en bootstrap vanaf de
   commandline:
   ```sh
   brew install gradle
   gradle wrapper --gradle-version 8.10.2
   ```

Zodra de wrapper bestaat, commit `gradle/wrapper/gradle-wrapper.jar`,
`gradlew` en `gradlew.bat` — `.gitignore` laat ze door.

## Bouwen & draaien

Na de eerste sync:

```sh
./gradlew :app:assembleDebug          # debug-APK bouwen
./gradlew :app:installDebug           # installeren op een gekoppeld toestel
adb shell am start -n app.dogrouter/.MainActivity   # starten
```

Of gebruik de groene play-knop in Android Studio.

## Project-indeling

```
DogRouter/
├── app/                          # de enige applicatie-module
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── kotlin/app/dogrouter/ # Kotlin-broncode
│       └── res/                  # Android-resources
├── gradle/
│   ├── libs.versions.toml        # version catalog
│   └── wrapper/                  # wrapper-config (jar bij eerste sync)
├── docs/                         # design-rationale (routing engines, …)
├── CLAUDE.md                     # werkafspraak met de assistent
├── SCOPE.md / SCOPE.nl.md        # wat v1 wel en niet doet
├── README.md / README.nl.md      # dit bestand
├── build.gradle.kts              # root-build-script
├── settings.gradle.kts
└── gradle.properties
```
