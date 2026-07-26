# Architectural changes relative to upstream

This fork tracks every deliberate deviation from
`nobiti/Mobile-Gamepad-Server`. Newest first.

## Phase 3: Binary full-state protocol

**Replaced the JSON per-event protocol with a 32-byte binary full-state
packet** (`docs/PROTOCOL.md`). Upstream sent one JSON datagram per
input event carrying only what changed — either the axes or a single
button — with no sequence number. Over UDP that means a reordered or
lost packet could leave a button stuck pressed indefinitely, and there
was no way to detect it. Now every packet carries the complete state,
so a lost packet costs one update, and a sequence number lets the
receiver drop duplicates and out-of-order deliveries.

- Sends immediately on any state change, plus a 50 ms heartbeat resend.
- Buttons are a u16 bit field; sticks are i16 and triggers u8, both
  normalized on the phone into Xbox conventions (up/right positive).
- Added GOODBYE (immediate release on stop) and PING/PONG message
  types. The phone does not send PING yet, so round-trip latency is not
  displayed — the PC already answers them.

**Fixed: digital L2/R2 presses were dropped.** Upstream mapped the
`BUTTON_L2`/`BUTTON_R2` keycodes to names `lt`/`rt` that the PC's
button switch had no case for, so controllers with digital triggers had
no trigger input at all. They now set the trigger axis to 255.

**Trigger axis variance.** Controllers report triggers on either
`AXIS_BRAKE`/`AXIS_GAS` or `AXIS_LTRIGGER`/`AXIS_RTRIGGER`; the phone
now takes whichever reports the larger value instead of assuming
BRAKE/GAS.

**Disconnect safety moved into the receiver** with a 500 ms watchdog
(was a 5 s check in the form's UI timer) plus immediate reset on
GOODBYE and on shutdown.

**Removed PC-side mapping profiles and axis inversion.** With the phone
sending a canonical Xbox state, per-input remapping on the PC no longer
has anything to act on. Per-device profiles belong on the phone, where
the device is known (planned next). `companion-settings.json` now holds
only the pairing code.

**Introduced `IVirtualGamepad`**, implemented by
`Xbox360VirtualGamepad` (ViGEm). Nothing above the interface references
ViGEm types, so replacing the retired ViGEmBus later is a contained
change. Reports are now submitted atomically (`AutoSubmitReport =
false` plus one `SubmitReport`) so a game never sees a half-applied
state.

**Dropped the one-way latency display.** It was computed from the
difference between the phone's timestamp and the PC's clock, which are
not synchronized, so the number was not meaningful. The status window
shows packet rate, last-packet age and loss estimate instead.

Verified on this machine by injecting binary packets at 127.0.0.1:9876
and reading the resulting XInput state back: state application, stale
and duplicate rejection, full-state replacement, 500 ms watchdog reset,
GOODBYE reset, PING/PONG echo, and tolerance of malformed datagrams.

## Phase 2: Simplification

**Removed the QR/ECDH/AES-GCM pairing and encryption layer.**
Rationale: for a LAN-only gamepad bridge the per-packet AES-GCM
encryption added latency-sensitive work, a camera permission + QR
scanner dependency on Android, and roughly a third of the code, with
several failure modes (key exchange over lossy UDP, nonce handling,
key rotation). Pairing is reduced to a plain-text pair-code check
during discovery. Encryption can be reintroduced later behind the
transport interface if ever needed.

- Android deleted: `CryptoUtils.kt`, `KeyExchangeUtils.kt`,
  `PairingClient.kt`, `QrPairingPayload.kt`; dropped
  `zxing-android-embedded` dependency and `CAMERA` permission; removed
  QR/secret UI from `MainActivity` and the layout.
- Windows deleted: `CryptoUtils.cs`, `KeyExchangeUtils.cs`,
  `PairingSession.cs`; dropped the `QRCoder` package; removed
  decryption and the pairing-exchange handler from
  `UdpGamepadServer`; discovery responses no longer carry
  `publicKey`/`keyId`.

**Removed the start-with-Windows autostart feature**
(`AutostartManager.cs`, registry Run key). Out of scope for the MVP;
easy to re-add.

**Removed the mapping-grid GUI (Mapping tab).** Mapping profiles still
live in `companion-settings.json` and are applied by
`ControllerMapper`; they can be edited by hand. A cleaner per-device
profile system replaces this in Phase 5.

**Collapsed the three-tab MainForm into a single small window** with
the pair code and live status (listening ports, connection, last
packet age, latency).

## Phase 1: Making upstream build (bug fixes, no architecture change)

- Windows: corrected a nonexistent NuGet package version
  (`Nefarius.ViGEm.Client` 1.21.442 → 1.21.256), removed the .NET
  Framework-only `System.Windows.Forms.DataVisualization` chart,
  fixed `Xbox360Button` being used as an enum (it is a class), and
  replaced a reset loop that would have thrown at runtime with
  `ResetReport()`.
- Android: committed the Gradle wrapper JAR (upstream omitted it),
  fixed a foreground-service start pattern that crashed the app within
  seconds, and added the `FOREGROUND_SERVICE_DATA_SYNC` permission
  required by targetSdk 34.
