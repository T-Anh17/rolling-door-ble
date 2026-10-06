#pragma once

#include "protocol.h"

// RF 433MHz layer: rc-switch transmitter on kRfTxPin; receiver on kRfRxPin, used only while
// learning, scanning or self-testing, with its own protocol 1 decoder. One fixed code per
// door button, kept in NVS (namespace "rf").
namespace rf {

void begin();

// Transmits the learned code for the command. Returns false if the button has no code yet
// or a code is being learned.
bool send(DoorCommand command);

// Listens on the receiver for up to kRfLearnTimeoutMs. The code is saved once the same code
// is received twice in a row. Returns false if already learning.
bool startLearn(DoorCommand button);
void cancelLearn();
bool isLearning();

// Counts edges on the receiver pin for 10s and logs them every second, to check the wiring.
// Returns false while learning or already scanning.
bool startScan();

// Sends a made-up test code with the transmitter and decodes it with the receiver.
// Returns false while learning or scanning.
bool selfTest();

// Sends each learned code and decodes it with the receiver: checks that what the board
// transmits is exactly the code learned from that remote button. Returns false while busy.
bool verify();

// Call every loop(): handles the receiver and the timeouts while learning or scanning.
void poll();

void clear(DoorCommand button);
void clearAll();

// Lists the buttons with protocol, bit count and pulse length. Never prints the code itself.
void print();

}  // namespace rf
