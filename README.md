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

Verified: the Windows companion builds and drives a virtual Xbox 360
pad (confirmed by reading back XInput state); the Android APK builds.
The end-to-end path with a real controller and phone has not yet been
tested on-device.
