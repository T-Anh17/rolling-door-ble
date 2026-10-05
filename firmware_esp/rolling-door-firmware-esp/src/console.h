#pragma once

// Serial console (needs a USB cable, so physical access): qr, keys, pair, wipe, help.
namespace console {

enum class Action {
  None,
  OpenPairing,
  WipeKeys,
};

// Reads Serial without blocking; handles qr/keys/help itself and returns the rest.
Action poll();

}  // namespace console
