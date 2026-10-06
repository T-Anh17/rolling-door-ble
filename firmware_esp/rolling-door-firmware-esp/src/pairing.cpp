#include "pairing.h"

#include <Arduino.h>
#include <esp_random.h>
#include <mbedtls/ecdh.h>
#include <mbedtls/gcm.h>
#include <mbedtls/hkdf.h>
#include <mbedtls/md.h>
#include <string.h>

#include "auth.h"
#include "device_secret.h"
#include "key_store.h"

namespace pairing {
namespace {

constexpr char kTagLabel[] = "RDPAIR-A";
constexpr char kKdfInfo[] = "RDPAIR v1";
constexpr char kInviteLabel[] = "RDINVITE";
constexpr uint8_t kInviteResponse = 0x02;

bool windowOpen = false;
uint32_t openedAt = 0;

bool inviteActive = false;
uint32_t invitedAt = 0;
uint8_t invite[config::kSecretLength];  // the secret derived from the digits

void dropInvite() {
  inviteActive = false;
  memset(invite, 0, sizeof(invite));
}

// The QR code's secret works while no phone is paired, or in the window after open().
bool windowIsOpen() {
  if (key_store::count() == 0) {
    return true;
  }
  if (windowOpen && millis() - openedAt >= config::kPairingWindowMs) {
    windowOpen = false;
    Serial.println("[PAIR] window closed (timeout)");
  }
  return windowOpen;
}

bool inviteIsValid() {
  if (inviteActive && millis() - invitedAt >= config::kInviteMs) {
    dropInvite();
    Serial.println("[PAIR] invite expired");
  }
  return inviteActive;
}

int randomBytes(void*, unsigned char* out, size_t length) {
  esp_fill_random(out, length);
  return 0;
}

// tagA = HMAC-SHA256(secret, "RDPAIR-A" + nonce + appPublicKey), first 16 bytes.
bool verifyTag(const uint8_t* secret, const uint8_t* appPublic, const uint8_t* tag,
               const uint8_t* nonce) {
  uint8_t message[sizeof(kTagLabel) - 1 + config::kNonceLength + config::kPublicKeyLength];
  size_t offset = 0;
  memcpy(message, kTagLabel, sizeof(kTagLabel) - 1);
  offset += sizeof(kTagLabel) - 1;
  memcpy(message + offset, nonce, config::kNonceLength);
  offset += config::kNonceLength;
  memcpy(message + offset, appPublic, config::kPublicKeyLength);

  uint8_t expected[config::kMacLength];
  auth::hmac16(secret, config::kSecretLength, message, sizeof(message), expected);
  return auth::equal(expected, tag, config::kMacLength);
}

// ECDH P-256 with an ephemeral key, K = HKDF-SHA256(salt = nonce, ikm = Z + secret, info),
// then AES-256-GCM(K, plain, aad = appPublic + devicePublic).
// out: [0x00][device public key][iv][ciphertext][tag]
bool seal(const uint8_t* secret, const uint8_t* appPublic, const uint8_t* nonce,
          const uint8_t* plain, uint8_t* out) {
  mbedtls_ecp_group group;
  mbedtls_mpi privateKey, shared;
  mbedtls_ecp_point devicePoint, appPoint;
  mbedtls_ecp_group_init(&group);
  mbedtls_mpi_init(&privateKey);
  mbedtls_mpi_init(&shared);
  mbedtls_ecp_point_init(&devicePoint);
  mbedtls_ecp_point_init(&appPoint);

  uint8_t* devicePublic = out + 1;
  uint8_t* iv = devicePublic + config::kPublicKeyLength;
  uint8_t* cipher = iv + config::kIvLength;
  uint8_t* tag = cipher + config::kPairingPlainLength;

  uint8_t ikm[32 + config::kSecretLength];
  uint8_t sessionKey[32];
  size_t written = 0;
  bool ok =
      mbedtls_ecp_group_load(&group, MBEDTLS_ECP_DP_SECP256R1) == 0 &&
      mbedtls_ecp_point_read_binary(&group, &appPoint, appPublic, config::kPublicKeyLength) == 0 &&
      mbedtls_ecp_check_pubkey(&group, &appPoint) == 0 &&
      mbedtls_ecdh_gen_public(&group, &privateKey, &devicePoint, randomBytes, nullptr) == 0 &&
      mbedtls_ecp_point_write_binary(&group, &devicePoint, MBEDTLS_ECP_PF_UNCOMPRESSED, &written,
                                     devicePublic, config::kPublicKeyLength) == 0 &&
      written == config::kPublicKeyLength &&
      mbedtls_ecdh_compute_shared(&group, &shared, &appPoint, &privateKey, randomBytes,
                                  nullptr) == 0 &&
      mbedtls_mpi_write_binary(&shared, ikm, 32) == 0;

  if (ok) {
    memcpy(ikm + 32, secret, config::kSecretLength);
    ok = mbedtls_hkdf(mbedtls_md_info_from_type(MBEDTLS_MD_SHA256), nonce, config::kNonceLength,
                      ikm, sizeof(ikm), reinterpret_cast<const uint8_t*>(kKdfInfo),
                      sizeof(kKdfInfo) - 1, sessionKey, sizeof(sessionKey)) == 0;
  }

  if (ok) {
    uint8_t aad[2 * config::kPublicKeyLength];
    memcpy(aad, appPublic, config::kPublicKeyLength);
    memcpy(aad + config::kPublicKeyLength, devicePublic, config::kPublicKeyLength);
    esp_fill_random(iv, config::kIvLength);

    mbedtls_gcm_context gcm;
    mbedtls_gcm_init(&gcm);
    ok = mbedtls_gcm_setkey(&gcm, MBEDTLS_CIPHER_ID_AES, sessionKey, 256) == 0 &&
         mbedtls_gcm_crypt_and_tag(&gcm, MBEDTLS_GCM_ENCRYPT, config::kPairingPlainLength, iv,
                                   config::kIvLength, aad, sizeof(aad), plain, cipher,
                                   config::kGcmTagLength, tag) == 0;
    mbedtls_gcm_free(&gcm);
  }

  memset(ikm, 0, sizeof(ikm));
  memset(sessionKey, 0, sizeof(sessionKey));
  mbedtls_ecp_point_free(&appPoint);
  mbedtls_ecp_point_free(&devicePoint);
  mbedtls_mpi_free(&shared);
  mbedtls_mpi_free(&privateKey);
  mbedtls_ecp_group_free(&group);

  out[0] = static_cast<uint8_t>(Result::Ok);
  return ok;
}

}  // namespace

bool isOpen() {
  const bool window = windowIsOpen();
  return inviteIsValid() || window;
}

void open(const char* reason) {
  windowOpen = true;
  openedAt = millis();
  Serial.printf("[PAIR] window open for %u s (%s)\n",
                static_cast<unsigned>(config::kPairingWindowMs / 1000), reason);
}

void close() {
  if (windowOpen) {
    windowOpen = false;
    Serial.println("[PAIR] window closed");
  }
  if (inviteActive) {
    dropInvite();
    Serial.println("[PAIR] invite used");
  }
}

void createInvite(const uint8_t adminKey[config::kKeyLength], uint8_t* response,
                  size_t& responseLength) {
  // Uniform 0-99999999: drop the top of the 32-bit range that does not divide evenly.
  uint32_t value;
  do {
    value = esp_random();
  } while (value >= 4200000000u);
  value %= 100000000u;
  uint8_t digits[config::kInviteDigits];
  for (size_t i = config::kInviteDigits; i-- > 0;) {
    digits[i] = static_cast<uint8_t>('0' + value % 10);
    value /= 10;
  }
  uint8_t derived[config::kMacLength];
  auth::hmac16(digits, sizeof(digits), reinterpret_cast<const uint8_t*>(kInviteLabel),
               sizeof(kInviteLabel) - 1, derived);
  memcpy(invite, derived, config::kSecretLength);
  memset(derived, 0, sizeof(derived));
  inviteActive = true;
  invitedAt = millis();

  uint8_t* salt = response + 1;
  uint8_t* masked = salt + config::kNonceLength;
  esp_fill_random(salt, config::kNonceLength);
  uint8_t message[sizeof(kInviteLabel) - 1 + config::kNonceLength];
  memcpy(message, kInviteLabel, sizeof(kInviteLabel) - 1);
  memcpy(message + sizeof(kInviteLabel) - 1, salt, config::kNonceLength);
  uint8_t mask[config::kMacLength];
  auth::hmac16(adminKey, config::kKeyLength, message, sizeof(message), mask);
  for (size_t i = 0; i < config::kInviteDigits; i++) {
    masked[i] = digits[i] ^ mask[i];
  }
  memset(mask, 0, sizeof(mask));
  memset(digits, 0, sizeof(digits));
  response[0] = kInviteResponse;
  responseLength = config::kInviteResponseLength;
  Serial.printf("[PAIR] invite created, valid for %u s\n",
                static_cast<unsigned>(config::kInviteMs / 1000));
}

Result handleRequest(const uint8_t* request, size_t length,
                     const uint8_t nonce[config::kNonceLength], uint16_t connHandle,
                     uint8_t* response, size_t& responseLength) {
  Result result = Result::Ok;
  const uint8_t* appPublic = request + 1;
  const uint8_t* tag = appPublic + config::kPublicKeyLength;
  // The QR code's secret while the window is open, else a valid invite's.
  const bool window = windowIsOpen();
  const bool invited = inviteIsValid();
  const uint8_t* secret = nullptr;

  if (length != config::kPairingRequestLength || request[0] != 0x01) {
    result = Result::BadCommand;
  } else if (auth::isLockedOut()) {
    result = Result::LockedOut;
  } else if (!window && !invited) {
    result = Result::PairingClosed;
  } else {
    if (window && verifyTag(device_secret::secret(), appPublic, tag, nonce)) {
      secret = device_secret::secret();
    } else if (invited && verifyTag(invite, appPublic, tag, nonce)) {
      secret = invite;
    }
    if (secret == nullptr) {
      Serial.println("[PAIR] wrong QR secret");
      auth::recordFailure();
      result = Result::AuthFailed;
    }
  }

  if (result == Result::Ok) {
    const int id = key_store::allocatePending(connHandle);
    if (id < 0) {
      result = Result::TableFull;
    } else {
      bool pending = false;
      const key_store::Slot* slot = key_store::find(id, connHandle, &pending);
      uint8_t plain[config::kPairingPlainLength];
      plain[0] = static_cast<uint8_t>(id);
      plain[1] = slot->role;
      memcpy(plain + 2, slot->key, config::kKeyLength);
      const uint32_t started = millis();
      const bool sealed = seal(secret, appPublic, nonce, plain, response);
      memset(plain, 0, sizeof(plain));
      if (sealed) {
        auth::recordSuccess();
        responseLength = config::kPairingResponseLength;
        Serial.printf("[PAIR] key sent for slot %d (%s) in %u ms, waiting for confirmation\n", id,
                      slot->role == static_cast<uint8_t>(Role::Admin) ? "admin" : "normal",
                      static_cast<unsigned>(millis() - started));
        return Result::Ok;
      }
      key_store::dropPending(connHandle);
      Serial.println("[PAIR] crypto failure");
      result = Result::BadCommand;
    }
  }

  response[0] = static_cast<uint8_t>(result);
  responseLength = 1;
  return result;
}

}  // namespace pairing
