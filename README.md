# rolling-door-ble

Control a 433MHz rolling door from an Android phone over Bluetooth Low Energy.

An ESP32-S3 sits near the door and replays the fixed codes of the original RF remote. The Android app connects to it over BLE as soon as it opens and shows the same four buttons as the remote: Up, Down, Lock, Unlock. The admin can add buttons (up to eight), rename them, change their icon and learn a code for each.

The remote has no Stop button. Pressing Lock while the door is moving stops it, and Unlock must then be pressed before Up or Down works again. The app keeps exactly this behaviour.

> **Status:** phases 1–3, 6 and 7 done. The firmware pairs phones by QR code or by an 8-digit invite from the admin phone, and authenticates every command; up to three phones can be connected at once, and the admin renames, revokes and hands over admin from the app. The app pairs by scanning the QR code or typing the invite, connects on its own while it is open and controls the door from a screen of up to eight buttons. The admin adds, edits and deletes buttons and learns their codes from the original remote; the board replays them with `rc-switch` and reports the backup battery level. Phase 4 in progress: next is under 1 second from launch to ready. See [Roadmap](#roadmap).

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
| MX-433 kit: XY-MK-5V (MX-05V) receiver | Powered from 5V. Only used once, to learn the codes from the remote |
| 5V USB-C adapter | Main power |
| 3.7V LiPo cell, JST 1.25mm | Backup power: keeps the board up through a power cut |
| 10kΩ and 20kΩ resistors | Voltage divider: receiver DATA to GPIO |

The door has no backup battery, so it does not move during a power cut. The LiPo cell only keeps the ESP32 alive, so the phones stay paired and it is ready when power comes back. The board measures the cell on GPIO4 (the T-Display-S3's own divider) and the app shows it in Settings. On USB that pin sees the charger instead of the cell, so the board then reports "charging" rather than a percent.

The remote's frequency still has to be confirmed from the marking on its SAW resonator (433.92MHz is assumed).

### Wiring

Both RF modules run from the board's 5V pin. The ESP32-S3 GPIOs are 3.3V only, so the receiver's 5V DATA output goes through a 10k/20k divider (5V × 20 / 30 ≈ 3.3V). The transmitter's DATA input accepts the 3.3V GPIO level directly.

```
LilyGO T-Display-S3                    FS1000A (transmitter)
  5V   ──────────────────────────────  VCC
  GND  ──────────────────────────────  GND
  GPIO13 (RF TX) ────────────────────  DATA
                                       ANT ── 17.3cm wire

LilyGO T-Display-S3                    XY-MK-5V (receiver)
  5V   ──────────────────────────────  VCC
  GND  ──────────────────────────────  GND
  GPIO12 (RF RX) ┬──── 10kΩ ─────────  DATA
                 │
                20kΩ
                 │
                GND

On-board buttons: BOOT = GPIO0 (hold 3 s: open pairing), KEY = GPIO14 (hold 10 s: erase phone keys)
USB-C: 5V adapter          JST 1.25mm: 3.7V LiPo cell
```

RF uses GPIO13 (transmitter DATA) and GPIO12 (receiver DATA, through the divider), set in `src/config.h`. The board's 5V pin is only live on USB power, so the RF modules are off while the board runs on the LiPo cell.

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

One custom GATT service with seven characteristics. The device advertises no name, only the service UUID in the advertising packet. While pairing is open, the scan response also carries manufacturer data `FF FF 01`.

The device takes up to three phones at once and keeps advertising while it has room, so one phone with the app open does not lock the others out. Each connection has its own `CHALLENGE` nonce and gets the `STATUS` of its own commands; `PAIRING` and `PHONES` read differently per connection; `INFO` goes to all.

| Characteristic | UUID | Properties | Payload |
|---|---|---|---|
| Service | `a7930001-966e-4240-b881-5c2e2f2203a8` | | |
| `CHALLENGE` | `a7930002-966e-4240-b881-5c2e2f2203a8` | read, notify | 16-byte random nonce for this connection, replaced after each of its commands and pairing requests |
| `COMMAND` | `a7930003-966e-4240-b881-5c2e2f2203a8` | write | `[key id][command][args 0–34 bytes][mac 16 bytes]`; only `0C` is longer than the default MTU allows, and the app asks for a larger MTU before sending it |
| `STATUS` | `a7930004-966e-4240-b881-5c2e2f2203a8` | notify | `[command][result]`; `80` as the command means a pairing request, `81` the end of RF learning |
| `INFO` | `a7930005-966e-4240-b881-5c2e2f2203a8` | read, notify | `[00][battery: 0–100 percent on battery, FE on USB (charging), FF no cell][learned buttons: bit n = button n + 1][button list revision]`. The first byte once said mains or battery and is always `00` now, notified when it changes |
| `PAIRING` | `a7930006-966e-4240-b881-5c2e2f2203a8` | read, write | Key exchange, see [Pairing](#pairing) |
| `BUTTONS` | `a7930007-966e-4240-b881-5c2e2f2203a8` | read | `[revision]` then, per button in display order, `[id 1–8][icon][name length 0–32][name UTF-8]` (a long read, up to 281 bytes) |
| `PHONES` | `a7930008-966e-4240-b881-5c2e2f2203a8` | read | Empty until this connection sends `0E`, then `[count]` and, per phone by key id, `[key id][role: 01 admin, 00 normal][name length 0–32][name UTF-8]` (a long read, up to 281 bytes). Other connections read it empty |

`mac = HMAC-SHA256(phone key, nonce ‖ command ‖ args)`, first 16 bytes. The nonce changes after every frame, accepted or not, so a captured frame cannot be replayed.

| Command | Function | Allowed for |
|---|---|---|
| `00` | Ping: no action; confirms a new pairing and checks that a key still works | Any paired phone |
| `01`–`04` | Press buttons 1–4 (Up, Down, Lock, Unlock on a new board), kept for older apps | Any paired phone |
| `05` | Enter OTA update mode (not implemented until phase 5) | Admin |
| `06` | Open pairing for 60 seconds | Admin |
| `07` | Revoke a phone, args: `[key id]` (an admin cannot revoke itself) | Admin |
| `08` | Leave: removes the sending phone's own slot and name. The admin gets `04` while other phones are paired (it hands over admin first with `10`); as the last phone it may leave, and pairing opens again | Any paired phone |
| `09` | Learn an RF code, args: `[01–08]` the button, or `[00]` to cancel. `00` means the receiver is listening, `03` that it is busy; the end comes later as `STATUS` `81 00` (saved) or `81 03` (no code within 15 s) | Admin |
| `0A` | Clear an RF code, args: `[01–08]` the button (`03` while learning) | Admin |
| `0B` | Press a button, args: `[01–08]`. `01` if there is no such button, `03` if it has no code or the receiver is busy | Any paired phone |
| `0C` | Add a button or change it, args: `[id 01–08][icon][name UTF-8, 0–32 bytes]`. A new button goes at the end of the list | Admin |
| `0D` | Delete a button and its RF code, args: `[01–08]` (`03` while learning) | Admin |
| `0E` | List phones into `PHONES`. A phone also learns its own role from the list | Any paired phone |
| `0F` | Name a phone, args: `[key id][name UTF-8, 0–32 bytes]`; empty clears it. The app names a new phone after the device right after pairing | Admin for any phone, others for themselves |
| `10` | Make another phone the admin, args: `[key id]`. The sender becomes a normal phone | Admin |
| `11` | Invite a phone: the device makes 8 digits for one new phone (see [Pairing](#pairing)) and puts them, masked, in `PAIRING` | Admin |

Learning stops when the admin phone disconnects or sends `09 00`; neither is reported with `81`.

**Buttons.** The board keeps up to eight buttons in NVS, each with an id (1–8, also the slot of its RF code), an icon and a name. A new board starts with Up, Down, Lock and Unlock as buttons 1–4, with empty names. Icons are `00` Up, `01` Down, `02` Lock, `03` Unlock, `04` Stop, `05` Gate, `06` Garage, `07` Light, `08` Power, `09` Bell; the app draws them, and a button with an empty name shows its icon's name in the phone's language. Every change to the list (`0C`, `0D`) bumps the revision in `INFO`, which the board notifies before the command's `STATUS`. The app keeps the last list it read and reads `BUTTONS` again only when the revision differs, so the buttons show before it connects and the list costs nothing on a normal launch. Names are readable by anyone who connects, like `INFO`.

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
- **Invites.** The usual way to add a phone. The admin sends `11` and the device makes 8 random digits, valid once for 5 minutes; a new invite replaces the last one. The new phone types the digits, or scans the admin's screen, which shows them as a QR code in the same `RDOOR1` form. In the exchange below, `secret` is then `HMAC-SHA256(key = the 8 digits in ASCII, "RDINVITE")`, first 16 bytes. The admin reads `PAIRING` right after: `[02][salt, 16][digits XOR HMAC-SHA256(admin key, "RDINVITE" ‖ salt), first 8 bytes]`, so the digits never go over the air in clear. While an invite is valid the scan response carries the pairing flag, which is how a phone that typed the digits finds the device without its MAC. Guessing is bounded by the lockout: about 25 tries in 5 minutes against 100 million codes.
- **Exchange.**
  1. The app reads `CHALLENGE` (nonce `N`) and creates an ephemeral P-256 key pair.
  2. It writes `[01][app public key, 65 bytes][tagA]` to `PAIRING`, where `tagA = HMAC-SHA256(secret, "RDPAIR-A" ‖ N ‖ app public key)`, first 16 bytes. This proves the app has the QR code.
  3. The device answers with `STATUS` `80 00` and puts `[00][device public key, 65][iv, 12][ciphertext, 34][GCM tag, 16]` in `PAIRING`. The key is `K = HKDF-SHA256(salt = N, ikm = ECDH shared secret ‖ secret, info = "RDPAIR v1")`, the cipher is AES-256-GCM with the two public keys as associated data, and the plaintext is `[key id][role: 01 admin, 00 normal][phone key, 32 bytes]`.
  4. The app sends `00` (Ping) with the new key on the same connection. Only then is the slot saved; if the phone disconnects first, the slot is discarded.
- Without the QR code the request is rejected (`80 02`, counted towards the lockout). Recording the radio traffic and getting the QR code later still does not reveal the phone key, because the ECDH keys are ephemeral.

### Phones and roles

- Each phone has its own 32-byte key, in a table of 8 slots stored in NVS on the ESP32.
- The first phone to pair becomes the admin; later phones are normal.
- Only the admin can update firmware, configure WiFi, learn RF codes, and add, rename or revoke phones. There is one admin at a time; it can hand the role to another phone (`10`).
- Each phone has a name of up to 32 bytes, kept on the device. The app sets it to the phone's device name when it pairs; the admin can change it. An unnamed phone shows as "Phone <slot + 1>".
- Forgetting the device in the app first asks the device to free this phone's slot (`08`). A normal phone out of range may still forget, and its slot stays until the admin revokes it. Connected, the admin can only leave as the last phone; otherwise it hands over admin first, so the device always has one. Out of range (or with a broken device), the admin may still forget after a warning: the device then has no admin until KEY is held for 10 seconds.
- Holding the second button (KEY / GPIO14) for 10 seconds erases all phone keys and restarts. The setup secret is kept, so the printed QR code stays valid, and pairing opens again for a new admin.

## Getting started

### Firmware

Requires [PlatformIO](https://platformio.org/). From `firmware_esp/rolling-door-firmware-esp`:

```
pio run -t upload && pio device monitor
```

The board has no user LED, so use the serial log to check behaviour. The serial console accepts `qr` (print the pairing QR code), `keys` (list paired phones, without keys), `pair` (open pairing for 60 seconds), `wipe` (erase all phone keys) `battery` (the cell's voltage and percent), `buttons` (list the buttons with icon and name) and `rf` (learn and test the remote's codes, see below).

The admin phone learns the codes from Settings, **Buttons** (see [Android app](#android-app)). Over the serial console, type `rf learn 1` and hold the remote's Up button a few centimetres from the receiver; the code is saved once it is received twice in a row (15 s timeout, `rf cancel` stops early). Repeat for buttons `2`, `3` and `4` (Down, Lock, Unlock on a new board). Codes learned before buttons had ids are moved to buttons 1–4 on the first boot. `rf list` shows which buttons are learned (protocol, bit count and pulse length, never the code), `rf send <button>` transmits a code without the app, and `rf clear [button]` erases one or all codes. `rf verify` sends each learned code and decodes it with the board's own receiver, to check that the board transmits exactly the code of that remote button (it also warns if two buttons were learned with the same code). Two more commands check the hardware: `rf selftest` does the same with a made-up code, and `rf scan` logs the receiver's edge counts for 10 seconds while a remote button is pressed.

Codes are transmitted with `rc-switch` but decoded by the firmware: the cheap receiver loses a short pulse in some frames, which makes `rc-switch` drop the whole frame, so the firmware decodes protocol 1 (PT2262, EV1527 and similar, 1:3 pulses with a 1:31 sync) itself and puts a lost pulse back. A rolling-code remote cannot be learned. Pressing a button with no code returns `03` (RF error).

### Android app

Requires Android Studio (for the SDK and JDK) and a phone running Android 8.0 (API 26) or newer with USB debugging enabled. From `android`:

```
gradlew installDebug
```

In PowerShell on Windows, use `.\gradlew.bat installDebug`. Unit tests run with `gradlew testDebugUnitTest`.

For everyday use, build a signed release APK with `gradlew assembleRelease` (output: `app/build/outputs/apk/release/app-release.apk`). Signing reads `android/keystore.properties`, which is gitignored and points to your own key store outside the repository:

```
storeFile=C:/path/outside/the/repo/release.jks
storePassword=...
keyAlias=rollingdoor
keyPassword=...
```

Without that file the release APK is unsigned. Sign every update with the same key: a phone moving from a debug build to a release build (or to a different key) must uninstall the app first, which deletes its phone key, so it has to pair again.

BLE does not work in the emulator; use a real device.

The app declares both sets of Bluetooth permissions. On Android 11 and older, scanning for the device during pairing needs the location permission and location turned on; Android 12 and newer use the dedicated Bluetooth permissions instead.

Once paired, the app opens straight on the control screen: the connection status and the buttons (two per row, up to eight, never scrolling), nothing else. It connects by itself while it is on screen and disconnects as soon as it is closed or hidden. Up to three phones can be connected at once. Out of range, it waits and connects when the device comes back; **Try now** forces a direct attempt. The gear button opens Settings: the device address and this phone's role, **Phones**, **Add watch** (a placeholder until the Wear OS app exists) and **Buttons** for the admin, and **Forget device**. Phones lists the paired phones with **Add phone**, which shows a one-time QR code and 8 digits for the new phone; a phone's page renames it, makes it the admin, or revokes it. Buttons lists the device's buttons and whether it has a code for each, with **Add button** while there are fewer than eight. A button's page sets its name and icon (**Save**), learns its code (tap **Learn code**, then hold the button on the remote close to the device, within 15 seconds; the receiver must be wired), clears the code, or deletes the button with its code. A new button is saved first, then learned. Every paired phone sees the same buttons. Forget device frees this phone's slot on the device first (see [Phones and roles](#phones-and-roles)).

The app follows the phone's language: Vietnamese on a Vietnamese phone, English otherwise. On Android 13 and newer it can also be set for this app alone in the system's app language setting.

### Setting up a new device

Every board makes its own pairing QR code. The source code contains no secrets, so building from this repository never gives you someone else's code, and your code never ends up in the repository.

1. **Flash the firmware** (see [Firmware](#firmware)). On first boot the board creates a random setup secret and stores it in its own flash (NVS).
2. **Get the QR code** with either of these, while the board is plugged into your computer:
   - Open [`tools/qr-viewer.html`](tools/qr-viewer.html) in Chrome or Edge, click **Connect** and pick the board. The page reads the code over USB (Web Serial) and shows it large enough to scan, with **Print** and **Save PNG** buttons. It works offline, sends nothing anywhere, and is available in English and Vietnamese. In other browsers, paste the serial output into the box at the bottom of the page.
   - Or open a serial monitor (`pio device monitor`) and type `qr`. Until a phone is paired, the code is also printed at every boot.
3. **Keep the code private.** Print it or save the PNG somewhere safe, not on the box by the door. Anyone with it can pair while pairing is open. Do not share your QR code, screenshots of it, or your serial logs when you share the project.
4. **Pair the first phone** by scanning the code in the app. It becomes the admin. To add more phones later, the admin opens Settings, **Phones**, **Add phone**: its screen shows a QR code and 8 digits, and the new phone scans the code or taps **Enter a code** and types the digits, within 5 minutes. No computer is needed. Holding BOOT for 3 seconds still lets a new phone pair with the device's own code for 60 seconds.

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
- [x] **Phase 3 – Android app:** pairing screen, four-button main screen, auto-connect on launch
- [ ] **Phase 4 – Polish:** under 1 second from launch to ready, reconnect handling, battery level in the app
- [ ] **Phase 5 – OTA:** admin-triggered update mode over WiFi, refused while on battery
- [x] **Phase 6 – Real RF and power:** learn codes from the remote, transmit with `rc-switch`, buttons the admin can add, edit and delete (up to eight), measure the backup battery
- [x] **Phase 7 – More phones:** admin UI to add (8-digit invite), rename, revoke and hand over admin; a phone that forgets the device leaves and frees its slot; up to three phones connected at once

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

The author's icon is not in this repository; a build from it uses the default Android icon. To use your own, put your launcher icon resources (and optionally `drawable/ic_launcher_foreground` for the splash screen) in `android/app/src/brand/res`, which is gitignored and overrides the defaults.
