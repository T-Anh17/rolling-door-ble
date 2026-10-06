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
#include "remote_buttons.h"
#include "rf.h"

namespace {

bool pairingAdvertised = false;
power::Status reportedPower = {power::Source::Mains, power::kBatteryUnknown};
uint8_t reportedLearned = 0;
uint8_t reportedRevision = 0;

// Learning started by an admin phone with LEARN_RF; its end is notified as STATUS [81][result].
struct PhoneLearning {
  bool active;
  uint16_t connHandle;
} phoneLearning = {};

// Notifies INFO whenever the power status, the learned buttons or the button list change.
// A phone that connects later reads it. BUTTONS is updated first, so a phone that reads it
// right after the INFO notify gets the new list.
void updateInfo(bool force = false) {
  const power::Status status = power::read();
  const uint8_t learned = rf::learnedMask();
  const uint8_t revision = remote_buttons::revision();
  if (!force && status == reportedPower && learned == reportedLearned &&
      revision == reportedRevision) {
    return;
  }
  if (force || revision != reportedRevision) {
    uint8_t buttons[remote_buttons::kSerializedMax];
    ble::setButtons(buttons, remote_buttons::serialize(buttons, sizeof(buttons)));
  }
  reportedPower = status;
  reportedLearned = learned;
  reportedRevision = revision;
  ble::setInfo(static_cast<uint8_t>(status.source), status.batteryPercent, learned, revision);
}

// Tells the admin phone how learning ended: 00 saved, 03 timed out or cancelled from the
// console. Learning cancelled by the phone itself is not reported.
void reportLearning(rf::LearnOutcome outcome) {
  if (!phoneLearning.active || rf::isLearning()) {
    return;
  }
  phoneLearning.active = false;
  const Result result = outcome == rf::LearnOutcome::Learned ? Result::Ok : Result::RfError;
  Serial.printf("[CMD] learning result 0x%02X\n", static_cast<uint8_t>(result));
  ble::notifyStatus(cmd::kRfLearned, result);
}

// LEARN_RF args: [00] cancels, [01-08] learns that button. Ok means the receiver is listening.
Result learnRf(const CommandFrame& frame, uint16_t connHandle) {
  if (frame.argsLength != 1) {
    return Result::BadCommand;
  }
  const uint8_t button = frame.args[0];
  if (button == 0x00) {
    phoneLearning.active = false;
    rf::cancelLearn();
    return Result::Ok;
  }
  if (!remote_buttons::exists(button)) {
    return Result::BadCommand;
  }
  if (!rf::startLearn(button)) {
    return Result::RfError;  // receiver busy: learning or scanning from the console
  }
  phoneLearning = {true, connHandle};
  return Result::Ok;
}

// SET_BUTTON args: [button 1-8][icon][name UTF-8 0-32]. Adds the button or changes it.
Result setButton(const CommandFrame& frame) {
  if (frame.argsLength < 2) {
    return Result::BadCommand;
  }
  const bool ok = remote_buttons::set(frame.args[0], frame.args[1], frame.args + 2,
                                      frame.argsLength - 2);
  return ok ? Result::Ok : Result::BadCommand;
}

// DELETE_BUTTON args: [button 1-8]. Removes the button and its RF code.
Result deleteButton(const CommandFrame& frame) {
  if (frame.argsLength != 1 || !remote_buttons::exists(frame.args[0])) {
    return Result::BadCommand;
  }
  if (rf::isLearning()) {
    return Result::RfError;  // the code being learned would be saved right after
  }
  rf::clear(frame.args[0]);
  remote_buttons::remove(frame.args[0]);
  return Result::Ok;
}

void wipeAndRestart() {
  key_store::wipe();
  Serial.println("[MAIN] restarting");
  Serial.flush();
  delay(200);
  ESP.restart();
}

Result execute(const CommandFrame& frame, const key_store::Slot& slot, uint16_t connHandle) {
  if (isAdminCommand(frame.command) && slot.role != static_cast<uint8_t>(Role::Admin)) {
    return Result::NotPermitted;
  }
  if (isPress(frame.command)) {
    const uint8_t button = pressedButton(frame);
    if (!remote_buttons::exists(button)) {
      return Result::BadCommand;
    }
    return rf::send(button) ? Result::Ok : Result::RfError;
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
    case cmd::kLearnRf:
      return learnRf(frame, connHandle);
    case cmd::kClearRf:
      if (frame.argsLength != 1 || !remote_buttons::exists(frame.args[0])) {
        return Result::BadCommand;
      }
      if (rf::isLearning()) {
        return Result::RfError;  // the code being learned would be saved right after
      }
      rf::clear(frame.args[0]);
      return Result::Ok;
    case cmd::kSetButton:
      return setButton(frame);
    case cmd::kDeleteButton:
      return deleteButton(frame);
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
  return execute(frame, *slot, connHandle);
}

void handleEvent(const ble::Event& event) {
  switch (event.type) {
    case ble::Event::Type::Connected:
      ble::rotateChallenge();
      break;

    case ble::Event::Type::Disconnected:
      key_store::dropPending(event.connHandle);
      ble::clearPairingResponse();
      // Nobody is left to hold the remote near the board.
      if (phoneLearning.active && phoneLearning.connHandle == event.connHandle) {
        phoneLearning.active = false;
        rf::cancelLearn();
      }
      break;

    case ble::Event::Type::BadCommand:
      ble::notifyStatus(0x00, Result::BadCommand);
      break;

    case ble::Event::Type::Command: {
      CommandFrame frame;
      parseFrame(event.data, event.length, frame);
      const Result result = handleCommand(frame, event.connHandle);
      Serial.printf("[CMD] result 0x%02X\n", static_cast<uint8_t>(result));
      // INFO first: the phone already sees a changed button list when STATUS arrives.
      updateInfo();
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
  remote_buttons::begin();
  rf::begin();
  power::begin();
  buttons::begin();
  ble::begin();
  updateInfo(true);

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

  const rf::LearnOutcome outcome = rf::poll();
  // INFO first: the phone already sees the new learned buttons when STATUS 81 arrives.
  updateInfo();
  reportLearning(outcome);

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
