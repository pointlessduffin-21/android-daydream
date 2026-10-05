# Changelog

## [1.1.0] - 2026-10-05

### Added
- Portrait-aware collages: side-by-side pairs and triples, a mosaic (one portrait plus two landscapes), and a blurred-backdrop view for a lone portrait. The layout adapts to portrait screens. A "Layout: Collages / Single photo" setting controls it.
- Real Ken Burns motion (random pan plus zoom in or out, bounded so no edges show), with a toggle. It respects the system "Remove animations" setting.
- Folder picker for the on-device photo source: choose which device folders the slideshow uses (screenshots are skipped only when no folder is picked), with cover thumbnails and photo counts.
- Android TV support: TV launcher entry and banner, no touchscreen required, and remote (D-pad) control of pages, clock faces, widget stacks, and the Settings button.
- Windows desktop version (`windows/`, Python with pygame-ce and Pillow): fullscreen at native resolution, with collages, Ken Burns, crossfades, clock faces, widgets (clock, calendar, weather, laptop battery), Immich, local folders and a JSON config.

### Changed
- Slideshow photos are decoded once per slide and drawn from the image cache, so collages never crossfade to an empty tile. The next slide is prepared while the current one is showing.
- When the controls are showing, Back hides them first.

## [1.0.0] - 2026-10-02

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
