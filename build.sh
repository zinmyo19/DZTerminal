#!/bin/bash
# Manual APK build for DZ Terminal v1.0.17 (no Gradle).
# Follows ~/workspace/shizuku-build/manual/build.sh patterns + ~/AGENTS.md lessons.
#
# v1.0.17: ARCHITECTURAL FIX — the interactive SESSION runs under
# PROOT (end of the dual-context design). v1.0.6–v1.0.16 ran the
# session on direct exec (host paths) while tools/second stage ran
# under proot (fake paths). That split kept breaking on helpers:
# v1.0.16's apt-key had a host-patched shebang (whole-tree patch),
# so apt inside proot could not execute it and `apt update`
# reported the repo "not signed". Now the session runs under
# proot too, the tree stays FAKE (pristine Termux), wrappers are
# REMOVED (real tools restored), the whole-tree host patch is
# DELETED, and a one-time migration reverts <=v1.0.16 host
# patching. Also: (1) the second-stage uid capture is hardened
# (`id -u 2>/dev/null` + strip non-digits — the Bionic linker
# warning polluted it and the second stage never completed, so
# postinsts never ran); (2) /linkerconfig is bound into the
# guest when it exists on the host, killing the linker warning
# at the source. Guest env is pure fake paths (no LD_PRELOAD,
# no LD_LIBRARY_PATH, no host CA vars — curl's compiled-in fake
# CA path resolves via the bind). QPYPI runs pip under proot.
# Debug proves: session proot argv, fake-tree check, wrapper
# removal, migration stats, keyring state (trusted.gpg.d).
#
# v1.0.14: (1) CA env for the direct session (SessionManager.directEnv):
# SSL_CERT_FILE / CURL_CA_BUNDLE / GIT_SSL_CAINFO -> <host>/etc/tls/cert.pem.
# On-device every HTTPS mirror tested "bad" while the one HTTP mirror
# tested "ok": curl's compiled-in CA path is the fake Termux prefix,
# which does not resolve under direct exec. (2) Proot wrappers for the
# package tools (pkg/apt/apt-get/apt-cache/dpkg/termux-change-repo):
# the interactive shell stays on direct exec, but those tools are ELF
# binaries with compiled-in fake paths (dpkg config dir, apt config
# dir) — they run under proot via a wrapper script so the fake path
# resolves through the fake->host bind. Wrappers + .real files are
# excluded from the whole-tree patch/unpatch; DZ_INSIDE_PROOT=1
# prevents nested proot (second stage + wrapped-tool-chains).
# Debug gains a Package-tools section (CA bundle + wrapper status).
#
# v1.0.13: (1) Typing: replace the v1.0.12 composing DELTA protocol
# (common-prefix) with erase-all + resend-all — on ANY composing-text
# change, erase the ENTIRE previously-sent composing span (one DEL per
# sent character), then send the ENTIRE new text; on commit, erase the
# sent span and send the committed text once. The delta dropped
# characters on-device ("pkg install" arrived as "pkginstl"). No
# common-prefix computation anywhere in the composing path.
# (2) Second stage runs UNDER PROOT with the fake-to-host bind:
# dpkg is an ELF binary with its config path COMPILED IN (fake
# prefix) — no text patch can fix that, so direct exec failed with
# "error opening configuration directory ... Permission denied" and
# setup stayed PARTIAL forever. Under proot the fake path resolves
# onto OUR prefix files. The guest sees a PRISTINE Termux tree:
# the whole-tree patch (host paths) is reverted before the proot run
# (unpatchWholeTreePaths) and re-applied by the next Console start.
# (3) Debug proves the patch: bin/pkg shebang first line on disk +
# bin/python listing + second-stage mode (proot).
#
# Follows ~/workspace/shizuku-build/manual/build.sh patterns + ~/AGENTS.md lessons.
#
# v1.0.12: (1) Whole-tree fake-path patch (PrefixSetup.patchWholeTreePaths,
# run from prepareForShell on every Console start): every text file in
# OUR OWN extracted prefix gets the hardcoded Termux prefix replaced
# with the real host prefix — v1.0.6/v1.0.9 only patched a handful of
# named files, so bin/pkg etc. kept fake shebangs ("bad interpreter").
# Binary-safe (NUL-byte check), symlinks never followed, home/ + tmp/
# never rewritten, writes verified. (2) Typed characters echo
# immediately: TerminalView's InputConnection now sends the DELTA of
# Gboard's composing text on every setComposingText instead of holding
# it unseen until Enter (commitText/finishComposingText are diff-aware
# so nothing duplicates). (3) CTRL/ALT colors corrected: unarmed is
# now exactly like every other key (white text + ripple) — v1.0.10 gave
# them green text while unarmed so they looked armed all the time;
# green (bg + black bold text) is reserved for the armed state only.
#
# v1.0.11: (1) Neutralize the two Termux-app first-run scripts in
# etc/profile.d (init-termux-properties.sh + second-stage fallback)
# to a no-op on every Console start — they must never mkdir/cp
# under the installed Termux app's data dir; Debug gains a
# fake-Termux-path scan proving the startup files are clean.
# --norc is NOT used: it also suppresses --init-file (verified
# with system bash on a pty), so the single SYS_BASHRC warning
# line is bash's own failed read (kernel denies it, never
# succeeds). (2) QPython scripts: direct read of
# /sdcard/qpython/scripts3/ with READ_EXTERNAL_STORAGE (already
# granted) as the primary path, SAF tree as fallback; folder
# changeable via a path dialog. (3) Layout: extra-keys labels no
# longer clip (minWidth 0 + small padding + 11sp); Programs
# title is single-line + ellipsize (no wrap).
#
# v1.0.10: (1) Termux-style extra-keys bar in Console (ESC / — HOME Up
# End PGUP / TAB CTRL ALT Left Down Right PGDN; CTRL/ALT sticky;
# CTRL+D sends 0x04 so the shell exits). (2) Programs gains a
# "QPython scripts" source: SAF tree picker (persisted URI), flat .py
# listing, tap runs in Console (direct /sdcard path when readable,
# else a fresh temp copy under files/qpython_cache).
#
# v1.0.27: shared storage reachable from the guest shell
# (Termux parity). The proot -b /sdcard + /storage binds were
# already in the session argv but never worked on their own:
# the app never held the runtime storage permission on
# Console start, so proot got EACCES on every shared-storage
# read, and the guest had no ~/storage shortcut. Now:
# (1) ConsoleActivity requests READ+WRITE_EXTERNAL_STORAGE
# ONCE on Console start (short rationale; denied -> never
# re-prompt, one-line hint printed into a fresh shell);
# WRITE is included because READ alone is read-only
# (sdcard_r) and `touch /sdcard/...` needs sdcard_rw.
# (2) PrefixSetup.ensureStorageLinks creates, on every
# Console start, ~/storage -> /storage/emulated/0 and
# /sdcard -> /storage/emulated/0 symlinks in the guest
# prefix (bind fallback), plus a real <prefix>/storage dir
# as the -b /storage bind target.
#
# v1.0.19: (1) Linker-warning DISPLAY filter — the Bionic linker
# warning ("WARNING: linker: ... /linkerconfig/ld.config.txt") printed
# on every guest ELF exec looked like an error on every command, so the
# user read working ls/cd as broken. The warning is harmless, so instead
# of fabricating an ld.config.txt in the guest (risks breaking library
# loading), the app strips the exact warning sequence (+ one trailing
# newline) from the PTY output stream in TerminalSession's handler
# (LinkerWarningFilter.java, cross-chunk safe). QPYPI skips the same
# exact line. Debug shows "linker warning filter: on".
#
# v1.0.20: MULTIPLE Console sessions (tabs, cap 8). Sessions live
# in DzTerminalApp.sessions (Application scope, no service — process
# death ends them, same as Termux). Each tab = its own TerminalSession
# (own proot bash + own TerminalEmulator screen/scrollback); the tab
# bar under the header (number + close ×, "+" at the end) switches by
# re-attaching the view. RESTART restarts only the current tab.
# onTextChanged only redraws for the attached session; the ended
# banner only shows for the current tab. BACK to Home keeps sessions
# (Application holder), returning re-attaches the current tab.
#
# The output APK filename is DERIVED from VERSION_NAME below — never
# hardcoded per-version. (A hardcoded DZTerminal-1.0.2.apk once caused
# the v1.0.3 build to overwrite and lose the real v1.0.2 release file.)
set -euo pipefail
export JAVA_HOME=$HOME/jdk17
export PATH=$JAVA_HOME/bin:$PATH

