#pragma once

#include <stddef.h>
#include <stdint.h>

#include "config.h"

// Same buttons as the RF remote. There is no Stop: Lock stops a moving door,
// and Unlock must be sent before Up or Down works again.
enum class DoorCommand : uint8_t {
  Up = 0x01,
  Down = 0x02,
  Lock = 0x03,
  Unlock = 0x04,
  // 0x05-0x07 (admin commands) are added in phase 2.
};

enum class Result : uint8_t {
  Ok = 0x00,
  BadCommand = 0x01,
  AuthFailed = 0x02,
  RfError = 0x03,
};

// COMMAND payload: [key id][command][HMAC-SHA256(key, nonce + command), first 16 bytes]
struct CommandFrame {
  uint8_t keyId;
  uint8_t command;
  uint8_t mac[config::kMacLength];
};

// Returns false if the payload is not exactly one frame long.
bool parseFrame(const uint8_t* data, size_t length, CommandFrame& out);

// True for the four door commands (Up, Down, Lock, Unlock).
bool isDoorCommand(uint8_t command);

// Short name for logs, "UNKNOWN" for unsupported codes.
const char* commandName(uint8_t command);
