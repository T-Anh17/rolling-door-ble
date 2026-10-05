#include <Arduino.h>

#include "rf.h"

namespace rf {

void begin() {
  Serial.println("[RF-FAKE] ready (no transmitter, serial log only)");
}

bool send(DoorCommand command) {
  Serial.printf("[RF-FAKE] send %s\n", commandName(static_cast<uint8_t>(command)));
  return true;
}

}  // namespace rf