VERSION_NAME="1.0.31"
VERSION_CODE=32
APK_NAME="DZTerminal-${VERSION_NAME}.apk"

SDK=~/android-sdk
BT=$SDK/build-tools/34.0.0
AAPT2=$BT/aapt2
D8=$BT/d8
ZIPALIGN=$BT/zipalign
APKSIGNER=$BT/apksigner
ANDROID_JAR=$SDK/platforms/android-34/android.jar

PROJ=~/workspace/dz-terminal
OUT=$PROJ/out
rm -rf $OUT && mkdir -p $OUT/compiled $OUT/gen $OUT/classes $OUT/dex $OUT/libtmp

echo "=== [1/7] Compiling resources ==="
$AAPT2 compile --dir $PROJ/src/main/res -o $OUT/compiled/app_res.zip
$AAPT2 compile --dir $PROJ/src/main/res-termux-view -o $OUT/compiled/tv_res.zip

echo "=== [2/7] Linking (aapt2 link) ==="
$AAPT2 link -o $OUT/base.apk \
  -I $ANDROID_JAR \
  --manifest $PROJ/src/main/AndroidManifest.xml \
  -R $OUT/compiled/app_res.zip \
  -R $OUT/compiled/tv_res.zip \
  --extra-packages com.termux.view \
  --auto-add-overlay \
  --min-sdk-version 24 \
  --target-sdk-version 28 \
  --version-code $VERSION_CODE \
  --version-name "$VERSION_NAME" \
  --java $OUT/gen
