#pragma once

#include "protocol.h"

namespace ble {

// Starts the GATT server and advertising.
void begin();

// Pops the next received command frame. Non-blocking; false if none is queued.
bool receive(CommandFrame& out);

// Notifies STATUS with [command][result].
void notifyStatus(uint8_t command, Result result);

// Replaces the CHALLENGE nonce and notifies the new value.
void rotateChallenge();

}  // namespace ble
