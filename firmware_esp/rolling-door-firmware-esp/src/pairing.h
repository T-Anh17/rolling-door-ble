#pragma once

#include <stddef.h>
#include <stdint.h>

#include "protocol.h"

namespace pairing {

// Pairing with the QR code's secret is open while no phone is paired, and for kPairingWindowMs
// after open(). Pairing with an invite works while it is valid. isOpen() is true in either case
// (it sets the "pairing open" flag in the scan response).
bool isOpen();
void open(const char* reason);

// Closes the window and drops the invite: called once a new phone is confirmed.
void close();

// INVITE from the admin: 8 random digits for one new phone, valid for kInviteMs or until a
// phone is confirmed with them; they replace any earlier invite. The new phone pairs as with the
// QR code, using secret = HMAC-SHA256(key = the digits in ASCII, "RDINVITE"), first 16 bytes.
// The admin phone shows the digits, and a QR code in the device's format with that secret.
// response (kInviteResponseLength bytes) carries the digits masked with the admin's key, so
// they never go over the air in clear:
//   [0x02][salt 16][digits XOR HMAC-SHA256(admin key, "RDINVITE" + salt), first 8 bytes]
void createInvite(const uint8_t adminKey[config::kKeyLength], uint8_t* response, size_t& responseLength);

// Handles a PAIRING write [0x01][app public key][tagA] with the current nonce.
// On success, reserves a pending slot and fills response (kPairingResponseLength bytes).
// On failure, response is the single result byte.
Result handleRequest(const uint8_t* request, size_t length,
                     const uint8_t nonce[config::kNonceLength], uint16_t connHandle,
                     uint8_t* response, size_t& responseLength);

}  // namespace pairing
