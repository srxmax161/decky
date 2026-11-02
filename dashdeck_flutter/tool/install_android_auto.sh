#!/usr/bin/env bash
# Installs DashDeck on a USB-connected phone so Android Auto 17.x lists it.
#
# Android Auto hides sideloaded Car App Library apps, even with "Unknown sources" on, unless the
# phone's own package installer installed them on behalf of the Play Store. A normal `adb install`
# is started by the shell, and apps can't name another installer, so this asks the phone's installer
# to do it from the adb shell, which may. Confirm "Update"/"Install" on the phone when asked.
#
# Usage: tool/install_android_auto.sh [path/to/app-release.apk]
set -euo pipefail

cd "$(dirname "$0")/.."
APK="${1:-build/app/outputs/flutter-apk/app-release.apk}"
ADB="$(command -v adb || echo "$HOME/Library/Android/sdk/platform-tools/adb")"
PKG=com.dashdeck
REMOTE=/storage/emulated/0/Download/dashdeck-install.apk

adb() { "$ADB" -d "$@"; }  # -d: the USB phone, never an emulator

[ -f "$APK" ] || { echo "No APK at $APK. Build it first: flutter build apk --release" >&2; exit 1; }
[ "$(adb get-state 2>/dev/null)" = "device" ] || { echo "No phone found. Plug it in and allow USB debugging." >&2; exit 1; }

before=$(adb shell dumpsys package "$PKG" | grep -m1 lastUpdateTime | tr -d '\r' || true)

adb push "$APK" "$REMOTE" >/dev/null 2>&1
adb shell content call --uri content://media --method scan_file --arg "$REMOTE" >/dev/null
id=$(adb shell content query --uri content://media/external/file --projection _id \
  --where "\"_data='$REMOTE'\"" | grep -oE '_id=[0-9]+' | head -1 | cut -d= -f2)
[ -n "$id" ] || { echo "The phone didn't register the APK in its media library." >&2; exit 1; }

# A prompt left open from an earlier run would be brought back instead of a new one, installing the old APK.
adb shell am force-stop com.google.android.packageinstaller

adb shell am start -a android.intent.action.INSTALL_PACKAGE \
  -d "content://media/external/file/$id" -t application/vnd.android.package-archive \
  --grant-read-uri-permission \
  --es android.intent.extra.INSTALLER_PACKAGE_NAME com.android.vending \
  --ez android.intent.extra.NOT_UNKNOWN_SOURCE true >/dev/null

echo "Tap Update (or Install) on the phone…"
for _ in $(seq 1 60); do
  now=$(adb shell dumpsys package "$PKG" | grep -m1 lastUpdateTime | tr -d '\r' || true)
  [ -n "$now" ] && [ "$now" != "$before" ] && break
  sleep 5
done
adb shell rm -f "$REMOTE"

if [ -z "$now" ] || [ "$now" = "$before" ]; then
  echo "Nothing was installed: the Update prompt wasn't confirmed on the phone within 5 minutes." >&2
  exit 1
fi

source=$(adb shell dumpsys package "$PKG" | grep -E "installerPackageName|initiatingPackageName" | tr -d ' \r')
if echo "$source" | grep -q "installerPackageName=com.android.vending" &&
   echo "$source" | grep -q "initiatingPackageName=com.google.android.packageinstaller"; then
  echo "Installed. Android Auto will accept DashDeck."
  adb shell am force-stop com.google.android.projection.gearhead  # so it rebuilds its app list
else
  echo "The install didn't finish as expected:" >&2
  echo "$source" >&2
  exit 1
fi
