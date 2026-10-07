#include "console.h"

#include <Arduino.h>

#include "device_secret.h"
#include "key_store.h"
#include "power.h"
#include "remote_buttons.h"
#include "rf.h"

namespace console {
namespace {

String line;

constexpr const char* kRfUsage =
    "[CONSOLE] usage: rf list | rf learn <button> | rf send <button> | rf verify | rf cancel | "
    "rf scan | rf selftest | "
    "rf clear [button]   (button: 1-8)";

bool parseButton(const String& name, uint8_t& out) {
  const long id = name.toInt();
  if (name.length() != 1 || id < 1 || id > config::kMaxButtons) {
    return false;
  }
  out = static_cast<uint8_t>(id);
  return true;
}

// "rf list|learn|send|cancel|clear [button]": learn and test the remote's codes.
void runRf(const String& args) {
  String verb = args;
  String name;
  const int space = args.indexOf(' ');
  if (space >= 0) {
    verb = args.substring(0, space);
    name = args.substring(space + 1);
    name.trim();
  }
  uint8_t button = 0;
  const bool hasButton = parseButton(name, button);
  if (verb == "list" && name.length() == 0) {
    rf::print();
  } else if (verb == "verify" && name.length() == 0) {
    if (!rf::verify()) {
      Serial.println("[CONSOLE] receiver busy");
    }
  } else if (verb == "selftest" && name.length() == 0) {
    if (!rf::selfTest()) {
      Serial.println("[CONSOLE] receiver busy");
    }
  } else if (verb == "scan" && name.length() == 0) {
    if (!rf::startScan()) {
      Serial.println("[CONSOLE] receiver busy");
    }
  } else if (verb == "cancel" && name.length() == 0) {
    rf::cancelLearn();
  } else if (verb == "clear" && name.length() == 0) {
    rf::clearAll();
  } else if (verb == "clear" && hasButton) {
    rf::clear(button);
  } else if (verb == "learn" && hasButton) {
    if (!rf::startLearn(button)) {
      Serial.println("[CONSOLE] already learning, \"rf cancel\" first");
    }
  } else if (verb == "send" && hasButton) {
    rf::send(button);
  } else {
    Serial.println(kRfUsage);
  }
}

// No connection handle: find() then returns saved slots only, never one still pairing.
constexpr uint16_t kNoConnection = 0xFFFF;

constexpr const char* kKeysUsage =
    "[CONSOLE] usage: keys | keys admin <slot> | keys revoke <slot>   (slot: 0-7)";

// "keys admin|revoke <slot>": fixes the table from the board itself (a USB cable means
// physical access, as for wipe), for example when the admin phone's app was uninstalled
// without leaving first and its slot is still the admin.
void runKeys(const String& args) {
  const int space = args.indexOf(' ');
  const String verb = space >= 0 ? args.substring(0, space) : args;
  String number = space >= 0 ? args.substring(space + 1) : String();
  number.trim();
  const long id = number.toInt();
  if (number.length() != 1 || !isDigit(number[0]) || id >= static_cast<long>(config::kSlotCount)) {
    Serial.println(kKeysUsage);
    return;
  }
  const uint8_t slot = static_cast<uint8_t>(id);
  int admin = -1;
  for (uint8_t i = 0; i < config::kSlotCount; i++) {
    const key_store::Slot* saved = key_store::find(i, kNoConnection);
    if (saved != nullptr && saved->role == static_cast<uint8_t>(Role::Admin)) {
      admin = i;
    }
  }
  if (verb == "admin") {
    if (admin == slot) {
      Serial.printf("[CONSOLE] slot %u is already the admin\n", slot);
    } else if (admin < 0 || !key_store::transferAdmin(static_cast<uint8_t>(admin), slot)) {
      Serial.printf("[CONSOLE] slot %u is not a saved phone\n", slot);
    }
  } else if (verb == "revoke") {
    if (admin == slot) {
      Serial.println("[CONSOLE] slot is the admin: make another phone admin first");
    } else if (!key_store::revoke(slot)) {
      Serial.printf("[CONSOLE] slot %u is not a saved phone\n", slot);
    }
  } else {
    Serial.println(kKeysUsage);
  }
}

Action run(const String& command) {
  if (command == "qr") {
    device_secret::printQr();
  } else if (command == "keys") {
    key_store::print();
  } else if (command.startsWith("keys ")) {
    runKeys(command.substring(5));
  } else if (command == "pair") {
    return Action::OpenPairing;
  } else if (command == "wipe") {
    return Action::WipeKeys;
  } else if (command == "battery") {
    power::print();
  } else if (command == "buttons") {
    remote_buttons::print();
  } else if (command == "rf" || command.startsWith("rf ")) {
    runRf(command.length() > 3 ? command.substring(3) : String());
  } else if (command.length() > 0) {
    Serial.println("[CONSOLE] commands: qr, keys [admin|revoke <slot>], pair, wipe, battery, buttons, rf");
  }
  return Action::None;
}

}  // namespace

Action poll() {
  while (Serial.available() > 0) {
    const char c = static_cast<char>(Serial.read());
    if (c == '\r' || c == '\n') {
      String command = line;
      line = "";
      command.trim();
      return run(command);
    }
    if (line.length() < 32) {
      line += c;
    }
  }
  return Action::None;
}

}  // namespace console
