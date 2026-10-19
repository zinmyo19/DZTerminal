# DZ Terminal

**DZ Terminal** — a Python + Linux terminal for Android. A full Termux-compatible
environment (bash, `pkg`/`apt`, Python, pip) bundled in its own app, with a
tabbed console, an editor, a program runner, and QPYPI package installs.

> Contact: Telegram [@Dominic_aiBot](https://t.me/Dominic_aiBot) ·
> GitHub: [github.com/zinmyo19/DZTerminal](https://github.com/zinmyo19/DZTerminal)

## Features

- **Console** — full Linux shell (Termux bootstrap + proot) with **tabs**
  (up to 8 sessions), each with its own shell, screen, and scrollback.
  Background tabs keep running; the attached tab's typing/echo is prioritized.
- **Python** — Python 3.14 preinstalled; `pip install` works from the console.
- **Programs** — run QPython scripts; every script run opens a fresh console tab
  (QPython-style), in the script's own folder.
- **QPYPI** — install PyPI packages by name from the app UI.
- **Editor** — edit files without leaving the app.
- **Export / Import** — copy home files to `/storage/emulated/0/DZTerminal/`
  (visible in any file manager) and import edits back. Conflicts are kept as
  `<name>.imported`, never overwritten.
- **Shared storage** — `/sdcard`, `/storage/emulated/0`, and `~/storage` all work
  from the shell (storage permission is requested once).
- **Background keep-alive** — a foreground service + wake lock keeps sessions
  alive when you leave the app (Home / app switch / screen off). The
  notification shows the live session count and has a Stop action.
- **Extra-keys bar** — Termux-style ESC / TAB / CTRL / ALT / arrows / HOME /
  END / PGUP / PGDN, with sticky CTRL/ALT (one-shot, disarms on any key).
- **Debug screen** — per-session drain counters (PTY bytes read / emulator
  bytes appended / queue depth), keep-alive status, and storage status.

## Install

Grab the APK from the
[Releases page](https://github.com/zinmyo19/DZTerminal/releases) and install
it. New versions are signed with the same certificate, so they install over
older 1.0.x builds without uninstalling.

## Build from source

Requirements: a Linux machine with JDK 17, Android SDK build-tools
(`aapt2`, `zipalign`, `apksigner`), `kotlinc`, and `d8` on `PATH`.

```sh
./build.sh
```

The script compiles the app, packages it, aligns it, and signs it with the
workspace debug keystore (see `build.sh` — the keystore file itself is local
and is never committed). The APK is written to `~/workspace/user/files/` when
run in the author's environment; in general it lands in `out/`.

`native-build.sh` rebuilds the bundled `proot` helper binary
(`assets/proot`) and the `libtermux.so` PTY JNI library (`out-native/`).

## License & attribution

DZ Terminal is released under the **GNU General Public License v3.0 only**
(see `LICENSE`). Full attribution is in [`NOTICE.md`](NOTICE.md). Summary:

- **Terminal engine** based on Termux (GPL-3.0).
- **Terminal view / emulator code** derived from the jackpal Android Terminal
  Emulator (Apache-2.0).
- **proot** helper binary from the Termux proot fork (GPL-2.0).
- **Termux bootstrap** (`assets/bootstrap-aarch64.zip`) — stock Termux
  packages, used unmodified. Its binaries were built with the Termux prefix
  (`/data/data/com.termux/files/usr`) baked in; DZ Terminal runs them under
  `proot`, which bind-mounts this app's own prefix onto that path. Nothing is
  read from or written to the installed Termux app or its data.

## Notes

- The app targets SDK 28 (required for direct execution from app data).
- The guest home the shell sees (`/data/data/com.termux/files/usr/home`) is
  the guest-side ABI path; on disk the files live in this app's own private
  directory (`/data/data/com.zynelabs.dzterminal/files/usr/home`).
- `/data` and `/data/user` of other apps are Android-protected and are never
  accessed — use `/sdcard` to move files between apps.
