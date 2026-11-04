#!/bin/bash
# Install + configure the launcher on a connected device.
# MIUI: needs Developer options -> "Install via USB" ON.
# Steps that need "USB debugging (Security settings)" are best-effort; the app also asks at runtime.
cd "$(dirname "$0")/.."
P=es.papa.launcher
APK=launcher/app/build/outputs/apk/release/app-release.apk
[ -f "$APK" ] || (cd launcher && ./gradlew -q assembleRelease)
adb install -r "$APK" || exit 1

try() { adb shell "$@" >/dev/null 2>&1 && echo "ok   $*" || echo "skip $*  (do it by hand)"; }
for perm in READ_CONTACTS WRITE_CONTACTS CALL_PHONE READ_PHONE_STATE READ_CALL_LOG WRITE_CALL_LOG ANSWER_PHONE_CALLS; do
  try pm grant $P android.permission.$perm
done
try settings put secure enabled_accessibility_services $P/$P.GuardService
try settings put secure accessibility_enabled 1
try cmd notification allow_listener $P/$P.NotifService
try dumpsys deviceidle whitelist +$P
try cmd package set-home-activity $P/.MainActivity
try appops set $P SYSTEM_ALERT_WINDOW allow
# Comfort
try settings put system accelerometer_rotation 0
try settings put system screen_off_timeout 120000
adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null
cat <<'TXT'

Manual steps on MIUI (long-press the clock + PIN first, so the guard lets you into Settings):
  1. Accept the runtime permission dialogs (calls, contacts, call log).
  2. Settings > Accessibility > Downloaded apps > "Guardián Inicio Papá" > ON
  3. App permissions > Other permissions: "Show on lock screen" + "Display pop-up windows while running in background" = Allow
     adb shell am start -n com.miui.securitycenter/com.miui.permcenter.permissions.PermissionsEditorActivity --es extra_pkgname es.papa.launcher
  4. Autostart = ON
     adb shell am start -n com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity
TXT
