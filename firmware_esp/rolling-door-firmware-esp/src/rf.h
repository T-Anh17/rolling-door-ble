#pragma once

#include <stdint.h>

// RF 433MHz layer: rc-switch transmitter on kRfTxPin; receiver on kRfRxPin, used only while
// learning, scanning or self-testing, with its own protocol 1 decoder. One fixed code per
// remote button (id 1-8, see remote_buttons.h), kept in NVS (namespace "rf").
namespace rf {

void begin();

// Transmits the learned code for the button. Returns false if the button has no code yet
// or the receiver is in use.
bool send(uint8_t id);

// Listens on the receiver for up to kRfLearnTimeoutMs. The code is saved once the same code
// is received twice in a row. Returns false if already learning.
bool startLearn(uint8_t id);
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

enum class LearnOutcome : uint8_t {
  None,      // still learning, or not learning
  Learned,   // the code was saved
  TimedOut,  // no code decoded twice within kRfLearnTimeoutMs
};

// Call every loop(): handles the receiver and the timeouts while learning or scanning.
// Returns how learning ended, once, on the call where it ends. Cancelling reports nothing.
LearnOutcome poll();

void clear(uint8_t id);
void clearAll();

// Bit n is set when button n + 1 has a code.
uint8_t learnedMask();

// Lists the buttons with protocol, bit count and pulse length. Never prints the code itself.
void print();

}  // namespace rf
