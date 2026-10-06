#include "console.h"

#include <Arduino.h>

#include "device_secret.h"
#include "key_store.h"
#include "power.h"

namespace console {
namespace {

String line;

// "power mains|battery [percent]": sets the fake power status that INFO reports.
// Without a percent the battery counts as not measured.
void runPower(const String& args) {
  String source = args;
  String percent;
  const int space = args.indexOf(' ');
  if (space >= 0) {
    source = args.substring(0, space);
    percent = args.substring(space + 1);
    percent.trim();
  }
  uint8_t battery = power::kBatteryUnknown;
  if (percent.length() > 0) {
    const long value = percent.toInt();
    if (value < 0 || value > 100 || (value == 0 && percent != "0")) {
      Serial.println("[CONSOLE] usage: power mains|battery [0-100]");
      return;
    }
    battery = static_cast<uint8_t>(value);
  }
  if (source == "mains") {
    power::simulate(power::Source::Mains, battery);
  } else if (source == "battery") {
    power::simulate(power::Source::Battery, battery);
  } else {
    Serial.println("[CONSOLE] usage: power mains|battery [0-100]");
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
  } else if (command.startsWith("power ")) {
    runPower(command.substring(6));
  } else if (command.length() > 0) {
    Serial.println("[CONSOLE] commands: qr, keys, pair, wipe, power mains|battery [0-100]");
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
