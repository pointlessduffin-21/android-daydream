# Daydream — StandBy for Android

A nightstand-style display for Android, inspired by Apple's StandBy and written in Kotlin with Jetpack Compose. Unlike StandBy, it doesn't wait for you to be charging in landscape. You can open it any time.

## Features

Swipe left and right between three pages:

| Page | What it shows |
|---|---|
| **Widgets** | Two widget stacks you swipe up and down: analog clock, battery ring, next alarm, calendar with a month grid, and weather |
| **Photos** | A crossfading slideshow with a slow Ken Burns zoom, a large clock overlay, and the photo's place and date |
| **Clock** | Full-screen clock faces (big digital, analog, stacked). Swipe up and down to change face |

- **Photo sources:** [Immich](https://immich.app) (random photos, favorites, or the albums you pick), photos on the device (screenshots and screen recordings are skipped), or none.
- **Night mode:** turns the whole display dim red and lowers the backlight. *Auto* uses the light sensor, with hysteresis so it doesn't flicker. Devices without a light sensor switch at 22:00–06:00.
- **Weather** comes from [Open-Meteo](https://open-meteo.com), which needs no API key. You pick a city in Settings.
- **Ways to open it:** the app icon, a Quick Settings tile ("StandBy"), or the Android screen saver. The screen saver can start it automatically while the phone charges.
- The screen stays on, system bars are hidden, and the app remembers which page and clock face you last used.

Tap anywhere to show the **Settings** button and the page dots. In screen-saver mode there is also a **Close** button.

## Immich setup

1. In Immich, go to **Account Settings → API Keys → New API Key**. The key needs at least `asset.read`, `asset.view`, and `album.read`.
2. In Daydream, open **Settings → Photos → Immich**. Enter your server URL and paste the key. Any of these URL forms work: `photos.example.com` (becomes https), `192.168.1.10:2283` (local addresses become http), `https://host/immich/api`.
3. Tap **Connect**. Daydream checks the server version and the key's permissions, then loads your albums.
4. Pick **Random**, **Favorites**, or **Albums**. For Albums, tick the albums you want.

Endpoints used: `GET /api/server/version`, `POST /api/search/random` (also used for albums, via `albumIds`), `GET /api/albums`, `GET /api/albums/{id}` (only a fallback for servers too old to filter random search by album), and `GET /api/assets/{id}/thumbnail?size=preview`. The slideshow only downloads photos while the Photos page is on screen.

### Security notes
- The API key is stored in the app's private DataStore. It is left out of cloud backup and device-to-device transfer.
- The key is only sent to the server you configured. Redirects are not followed for API calls, and image requests drop the key if a redirect points to a different host.
- Plain HTTP is allowed because many Immich servers run on a home network. If the key would go over HTTP to a host that isn't local, Settings asks you to confirm **before anything is sent**. User-installed CAs are trusted for your Immich server so that private-CA setups work, but not for Open-Meteo.
- **Show over lock screen** is off by default. While it's off, the screensaver hides photos whenever the phone is locked. Opening Settings from StandBy always asks you to unlock first, and the Settings screen is blocked from screenshots and the Recents preview.
- Disconnecting Immich clears the cached photos.

## Building

Requirements: JDK 17 or newer, and the Android SDK (compileSdk 37; the Gradle build downloads the platform if it's missing). Minimum Android version is 8.0 (API 26).

```bash
./gradlew :app:assembleDebug        # build the APK
./gradlew :app:installDebug         # install on a connected device
./gradlew :app:testDebugUnitTest    # run the unit tests
./gradlew :app:lintDebug            # run Android lint
```

The first build downloads about 1.5 GB of tooling and libraries. `gradle.properties` sets longer timeouts and more retries so that slow or patchy connections still finish. After that first build, `./gradlew --offline …` works without any network.

Stack: AGP 9.4 (built-in Kotlin), Kotlin 2.4, Compose BOM 2026.09, Coil 3, OkHttp 5, kotlinx.serialization, and DataStore.

## Project layout

```
app/src/main/java/com/daydream/standby/
├── StandByActivity.kt        launcher entry point (landscape, keeps screen on)
├── StandByDreamService.kt    the same UI as an Android screen saver
├── StandByTileService.kt     Quick Settings tile
├── AppContainer.kt           manual dependency wiring
├── data/
│   ├── immich/               Immich REST client, DTOs, URL normalisation
│   ├── photos/               PhotoSource implementations and the Slideshow engine
│   ├── weather/              Open-Meteo client and WMO weather-code mapping
│   ├── settings/             AppSettings and the DataStore repository
│   └── net/                  shared HTTP helpers and the credential-stripping interceptor
└── ui/
    ├── standby/              pager, photos, clock faces, widgets
    ├── settings/             settings screen and ViewModel
    └── common/               clock formatting, night-mode detection, system state
```
