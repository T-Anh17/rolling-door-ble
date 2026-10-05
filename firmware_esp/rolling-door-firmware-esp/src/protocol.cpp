#include "protocol.h"

#include <string.h>

bool parseFrame(const uint8_t* data, size_t length, CommandFrame& out) {
  if (data == nullptr || length < config::kMinFrameLength || length > config::kMaxFrameLength) {
    return false;
  }
  out.keyId = data[0];
  out.command = data[1];
  out.argsLength = static_cast<uint8_t>(length - config::kMinFrameLength);
  memcpy(out.args, data + 2, out.argsLength);
  memcpy(out.mac, data + 2 + out.argsLength, config::kMacLength);
  return true;
}

bool isDoorCommand(uint8_t command) {
  return command >= static_cast<uint8_t>(DoorCommand::Up) &&
         command <= static_cast<uint8_t>(DoorCommand::Unlock);
}

bool isAdminCommand(uint8_t command) {
  return command >= cmd::kUpdateMode && command <= cmd::kRevokePhone;
}

const char* commandName(uint8_t command) {
  switch (command) {
    case cmd::kPing:        return "PING";
    case 0x01:              return "UP";
    case 0x02:              return "DOWN";
    case 0x03:              return "LOCK";
    case 0x04:              return "UNLOCK";
    case cmd::kUpdateMode:  return "UPDATE_MODE";
    case cmd::kOpenPairing: return "OPEN_PAIRING";
    case cmd::kRevokePhone: return "REVOKE_PHONE";
    default:                return "UNKNOWN";
  }
}
