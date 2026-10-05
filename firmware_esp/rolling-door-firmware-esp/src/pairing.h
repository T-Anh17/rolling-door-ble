#pragma once

#include <stddef.h>
#include <stdint.h>

#include "protocol.h"

namespace pairing {

// Pairing is always open while no phone is paired; otherwise for kPairingWindowMs after open().
bool isOpen();
void open(const char* reason);
void close();

// Handles a PAIRING write [0x01][app public key][tagA] with the current nonce.
// On success, reserves a pending slot and fills response (kPairingResponseLength bytes).
// On failure, response is the single result byte.
Result handleRequest(const uint8_t* request, size_t length,
                     const uint8_t nonce[config::kNonceLength], uint16_t connHandle,
                     uint8_t* response, size_t& responseLength);

}  // namespace pairing
