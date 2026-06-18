# DogRouter

Android app for planning a dog walker's working day and the cargo-bike routes
between pickup/drop-off addresses. See [`SCOPE.md`](SCOPE.md) for what the
v1 does and does not cover, and
[`docs/ROUTING_ENGINES.md`](docs/ROUTING_ENGINES.md) for the routing-engine
trade-off analysis.

> Dutch translation: [`README.nl.md`](README.nl.md). The English version is
> canonical; in case of divergence the English text applies.

## Tech stack

- Kotlin + Jetpack Compose (Material 3)
- `minSdk=26`, `compileSdk=37`, `targetSdk=37`
- Gradle 8.10.2 + AGP 8.7.2 + Kotlin 2.1.0
- JDK 17 source/target (using the JBR 21 bundled with Android Studio)

Versions are pinned in [`gradle/libs.versions.toml`](gradle/libs.versions.toml).

## First-time setup

The Gradle wrapper jar (`gradle/wrapper/gradle-wrapper.jar`) is **not**
checked in yet — generate it once with either path:

1. **Open the project in Android Studio.** `File → Open` → select this
   directory. Studio detects the missing wrapper, downloads Gradle 8.10.2
   per `gradle/wrapper/gradle-wrapper.properties`, and creates the wrapper.
   After the first sync, `./gradlew` works.
2. **Or install Gradle once via Homebrew** and bootstrap from the command
   line:
   ```sh
   brew install gradle
   gradle wrapper --gradle-version 8.10.2
   ```

Once the wrapper exists, commit `gradle/wrapper/gradle-wrapper.jar`,
`gradlew`, and `gradlew.bat` — the project's `.gitignore` allows them.

## Build & run

After the first sync:

```sh
./gradlew :app:assembleDebug          # build a debug APK
./gradlew :app:installDebug           # install on a connected device
adb shell am start -n app.dogrouter/.MainActivity   # launch
```

Or use the green play button in Android Studio.

## Project layout

```
DogRouter/
├── app/                          # the single application module
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── kotlin/app/dogrouter/ # Kotlin source
│       └── res/                  # Android resources
├── gradle/
│   ├── libs.versions.toml        # version catalog
│   └── wrapper/                  # wrapper config (jar generated on first sync)
├── docs/                         # design rationale (routing engines, …)
├── CLAUDE.md                     # working agreement with the assistant
├── SCOPE.md / SCOPE.nl.md        # what v1 does and does not do
├── README.md / README.nl.md      # this file
├── build.gradle.kts              # root build script
├── settings.gradle.kts
└── gradle.properties
```
