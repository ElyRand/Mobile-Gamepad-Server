# Setup Guide (Windows PC + Android phone)

This guide reflects the state of this fork. It replaces the upstream
Codespaces-oriented instructions for local development on Windows.

Architecture:

```
physical gamepad → Android phone (Bluetooth/USB-C) → Android app
    → UDP over local Wi-Fi → Windows companion → ViGEmBus
    → virtual Xbox 360 controller → game
```

## 1. Windows companion

### Prerequisites

- Windows 10/11 x64
- .NET SDK 8 or 9 (`winget install Microsoft.DotNet.SDK.8`)
- ViGEmBus driver. Check whether it is already installed:

```bash
powershell -Command "Get-Service ViGEmBus"
```

  If missing, install it (requires admin):

```bash
winget install ViGEm.ViGEmBus
```

  Note: ViGEmBus is retired (no new development), but the signed driver
  still installs and works on Windows 10/11. This project keeps it for
  the MVP; the virtual-controller layer will be put behind an interface
  so it can be swapped later.

### Build and run

```bash
dotnet build "pc-companion/CompanionApp/CompanionApp.csproj" -c Debug
```

Run the tests with:

```bash
dotnet test "pc-companion/CompanionApp.Tests/CompanionApp.Tests.csproj"
```

Run `pc-companion/CompanionApp/bin/Debug/net8.0-windows/CompanionApp.exe`.
On first run Windows Firewall will ask to allow the app — allow it on
**Private networks** (it listens on UDP 9876 for controller state and
UDP 9877 for discovery).

Settings are stored in `companion-settings.json` next to the working
directory the app is started from.

### Verify the virtual controller

- Press Win+R, run `joy.cpl`. You should see **Xbox 360 Controller for
  Windows** while the companion app is running.
- Select it → Properties to watch axes/buttons live.

## 2. Android app

### Option A: command-line build (no Android Studio)

Requirements: JDK 17 and the Android SDK (platform 34, build-tools
34.0.0). A user-local toolchain works fine, e.g.:

- JDK 17: Eclipse Temurin zip extracted to `%LOCALAPPDATA%\Java`
- SDK: `commandlinetools` zip extracted to
  `%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest`, then:

```bash
sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
```

Create `android-app/local.properties`:

```
sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk
```

Build:

```bash
cd android-app && ./gradlew assembleDebug
```

Output: `android-app/app/build/outputs/apk/debug/app-debug.apk`

Run the unit tests with:

```bash
cd android-app && ./gradlew testDebugUnitTest
```

### Option B: Android Studio

Open `android-app/` in Android Studio (Hedgehog or newer, bundles
JDK 17 + SDK). Build → Build APK, or Run on a connected device.

### Install on the phone

With USB debugging enabled (Settings → Developer options):

```bash
adb install -r android-app/app/build/outputs/apk/debug/app-debug.apk
```

Or copy the APK to the phone and sideload it (allow "install unknown
apps" for your file manager).

## 3. Connect phone and PC

1. Put the phone and PC on the **same Wi-Fi network** (or use the
   phone's hotspot with the PC connected to it).
2. Start the Windows companion app.
3. Pair a physical controller with the phone (Bluetooth) or plug it in
   via USB-C OTG. Android Settings should show it as an input device.
4. Open the Mobile Gamepad app on the phone.
5. Either tap **Discover PC** (UDP broadcast, fills host/port
   automatically) or enter the PC's IPv4 address (`ipconfig` on the PC)
   and port `9876` manually.
6. Tap **Start streaming**. The app must stay in the **foreground** —
   input capture uses the Activity's key/motion callbacks, so the
   screen has to stay on with the app open.

## 4. Test with joy.cpl

1. On the PC: Win+R → `joy.cpl` → Xbox 360 Controller for Windows →
   Properties.
2. Move sticks, pull triggers, press D-pad and face buttons on the
   physical controller; the test panel should mirror them.
3. Disconnect test: turn off Wi-Fi on the phone (or force-quit the
   app) while holding a button — the companion resets all inputs to
   neutral after the idle timeout, so nothing stays stuck.

## 5. Test in a game

Launch any XInput game (most PC games from the last decade). The
virtual pad appears as player 1 (or the next free slot). If the game
also sees your physical controller (e.g. it is *also* paired to the
PC), unpair it from the PC to avoid double input.

## Troubleshooting

- **Companion receives nothing**: check both devices are on the same
  subnet; allow the app through Windows Firewall (UDP 9876/9877); some
  routers block UDP broadcast between clients ("AP isolation") — use
  manual IP entry in that case.
- **No virtual controller in joy.cpl**: `Get-Service ViGEmBus` must
  report Running; reinstall ViGEmBus if not.
- **Controller input doesn't reach the phone app**: verify the pad
  works on the phone in another app; keep the Mobile Gamepad app
  focused; some pads need a mode switch (e.g. 8BitDo: Android/X-input
  mode) before Android reports proper gamepad events.
- **Pairing code**: shown in the companion window; enter it in the
  phone app if discovery requires it.
- **A controller's buttons or triggers do nothing**: the app logs every
  axis a controller reports, with its range, plus any keycode it does
  not recognize. Capture it while pressing the offending control:

```bash
adb logcat -s GamepadMapper:I DeviceProfiles:I
```

- **`gradlew test` fails with "Could not find or load main class
  Files\Google\Chrome\..."**: this machine's `PATH` contains a quoted
  *command line* (`"C:\Program Files\Google\Chrome\Application\chrome.exe
  --single-argument %1"`) where a directory is expected, which breaks
  Gradle's forked test workers. Removing that one `PATH` entry (and the
  similar `BROWSER` variable) fixes it; the compile and APK build are
  unaffected.
