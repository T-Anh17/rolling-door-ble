# rolling-door-ble

Control a 433MHz rolling door from an Android phone over Bluetooth Low Energy.

An ESP32-S3 sits near the door and replays the fixed codes of the original RF remote. The Android app connects to it over BLE as soon as it opens and shows the same four buttons as the remote: Up, Down, Lock, Unlock.

The remote has no Stop button. Pressing Lock while the door is moving stops it, and Unlock must then be pressed before Up or Down works again. The app keeps exactly this behaviour.

> **Status:** phase 3 in progress. The firmware pairs phones by QR code and authenticates every command; RF is still a fake layer (serial log only). The app can pair by scanning the QR code; the control screen is not built yet. See [Roadmap](#roadmap).

> **Use this project only on your own door.** The original remote uses a fixed code, which is weak by design: anyone nearby with a cheap receiver can record it and replay it. This project does not fix that, and it is not a tool for opening doors that are not yours. See [Security notes](#security-notes).

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
| MX-433 kit: FS1000A transmitter | Powered from 5V. Replays the remote's codes. Needs a 17.3cm wire antenna |
| MX-433 kit: XY-MK-5V receiver | Powered from 5V. Only used once, to learn the codes from the remote |
| 5V USB-C adapter | Main power |
| 3.7V LiPo cell, JST 1.25mm | Backup power, for status reporting only |
| 10kΩ and 20kΩ resistors, two of each | Voltage dividers: receiver DATA to GPIO, and mains power detection |

The door has no backup battery, so it does not move during a power cut. The LiPo cell only keeps the ESP32 alive so the app can show "power outage" instead of failing to connect.

The remote's frequency still has to be confirmed from the marking on its SAW resonator (433.92MHz is assumed).

### Wiring

Both RF modules run from the board's 5V pin. The ESP32-S3 GPIOs are 3.3V only, so the receiver's 5V DATA output goes through a 10k/20k divider (5V × 20 / 30 ≈ 3.3V). The transmitter's DATA input accepts the 3.3V GPIO level directly.

```
LilyGO T-Display-S3                    FS1000A (transmitter)
  5V   ──────────────────────────────  VCC
  GND  ──────────────────────────────  GND
  GPIO (RF TX) ──────────────────────  DATA
                                       ANT ── 17.3cm wire

LilyGO T-Display-S3                    XY-MK-5V (receiver)
  5V   ──────────────────────────────  VCC
  GND  ──────────────────────────────  GND
  GPIO (RF RX) ──┬──── 10kΩ ─────────  DATA
                 │
                20kΩ
                 │
                GND

On-board buttons: BOOT = GPIO0 (hold 3 s: open pairing), KEY = GPIO14 (hold 10 s: erase phone keys)
USB-C: 5V adapter          JST 1.25mm: 3.7V LiPo cell
```

The RF and mains detection GPIO numbers are chosen in phase 6 and will be listed here and in `src/config.h`. Until then the firmware uses a fake RF layer and nothing needs to be connected.

## Repository layout

```
rolling-door-ble/
├── android/                                  Android app (Kotlin, Jetpack Compose)
├── firmware_esp/rolling-door-firmware-esp/   ESP32-S3 firmware (PlatformIO, Arduino)
├── tools/                                    QR code viewer (qr-viewer.html)
├── docs/                                     Early planning notes (Vietnamese)
├── USEGUIDE.md                               Working rules for contributors (Vietnamese)
└── LICENSE                                   MIT, source code only
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

### Android app

Requires Android Studio (for the SDK and JDK) and a phone running Android 8.0 (API 26) or newer with USB debugging enabled. From `android`:

```
gradlew installDebug
```

In PowerShell on Windows, use `.\gradlew.bat installDebug`. Unit tests run with `gradlew testDebugUnitTest`.

BLE does not work in the emulator; use a real device.

The app declares both sets of Bluetooth permissions. On Android 11 and older, scanning for the device during pairing needs the location permission and location turned on; Android 12 and newer use the dedicated Bluetooth permissions instead.

### Setting up a new device

Every board makes its own pairing QR code. The source code contains no secrets, so building from this repository never gives you someone else's code, and your code never ends up in the repository.

1. **Flash the firmware** (see [Firmware](#firmware)). On first boot the board creates a random setup secret and stores it in its own flash (NVS).
2. **Get the QR code** with either of these, while the board is plugged into your computer:
   - Open [`tools/qr-viewer.html`](tools/qr-viewer.html) in Chrome or Edge, click **Connect** and pick the board. The page reads the code over USB (Web Serial) and shows it large enough to scan, with **Print** and **Save PNG** buttons. It works offline, sends nothing anywhere, and is available in English and Vietnamese. In other browsers, paste the serial output into the box at the bottom of the page.
   - Or open a serial monitor (`pio device monitor`) and type `qr`. Until a phone is paired, the code is also printed at every boot.
3. **Keep the code private.** Print it or save the PNG somewhere safe, not on the box by the door. Anyone with it can pair while pairing is open. Do not share your QR code, screenshots of it, or your serial logs when you share the project.
4. **Pair the first phone** by scanning the code in the app. It becomes the admin. To add more phones later, the admin taps "Add phone" (or you hold BOOT for 3 seconds) and the new phone scans the same code within 60 seconds.

Erasing the phone keys (hold KEY / GPIO14 for 10 seconds) keeps the setup secret, so the printed code stays valid. Only a full flash erase (`pio run -t erase`) creates a new secret and a new code.

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

## Security notes

- **Never commit secrets:** the setup secret, learned RF codes, phone keys, WiFi credentials and OTA passwords stay on the device (NVS) and out of this repository.
- **Keep the QR code private.** Anyone with it can pair while pairing is open.
- **Do not publish anything that reveals your door's code:** photos of the inside of the remote, its DIP switch positions, learned codes, or serial logs. Do not publish your address or photos that identify your house.
- The remote uses a fixed code, which can be captured and replayed by anyone nearby. That weakness belongs to the door itself; the BLE side is authenticated so this project does not add a new one. If your door supports a rolling-code remote, that is the safer choice.
- **Use this project only on a door you own or are allowed to control.**
- Keep the original remote and the manual chain as a fallback.

## License

The source code is released under the [MIT License](LICENSE).

The license covers the source code only. The app name and the app icon are not covered by it. If you publish your own build or a fork, give it your own name and icon.
