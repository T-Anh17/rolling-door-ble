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

Action run(const String& command) {
  if (command == "qr") {
    device_secret::printQr();
  } else if (command == "keys") {
    key_store::print();
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
    Serial.println("[CONSOLE] commands: qr, keys, pair, wipe, battery, buttons, rf");
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
