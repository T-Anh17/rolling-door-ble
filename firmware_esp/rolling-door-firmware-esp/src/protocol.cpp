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

bool isPress(uint8_t command) {
  return (command >= 0x01 && command <= 0x04) || command == cmd::kPress;
}

uint8_t pressedButton(const CommandFrame& frame) {
  if (frame.command == cmd::kPress) {
    return frame.argsLength == 1 ? frame.args[0] : 0;
  }
  return frame.argsLength == 0 ? frame.command : 0;
}

bool isAdminCommand(uint8_t command) {
  return (command >= cmd::kUpdateMode && command <= cmd::kRevokePhone) ||
         command == cmd::kLearnRf || command == cmd::kClearRf || command == cmd::kSetButton ||
         command == cmd::kDeleteButton || command == cmd::kMakeAdmin || command == cmd::kInvite;
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
    case cmd::kLeave:       return "LEAVE";
    case cmd::kLearnRf:     return "LEARN_RF";
    case cmd::kClearRf:     return "CLEAR_RF";
    case cmd::kPress:       return "PRESS";
    case cmd::kSetButton:   return "SET_BUTTON";
    case cmd::kDeleteButton: return "DELETE_BUTTON";
    case cmd::kListPhones:  return "LIST_PHONES";
    case cmd::kRenamePhone: return "RENAME_PHONE";
    case cmd::kMakeAdmin:   return "MAKE_ADMIN";
    case cmd::kInvite:      return "INVITE";
    default:                return "UNKNOWN";
  }
}
