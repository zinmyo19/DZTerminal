#!/bin/bash
# Native builds for DZ Terminal: libtermux.so (PTY JNI), libtalloc.a,
# libandroid-shmem.a, proot (termux fork). All for Android arm64 (+arm 32-bit loader).
set -euo pipefail

PROJ=~/workspace/dz-terminal
DL=$PROJ/downloads
NDK=$DL/ndk/android-ndk-r27d
BIN=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
CLANG64=$BIN/aarch64-linux-android24-clang
CLANG32=$BIN/armv7a-linux-androideabi24-clang
OUT=$PROJ/out-native
mkdir -p $OUT/lib $OUT/bin

# --- compiler wrapper: -m32 maps to the 32-bit NDK clang ---
cat > $OUT/cc-wrap.sh <<EOF
#!/bin/bash
args=()
use32=0
for a in "\$@"; do
  if [ "\$a" = "-m32" ]; then use32=1; else args+=("\$a"); fi
done
if [ \$use32 -eq 1 ]; then
  exec "$CLANG32" "\${args[@]}"
else
  exec "$CLANG64" "\${args[@]}"
fi
EOF
chmod +x $OUT/cc-wrap.sh
# NOTE: host `readelf` already handles aarch64 ELF (verified on bootstrap
# binaries), so proot's loader-info step uses the system readelf.

echo "=== [1/4] libtermux.so (PTY JNI) ==="
$CLANG64 -shared -fPIC -O2 -o $OUT/lib/libtermux.so $PROJ/jni/termux.c
file $OUT/lib/libtermux.so

echo "=== [2/4] libtalloc.a (talloc.c only; replace.c not needed) ==="
cd $DL/talloc-2.4.2
$CLANG64 -c -O2 -fPIC -I. -Ilib/replace talloc.c -o $OUT/talloc.o
$BIN/llvm-ar rcs $OUT/lib/libtalloc.a $OUT/talloc.o
ls -la $OUT/lib/libtalloc.a

echo "=== [3/4] libandroid-shmem.a ==="
cd $DL/libandroid-shmem-master
$CLANG64 -c -O2 -fPIC -D_GNU_SOURCE -D_PATH_TMP='"/tmp/"' -include fcntl.h shmem.c -o $OUT/shmem.o
$BIN/llvm-ar rcs $OUT/lib/libandroid-shmem.a $OUT/shmem.o
ls -la $OUT/lib/libandroid-shmem.a

echo "=== [4/4] proot (termux fork) ==="
cd $DL/proot-5.1.107.98/src
mkdir -p $OUT/include/sys
cp $DL/talloc-2.4.2/talloc.h $OUT/include/talloc.h
cp $DL/libandroid-shmem-master/shm.h $OUT/include/sys/shm.h
export PATH=$OUT/bin:$BIN:$PATH
# NOTE: CPPFLAGS/CFLAGS/LDFLAGS must go in the ENVIRONMENT, not on the make
# command line: a command-line value replaces the Makefile's `+=` appends
# (which carry the vital -I. -I$(VPATH) -D_GNU_SOURCE flags). Environment
# values get the Makefile's `+=` appends added to them.
# NOTE: Do NOT pass -DVERSION here: the quoting needed for a string-literal
# -D breaks across the make->shell->compiler layers and swallows the
# following -I flags (that was the cli/note.h failure). proot.h already
# defaults VERSION to "5.1.0" when undefined, which is fine for our use
# (version string only, shown by --version).
export CPPFLAGS="-DARG_MAX=131072 -I$OUT/include"
export CFLAGS="-Wall -Wextra -O2"
export LDFLAGS="-L$OUT/lib -ltalloc -landroid-shmem -llog -Wl,-z,noexecstack"
make -f GNUmakefile clean || true
make -f GNUmakefile -j"$(nproc)" \
  CC=$OUT/cc-wrap.sh \
  LD=$OUT/cc-wrap.sh \
  STRIP=$BIN/llvm-strip \
  OBJCOPY=$BIN/llvm-objcopy \
  OBJDUMP=$BIN/llvm-objdump \
  PROOT_WITH_LIBANDROID_SHMEM=true
ls -la proot
file proot
cp proot $PROJ/assets/proot
echo "proot copied to assets/"
echo "ALL NATIVE BUILDS DONE"
