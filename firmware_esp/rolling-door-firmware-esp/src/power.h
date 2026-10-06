#pragma once

#include <stdint.h>

// Power status reported in INFO. Phase 4 links power_fake.cpp (values set from the serial
// console); phase 6 replaces it with the mains detection divider and the battery voltage.
namespace power {

enum class Source : uint8_t {
  Mains = 0x00,
  Battery = 0x01,
};

constexpr uint8_t kBatteryUnknown = 0xFF;  // no battery fitted, or not measured

struct Status {
  Source source;
  uint8_t batteryPercent;  // 0-100, or kBatteryUnknown

  bool operator==(const Status& other) const {
    return source == other.source && batteryPercent == other.batteryPercent;
  }
  bool operator!=(const Status& other) const { return !(*this == other); }
};

void begin();

// Latest status. Cheap: called on every loop().
Status read();

// Console "power" command. Only the fake layer acts on it; returns false if ignored.
bool simulate(Source source, uint8_t batteryPercent);

}  // namespace power
