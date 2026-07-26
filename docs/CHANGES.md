# Architectural changes relative to upstream

This fork tracks every deliberate deviation from
`nobiti/Mobile-Gamepad-Server`. Newest first.

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
