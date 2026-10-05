# rolling-door-ble

Control a 433MHz rolling door from an Android phone over Bluetooth Low Energy.

An ESP32-S3 sits near the door and replays the fixed codes of the original RF remote. The Android app connects to it over BLE as soon as it opens and shows the same four buttons as the remote: Up, Stop, Down, Lock.

> **Status:** planning done, project skeletons created. No working firmware or app yet. See [Roadmap](#roadmap).

## How it works

```
Android app  ⇄  BLE (GATT)  ⇄  ESP32-S3  →  433MHz transmitter  →  door receiver
```

- **BLE is the only control channel.** There is no remote control over the internet.
- **WiFi is off by default** and is only switched on for firmware updates (OTA).
- **Every command is authenticated** with a per-phone key and a one-time nonce, so captured BLE packets cannot be replayed.
- **Nothing is wired into the door's control box.** The ESP32 only transmits RF, like a second remote.

## Hardware

| Part | Notes |
|---|---|
| LilyGO T-Display-S3 | ESP32-S3 board, display removed |
| 433MHz transmitter (FS1000A) | Replays the remote's codes. Needs a 17.3cm wire antenna |
| 433MHz receiver (XY-MK-5V) | Only used once, to learn the codes from the remote |
| 5V USB-C adapter | Main power |
| 3.7V LiPo cell, JST 1.25mm | Backup power, for status reporting only |
| 2 resistors (e.g. 10kΩ + 20kΩ) | Voltage divider to detect mains power |

The door has no backup battery, so it does not move during a power cut. The LiPo cell only keeps the ESP32 alive so the app can show "power outage" instead of failing to connect.

The remote's frequency still has to be confirmed from the marking on its SAW resonator (433.92MHz is assumed).

## Repository layout

```
rolling-door-ble/
├── android/                                  Android app (Kotlin, Jetpack Compose)
├── firmware_esp/rolling-door-firmware-esp/   ESP32-S3 firmware (PlatformIO, Arduino)
└── docs/                                     Project plan (PLAN.md, in Vietnamese)
```

## BLE protocol

One custom GATT service with four characteristics:

| Characteristic | Properties | Payload |
|---|---|---|
| `CHALLENGE` | read, notify | 16-byte random nonce, replaced after every command |
| `COMMAND` | write | `[key id: 1 byte][command: 1 byte][HMAC-SHA256(key, nonce + command), first 16 bytes]` |
| `STATUS` | notify | `[command][result]` |
| `INFO` | read, notify | `[power source][battery percent]` |

| Command | Function | Allowed for |
|---|---|---|
| `01` | Up | Any paired phone |
| `02` | Stop | Any paired phone |
| `03` | Down | Any paired phone |
| `04` | Lock | Any paired phone |
| `05` | Enter OTA update mode | Admin |
| `06` | Open pairing for a new phone | Admin |
| `07` | Revoke a phone | Admin |

Codes `05` to `07` are proposals and may change during implementation.

### Phones and roles

- Each phone has its own 32-byte key, stored in a key table on the ESP32.
- The first phone paired (hold BOOT / GPIO0 for 3 seconds) becomes the admin.
- Only the admin can update firmware, configure WiFi, learn RF codes, and add or revoke phones.
- Holding the second button (GPIO14) for 10 seconds wipes all keys.

## Getting started

### Firmware

Requires [PlatformIO](https://platformio.org/). From `firmware_esp/rolling-door-firmware-esp`:

```
pio run -t upload && pio device monitor
```

The board has no user LED, so use the serial log to check behaviour.

### Android app

Requires Android Studio (for the SDK and JDK) and a phone running Android 8.0 (API 26) or newer with USB debugging enabled. From `android`:

```
gradlew installDebug
```

BLE does not work in the emulator; use a real device.

The app declares both sets of Bluetooth permissions. On Android 11 and older, scanning for the device during pairing needs the location permission and location turned on; Android 12 and newer use the dedicated Bluetooth permissions instead.

## Branches

| Branch | Purpose |
|---|---|
| `main` | Stable code that is deployed on the device and phone |
| `dev` | Day-to-day development |
| `android` | Android app work, merged into `dev` |
| `esp32` | ESP32 firmware work, merged into `dev` |

Merge `dev` into `main` when a phase works, then tag the release (`v0.1.0`, `v0.2.0`, ...).

## Roadmap

- [ ] **Phase 1 – BLE skeleton:** GATT server on the ESP32 with a fake RF layer (serial log only)
- [ ] **Phase 2 – Pairing and authentication:** pairing button, key table, HMAC check, admin role
- [ ] **Phase 3 – Android app:** pairing screen, four-button main screen, auto-connect on launch
- [ ] **Phase 4 – Polish:** under 1 second from launch to ready, reconnect handling, power status in the app
- [ ] **Phase 5 – OTA:** admin-triggered update mode over WiFi, refused while on battery
- [ ] **Phase 6 – Real RF and power:** learn codes from the remote, transmit with `rc-switch`, fit battery and mains detection
- [ ] **Phase 7 – More phones:** admin UI to add, rename and revoke phones

The full plan (in Vietnamese) is in [`docs/PLAN.md`](docs/PLAN.md), also available as [`docs/ke-hoach-cua-cuon-esp32.docx`](docs/ke-hoach-cua-cuon-esp32.docx).

## Security notes

- **Never commit secrets:** learned RF codes, phone keys, WiFi credentials and OTA passwords stay on the device (NVS) and out of this repository.
- The remote uses a fixed code, which can be captured and replayed by anyone nearby. That weakness belongs to the door itself; the BLE side is authenticated so this project does not add a new one.
- Keep the original remote and the manual chain as a fallback.
