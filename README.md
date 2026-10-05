# Daydream — StandBy for Android

A nightstand-style display for Android, inspired by Apple's StandBy and written in Kotlin with Jetpack Compose. Unlike StandBy, it doesn't wait for you to be charging in landscape. You can open it any time.

## Features

Swipe left and right between three pages:

| Page | What it shows |
|---|---|
| **Widgets** | Two widget stacks you swipe up and down: analog clock, battery ring, next alarm, calendar with a month grid, and weather |
| **Photos** | A crossfading slideshow with Ken Burns pan-and-zoom, a large clock overlay, and the photo's place and date. Portrait photos are arranged into collages (see below) |
| **Clock** | Full-screen clock faces (big digital, analog, stacked). Swipe up and down to change face |

- **Photo sources:** [Immich](https://immich.app) (random photos, favorites, or the albums you pick), photos on the device (all folders except screenshots and screen recordings by default, or only the folders you pick under **Settings → Photos → Folders**), or none.
- **Collages:** each slide picks a layout that fits its photos, rather than cropping a portrait into a thin band:
  - landscape photos fill the screen;
  - two or three portraits sit side by side (three only on screens at least 1.9:1 wide);
  - one portrait can sit beside two stacked landscapes (mosaic);
  - a portrait with no partner is shown whole over a blurred copy of itself.

  On a portrait screen the layouts turn sideways. Layouts are chosen from the photo's metadata and then checked against the decoded image, so a wrongly rotated EXIF tag can't produce a bad crop. Choose **Single photo** under **Settings → Photos → Layout** to turn collages off.
- **Ken Burns:** a slow, random pan and zoom on each photo and tile. The zoom always covers the frame, so no edges ever show. Turn it off in Settings; it also stops when Android's "Remove animations" setting is on.
- **Night mode:** turns the whole display dim red and lowers the backlight. *Auto* uses the light sensor, with hysteresis so it doesn't flicker. Devices without a light sensor switch at 22:00–06:00.
- **Weather** comes from [Open-Meteo](https://open-meteo.com), which needs no API key. You pick a city in Settings.
- **Ways to open it:** the app icon, a Quick Settings tile ("StandBy"), or the Android screen saver. The screen saver can start it automatically while the phone charges.
- The screen stays on, system bars are hidden, and the app remembers which page and clock face you last used.

Tap anywhere to show the **Settings** button and the page dots. In screen-saver mode there is also a **Close** button.

### Android TV

Install the APK with `adb install app-debug.apk` or any sideloading app. Daydream then shows up in the TV launcher.

- **Remote:** ←/→ switch pages, ↑/↓ change the clock face or flip both widget stacks, **OK** shows the controls and focuses **Settings**, **Back** hides the controls (or exits).
- **Screensaver:** it works as the Android TV screensaver (Settings › Screen saver). Google TV hides third-party screensavers, so select it over adb: `adb shell settings put secure screensaver_components com.daydream.standby/.StandByDreamService`.

## Windows (desktop) version
`windows/` is a fullscreen Python port with the same three pages, collages, Ken Burns motion, Immich and weather. It runs at the monitor's native resolution, keeps the display awake, and hides the cursor when idle.

```bat
cd windows
pip install -r requirements.txt
python daydream.py              REM or pythonw daydream.py (no console window)
```

The first run creates `%APPDATA%\Daydream\config.json`. Edit it and restart; see `windows/config.example.json` for the settings:
- `photo_source`: `local`, `immich` or `none`;
- `folders`;
- `immich`: `url`, `api_key`, and `mode` (`random`, `favorites` or `albums`; for `albums`, list the album ids in `albums`);
- `interval_seconds`;
- `layout`: `collages` or `single`;
- `ken_burns`;
- `clock_24h`;
- `night_mode`: `auto` means 22:00–06:00;
- `weather.city`;
- `display`: the monitor index.

**Keys:**
- ← / → or mouse drag: switch pages;
- ↑ / ↓: change the clock face or widget stack;
- `N`: cycle night mode (auto, on, off);
- `F11`: toggle windowed mode;
- `Esc` / `Q`: quit.

`--config PATH` and `--windowed` are also available.

Differences from Android:
- there's no in-app settings screen (you edit the JSON);
- night mode follows the clock rather than a light sensor;
- there's no next-alarm widget, since Windows has no alarm API;
- the battery widget only appears on laptops;
- HEIC photos aren't supported.

Tests: `cd windows && python -m unittest test_core`.

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
