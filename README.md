# Rocket GS for Android

Android version of the ground station, in the "Range Console" look (mockup D).
Kotlin + Jetpack Compose. Reads the CODE-001 receiver (Uno + E22) over a
**USB-OTG cable**: no change to the receiver hardware or code.

## What it does

Same features as the desktop app, laid out for a phone (one scrolling column;
two columns in landscape or on a tablet):

- **Header:** flight state, ARMED/SAFE, T+ clock since launch, link LIVE / STALE / LOST
- **Telemetry:** altitude, max, velocity, acceleration, peaks
- **Trace:** graphs of ALT, VEL, ACC, RSSI, attitude, voltages; 30/60/120/300 s or whole flight
- **Attitude scope:** where the nose leans (tilt and direction) plus roll, seen from above
- **Position:** distance and direction from the pad, coordinates, track; **MAP** opens the
  phone's map app at the rocket for recovery, **COPY** copies the coordinates
- **Power / link:** F1, F2, RSSI, packet loss, rate, bad blocks
- **Flags, events, raw receiver text**
- **Setup:** USB receiver, built-in simulator (~6 km flight), replay a log (also desktop logs),
  attitude conventions, keep screen on, share the latest log

Every USB session is saved as a raw log (same format as the desktop app) in
`Android/data/com.rocketgs/files/logs/`. Use **SETUP > SHARE LATEST LOG** to send one to
your laptop.

## Build and install

1. Install **Android Studio** (it includes the Android SDK).
2. **File > Open** and choose this `groundstation-android` folder. Let Gradle sync; the
   first sync downloads the libraries. If Android Studio offers to upgrade the Android
   Gradle Plugin, you can accept or skip it.
3. On the phone: enable **Developer options** and **USB debugging**, and connect it to the
   computer (or use Wi-Fi debugging).
4. Press **Run**. To get an installable file instead: **Build > Build App Bundle(s) / APK(s) >
   Build APK(s)**, then copy `app/build/outputs/apk/debug/app-debug.apk` to the phone.

Tests for the parser, statistics, GPS maths, attitude maths and simulator:
right-click `app/src/test/.../CoreTest` > **Run**.

**Note:** this code was written and the non-Android part tested in an environment without
access to the Android SDK, so the Android/Compose part has not been compiled yet. If the first
build shows errors, paste them to Claude; they are usually one-line fixes.

## Build on GitHub instead (no Android Studio)

The project includes `.github/workflows/build.yml`. Every push to GitHub builds the app
on GitHub's servers and runs the tests.

1. On github.com create a new **empty** repository (no README), e.g. `rocketgs-android`.
2. On your laptop, inside this folder:
   ```bash
   git init -b main
   git add .
   git commit -m "Rocket GS Android"
   git remote add origin https://github.com/YOUR_NAME/rocketgs-android.git
   git push -u origin main
   ```
   (GitHub asks for a login; use a personal access token as the password, or install `github-cli`
   and run `gh auth login` first.)
3. Open the repository's **Actions** tab: the build takes about 5-10 minutes the first time.
4. When it is green, open **Releases > Latest build** on the phone and download
   `RocketGS-debug.apk`. Open it to install (allow "install unknown apps" for your browser once).

Every later `git push` builds a new APK and replaces the "Latest build" release.
If a build fails, open the failed run, copy the red error lines and paste them to Claude.
Private repositories also work, but then downloading needs you to be logged in to GitHub.

## Using it with the receiver

1. Plug the receiver Uno into the phone through a **USB-OTG adapter** (USB-C or micro-USB
   to USB-A female). The phone powers the Uno and E22.
2. Android asks "Open Rocket GS?" and/or "Allow access?": tap OK. The app starts reading.
3. Unplugging and replugging reconnects automatically.

Supported USB chips: CH340 (most Uno/Nano clones), genuine Arduino Uno (ATmega16U2),
FTDI, CP210x. Some phones need OTG switched on in Settings. The phone powers the Uno and
E22 through the cable; if a phone cannot supply enough power, use a powered OTG hub or an
OTG Y-cable with a power bank. Keep the screen on (default) during a flight.

## Attitude tilt test

Same as the desktop app: stand the flight computer board nose-up, press **SET UPRIGHT**,
then tilt it toward north: the marker on the scope must move up. Roll it: the amber
triangle must turn the same way. If a motion is mirrored, use **FLIP** in SETUP.
Display only: never use it for a safety decision.

## Code layout

| Folder | What |
|---|---|
| `app/src/main/java/com/rocketgs/core/` | Parser, statistics, history, GPS and attitude maths, simulator. Plain Kotlin, no Android: a line-by-line port of the desktop C++ code, tested against it. |
| `.../source/Sources.kt` | USB (usb-serial-for-android), replay and simulator sources, each on its own thread |
| `.../GroundStationViewModel.kt` | Holds the data, applies new packets on the UI thread at most 10 times a second |
| `.../ui/` | Compose screens: `ConsoleScreen`, `SetupScreen`, `Charts`, `Widgets` |
| `core/Telemetry.kt` (`Config`) | Flag names, warning limits, link timeouts: change these for your rocket |

Fonts: Share Tech Mono and VT323, both SIL Open Font License (see `licenses/`).
