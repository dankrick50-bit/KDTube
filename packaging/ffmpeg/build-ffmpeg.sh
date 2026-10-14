#!/usr/bin/env bash
# Builds the native part of the player's FFmpeg extension: open source decoders for the sound formats
# many devices have no decoder for (Dolby Digital, Dolby Digital Plus, Dolby TrueHD, DTS, DTS-HD).
#
#   NDK_DIR=<path to an Android NDK, r21 or newer> packaging/ffmpeg/build-ffmpeg.sh [abi ...]
#
# The result is exoplayer-amzn-2.10.6/extensions/ffmpeg/src/main/jniLibs/<abi>/libffmpeg.so
# The libraries are a part of the repository: run this again only to change the version of FFmpeg or
# the list of the decoders. Without the libraries the app still builds: it just has no such decoders.
#
# Works on Linux, macOS and Windows (Git Bash: the 'make' of the NDK is used, nothing else is needed).
# FFmpeg is built with its default (LGPL) configuration and with the decoders below only.
set -eu

FFMPEG_VERSION="${FFMPEG_VERSION:-4.4.8}" # the last branch that still has libavresample, which the glue code uses
DECODERS="ac3 eac3 truehd mlp dca"
ABIS="${*:-armeabi-v7a arm64-v8a x86}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK="${FFMPEG_WORK_DIR:-$ROOT/packaging/build/ffmpeg}"
EXTENSION="$ROOT/exoplayer-amzn-2.10.6/extensions/ffmpeg/src/main"
SOURCE="$WORK/ffmpeg-$FFMPEG_VERSION"

fail() { echo "ERROR: $*" >&2; exit 1; }

[ -n "${NDK_DIR:-}" ] && [ -d "$NDK_DIR/toolchains/llvm/prebuilt" ] || fail "Set NDK_DIR to an Android NDK (r21 or newer)"

EXE=""
MAKE="make"
case "$(uname -s)" in
    Linux) HOST_TAG="linux-x86_64" ;;
    Darwin) HOST_TAG="darwin-x86_64" ;;
    MINGW*|MSYS*|CYGWIN*)
        HOST_TAG="windows-x86_64"
        EXE=".exe"
        MAKE="$NDK_DIR/prebuilt/windows-x86_64/bin/make.exe"
        # The tools of the NDK are Windows programs: give them Windows paths
        NDK_DIR="$(cygpath -m "$NDK_DIR")"
        ;;
    *) fail "Unknown host system" ;;
esac

TOOLS="$NDK_DIR/toolchains/llvm/prebuilt/$HOST_TAG/bin"
[ -f "$TOOLS/clang$EXE" ] || fail "No compiler in $TOOLS"
JOBS="$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)"

mkdir -p "$WORK"

if [ ! -f "$SOURCE/configure" ]; then
    echo "== downloading FFmpeg $FFMPEG_VERSION"
    curl -fL -o "$WORK/ffmpeg-$FFMPEG_VERSION.tar.bz2" "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.bz2"
    tar -xjf "$WORK/ffmpeg-$FFMPEG_VERSION.tar.bz2" -C "$WORK"
fi

DECODER_FLAGS=""
for decoder in $DECODERS; do
    DECODER_FLAGS="$DECODER_FLAGS --enable-decoder=$decoder"
done

# NOTE: built inside of the source folder on purpose: all the paths of the makefiles stay relative,
# which is what the 'make' of the NDK for Windows needs.
cd "$SOURCE"

# configure compiles small test programs in TMPDIR. A relative folder is a path that both the shell
# and the (Windows) tools of the NDK understand.
mkdir -p fftmp
export TMPDIR=fftmp

for abi in $ABIS; do
    case "$abi" in
        armeabi-v7a) ARCH="arm"; CPU="armv7-a"; TRIPLE="armv7a-linux-androideabi21"; EXTRA="" ; LINK="" ;;
        arm64-v8a) ARCH="aarch64"; CPU="armv8-a"; TRIPLE="aarch64-linux-android21"; EXTRA=""
            # Android 15+: devices with 16 KB memory pages
            LINK="-Wl,-z,max-page-size=16384" ;;
        # The hand written assembly of x86 doesn't fit position independent code with this compiler
        x86) ARCH="x86"; CPU="i686"; TRIPLE="i686-linux-android21"; EXTRA="--disable-asm"; LINK="" ;;
        x86_64) ARCH="x86_64"; CPU="x86-64"; TRIPLE="x86_64-linux-android21"; EXTRA="--disable-asm"; LINK="-Wl,-z,max-page-size=16384" ;;
        *) fail "Unknown ABI: $abi" ;;
    esac

    echo "== $abi: configure"
    [ -f config.h ] && "$MAKE" distclean >/dev/null 2>&1 || true

    # shellcheck disable=SC2086
    ./configure \
        --target-os=android --enable-cross-compile --arch="$ARCH" --cpu="$CPU" \
        --cc="$TOOLS/clang$EXE --target=$TRIPLE" \
        --ar="$TOOLS/llvm-ar$EXE" --nm="$TOOLS/llvm-nm$EXE" --ranlib="$TOOLS/llvm-ranlib$EXE" --strip="$TOOLS/llvm-strip$EXE" \
        --enable-static --disable-shared --enable-pic \
        --disable-everything --disable-programs --disable-doc --disable-debug --disable-autodetect \
        --disable-avdevice --disable-avformat --disable-swscale --disable-postproc --disable-avfilter \
        --disable-swresample --enable-avresample --disable-symver --disable-network \
        --extra-cflags="-ffunction-sections -fdata-sections" \
        $DECODER_FLAGS $EXTRA

    echo "== $abi: make"
    "$MAKE" -j"$JOBS"

    echo "== $abi: link"
    mkdir -p "$EXTENSION/jniLibs/$abi"
    # NOTE: -idirafter and not -I: FFmpeg has a file named VERSION, which a file system that ignores
    # the case of names (Windows, macOS) would hand to the C++ library when it asks for <version>
    # shellcheck disable=SC2086
    "$TOOLS/clang++$EXE" --target="$TRIPLE" -shared -fPIC -O2 -fno-exceptions -fno-rtti -nostdlib++ \
        -Wno-deprecated-declarations -idirafter . "$EXTENSION/jni/ffmpeg_jni.cc" -o "$EXTENSION/jniLibs/$abi/libffmpeg.so" \
        -Llibavcodec -Llibavresample -Llibavutil -lavcodec -lavresample -lavutil -llog -lm \
        -Wl,-Bsymbolic -Wl,--gc-sections -Wl,--exclude-libs,ALL $LINK
    "$TOOLS/llvm-strip$EXE" --strip-unneeded "$EXTENSION/jniLibs/$abi/libffmpeg.so"
    ls -la "$EXTENSION/jniLibs/$abi/libffmpeg.so" | awk '{print "   " $5 " bytes  " $9}'
done

"$MAKE" distclean >/dev/null 2>&1 || true
echo "== done"
