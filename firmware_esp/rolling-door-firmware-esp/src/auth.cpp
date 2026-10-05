#include "auth.h"

#include <Arduino.h>
#include <mbedtls/md.h>
#include <string.h>

namespace auth {
namespace {

uint8_t failures = 0;
bool lockedOut = false;
uint32_t lockedAt = 0;

}  // namespace

void hmac16(const uint8_t* key, size_t keyLength, const uint8_t* data, size_t length,
            uint8_t out[config::kMacLength]) {
  uint8_t full[32];
  mbedtls_md_hmac(mbedtls_md_info_from_type(MBEDTLS_MD_SHA256), key, keyLength, data, length,
                  full);
  memcpy(out, full, config::kMacLength);
}

bool equal(const uint8_t* a, const uint8_t* b, size_t length) {
  uint8_t diff = 0;
  for (size_t i = 0; i < length; i++) {
    diff |= a[i] ^ b[i];
  }
  return diff == 0;
}

bool verifyFrame(const uint8_t key[config::kKeyLength], const uint8_t nonce[config::kNonceLength],
                 const CommandFrame& frame) {
  uint8_t message[config::kNonceLength + 1 + config::kMaxArgsLength];
  memcpy(message, nonce, config::kNonceLength);
  message[config::kNonceLength] = frame.command;
  memcpy(message + config::kNonceLength + 1, frame.args, frame.argsLength);

  uint8_t expected[config::kMacLength];
  hmac16(key, config::kKeyLength, message, config::kNonceLength + 1 + frame.argsLength, expected);
  return equal(expected, frame.mac, config::kMacLength);
}

bool isLockedOut() {
  if (lockedOut && millis() - lockedAt >= config::kLockoutMs) {
    lockedOut = false;
    failures = 0;
    Serial.println("[AUTH] lockout ended");
  }
  return lockedOut;
}

bool recordFailure() {
  if (lockedOut) {
    return false;
  }
  failures++;
  Serial.printf("[AUTH] failure %u/%u\n", failures, config::kMaxAuthFailures);
  if (failures >= config::kMaxAuthFailures) {
    lockedOut = true;
    lockedAt = millis();
    Serial.printf("[AUTH] locked out for %u s\n",
                  static_cast<unsigned>(config::kLockoutMs / 1000));
    return true;
  }
  return false;
}

void recordSuccess() {
  failures = 0;
}

}  // namespace auth
