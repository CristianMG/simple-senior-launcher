# simple-senior-launcher

An Android launcher and setup kit that turns a cluttered phone into something an elderly person can use without help, and without accidentally breaking it.

> 🇪🇸 Resumen en español al final.

Built in one "vibe hacking" session for a family member on a **Xiaomi Redmi 9C (MIUI 12, Android 10, 2 GB RAM)**, but the launcher works on any Android 8+ phone.

---

## The problem

The phone belonged to an older person who only really needs to:

- call people (favourites, a random number, call back a missed call)
- use WhatsApp
- add a new contact now and then
- search Google
- open one or two apps (e.g. a car marketplace)

What actually happened:

| Pain point | Why |
|---|---|
| Got lost on the home screen | Dozens of preinstalled apps (Mi Browser, GetApps, Netflix, Facebook, LinkedIn, AliExpress, …), ads, wallpaper carousel |
| Changed settings by accident | Pulling down the status bar turns on airplane mode, kills Wi-Fi or data; Settings is one tap away |
| Couldn't answer calls | When the phone is unlocked, incoming calls show as a small heads-up card; on the lock screen you have to **swipe** |
| Google Contacts too complex | Only needs search, call and "add name + number" |
| A red dot on the phone app, but nothing inside | The missed-call notification was there, but the dialer opened on the keypad |

## The solution

### 1. Backup first
Media (DCIM, Pictures, WhatsApp incl. encrypted DB backups, Downloads), every user APK, all `settings` namespaces and `dumpsys package`. Contacts were already synced to the Google account.

### 2. Reversible debloat
`pm uninstall -k --user 0` keeps the system APK, so anything can be restored with `cmd package install-existing`.

- `scripts/bloat.txt`: MIUI ads and telemetry (msa, analytics, daemon), Mi apps, Facebook stubs, carrier preloads, extra Google apps
- `scripts/bloat_preloads.txt`: factory preloads installed at first boot with **zero** usage in `usagestats`
- `scripts/restore.sh <pkg>|all` undoes it

### 3. The launcher (`launcher/`, Kotlin + Jetpack Compose)

**Home**
- Big clock, date and battery, with a "charge me!" warning under 20 %
- 2-column grid of huge tiles (Phone, WhatsApp, Contacts, Camera, Photos, and any app you choose), each with a **red badge** that counts unread notifications
- Favourite contacts with photo and big **Call** / **WhatsApp** buttons
- Google search box with a **Talk** button for voice search
- Fixed font scale inside the app (sizes are already large) so the system's 1.5× font size can't break the layout

**Built-in phone screens** (no more Google Contacts / Dialer UI)
- **Keypad**: giant keys and a big green Call button. Shows "It's: John" if the number is known, plus a *Save number* button if it isn't
- **Missed calls**: a red card at the top of the phone screen. Calling back or tapping "OK, seen" marks the calls read and **clears the red dot** (the notification listener cancels the dialer's notification)
- **Recent calls**, with *Save* for unknown numbers
- **Contacts**: accent-insensitive search (by name or number), operator codes (`*100#`) and duplicates hidden, letters before numbers
- **Add contact**: only *Name* and *Phone*. Saved to the Google account so it syncs; warns if the number already exists

**Big call screen** (the key fix)
- Incoming call: full screen, even on the lock screen, with a huge green **ANSWER** button and a red "Don't answer" button. **No swipe.**
- In call: a huge **HANG UP** button, a speaker toggle and a timer. A proximity wake lock turns the screen off at the ear so the cheek can't hang up
- If the person goes home during a call, a "Back to the call" button appears

It does **not** replace the default dialer, which is less risky: if the launcher dies, the stock dialer still works. How it works:
`AccessibilityService` (always running) → `PhoneStateListener` → starts `CallActivity` (`showWhenLocked`, `turnScreenOn`), brought back to the front whenever the stock in-call UI appears (it arrives late on the lock screen, so the accessibility service watches for it) plus timed retries. It answers and hangs up with `TelecomManager.acceptRingingCall()` / `endCall()` (`ANSWER_PHONE_CALLS`).

**Guardian (anti-accident)**
- The `AccessibilityService` sends the user back home if Settings, MIUI Security, Digital Wellbeing, the Play Store or the package installer is opened. Bluetooth pairing dialogs are still allowed
- An invisible `TYPE_ACCESSIBILITY_OVERLAY` over the status bar stops the quick-settings shade from being pulled down
- **Admin mode**: long-press the clock and enter the PIN (default `1234`, change it). From there you can edit tiles and favourites, change the PIN, toggle the guardian, and open the real Settings or the stock launcher. It re-locks after 15 min

## Install

```bash
cd launcher && ./gradlew assembleRelease
cd .. && ./scripts/setup_launcher.sh
```

The script installs the APK, tries every permission and system tweak, and prints the manual steps that are left.

### MIUI gotchas (learned the hard way)

| Symptom | Fix |
|---|---|
| `INSTALL_FAILED_USER_RESTRICTED` | Developer options → **Install via USB** (needs a Mi account and a SIM) |
| `pm grant`, `settings put secure`, `input` → `SecurityException` | Developer options → **USB debugging (Security settings)**. Without it, the app asks for permissions at runtime instead |
| Call screen doesn't appear | MIUI app op `10021` *background pop-ups* and `10020` *show on lock screen* must be **Allow**. `appops set` is ignored, so set them in the permission editor (see the script output) |
| Service killed | MIUI op `10008` **Autostart** = ON, and add the app to the battery whitelist |
| `while read … adb shell …` only runs once | `adb shell` eats stdin, so use `adb shell -n` |
| Guardian blocks you from MIUI permission screens | Enter admin mode first, then open them with `am start` |

QA without touch injection: `adb shell am start -n es.papa.launcher/.MainActivity --es open keypad|contacts|recent|add --es phone 600000000`

## Project layout

```
launcher/app/src/main/java/es/papa/launcher/
  MainActivity.kt    home, tiles, favourites, search, navigation
  PhoneScreens.kt    keypad, contacts, add contact, missed + recent calls
  CallActivity.kt    big incoming / in-call screen
  Calls.kt           call state listener, answer/hang up, call log
  Services.kt        GuardService (accessibility) + NotifService (badges)
  AdminScreen.kt     PIN-protected configuration
  Prefs.kt           config persistence
scripts/             debloat / restore / setup
.claude/skills/      Claude Code skill to repeat this on another phone
```

## Claude Code skill

`.claude/skills/simple-senior-launcher/SKILL.md` describes the whole workflow (backup → debloat → install → MIUI permissions → verification), so an agent with `adb` access can redo it on another phone.

---

## 🇪🇸 Resumen

Launcher para personas mayores. Se hizo para un Redmi 9C con MIUI 12, que era un caos de apps preinstaladas y en el que se cambiaban ajustes sin querer.

- **Copia de seguridad** completa antes de tocar nada.
- **Debloat reversible**: anuncios, telemetría y apps de fábrica sin uso.
- **Pantalla de inicio** con botones gigantes, contactos favoritos (Llamar / WhatsApp), buscador de Google por voz y puntos rojos de avisos.
- **Teléfono y contactos propios**: teclado grande, llamadas perdidas visibles (y el punto rojo se borra al verlas), últimas llamadas, y añadir contacto con solo nombre y teléfono.
- **Pantalla de llamada gigante**: botón **CONTESTAR** sin deslizar, **COLGAR** grande y altavoz.
- **Guardián**: bloquea Ajustes, Play Store y la persiana superior. Modo configuración: mantener pulsado el reloj y meter el PIN.

## License

MIT
