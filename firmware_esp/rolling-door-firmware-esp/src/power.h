#pragma once

#include <stdint.h>

// The LiPo cell's charge, reported in INFO. The cell keeps the board up through a power cut;
// the RF modules run from USB 5V only, so the door cannot move on battery anyway. On USB the
// voltage pin sees the charger, so the charge is only known on battery. INFO's first byte stays 00.
namespace power {

constexpr uint8_t kBatteryUnknown = 0xFF;   // no cell, or not measured yet
constexpr uint8_t kBatteryCharging = 0xFE;  // on USB: charging or full, the percent is not known

void begin();

// Measures every config::kBatterySampleMs; call from loop().
void poll();

// 0-100, kBatteryCharging or kBatteryUnknown. A percent changes only by
// config::kBatteryReportStep or more, so INFO is not notified for every bit of ADC noise.
uint8_t batteryPercent();

// Console "battery": the last reading in millivolts and percent.
void print();

}  // namespace power
