#pragma once

namespace buttons {

enum class Event {
  None,
  OpenPairing,  // BOOT held for kBootHoldMs
  WipeKeys,     // KEY held for kWipeHoldMs
};

void begin();

// Call every loop; returns at most one event per long press.
Event poll();

}  // namespace buttons
