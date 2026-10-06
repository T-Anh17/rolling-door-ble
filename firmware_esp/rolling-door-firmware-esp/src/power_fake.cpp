#include <Arduino.h>

#include "power.h"

namespace power {
namespace {

Status current = {Source::Mains, kBatteryUnknown};

}  // namespace

void begin() {
  Serial.println("[POWER-FAKE] ready (mains, no battery; change with the \"power\" command)");
}

Status read() {
  return current;
}

bool simulate(Source source, uint8_t batteryPercent) {
  current = {source, batteryPercent};
  if (batteryPercent == kBatteryUnknown) {
    Serial.printf("[POWER-FAKE] %s, battery not measured\n",
                  source == Source::Mains ? "mains" : "battery");
  } else {
    Serial.printf("[POWER-FAKE] %s, battery %u%%\n", source == Source::Mains ? "mains" : "battery",
                  batteryPercent);
  }
  return true;
}

}  // namespace power
