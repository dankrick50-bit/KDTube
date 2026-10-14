#!/usr/bin/env bash
# Makes the installers of all the platforms and puts them into installers/.
#
#   ./gradlew :smarttubetv:assembleStkdtubeRelease -PrustCore      (first: the apk files of the Android app)
#   packaging/make-installers.sh
#
# Runs on Windows (Git Bash): the Windows setups are compiled with the C# compiler that is a part of Windows.
#
# What it needs (environment variables, with the defaults):
#   KDTUBE_KEYSTORE        ~/.kdtube/kdtube-release.jks       the signing key. Keep it: updates need the same one.
#   KDTUBE_KEYSTORE_PASS   ~/.kdtube/kdtube-release.pass      a file with the password of the key
#   KDTUBE_KEY_ALIAS       kdtube
#   JAVA                   java                               JDK 11+
#   APKSIGNER_JAR          $ANDROID_HOME/build-tools/*/lib/apksigner.jar
#   ADB_DIR                $ANDROID_HOME/platform-tools  or  %LOCALAPPDATA%/Android/Sdk/platform-tools
#   KDTUBE_SKIP_DESKTOP    set to 1 to leave out the PC edition (Windows setup, Ubuntu packages)
#   KDTUBE_CHANGELOG       "KDTube <version>"                 what the update dialog shows, lines separated by |
set -eu

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PACKAGING="$ROOT/packaging"
RELEASE_DIR="$ROOT/smarttubetv/build/outputs/apk/stkdtube/release"
OUT="$ROOT/installers"
WORK="$ROOT/packaging/build"
# Where the app looks for its updates: see smarttubetv/src/stkdtube/res/values/update_urls.xml
UPDATE_URL="https://github.com/dankrick50-bit/KDTube/releases/download/latest"

KEYSTORE="${KDTUBE_KEYSTORE:-$HOME/.kdtube/kdtube-release.jks}"
KEYSTORE_PASS="${KDTUBE_KEYSTORE_PASS:-$HOME/.kdtube/kdtube-release.pass}"
KEY_ALIAS="${KDTUBE_KEY_ALIAS:-kdtube}"
JAVA="${JAVA:-java}"
CSC="/c/Windows/Microsoft.NET/Framework64/v4.0.30319/csc.exe"

fail() { echo "ERROR: $*" >&2; exit 1; }
win() { cygpath -w "$1"; }

