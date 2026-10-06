#pragma once

#include <stddef.h>
#include <stdint.h>

#include "config.h"

// Remote buttons are numbered 1-8 (see remote_buttons.h). The first four are created as Up,
// Down, Lock and Unlock, like the RF remote: there is no Stop, Lock stops a moving door, and
// Unlock must be sent before Up or Down works again. Commands 01-04 press buttons 1-4 and are
// kept for older apps; PRESS takes any button.
namespace cmd {
constexpr uint8_t kPing = 0x00;         // no-op; confirms pairing and checks a key
constexpr uint8_t kUpdateMode = 0x05;   // admin, phase 5
constexpr uint8_t kOpenPairing = 0x06;  // admin
constexpr uint8_t kRevokePhone = 0x07;  // admin, args: [key id]
constexpr uint8_t kLearnRf = 0x09;      // admin, args: [button 1-8] to learn, [00] to cancel
constexpr uint8_t kClearRf = 0x0A;      // admin, args: [button 1-8]
constexpr uint8_t kPress = 0x0B;        // args: [button 1-8]
constexpr uint8_t kSetButton = 0x0C;    // admin, args: [button 1-8][icon][name UTF-8 0-32]
constexpr uint8_t kDeleteButton = 0x0D; // admin, args: [button 1-8]
constexpr uint8_t kPairing = 0x80;      // STATUS code for a PAIRING request
constexpr uint8_t kRfLearned = 0x81;    // STATUS code when learning started by kLearnRf ends
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

// COMMAND payload: [key id][command][args 0-34][HMAC-SHA256(key, nonce + command + args), first 16 bytes]
struct CommandFrame {
  uint8_t keyId;
  uint8_t command;
  uint8_t argsLength;
  uint8_t args[config::kMaxArgsLength];
  uint8_t mac[config::kMacLength];
};

// Returns false if the payload length is outside 18-52 bytes.
bool parseFrame(const uint8_t* data, size_t length, CommandFrame& out);

// True for commands that press a remote button: PRESS, and 01-04 for buttons 1-4.
bool isPress(uint8_t command);

// The button a press command asks for; 0 if its arguments are wrong.
uint8_t pressedButton(const CommandFrame& frame);

// True for commands only an admin phone may send.
bool isAdminCommand(uint8_t command);

// Short name for logs, "UNKNOWN" for unsupported codes.
const char* commandName(uint8_t command);
