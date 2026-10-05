#pragma once

#include <stddef.h>
#include <stdint.h>

namespace config {

constexpr const char* kDeviceName = "RollingDoor";

// One random base UUID; only the second 16-bit group of the first field changes.
constexpr const char* kServiceUuid   = "a7930001-966e-4240-b881-5c2e2f2203a8";
constexpr const char* kChallengeUuid = "a7930002-966e-4240-b881-5c2e2f2203a8";
constexpr const char* kCommandUuid   = "a7930003-966e-4240-b881-5c2e2f2203a8";
constexpr const char* kStatusUuid    = "a7930004-966e-4240-b881-5c2e2f2203a8";
constexpr const char* kInfoUuid      = "a7930005-966e-4240-b881-5c2e2f2203a8";
constexpr const char* kPairingUuid   = "a7930006-966e-4240-b881-5c2e2f2203a8";

// Advertising interval in units of 0.625ms: 160 = 100ms, 320 = 200ms.
constexpr uint16_t kAdvMinInterval = 160;
constexpr uint16_t kAdvMaxInterval = 320;
// Scan response manufacturer data while pairing is open: [company id 0xFFFF][0x01].
constexpr uint16_t kManufacturerId = 0xFFFF;

// COMMAND: [key id][command][args 0-16][truncated HMAC 16]
constexpr size_t kNonceLength = 16;
constexpr size_t kMacLength = 16;
constexpr size_t kMaxArgsLength = 16;
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

constexpr size_t kEventQueueDepth = 6;

}  // namespace config