VERSION="$(grep -m 1 -o 'versionName "[^"]*"' "$ROOT/smarttubetv/build.gradle" | cut -d'"' -f2)"
VERSION_CODE="$(grep -m 1 -o 'versionCode [0-9]*' "$ROOT/smarttubetv/build.gradle" | cut -d' ' -f2)"
STAMP="$(date +%Y%m%d-%H%M)"
[ -n "$VERSION" ] && [ -n "$VERSION_CODE" ] || fail "Can't read the version from smarttubetv/build.gradle"

if [ -z "${APKSIGNER_JAR:-}" ]; then
    APKSIGNER_JAR="$(ls "${ANDROID_HOME:-/nonexistent}"/build-tools/*/lib/apksigner.jar 2>/dev/null | sort | tail -n 1)"
fi
if [ -z "${ADB_DIR:-}" ]; then
    for dir in "${ANDROID_HOME:-/nonexistent}/platform-tools" "${LOCALAPPDATA:-/nonexistent}/Android/Sdk/platform-tools"; do
        [ -f "$dir/adb.exe" ] && ADB_DIR="$dir" && break
    done
fi

[ -f "$KEYSTORE" ] || fail "No signing key: $KEYSTORE"
[ -f "${APKSIGNER_JAR:-}" ] || fail "apksigner.jar isn't found: set APKSIGNER_JAR"
[ -f "${ADB_DIR:-}/adb.exe" ] || fail "adb isn't found: set ADB_DIR"
[ -f "$CSC" ] || fail "The C# compiler of Windows isn't found: $CSC"
[ -f "$RELEASE_DIR/KDTube_${VERSION}_universal.apk" ] || fail "Build the release first (see the top of this file)"

# One version in the folder: what the run before left there goes (the files this script makes, nothing else)
if [ -d "$OUT" ]; then
    find "$OUT" -maxdepth 3 -type f \( -name 'KDTube-*.apk' -o -name 'KDTube-*.exe' -o -name 'KDTube-*.deb' -o -name 'KDTube-*.tar.gz' \
        -o -name 'kdtube_*.apk' -o -name 'kdtube.json' -o -name 'kdtube-pc*' -o -name 'README.txt' -o -name 'README.md' \) -delete
fi
# The same for the signed files of other versions: the TV installers pack what is in that folder
if [ -d "$WORK/apk" ]; then
    find "$WORK/apk" -maxdepth 1 -type f -name 'KDTube-*' ! -name "KDTube-$VERSION-*" -delete
fi
mkdir -p "$WORK/apk" "$OUT/Android/From-a-PC" "$OUT/FireOS/Fire-TV-Stick-4K" "$OUT/FireOS/Fire-HD-10-2023" "$OUT/Windows" "$OUT/Ubuntu" "$OUT/Updates"

echo "== KDTube $VERSION ($STAMP)"
echo "== signing"
for abi in arm64-v8a armeabi-v7a x86 universal; do
    "$JAVA" -jar "$(win "$APKSIGNER_JAR")" sign --ks "$(win "$KEYSTORE")" --ks-key-alias "$KEY_ALIAS" --ks-pass "file:$(win "$KEYSTORE_PASS")" \
        --out "$(win "$WORK/apk/KDTube-$VERSION-$abi.apk")" "$(win "$RELEASE_DIR/KDTube_${VERSION}_$abi.apk")"
    "$JAVA" -jar "$(win "$APKSIGNER_JAR")" verify "$(win "$WORK/apk/KDTube-$VERSION-$abi.apk")" || fail "The signature of $abi doesn't verify"
done

echo "== Android, Fire OS"
cp "$WORK/apk/KDTube-$VERSION-universal.apk" "$OUT/Android/KDTube-android-v$VERSION-$STAMP.apk"
cp "$WORK/apk/KDTube-$VERSION-arm64-v8a.apk" "$OUT/Android/KDTube-android-arm64-v$VERSION-$STAMP.apk"
cp "$WORK/apk/KDTube-$VERSION-armeabi-v7a.apk" "$OUT/Android/KDTube-android-arm32-v$VERSION-$STAMP.apk"
# Fire TV runs 32-bit apps only (Stick 4K: 1.5-2 GB of RAM). The Fire HD 10 of 2023 is 64-bit (3 GB of RAM):
# the 64-bit build is the faster one there.
cp "$WORK/apk/KDTube-$VERSION-armeabi-v7a.apk" "$OUT/FireOS/Fire-TV-Stick-4K/KDTube-fireos-firetv-stick-4k-v$VERSION-$STAMP.apk"
cp "$WORK/apk/KDTube-$VERSION-arm64-v8a.apk" "$OUT/FireOS/Fire-HD-10-2023/KDTube-fireos-fire-hd-10-2023-v$VERSION-$STAMP.apk"

echo "== the update channel"
# The names are fixed: the app asks for them (see the list below)
for abi in arm64-v8a armeabi-v7a x86; do
    cp "$WORK/apk/KDTube-$VERSION-$abi.apk" "$OUT/Updates/kdtube_$abi.apk"
done
# What the update dialog of the app shows. KDTUBE_CHANGELOG="first line|second line"
CHANGELOG="$(printf '%s' "${KDTUBE_CHANGELOG:-KDTube $VERSION}" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' -e 's/|/", "/g')"
cat > "$OUT/Updates/kdtube.json" <<EOF
{
    "package": {
        "downloadUrlList": [
            "$UPDATE_URL/kdtube_armeabi-v7a.apk"
        ],
        "downloadUrlList_arm64-v8a": [
            "$UPDATE_URL/kdtube_arm64-v8a.apk"
        ],
        "downloadUrlList_x86": [
            "$UPDATE_URL/kdtube_x86.apk"
        ]
    },

    "$VERSION": {
        "versionCode": $VERSION_CODE,
        "changelog": ["$CHANGELOG"]
    }
}
EOF

echo "== tools that send the app from a PC to a TV"
ICON_PNG="$ROOT/smarttubetv/src/stkdtube/res/mipmap-nodpi/app_icon.png"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$(win "$PACKAGING/windows/make-ico.ps1")" -Png "$(win "$ICON_PNG")" -Ico "$(win "$WORK/kdtube.ico")"

cat > "$WORK/BuildInfo.cs" <<EOF
internal static class BuildInfo
{
    public const string Version = "$VERSION";
    public const string AssemblyVersion = "$VERSION.0.0";
}
EOF

SETUP="$OUT/Android/From-a-PC/KDTube-tv-installer-windows-x64-v$VERSION-$STAMP.exe"
RESOURCES=()
for abi in arm64-v8a armeabi-v7a x86; do
    RESOURCES+=("-resource:$(win "$WORK/apk/KDTube-$VERSION-$abi.apk"),apk/$abi")
done
for name in adb.exe AdbWinApi.dll AdbWinUsbApi.dll libwinpthread-1.dll NOTICE.txt; do
    [ -f "$ADB_DIR/$name" ] || fail "Missing $ADB_DIR/$name"
    RESOURCES+=("-resource:$(win "$ADB_DIR/$name"),adb/$name")
done

MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' "$CSC" -nologo -target:winexe -platform:anycpu -optimize+ \
    "-out:$(win "$SETUP")" "-win32icon:$(win "$WORK/kdtube.ico")" \
    -reference:System.dll -reference:System.Drawing.dll -reference:System.Windows.Forms.dll -reference:System.Core.dll \
    "${RESOURCES[@]}" "$(win "$PACKAGING/windows/KDTubeSetup.cs")" "$(win "$WORK/BuildInfo.cs")"

DEB="$OUT/Android/From-a-PC/KDTube-tv-installer-ubuntu-all-v$VERSION-$STAMP.deb"
node "$(win "$PACKAGING/linux/make-deb.js")" --version "$VERSION" --out "$(win "$DEB")" --apk-dir "$(win "$WORK/apk")" \
    --script "$(win "$PACKAGING/linux/install-kdtube.sh")" --icon "$(win "$ICON_PNG")"

if [ "${KDTUBE_SKIP_DESKTOP:-}" != "1" ]; then
    echo "== the PC edition (Windows, Ubuntu)"
    # NOTE: a program that is built on the same runtime (an editor, e.g.) may leave this variable behind
    env -u ELECTRON_RUN_AS_NODE node "$(win "$PACKAGING/desktop/build-desktop.js")" --out "$(win "$OUT")" --stamp "$STAMP"
fi

echo "== the instructions"
readme() { sed -e "s/@VERSION@/$VERSION/g" -e "s/@STAMP@/$STAMP/g" "$PACKAGING/readme/$1" > "$2"; }
readme README.md "$OUT/README.md"
readme Windows.txt "$OUT/Windows/README.txt"
readme Ubuntu.txt "$OUT/Ubuntu/README.txt"
readme Android.txt "$OUT/Android/README.txt"
readme FireOS.txt "$OUT/FireOS/README.txt"
readme Updates.txt "$OUT/Updates/README.txt"

echo "== done"
find "$OUT" -type f -size +100k -printf '%s %P\n' | sort -k 2 | awk '{ size = $1; sub(/^[0-9]+ /, ""); printf "%8.1f MB  %s\n", size / 1048576, $0 }'
