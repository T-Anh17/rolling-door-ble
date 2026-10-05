#pragma once

#include <stddef.h>
#include <stdint.h>

#include "protocol.h"

namespace ble {

// Everything the NimBLE task receives is queued and handled in loop(), on one task.
struct Event {
  enum class Type : uint8_t {
    Connected,
    Disconnected,
    Command,         // data: COMMAND payload, 18-34 bytes
    BadCommand,      // COMMAND payload with a wrong length
    PairingRequest,  // data: PAIRING write
  };
  Type type;
  uint16_t connHandle;
  uint8_t length;
  uint8_t data[config::kPairingRequestLength];
};

// Starts the GATT server and advertising.
void begin();

// Pops the next event. Non-blocking; false if none is queued.
bool nextEvent(Event& out);

// Current CHALLENGE nonce (config::kNonceLength bytes).
const uint8_t* nonce();

// Replaces the CHALLENGE nonce and notifies the new value.
void rotateChallenge();

// Notifies STATUS with [command][result].
void notifyStatus(uint8_t command, Result result);

// Value returned by reads of PAIRING.
void setPairingResponse(const uint8_t* data, size_t length);
void clearPairingResponse();

// Adds or removes the "pairing open" flag in the scan response.
void setPairingAdvertised(bool open);

void disconnect(uint16_t connHandle);

}  // namespace ble
