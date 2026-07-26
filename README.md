# Mobile Gamepad Server

Fork of [nobiti/Mobile-Gamepad-Server](https://github.com/nobiti/Mobile-Gamepad-Server).

Streams input from a physical gamepad attached to an Android phone to a
Windows PC, where it appears as a virtual Xbox 360 controller:

```
physical gamepad → Android phone (Bluetooth/USB-C) → Android app
    → UDP over local Wi-Fi → Windows companion → ViGEmBus
    → virtual Xbox 360 controller → game
```

## Project layout

- `android-app/` — Kotlin app that captures gamepad `KeyEvent` /
  `MotionEvent` input and sends it over UDP.
- `pc-companion/` — C#/.NET WinForms receiver that drives a virtual
  Xbox 360 controller via ViGEm.
- `docs/SETUP.md` — build, install, and test instructions.
- `docs/CHANGES.md` — every deliberate change relative to upstream.
- `docs/PROTOCOL.md` — wire format.
- `scripts/` — upstream Codespaces/Windows helper scripts.

## Quick start

```bash
dotnet run --project pc-companion/CompanionApp
```

```bash
cd android-app && ./gradlew assembleDebug
```

Full instructions, including the ViGEmBus driver and firewall setup,
are in [`docs/SETUP.md`](docs/SETUP.md).

## Status

**Verified on a Windows 11 machine:**

- The companion builds clean and drives a real virtual Xbox 360 pad —
  confirmed by injecting protocol packets and reading the resulting
  XInput state back, including stale/duplicate rejection, the 500 ms
  disconnect reset, GOODBYE, and ping replies.
- 49 automated tests pass (24 xUnit, 25 JUnit), including a golden-byte
  test asserting the Kotlin and C# encoders produce identical packets.
- The Android APK builds and its unit tests pass.

**Not yet verified:** the phone half of the loop — capturing input from
a physical controller and sending it — has never been run on a device.
Everything on the phone side is written but untested against real
hardware.
