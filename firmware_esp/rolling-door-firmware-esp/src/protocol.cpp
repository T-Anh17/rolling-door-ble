#include "protocol.h"

#include <string.h>

bool parseFrame(const uint8_t* data, size_t length, CommandFrame& out) {
  if (data == nullptr || length != config::kFrameLength) {
    return false;
  }
  out.keyId = data[0];
  out.command = data[1];
  memcpy(out.mac, data + 2, config::kMacLength);
  return true;
}

bool isDoorCommand(uint8_t command) {
  return command >= static_cast<uint8_t>(DoorCommand::Up) &&
         command <= static_cast<uint8_t>(DoorCommand::Unlock);
}

const char* commandName(uint8_t command) {
  switch (command) {
    case 0x01: return "UP";
    case 0x02: return "DOWN";
    case 0x03: return "LOCK";
    case 0x04: return "UNLOCK";
    default:   return "UNKNOWN";
  }
}
