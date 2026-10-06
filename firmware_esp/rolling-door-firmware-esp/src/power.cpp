#include "power.h"

#include <Arduino.h>

#include "config.h"

namespace power {
namespace {

// Resting LiPo voltage against charge, from full to empty. Readings while charging sit higher,
// so the percent is only rough then.
struct Point {
  uint16_t millivolts;
  uint8_t percent;
};
constexpr Point kCurve[] = {
    {4200, 100}, {4100, 90}, {4000, 80}, {3900, 65}, {3800, 50},
    {3750, 40},  {3700, 30}, {3650, 20}, {3600, 10}, {3500, 5}, {3300, 0},
};

uint32_t lastSampleAt = 0;
bool sampled = false;
uint16_t millivolts = 0;
uint8_t reported = kBatteryUnknown;

uint8_t percentFor(uint16_t mv) {
  if (mv < config::kBatteryMissingMv) {
    return kBatteryUnknown;
  }
  if (mv >= config::kBatteryUsbMv) {
    return kBatteryCharging;
  }
  if (mv >= kCurve[0].millivolts) {
    return 100;
  }
  for (size_t i = 1; i < sizeof(kCurve) / sizeof(kCurve[0]); i++) {
    const Point& high = kCurve[i - 1];
    const Point& low = kCurve[i];
    if (mv >= low.millivolts) {
      return static_cast<uint8_t>(low.percent + (mv - low.millivolts) * (high.percent - low.percent) /
                                                    (high.millivolts - low.millivolts));
    }
  }
  return 0;
}

// The board halves the cell voltage before the ADC; averaging smooths the ADC noise.
uint16_t measure() {
  digitalWrite(config::kBatteryEnablePin, HIGH);
  delay(config::kBatterySettleMs);
  uint32_t sum = 0;
  for (uint8_t i = 0; i < config::kBatterySamples; i++) {
    sum += analogReadMilliVolts(config::kBatteryPin);
  }
  digitalWrite(config::kBatteryEnablePin, LOW);
  return static_cast<uint16_t>(sum / config::kBatterySamples * config::kBatteryDivider);
}

}  // namespace

// analogReadMilliVolts uses 11 dB attenuation by default: up to ~3.1 V at the pin.
void begin() {
  pinMode(config::kBatteryEnablePin, OUTPUT);
  digitalWrite(config::kBatteryEnablePin, LOW);
  millivolts = measure();
  reported = percentFor(millivolts);
  sampled = true;
  lastSampleAt = millis();
  print();
}

void poll() {
  if (sampled && millis() - lastSampleAt < config::kBatterySampleMs) {
    return;
  }
  lastSampleAt = millis();
  sampled = true;
  millivolts = measure();
  const uint8_t percent = percentFor(millivolts);
  const bool isPercent = percent <= 100;
  const bool wasPercent = reported <= 100;
  if (isPercent != wasPercent || (!isPercent && percent != reported) ||
      (isPercent && abs(static_cast<int>(percent) - static_cast<int>(reported)) >= config::kBatteryReportStep)) {
    reported = percent;
  }
}

uint8_t batteryPercent() {
  return reported;
}

void print() {
  if (reported == kBatteryUnknown) {
    Serial.printf("[POWER] battery %u mV, no cell\n", millivolts);
  } else if (reported == kBatteryCharging) {
    Serial.printf("[POWER] battery %u mV, on USB (charging)\n", millivolts);
  } else {
    Serial.printf("[POWER] battery %u mV, %u%%\n", millivolts, reported);
  }
}

}  // namespace power
