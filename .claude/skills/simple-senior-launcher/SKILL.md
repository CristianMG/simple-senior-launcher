---
name: simple-senior-launcher
description: Set up an Android phone (especially Xiaomi/MIUI) for an elderly person with adb. Covers backup, reversible debloat, installing the senior launcher in this repo (big tiles, simple phone/contacts, swipe-free big call screen, guardian that blocks Settings and the status bar), MIUI permission gotchas, and on-device verification. Use when asked to simplify, debloat, lock down or "make easy" a phone for a parent/grandparent, or to redeploy this launcher.
---

# Simple senior launcher: phone setup workflow

Goal: the person can call, answer calls, use WhatsApp, add a contact and search Google, and **cannot break settings by accident**. Never delete anything that can't be restored.

## 0. Recon
```bash
adb devices -l
adb shell getprop ro.product.model; adb shell getprop ro.build.version.release; adb shell getprop ro.miui.ui.version.name
adb shell pm list packages -3              # user apps
adb shell telecom get-default-dialer
adb shell dumpsys content | grep com.android.contacts   # are contacts synced to Google?
```

## 1. Backup (always first)
- `adb shell settings list system|secure|global`, `dumpsys package`, `pm list packages -f`
- `adb pull` each of `/sdcard/DCIM /sdcard/Pictures /sdcard/WhatsApp /sdcard/Download` **separately** (if one fails in an `&&` chain, the rest are skipped)
- APKs: `for p in $(pm list packages -3) → pm path → adb pull`
- Keep the backup **outside git** (`backup/` is gitignored). It holds private media.

## 2. Debloat (reversible)
```bash
scripts/debloat.sh                       # scripts/bloat.txt (MIUI ads/telemetry, Mi apps, stubs)
scripts/debloat.sh scripts/bloat_preloads.txt
scripts/restore.sh <pkg>|all             # cmd package install-existing
```
- Uses `pm uninstall -k --user 0`. Inside `while read` loops use `adb shell -n` (otherwise stdin is consumed and only the first package runs).
- Find factory preloads by `firstInstallTime` ≈ setup time, then check real use: `dumpsys usagestats | grep "package=<pkg>.*lastTimeUsed"`. Remove only apps with no usage.
- Protected packages (`-1000`, e.g. `com.miui.android.fashiongallery`): `pm disable-user --user 0`.
- Never remove `com.miui.home` (fallback launcher), `com.miui.securitycenter`, `com.lbe.security.miui`, the dialer, or the camera.
- Ask before removing apps the person installed (banking, utilities). Hide them in the launcher instead.

## 3. Build & install
```bash
cd launcher && ./gradlew assembleRelease && cd ..
scripts/setup_launcher.sh
```
MIUI blocks this until Developer options → **Install via USB** is on. `pm grant` and `settings put secure` also need **USB debugging (Security settings)**, which requires a SIM and a Mi account. If that toggle can't be enabled, the app asks for its permissions at runtime and the user accepts them on screen.

## 4. Manual MIUI steps (guide the user)
The guardian blocks Settings and Security Center, so have the user enter **admin mode** first (long-press the clock, PIN default `1234`). Then:
```bash
# Other permissions: lock screen (op 10020) + background pop-ups (op 10021) = Allow
adb shell am start -n com.miui.securitycenter/com.miui.permcenter.permissions.PermissionsEditorActivity --es extra_pkgname es.papa.launcher
# Autostart (op 10008)
adb shell am start -n com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity
# Accessibility service, if it couldn't be set via adb
adb shell am start -a android.settings.ACCESSIBILITY_SETTINGS
```
Verify:
```bash
adb shell appops get es.papa.launcher | grep MIUIOP    # 10008/10020/10021 must be allow
adb shell dumpsys accessibility | grep -i papa         # GuardService bound
adb shell settings get secure enabled_notification_listeners
adb shell dumpsys package es.papa.launcher | grep -E "(PHONE|CALL_LOG|CONTACTS).*granted"
```

## 5. Verify on the device
- Screenshots: `adb exec-out screencap -p > shot.png`, then look at them. The first frame after install can be blank (cold start), so retake it.
- Open the internal screens without touch injection:
  `adb shell am start -n es.papa.launcher/.MainActivity --es open keypad|contacts|recent|add --es phone 600000000`
- Guardian: `adb shell am start -a android.settings.SETTINGS`, then `mResumedActivity` must be the launcher.
- Shade overlay: `dumpsys window windows | grep ty=2032`. Ask the user to try pulling the shade down.
- Big call screen: ask someone to call, take a screenshot while it rings, and confirm `CallActivity` is resumed. The stock heads-up covers the top ~150 dp while ringing; that area is left empty on purpose.
- Badges: send a WhatsApp → red dot. Missed call → red card in Phone → "seen" clears the dot.
- The launcher must be the **default phone app** (role DIALER) so only its `InCallService` UI shows. Covering the stock dialer from outside caused a flicker war on the lock screen. Check with `adb shell dumpsys role | grep -A1 role.DIALER` (it must list `es.papa.launcher`). `cmd role add-role-holder` works but applies a few seconds later; `telecom set-default-dialer` is only a temporary override that makes `defaultDialerPackage` look right.
- **After a reboot**, on MIUI `dumpsys role` may list the stock dialer again while `adb shell telecom get-default-dialer` returns the launcher. Telecom decides which call UI is used, so the real check is a call with the phone locked after rebooting. If the stock UI comes back, set it again from admin mode → *Default phone app* → Activate.
- **Never reinstall during a call**: check `adb shell dumpsys telephony.registry | grep mCallState` (0 = idle).

## 6. Hand-off checklist for the family
- Change the admin PIN, add favourites (photos come from the contacts)
- Explain: long-press the clock = configuration; it auto-locks after 15 min
- Keep `backup/` somewhere safe and private

## Design rules for changes
- Huge targets (≥ 64 dp), text ≥ 22 sp, high contrast, one action per button, no swipes, no long-press for the person's own actions
- The font scale is fixed inside the app (`Density(d.density, 1f)`), so size things explicitly
- Prefer in-app screens over stock apps when the stock UI is complex
- Every destructive action must be reversible or confirmed
