#!/usr/bin/env bash
# Installs KDTube on an Android TV, a Fire TV, a phone/tablet or an Android emulator from Ubuntu (or any Linux/macOS).
#
#   ./install-kdtube.sh                     asks for the address of the device (or uses the one that is connected)
#   ./install-kdtube.sh 192.168.1.50        a device in the network (see README.txt how to find its address)
#   ./install-kdtube.sh --waydroid          Waydroid on this computer (Android apps on the Linux desktop)
#   ./install-kdtube.sh emulator-5554 --no-launch
#
# Needs the folder "apk" next to this file.
set -u

PACKAGE_NAME="com.kdtube.app"
LAUNCH_ACTIVITY="$PACKAGE_NAME/com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APK_DIR="$HERE/apk"
DEVICE=""
WAYDROID=0
LAUNCH=1
ASSUME_YES=0
ADB=""

for arg in "$@"; do
    case "$arg" in
        --waydroid) WAYDROID=1 ;;
        --no-launch) LAUNCH=0 ;;
        -y|--yes) ASSUME_YES=1 ;;
        -h|--help) sed -n '2,9p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        -*) echo "Unknown option: $arg" >&2; exit 2 ;;
        *) DEVICE="$arg" ;;
    esac
done

fail() {
    echo >&2
    echo "NOT INSTALLED: $*" >&2
    exit 1
}

confirm() {
    [ "$ASSUME_YES" = 1 ] && return 0
    local answer
    read -r -p "$1 [y/N] " answer
    case "$answer" in y|Y|yes|Yes) return 0 ;; *) return 1 ;; esac
}

find_apk() { # $1 - processor type
    ls "$APK_DIR"/KDTube-*-"$1".apk 2>/dev/null | head -n 1
}

