#pragma once

#include <stdint.h>

#include "config.h"

// Per-device setup secret, created on first boot and kept in NVS (namespace "device").
// It is shown as a QR code: RDOOR1:<BLE MAC, 12 hex>:<secret, base32>.
// Wiping the phone keys keeps the secret, so a printed QR code stays valid.
namespace device_secret {

void begin();

const uint8_t* secret();  // config::kSecretLength bytes

// Prints the QR text and the QR code itself to Serial.
void printQr();

}  // namespace device_secret
