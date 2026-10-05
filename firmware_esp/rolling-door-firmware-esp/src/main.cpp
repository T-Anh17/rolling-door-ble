#include <Arduino.h>

#include "ble_server.h"
#include "protocol.h"
#include "rf.h"

namespace {

Result handleCommand(const CommandFrame& frame) {
  Serial.printf("[CMD] key=%u cmd=0x%02X %s\n", frame.keyId, frame.command,
                commandName(frame.command));
  Serial.println("[AUTH] not checked (phase 1)");

  if (!isDoorCommand(frame.command)) {
    return Result::BadCommand;
  }
  return rf::send(static_cast<DoorCommand>(frame.command)) ? Result::Ok : Result::RfError;
}

}  // namespace

void setup() {
  Serial.begin(115200);
  // USB CDC: never block on logging when no computer is reading the port.
  Serial.setTxTimeoutMs(0);
  delay(500);
  Serial.println();
  Serial.println("=== rolling-door-ble firmware, phase 1 ===");

  rf::begin();
  ble::begin();
}

void loop() {
  CommandFrame frame;
  if (!ble::receive(frame)) {
    delay(10);
    return;
  }

  const Result result = handleCommand(frame);
  Serial.printf("[CMD] result 0x%02X\n", static_cast<uint8_t>(result));
  ble::notifyStatus(frame.command, result);
  ble::rotateChallenge();
}
