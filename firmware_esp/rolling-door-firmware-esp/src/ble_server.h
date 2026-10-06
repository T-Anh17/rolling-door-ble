#pragma once

#include <stddef.h>
#include <stdint.h>

#include "protocol.h"

// Up to config::kMaxConnections phones at once: the board keeps advertising while it has room,
// so one phone with the app open does not lock the others out. Each connection has its own
// CHALLENGE nonce, gets its own STATUS notifications, and reads its own PAIRING and PHONES
// values; INFO is notified to all.
namespace ble {

// Everything the NimBLE task receives is queued and handled in loop(), on one task.
struct Event {
  enum class Type : uint8_t {
    Connected,
    Disconnected,
    Command,         // data: COMMAND payload, 18-52 bytes
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

// This connection's CHALLENGE nonce (config::kNonceLength bytes); nullptr if it is gone.
const uint8_t* nonce(uint16_t connHandle);

// Replaces this connection's nonce and notifies it the new value.
void rotateChallenge(uint16_t connHandle);

// Notifies STATUS with [command][result] to this connection.
void notifyStatus(uint16_t connHandle, uint8_t command, Result result);

// Sets INFO to [power source][battery percent][learned RF buttons][button list revision]
// and notifies it to every connection.
void setInfo(uint8_t powerSource, uint8_t batteryPercent, uint8_t learnedMask, uint8_t revision);

// Value returned by reads of BUTTONS (see remote_buttons::serialize).
void setButtons(const uint8_t* data, size_t length);

// What this connection reads from PHONES (see key_store::serialize); empty for the others and
// after it disconnects.
void setPhones(uint16_t connHandle, const uint8_t* data, size_t length);

// What this connection reads from PAIRING: a pairing response or an invite; empty for the
// others and after it disconnects.
void setPairingResponse(uint16_t connHandle, const uint8_t* data, size_t length);

// Adds or removes the "pairing open" flag in the scan response.
void setPairingAdvertised(bool open);

void disconnect(uint16_t connHandle);

}  // namespace ble
