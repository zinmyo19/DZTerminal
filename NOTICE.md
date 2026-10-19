# DZ Terminal — attribution and licensing

DZ Terminal is released under the **GNU General Public License v3.0 only**
(see `LICENSE`).

## Bundled / vendored code

- **Termux bootstrap** (`assets/bootstrap-aarch64.zip`): the stock Termux
  bootstrap packages, built by the Termux project
  (https://github.com/termux/termux-packages, GPL-3.0). The bootstrap is
  used unmodified. Its binaries were built with the Termux PREFIX
  (`/data/data/com.termux/files/usr`) baked in; DZ Terminal runs them
  under `proot`, which bind-mounts this app's own prefix onto that path.
  Nothing is read from or written to the installed Termux app or its data.

- **Terminal emulator + terminal view** (`src/main/java/com/termux/...`):
  derived from the Termux project's `terminal-emulator` and
  `terminal-view` libraries (GPL-3.0 in the termux-app monorepo), which
  themselves derive from the jackpal Android Terminal Emulator
  (Apache License 2.0). The vendored sources keep their original headers.

- **PTY JNI** (`jni/termux.c`): from the Termux project's
  `terminal-emulator` JNI (GPL-3.0 / Apache-2.0, see above).

- **proot** (`assets/proot`): built from the Termux project's proot fork
  (https://github.com/termux/proot, GPL-2.0). proot is used unmodified as
  a helper binary: it bind-mounts this app's prefix onto the guest
  prefix path so the stock bootstrap binaries resolve their hardcoded
  paths. No Termux app code or data is involved.

- **talloc** (linked into proot): the talloc memory-pool library
  (LGPL-3.0), built from https://www.samba.org/ftp/talloc/.

- **libandroid-shmem** (linked into proot): Termux project's
  libandroid-shmem (https://github.com/termux/libandroid-shmem).

## Own code

All other code in this repository (Home/Editor/Programs/QPYPI/
Community screens, prefix setup, session wiring) was written for
DZ Terminal and is released under GPL-3.0.
