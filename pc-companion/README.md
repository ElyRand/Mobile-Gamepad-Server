# PC Companion (Windows)

This companion app listens for UDP gamepad packets from the Android
app, replies to discovery broadcasts, and emulates a virtual Xbox 360
controller using ViGEm.

## Requirements

- Windows 10/11
- [ViGEmBus driver](https://github.com/ViGEm/ViGEmBus) installed
  (`winget install ViGEm.ViGEmBus`)
- .NET SDK 8 or 9

## Build & run

```bash
dotnet run --project pc-companion/CompanionApp -- --stream-port 9876 --discovery-port 9877
```

On first launch the app creates `companion-settings.json` in the
current working directory, holding the pairing code and mapping
profiles.

## Behavior

- Discovery responder listens on UDP `9877` and replies with the
  stream port and pairing code.
- UDP listener receives JSON packets on `9876` and maps them to a
  virtual Xbox 360 controller.
- All inputs are reset to neutral when no packet arrives for 5 seconds,
  so a dropped connection cannot leave a button stuck down.
- The window shows listening ports, connection state, last-packet age,
  and latency.

## Notes

- The Android app sends standardized axis/button names
  (`left_stick_x`, `a`, `lb`, …).
- For full setup steps, see [`docs/SETUP.md`](../docs/SETUP.md).
- Deviations from upstream are recorded in
  [`docs/CHANGES.md`](../docs/CHANGES.md).
