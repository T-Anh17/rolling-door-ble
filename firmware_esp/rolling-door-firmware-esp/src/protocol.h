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
};

namespace cmd {
constexpr uint8_t kPing = 0x00;         // no-op; confirms pairing and checks a key
constexpr uint8_t kUpdateMode = 0x05;   // admin, phase 5
constexpr uint8_t kOpenPairing = 0x06;  // admin
constexpr uint8_t kRevokePhone = 0x07;  // admin, args: [key id]
constexpr uint8_t kPairing = 0x80;      // STATUS code for a PAIRING request
}  // namespace cmd

enum class Result : uint8_t {
  Ok = 0x00,
  BadCommand = 0x01,
  AuthFailed = 0x02,
  RfError = 0x03,
  NotPermitted = 0x04,
  LockedOut = 0x05,
  PairingClosed = 0x06,
  TableFull = 0x07,
};

enum class Role : uint8_t {
  Normal = 0x00,
  Admin = 0x01,
};

// COMMAND payload: [key id][command][args 0-16][HMAC-SHA256(key, nonce + command + args), first 16 bytes]
struct CommandFrame {
  uint8_t keyId;
  uint8_t command;
  uint8_t argsLength;
  uint8_t args[config::kMaxArgsLength];
  uint8_t mac[config::kMacLength];
};

// Returns false if the payload length is outside 18-34 bytes.
bool parseFrame(const uint8_t* data, size_t length, CommandFrame& out);

// True for the four door commands (Up, Down, Lock, Unlock).
bool isDoorCommand(uint8_t command);

// True for commands only an admin phone may send.
bool isAdminCommand(uint8_t command);

// Short name for logs, "UNKNOWN" for unsupported codes.
const char* commandName(uint8_t command);