install_waydroid() {
    command -v waydroid >/dev/null 2>&1 || fail "Waydroid isn't installed. See https://docs.waydro.id/usage/install-on-desktops"
    waydroid status 2>/dev/null | grep -q "RUNNING" || fail "Waydroid isn't running. Start it first: waydroid session start (or open Waydroid from the menu)."

    local abis abi apk=""
    abis="$(waydroid prop get ro.product.cpu.abilist 2>/dev/null | tr -d '\r')"
    [ -n "$abis" ] || abis="x86"

    for abi in ${abis//,/ }; do
        apk="$(find_apk "$abi")"
        [ -n "$apk" ] && break
    done

    [ -n "$apk" ] || fail "No build for this Waydroid image (it reports: $abis). Builds in the folder: arm64-v8a, armeabi-v7a, x86."

    echo "Installing $(basename "$apk") into Waydroid ..."
    waydroid app install "$apk" || fail "Waydroid refused the app."
    [ "$LAUNCH" = 1 ] && waydroid app launch "$PACKAGE_NAME" >/dev/null 2>&1
    echo "KDTube is installed. It is in the list of the Waydroid apps."
    exit 0
}

find_adb() {
    if command -v adb >/dev/null 2>&1; then ADB="$(command -v adb)"; return; fi
    if [ -x "$HERE/platform-tools/adb" ]; then ADB="$HERE/platform-tools/adb"; return; fi
    if [ -n "${ANDROID_HOME:-}" ] && [ -x "$ANDROID_HOME/platform-tools/adb" ]; then ADB="$ANDROID_HOME/platform-tools/adb"; return; fi

    echo "The Android tool 'adb' is needed to talk to the device and it isn't on this computer."
    echo "It can be installed with: sudo apt install adb"
    confirm "Or download it from Google (about 7 MB) into this folder now?" || fail "adb is required."

    local os="linux"
    [ "$(uname -s)" = "Darwin" ] && os="darwin"
    command -v unzip >/dev/null 2>&1 || fail "'unzip' is needed for that: sudo apt install unzip (or install adb with apt)."
    curl -fL -o "$HERE/platform-tools.zip" "https://dl.google.com/android/repository/platform-tools-latest-$os.zip" || fail "The download failed."
    unzip -q -o "$HERE/platform-tools.zip" -d "$HERE" || fail "Can't unpack the download."
    rm -f "$HERE/platform-tools.zip"
    ADB="$HERE/platform-tools/adb"
}

resolve_target() {
    if [ -z "$DEVICE" ]; then
        local connected count
        connected="$("$ADB" devices | awk 'NR > 1 && ($2 == "device" || $2 == "unauthorized" || $2 == "offline") {print $1}')"
        count="$(printf '%s\n' "$connected" | grep -c .)"

        if [ "$count" = 1 ]; then
            DEVICE="$connected"
            echo "Using the connected device: $DEVICE"
            return
        elif [ "$count" -gt 1 ]; then
            echo "Several devices are connected:"
            printf '%s\n' "$connected" | nl -w 3 -s ') '
            local choice
            read -r -p "Number of the device: " choice
            DEVICE="$(printf '%s\n' "$connected" | sed -n "${choice}p")"
            [ -n "$DEVICE" ] || fail "No such device."
            return
        fi

        read -r -p "Address of the device in your network (e.g. 192.168.1.50): " DEVICE
        [ -n "$DEVICE" ] || fail "No device given."
    fi

    # An address: connect over the network. Anything else is a serial number of a device that is attached already.
    if printf '%s' "$DEVICE" | grep -Eq '^[0-9]{1,3}(\.[0-9]{1,3}){3}(:[0-9]+)?$'; then
        printf '%s' "$DEVICE" | grep -q ':' || DEVICE="$DEVICE:5555"
        echo "Connecting to $DEVICE ..."
        local answer
        answer="$("$ADB" connect "$DEVICE" 2>&1)"
        echo "  $answer"
        if printf '%s' "$answer" | grep -Eqi 'refused|failed|unable|cannot'; then
            fail "Can't connect to $DEVICE. Check the address and that 'ADB debugging' (or 'USB debugging' / 'Network debugging') is switched on in the developer options of the device."
        fi
    fi
}

wait_authorized() {
    local told=0 i
    for i in $(seq 1 60); do
        [ "$("$ADB" -s "$DEVICE" get-state 2>/dev/null)" = "device" ] && return
        if [ "$told" = 0 ]; then
            echo "Look at the screen of the device: it asks to allow debugging from this computer. Choose 'Always allow' and OK."
            told=1
        fi
        sleep 2
    done
    fail "The device didn't allow the connection (waited 2 minutes)."
}

install_device() {
    local abis abi apk="" answer
    abis="$("$ADB" -s "$DEVICE" shell getprop ro.product.cpu.abilist | tr -d '\r')"

    for abi in ${abis//,/ }; do
        apk="$(find_apk "$abi")"
        if [ -n "$apk" ]; then
            echo "Processor type of the device: $abi"
            break
        fi
    done

    [ -n "$apk" ] || fail "No build for this device (it reports: $abis). Builds in the folder: arm64-v8a, armeabi-v7a, x86."

    echo "Installing $(basename "$apk") (takes a minute over Wi-Fi) ..."
    answer="$("$ADB" -s "$DEVICE" install -r "$apk" 2>&1)"

    if printf '%s' "$answer" | grep -Eq 'INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match'; then
        echo "A build of KDTube that was signed with another key is installed already."
        echo "It has to be removed first. Its settings and sign-in on the device will be lost."
        confirm "Remove it and install KDTube?" || fail "Nothing was changed on the device."
        "$ADB" -s "$DEVICE" uninstall "$PACKAGE_NAME" >/dev/null 2>&1
        answer="$("$ADB" -s "$DEVICE" install -r "$apk" 2>&1)"
    fi

    if ! printf '%s' "$answer" | grep -q 'Success'; then
        printf '%s' "$answer" | grep -q 'INSTALL_FAILED_INSUFFICIENT_STORAGE' && fail "Not enough free space on the device (about 150 MB is needed)."
        fail "The device refused the app: $answer"
    fi

    # Compile the app for the processor of the device right now. Otherwise the first days it runs slower
    # (Android does this later by itself, when the device is idle).
    echo "Optimizing the app for this device (a minute or two) ..."
    "$ADB" -s "$DEVICE" shell cmd package compile -m speed -f "$PACKAGE_NAME" 2>&1 | grep -q Success || echo "  skipped (the device will do it later by itself)"

    if [ "$LAUNCH" = 1 ]; then
        "$ADB" -s "$DEVICE" shell am start -n "$LAUNCH_ACTIVITY" >/dev/null 2>&1
        echo "KDTube is installed and started on the device."
    else
        echo "KDTube is installed."
    fi
}

[ -d "$APK_DIR" ] || fail "The folder 'apk' is missing next to this script."
[ "$WAYDROID" = 1 ] && install_waydroid

find_adb
resolve_target
wait_authorized
install_device
