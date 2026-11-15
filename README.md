# RETRO-OS Launcher

An 80s home-computer / CRT terminal home screen for Android (8.0+). Plain Java, no libraries.

## Features
- Boot sequence with a CRT "power-on" snap (tap to skip; `REBOOT` replays it)
- Apps listed like a floppy directory: `12  "CHROME"  PRG` (blocks = APK size in MB, footer = real free storage)
- 16x16 pixel-art app icons, re-coloured to the theme's phosphor colour
- Command line: type to filter, Enter runs the best match
- F1–F4 quick keys (Phone / Messages / Web / Camera by default; long-press any app to assign one), F5 = system menu
- 5 themes: GREEN, AMBER, 64, NEON (synthwave grid), ICE
- CRT overlay: scanlines, rolling refresh band, vignette, rounded tube corners
- Original 5x7 pixel font, generated at build time by `tools/make_font.py` (run `pip install fonttools && python3 tools/make_font.py app/src/main/assets/fonts/retro.ttf` before a local build, otherwise it falls back to monospace)

## Commands
`HELP` · `RUN <name>` / `LOAD <name>` · `THEME [GREEN|AMBER|64|NEON|ICE]` · `CRT` · `ICONS` ·
`WEB <query>` · `CALL <number>` · `SETTINGS` · `HOME` (pick default launcher) · `REBOOT` · `TIME` · `ABOUT` · `CLS`

## Build
**GitHub (no PC needed):** every push runs Actions → "Build APK" →
download `retro-launcher-apk` from the run, unzip, install the APK (allow "install unknown apps").

**Android Studio:** generate the font (above), then File → Open → this folder → Run.

## Make it your home screen
Press Home → pick RETRO-OS → "Always". Or type `HOME` in the launcher.
To go back: Settings → Apps → Default apps → Home app.
