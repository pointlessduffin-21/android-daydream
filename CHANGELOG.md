# Changelog

## [Unreleased]

### Added
- StandBy-style full-screen display with three pages: widget stacks, photo slideshow, and clock faces.
- Immich photo source (random, favorites, or selected albums) using API-key authentication.
- On-device photo source through MediaStore, including Android 14 partial photo access.
- Widgets: analog clock, calendar with month grid, battery, next alarm, and Open-Meteo weather.
- Red night mode, driven by the ambient light sensor or by time of day.
- Ways to open it: launcher icon, Quick Settings tile, and Android screen saver (DreamService).
- Settings screen covering photo source, Immich connection test, album picker, slideshow interval, clock format, night mode, weather location, and lock-screen visibility.

### Security
- The Immich API key is excluded from backup, never sent to other hosts on redirects, and requires explicit confirmation before it is sent over plain HTTP to a non-local host.
- The Settings screen uses `FLAG_SECURE`. Photos stay hidden on the lock screen and in the screensaver unless "Show over lock screen" is on.
