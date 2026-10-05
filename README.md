# rolling-door-ble

Control a 433MHz rolling door from an Android phone over Bluetooth Low Energy.

An ESP32-S3 sits near the door and replays the fixed codes of the original RF remote. The Android app connects to it over BLE as soon as it opens and shows the same four buttons as the remote: Up, Down, Lock, Unlock.

The remote has no Stop button. Pressing Lock while the door is moving stops it, and Unlock must then be pressed before Up or Down works again. The app keeps exactly this behaviour.

> **Status:** phase 2 done. The firmware pairs phones by QR code and authenticates every command; RF is still a fake layer (serial log only) and there is no app yet. See [Roadmap](#roadmap).

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
├── tools/                                    Test helpers (pair_test.py)
└── docs/                                     Project plan (PLAN.md, in Vietnamese)
```

## BLE protocol

One custom GATT service with five characteristics. The device advertises as `RollingDoor`, with the service UUID in the advertising packet. While pairing is open, the scan response also carries manufacturer data `FF FF 01`.

| Characteristic | UUID | Properties | Payload |
|---|---|---|---|
| Service | `a7930001-966e-4240-b881-5c2e2f2203a8` | | |
| `CHALLENGE` | `a7930002-966e-4240-b881-5c2e2f2203a8` | read, notify | 16-byte random nonce, replaced after every command and pairing request |
| `COMMAND` | `a7930003-966e-4240-b881-5c2e2f2203a8` | write | `[key id][command][args 0–16 bytes][mac 16 bytes]` |
| `STATUS` | `a7930004-966e-4240-b881-5c2e2f2203a8` | notify | `[command][result]`; `80` as the command means a pairing request |
| `INFO` | `a7930005-966e-4240-b881-5c2e2f2203a8` | read, notify | `[power source][battery percent]` |
| `PAIRING` | `a7930006-966e-4240-b881-5c2e2f2203a8` | read, write | Key exchange, see [Pairing](#pairing) |

`mac = HMAC-SHA256(phone key, nonce ‖ command ‖ args)`, first 16 bytes. The nonce changes after every frame, accepted or not, so a captured frame cannot be replayed.

| Command | Function | Allowed for |
|---|---|---|
| `00` | Ping: no action; confirms a new pairing and checks that a key still works | Any paired phone |
| `01` | Up | Any paired phone |
| `02` | Down | Any paired phone |
| `03` | Lock (stops a moving door) | Any paired phone |
| `04` | Unlock | Any paired phone |
| `05` | Enter OTA update mode (not implemented until phase 5) | Admin |
| `06` | Open pairing for 60 seconds | Admin |
| `07` | Revoke a phone, args: `[key id]` (an admin cannot revoke itself) | Admin |

| Result | Meaning |
|---|---|
| `00` | OK |
| `01` | Bad command or arguments |
| `02` | Authentication failed (unknown key, wrong MAC, wrong QR secret) |
| `03` | RF error |
| `04` | Not permitted for this phone's role |
| `05` | Locked out: 5 failures in a row block everything for 60 seconds |
| `06` | Pairing is closed |
| `07` | Key table full (8 phones) |

### Pairing

Pairing works like a camera: scan the device's QR code with the app. There is no Bluetooth bonding and no PIN; everything is done by the app and the firmware.

- **QR code.** On first boot the ESP32 creates a random 16-byte setup secret and keeps it in NVS. The QR code holds `RDOOR1:<BLE MAC, 12 hex>:<secret, base32>`. Print it from the serial console (`qr`) and keep it somewhere safe, not on the box by the door.
- **When pairing is open.** Always while no phone is paired. After that, for 60 seconds when BOOT (GPIO0) is held for 3 seconds, when the admin sends `06`, or with the `pair` console command.
- **Exchange.**
  1. The app reads `CHALLENGE` (nonce `N`) and creates an ephemeral P-256 key pair.
  2. It writes `[01][app public key, 65 bytes][tagA]` to `PAIRING`, where `tagA = HMAC-SHA256(secret, "RDPAIR-A" ‖ N ‖ app public key)`, first 16 bytes. This proves the app has the QR code.
  3. The device answers with `STATUS` `80 00` and puts `[00][device public key, 65][iv, 12][ciphertext, 34][GCM tag, 16]` in `PAIRING`. The key is `K = HKDF-SHA256(salt = N, ikm = ECDH shared secret ‖ secret, info = "RDPAIR v1")`, the cipher is AES-256-GCM with the two public keys as associated data, and the plaintext is `[key id][role: 01 admin, 00 normal][phone key, 32 bytes]`.
  4. The app sends `00` (Ping) with the new key on the same connection. Only then is the slot saved; if the phone disconnects first, the slot is discarded.
- Without the QR code the request is rejected (`80 02`, counted towards the lockout). Recording the radio traffic and getting the QR code later still does not reveal the phone key, because the ECDH keys are ephemeral.

### Phones and roles

- Each phone has its own 32-byte key, in a table of 8 slots stored in NVS on the ESP32.
- The first phone to pair becomes the admin; later phones are normal.
- Only the admin can update firmware, configure WiFi, learn RF codes, and add or revoke phones.
- Holding the second button (KEY / GPIO14) for 10 seconds erases all phone keys and restarts. The setup secret is kept, so the printed QR code stays valid, and pairing opens again for a new admin.

## Getting started

### Firmware

Requires [PlatformIO](https://platformio.org/). From `firmware_esp/rolling-door-firmware-esp`:

```
pio run -t upload && pio device monitor
```

The board has no user LED, so use the serial log to check behaviour. The serial console accepts `qr` (print the pairing QR code), `keys` (list paired phones, without keys), `pair` (open pairing for 60 seconds) and `wipe` (erase all phone keys).

`tools/pair_test.py` plays the app side of the protocol for manual testing with nRF Connect: it builds pairing requests and command frames and decrypts pairing responses. It needs `pip install cryptography` and keeps the keys it receives in `~/.rolling-door-test.json`, outside the repository.

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

- [x] **Phase 1 – BLE skeleton:** GATT server on the ESP32 with a fake RF layer (serial log only)
- [x] **Phase 2 – Pairing and authentication:** QR code pairing, key table, HMAC check, admin role
- [ ] **Phase 3 – Android app:** pairing screen, four-button main screen, auto-connect on launch
- [ ] **Phase 4 – Polish:** under 1 second from launch to ready, reconnect handling, power status in the app
- [ ] **Phase 5 – OTA:** admin-triggered update mode over WiFi, refused while on battery
- [ ] **Phase 6 – Real RF and power:** learn codes from the remote, transmit with `rc-switch`, fit battery and mains detection
- [ ] **Phase 7 – More phones:** admin UI to add, rename and revoke phones

The full plan (in Vietnamese) is in [`docs/PLAN.md`](docs/PLAN.md), also available as [`docs/ke-hoach-cua-cuon-esp32.docx`](docs/ke-hoach-cua-cuon-esp32.docx).

## Security notes

- **Never commit secrets:** the setup secret, learned RF codes, phone keys, WiFi credentials and OTA passwords stay on the device (NVS) and out of this repository.
- **Keep the QR code private.** Anyone with it can pair while pairing is open.
- The remote uses a fixed code, which can be captured and replayed by anyone nearby. That weakness belongs to the door itself; the BLE side is authenticated so this project does not add a new one.
- Keep the original remote and the manual chain as a fallback.
