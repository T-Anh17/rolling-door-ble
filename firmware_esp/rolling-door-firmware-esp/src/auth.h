#pragma once

#include <stddef.h>
#include <stdint.h>

#include "protocol.h"

namespace auth {

// HMAC-SHA256(key, data), truncated to the first 16 bytes.
void hmac16(const uint8_t* key, size_t keyLength, const uint8_t* data, size_t length,
            uint8_t out[config::kMacLength]);

// Constant-time comparison.
bool equal(const uint8_t* a, const uint8_t* b, size_t length);

// Checks frame.mac against HMAC(key, nonce + command + args).
bool verifyFrame(const uint8_t key[config::kKeyLength], const uint8_t nonce[config::kNonceLength],
                 const CommandFrame& frame);

// Failure counter: kMaxAuthFailures consecutive failures lock everything for kLockoutMs.
bool isLockedOut();
// Returns true if this failure just triggered the lockout.
bool recordFailure();
void recordSuccess();

}  // namespace auth
