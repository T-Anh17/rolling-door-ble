#include <Arduino.h>

#include "auth.h"
#include "ble_server.h"
#include "buttons.h"
#include "console.h"
#include "device_secret.h"
#include "key_store.h"
#include "pairing.h"
#include "power.h"
#include "protocol.h"
#include "rf.h"

namespace {

bool pairingAdvertised = false;
power::Status reportedPower = {power::Source::Mains, power::kBatteryUnknown};

// Notifies INFO whenever the power status changes. A phone that connects later reads it.
void updatePower(bool force = false) {
  const power::Status status = power::read();
  if (!force && status == reportedPower) {
    return;
  }
  reportedPower = status;
  ble::setInfo(static_cast<uint8_t>(status.source), status.batteryPercent);
}

void wipeAndRestart() {
  key_store::wipe();
  Serial.println("[MAIN] restarting");
  Serial.flush();
  delay(200);
  ESP.restart();
}

Result execute(const CommandFrame& frame, const key_store::Slot& slot) {
  if (isAdminCommand(frame.command) && slot.role != static_cast<uint8_t>(Role::Admin)) {
    return Result::NotPermitted;
  }
  if (isDoorCommand(frame.command)) {
    return rf::send(static_cast<DoorCommand>(frame.command)) ? Result::Ok : Result::RfError;
  }
  switch (frame.command) {
    case cmd::kPing:
      return Result::Ok;
    case cmd::kOpenPairing:
      pairing::open("admin command");
      return Result::Ok;
    case cmd::kRevokePhone:
      if (frame.argsLength != 1 || frame.args[0] == frame.keyId) {
        return Result::BadCommand;  // admins cannot revoke themselves
      }
      return key_store::revoke(frame.args[0]) ? Result::Ok : Result::BadCommand;
    case cmd::kUpdateMode:  // phase 5
    default:
      return Result::BadCommand;
  }
}

// Order: lockout, key lookup, HMAC, then role. Every frame consumes the nonce.
Result handleCommand(const CommandFrame& frame, uint16_t connHandle) {
  Serial.printf("[CMD] key=%u cmd=0x%02X %s\n", frame.keyId, frame.command,
                commandName(frame.command));
  if (auth::isLockedOut()) {
    return Result::LockedOut;
  }
  bool pending = false;
  const key_store::Slot* slot = key_store::find(frame.keyId, connHandle, &pending);
  if (slot == nullptr || !auth::verifyFrame(slot->key, ble::nonce(), frame)) {
    Serial.println(slot == nullptr ? "[AUTH] unknown key" : "[AUTH] bad HMAC");
    if (auth::recordFailure()) {
      ble::disconnect(connHandle);
    }
    return Result::AuthFailed;
  }
  auth::recordSuccess();
  if (pending) {
    key_store::commitPending();
    pairing::close();
    slot = key_store::find(frame.keyId, connHandle);
  }
  return execute(frame, *slot);
}

void handleEvent(const ble::Event& event) {
  switch (event.type) {
    case ble::Event::Type::Connected:
      ble::rotateChallenge();
      break;

    case ble::Event::Type::Disconnected:
      key_store::dropPending(event.connHandle);
      ble::clearPairingResponse();
      break;

    case ble::Event::Type::BadCommand:
      ble::notifyStatus(0x00, Result::BadCommand);
      break;

    case ble::Event::Type::Command: {
      CommandFrame frame;
      parseFrame(event.data, event.length, frame);
      const Result result = handleCommand(frame, event.connHandle);
      Serial.printf("[CMD] result 0x%02X\n", static_cast<uint8_t>(result));
      ble::notifyStatus(frame.command, result);
      ble::rotateChallenge();
      break;
    }

    case ble::Event::Type::PairingRequest: {
      uint8_t response[config::kPairingResponseLength];
      size_t responseLength = 0;
      const Result result = pairing::handleRequest(event.data, event.length, ble::nonce(),
                                                   event.connHandle, response, responseLength);
      Serial.printf("[PAIR] result 0x%02X\n", static_cast<uint8_t>(result));
      ble::setPairingResponse(response, responseLength);
      ble::notifyStatus(cmd::kPairing, result);
      ble::rotateChallenge();
      if (result == Result::AuthFailed && auth::isLockedOut()) {
        ble::disconnect(event.connHandle);
      }
      break;
    }
  }
}

}  // namespace

void setup() {
  // Large enough for the QR code printout in one go.
  Serial.setTxBufferSize(4096);
  Serial.begin(115200);
  // USB CDC: never block on logging when no computer is reading the port.
  Serial.setTxTimeoutMs(0);
  delay(500);
  Serial.println();
  Serial.println("=== rolling-door-ble firmware, phase 6 ===");

  device_secret::begin();
  key_store::begin();
  rf::begin();
  power::begin();
  buttons::begin();
  ble::begin();
  updatePower(true);

  if (key_store::count() == 0) {
    Serial.println("[PAIR] no phone paired yet: pairing stays open until the first phone pairs");
    device_secret::printQr();
  }
}

void loop() {
  switch (buttons::poll()) {
    case buttons::Event::OpenPairing:
      pairing::open("BOOT button");
      break;
    case buttons::Event::WipeKeys:
      wipeAndRestart();
      break;
    case buttons::Event::None:
      break;
  }

  switch (console::poll()) {
    case console::Action::OpenPairing:
      pairing::open("console");
      break;
    case console::Action::WipeKeys:
      wipeAndRestart();
      break;
    case console::Action::None:
      break;
  }

  updatePower();
  rf::poll();

  const bool open = pairing::isOpen();
  if (open != pairingAdvertised) {
    pairingAdvertised = open;
    ble::setPairingAdvertised(open);
  }

  ble::Event event;
  if (ble::nextEvent(event)) {
    handleEvent(event);
  } else {
    delay(10);
  }
}
