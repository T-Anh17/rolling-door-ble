#pragma once

#include "protocol.h"

// RF 433MHz transmitter. Phase 1 links rf_fake.cpp (serial log only);
// phase 6 replaces it with an rc-switch implementation.
namespace rf {

void begin();

// Transmits the learned code for the command. Returns false on failure.
bool send(DoorCommand command);

}  // namespace rf
