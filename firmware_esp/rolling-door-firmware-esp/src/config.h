#pragma once

#include <stddef.h>
#include <stdint.h>

namespace config {

// One random base UUID; only the second 16-bit group of the first field changes.
constexpr const char* kServiceUuid   = "a7930001-966e-4240-b881-5c2e2f2203a8";
constexpr const char* kChallengeUuid = "a7930002-966e-4240-b881-5c2e2f2203a8";
constexpr const char* kCommandUuid   = "a7930003-966e-4240-b881-5c2e2f2203a8";
constexpr const char* kStatusUuid    = "a7930004-966e-4240-b881-5c2e2f2203a8";
constexpr const char* kInfoUuid      = "a7930005-966e-4240-b881-5c2e2f2203a8";
constexpr const char* kPairingUuid   = "a7930006-966e-4240-b881-5c2e2f2203a8";
constexpr const char* kButtonsUuid   = "a7930007-966e-4240-b881-5c2e2f2203a8";

// Advertising interval in units of 0.625ms: 32 = 20ms, 48 = 30ms. Short, so a phone that
// connects directly finds the board within one or two of its scan windows. Costs more power,
// which only matters on battery (phase 6 can lengthen it there).
constexpr uint16_t kAdvMinInterval = 32;
constexpr uint16_t kAdvMaxInterval = 48;
// Scan response manufacturer data while pairing is open: [company id 0xFFFF][0x01].
constexpr uint16_t kManufacturerId = 0xFFFF;

// COMMAND: [key id][command][args 0-34][truncated HMAC 16]. Frames over 20 bytes (only
// SET_BUTTON) need a larger MTU, which the app asks for before sending one.
constexpr size_t kNonceLength = 16;
constexpr size_t kMacLength = 16;
constexpr size_t kMaxArgsLength = 34;
constexpr size_t kMinFrameLength = 2 + kMacLength;
constexpr size_t kMaxFrameLength = kMinFrameLength + kMaxArgsLength;

// Keys and pairing.
constexpr size_t kSecretLength = 16;     // device setup secret, shown as a QR code
constexpr size_t kKeyLength = 32;        // per-phone key
constexpr size_t kSlotCount = 8;
constexpr size_t kPublicKeyLength = 65;  // uncompressed P-256 point
constexpr size_t kIvLength = 12;
constexpr size_t kGcmTagLength = 16;
constexpr size_t kPairingPlainLength = 2 + kKeyLength;  // key id + role + key
// PAIRING write: [0x01][app public key][tagA]
constexpr size_t kPairingRequestLength = 1 + kPublicKeyLength + kMacLength;
// PAIRING read: [0x00][device public key][iv][ciphertext][GCM tag]
constexpr size_t kPairingResponseLength =
    1 + kPublicKeyLength + kIvLength + kPairingPlainLength + kGcmTagLength;

constexpr uint32_t kPairingWindowMs = 60 * 1000;
constexpr uint8_t kMaxAuthFailures = 5;
constexpr uint32_t kLockoutMs = 60 * 1000;

// Buttons (active low).
constexpr uint8_t kBootPin = 0;   // hold to open pairing
constexpr uint8_t kWipePin = 14;  // hold to erase all phone keys
constexpr uint32_t kBootHoldMs = 3 * 1000;
constexpr uint32_t kWipeHoldMs = 10 * 1000;

// RF 433MHz. TX drives the FS1000A DATA pin directly; RX reads the XY-MK-5V DATA through
// a 10k/20k divider (the receiver runs from 5V).
constexpr uint8_t kRfTxPin = 13;
constexpr uint8_t kRfRxPin = 12;
constexpr uint8_t kRfRepeat = 10;  // frames per command, like holding the remote button briefly
constexpr uint32_t kRfLearnTimeoutMs = 15 * 1000;

// Remote buttons the admin sets up: id 1-8, each with an icon, a name and one RF code.
constexpr uint8_t kMaxButtons = 8;
constexpr size_t kButtonNameLength = 32;  // UTF-8 bytes

constexpr size_t kEventQueueDepth = 6;

}  // namespace config
