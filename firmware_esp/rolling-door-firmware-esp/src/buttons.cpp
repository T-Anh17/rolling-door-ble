#include "buttons.h"

#include <Arduino.h>

#include "config.h"

namespace buttons {
namespace {

constexpr uint32_t kDebounceMs = 50;

struct HoldButton {
  uint8_t pin;
  uint32_t holdMs;
  Event event;
  bool pressed;
  bool fired;
  uint32_t changedAt;

  // Debounced press; fires once when held long enough, re-arms on release.
  Event poll(uint32_t now) {
    const bool down = digitalRead(pin) == LOW;
    if (down != pressed) {
      if (now - changedAt < kDebounceMs) {
        return Event::None;
      }
      pressed = down;
      changedAt = now;
      fired = false;
    }
    if (pressed && !fired && now - changedAt >= holdMs) {
      fired = true;
      return event;
    }
    return Event::None;
  }
};

HoldButton boot = {config::kBootPin, config::kBootHoldMs, Event::OpenPairing, false, false, 0};
HoldButton wipe = {config::kWipePin, config::kWipeHoldMs, Event::WipeKeys, false, false, 0};

}  // namespace

void begin() {
  pinMode(config::kBootPin, INPUT_PULLUP);
  pinMode(config::kWipePin, INPUT_PULLUP);
}

Event poll() {
  const uint32_t now = millis();
  const Event event = boot.poll(now);
  return event != Event::None ? event : wipe.poll(now);
}

}  // namespace buttons