echo "R.java files: $(find $OUT/gen -name 'R.java')"

echo "=== [3/7] Compiling vendored Java (javac) ==="
CP="$ANDROID_JAR"
$JAVA_HOME/bin/javac -encoding UTF-8 -nowarn \
  -cp "$CP" \
  -d $OUT/classes \
  $(find $PROJ/src/main/java/com/termux $PROJ/src/main/java/androidx -name '*.java') \
  $(find $OUT/gen -name 'R.java')
echo "classes after javac: $(find $OUT/classes -name '*.class' | wc -l)"
[ -f $OUT/classes/com/zynelabs/dzterminal/R.class ] || { echo "FATAL: app R.class missing after javac"; exit 1; }
[ -f $OUT/classes/com/termux/view/R.class ] || { echo "FATAL: termux.view R.class missing after javac"; exit 1; }

echo "=== [4/7] Compiling app Kotlin ==="
$HOME/kotlinc/bin/kotlinc \
  -cp "$CP:$OUT/classes" \
  -d $OUT/classes \
  -jvm-target 17 \
  -no-reflect \
  $(find $PROJ/src/main/java/com/zynelabs -name '*.kt')
echo "classes after kotlinc: $(find $OUT/classes -name '*.class' | wc -l)"
[ -f $OUT/classes/com/zynelabs/dzterminal/MainActivity.class ] || { echo "FATAL: MainActivity.class missing"; exit 1; }

echo "=== [5/7] Dexing (d8) ==="
mkdir -p $OUT/allclasses
unzip -q -n $HOME/kotlinc/lib/kotlin-stdlib.jar -d $OUT/allclasses -x 'META-INF/*' || true
cp -r $OUT/classes/. $OUT/allclasses/
(cd $OUT/allclasses && zip -q -r $OUT/allclasses.jar . -x 'META-INF/*')
$D8 --min-api 24 --output $OUT/dex $OUT/allclasses.jar
ls -la $OUT/dex/

echo "=== [6/7] Packaging APK ==="
cp $OUT/base.apk $OUT/unsigned.apk
(cd $OUT/dex && zip -q -j $OUT/unsigned.apk classes*.dex)
# native libs (STORED, uncompressed) + assets.
# proot ships as lib/arm64-v8a/libdzproot.so and is exec'd from
# nativeLibraryDir. Since v1.0.17 the SESSION runs under proot
# (fake-path bind), as does the SECOND STAGE (since v1.0.13).
# The Debug "Test proot" probe also runs it (`proot --help`).
# assets/proot is only the build input (built by native-build.sh).
mkdir -p $OUT/libtmp/lib/arm64-v8a $OUT/libtmp/assets
cp $PROJ/out-native/lib/libtermux.so $OUT/libtmp/lib/arm64-v8a/
cp $PROJ/assets/proot $OUT/libtmp/lib/arm64-v8a/libdzproot.so
cp $PROJ/assets/bootstrap-aarch64.zip $OUT/libtmp/assets/
(cd $OUT/libtmp && zip -q -0 -r $OUT/unsigned.apk lib && zip -q -r $OUT/unsigned.apk assets)
$ZIPALIGN -f -p 4 $OUT/unsigned.apk $OUT/aligned.apk
$ZIPALIGN -c -p 4 $OUT/aligned.apk && echo "zipalign check OK"

echo "=== [7/7] Signing (workspace debug keystore) ==="
# NOTE: v1.0–v1.0.8 are signed with the shared workspace debug keystore
# because the zynelabs keystore password is not available to this build.
# Future updates MUST keep using this same cert or users must reinstall.
$APKSIGNER sign --ks ~/workspace/shizuku-build/manual/debug.keystore \
  --ks-pass pass:android --key-pass pass:android \
  --out $OUT/$APK_NAME $OUT/aligned.apk
$APKSIGNER verify --print-certs $OUT/$APK_NAME
cp $OUT/$APK_NAME ~/workspace/user/files/$APK_NAME
sha256sum ~/workspace/user/files/$APK_NAME
ls -la ~/workspace/user/files/$APK_NAME
echo "BUILD SUCCESS"
