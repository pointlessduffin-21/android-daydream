# Daydream for Windows

Daydream is a Python desktop display built with pygame-ce and Pillow. `daydream.py`
handles the window, drawing, keyboard input, and background workers. `core.py`
handles photo layout planning, Ken Burns motion, local photo discovery, Immich
requests, weather requests, and configuration. `test_core.py` contains 21 tests
covering layout rules, motion bounds, configuration, and Immich HTTP behavior.

## Run the executable

Double-click `../releases/Daydream.exe`. This is a standalone Windows x64 executable;
Python does not need to be installed on the destination computer.

The app starts fullscreen. Press **S** or click **Settings (S)** in the top-right
corner to open settings. Move the mouse to reveal the button. The native settings
window has Photos, Clock & Weather, and Immich tabs. Save & Apply updates the
display immediately; Cancel leaves your configuration unchanged.

Escape or Q quits; F11 toggles windowed mode.
Left/right switches pages, up/down changes the clock face or widget stack,
and N cycles night mode.

On first launch, settings are created at `%APPDATA%\Daydream\config.json`.
The default photo folder is `~/Pictures`. Use the settings window to choose photo
folders, configure Immich, or set the weather city. You can also edit the JSON
file directly and restart the app.
Weather and Immich need network access; local photos and clocks work offline.
Keep Immich API keys private when sharing configuration files.

Command-line options:

```powershell
.\dist\Daydream.exe --windowed
.\dist\Daydream.exe --config "C:\path\to\config.json"
```

`config.example.json` shows the available settings. Supply an existing JSON file
with `--config`; the default configuration location requires no setup.

## Rebuild

Use Windows x64 with Python 3.11 or newer:

```powershell
.\build.ps1
# Or specify a Python executable:
.\build.ps1 -Python "C:\path\to\python.exe"
```

`requirements-build.txt` records the build dependency versions. `Daydream.spec`
packages the Python runtime, Pygame's SDL libraries and default font, Pillow,
Tkinter's settings window, and the app into one executable without a console window. The build output is
`dist/Daydream.exe`, also copied to `../releases/Daydream.exe` with a
`Daydream.exe.sha256` checksum file. The executable is unsigned.

## Analysis and verification

The existing separation between display code and core logic makes the project
suitable for PyInstaller packaging without changing application behavior.
Photo and weather workers run in the background; failed sources report status
and retry. Core logic validates configuration types and restricts Immich request
sizes, redirects, and asset identifiers. Runtime settings remain outside the
executable so users can change them after packaging.

All 26 tests passed on Windows with Python 3.11.17, including five tests for
settings validation, atomic saves, and applying configuration while preserving
the display state. A native Tkinter form check verified all three tabs, masked
API-key input, saving, and cancelling. A headless rendering
check exercised all pages, three clock faces, six widget stacks, and the quit
event. The packaged executable remained running during a five-second headless
startup check with network features disabled. These checks do not verify visual
appearance on a physical display. Live Immich and weather services require
separate testing with the user's server and city.
