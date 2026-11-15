# RETRO-OS Launcher

An 80s home-computer / CRT terminal home screen for Android (8.0+). Plain Java, no libraries.

## Features
- Boot sequence with a CRT "power-on" snap (tap to skip; `REBOOT` replays it)
- Apps listed like a floppy directory: `12  "CHROME"  PRG` (blocks = APK size in MB, footer = real free storage)
- 16x16 pixel-art app icons, re-coloured to the theme's phosphor colour
- Command line: type to filter, Enter runs the best match
- F1–F4 quick keys (Phone / Messages / Web / Camera by default; long-press any app to assign one), F5 = system menu
- 13 skins (`THEME <NAME>` or F5 → SKIN):
  - **GREEN / AMBER / 64 / NEON / ICE**: 80s CRT terminals
  - **RADIO**: field radio handset with a rotary channel knob (drag to tune, tap PTT to launch), MGRS grid, compass bearing, DTG clock, battery-time estimate
  - **SPECTRUM**: live waterfall + trace with your apps as signal markers; Wi-Fi S-meter
  - **TELETEXT**: apps on pages 300+, type a page number to open; fastext colour keys
  - **LCD**: 4-shade handheld with D-pad, A/B, SELECT (next skin) and START (menu)
  - **DESKTOP**: 90s Program Manager window, taskbar with Start and clock, starfield screensaver after 60 s
  - **MAINFRAME**: operator console, blinking front-panel lamps, punch-card keys, tape reels spin up on launch
  - **CARTOON**: loud 90s stickers, wonky rows, ZAP!/POW! on launch
  - **CRITTER**: original creature-collector RPG look; every app gets its own generated pixel creature
- CRT overlay: scanlines, rolling refresh band, vignette, rounded tube corners
- Original 5x7 pixel font, generated at build time by `tools/make_font.py` (run `pip install fonttools && python3 tools/make_font.py app/src/main/assets/fonts/retro.ttf` before a local build, otherwise it falls back to monospace)

## Commands
`HELP` · `RUN <name>` / `LOAD <name>` · `THEME [name]` · `SKINS` · `CRT` · `ICONS` ·
`WEB <query>` · `CALL <number>` · `SETTINGS` · `HOME` (pick default launcher) · `REBOOT` · `TIME` · `ABOUT` · `CLS`

## Build
**GitHub (no PC needed):** push this folder to a repo → Actions → "Build APK" runs automatically →
download `retro-launcher-apk` from the run, unzip, install the APK (allow "install unknown apps").

**Android Studio:** generate the font (above), then File → Open → this folder → Run.

## Make it your home screen
Press Home → pick RETRO-OS → "Always". Or type `HOME` in the launcher.
To go back: Settings → Apps → Default apps → Home app.
