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

// INFO's first byte once said mains or battery; the board no longer tells them apart.
constexpr uint8_t kInfoMains = 0x00;

bool pairingAdvertised = false;
uint8_t reportedBattery = power::kBatteryUnknown;
uint8_t reportedLearned = 0;
uint8_t reportedRevision = 0;

// Learning started by an admin phone with LEARN_RF; its end is notified as STATUS [81][result].
struct PhoneLearning {
  bool active;
  uint16_t connHandle;
} phoneLearning = {};

// Notifies INFO whenever the battery, the learned buttons or the button list change.
// A phone that connects later reads it. BUTTONS is updated first, so a phone that reads it
// right after the INFO notify gets the new list.
void updateInfo(bool force = false) {
  const uint8_t battery = power::batteryPercent();
  const uint8_t learned = rf::learnedMask();
  const uint8_t revision = remote_buttons::revision();
  if (!force && battery == reportedBattery && learned == reportedLearned &&
      revision == reportedRevision) {
    return;
  }
  if (force || revision != reportedRevision) {
    uint8_t buttons[remote_buttons::kSerializedMax];
    ble::setButtons(buttons, remote_buttons::serialize(buttons, sizeof(buttons)));
  }
  reportedBattery = battery;
  reportedLearned = learned;
  reportedRevision = revision;
  ble::setInfo(kInfoMains, battery, learned, revision);
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
  ble::notifyStatus(phoneLearning.connHandle, cmd::kRfLearned, result);
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

// LEAVE: the sender gives up its own slot. An admin may only leave as the last phone, so the
// board is never left without one; with other phones it hands over admin first (MAKE_ADMIN).
// With no phone left, pairing opens again for a new admin.
Result leave(const CommandFrame& frame, const key_store::Slot& slot) {
  if (frame.argsLength != 0) {
    return Result::BadCommand;
  }
  if (slot.role == static_cast<uint8_t>(Role::Admin) && key_store::count() > 1) {
    return Result::NotPermitted;
  }
  return key_store::revoke(frame.keyId) ? Result::Ok : Result::BadCommand;
}

// LIST_PHONES: fills PHONES for this connection only. Any phone may ask: a phone made admin by
// another one learns its new role from the list.
Result listPhones(const CommandFrame& frame, uint16_t connHandle) {
  if (frame.argsLength != 0) {
    return Result::BadCommand;
  }
  uint8_t phones[key_store::kSerializedMax];
  ble::setPhones(connHandle, phones, key_store::serialize(phones, sizeof(phones)));
  return Result::Ok;
}

// RENAME_PHONE args: [key id][name UTF-8 0-32]. The admin renames any phone, others only
// themselves (the app names a new phone after the device right after pairing).
Result renamePhone(const CommandFrame& frame, const key_store::Slot& slot) {
  if (frame.argsLength < 1) {
    return Result::BadCommand;
  }
  if (slot.role != static_cast<uint8_t>(Role::Admin) && frame.args[0] != frame.keyId) {
    return Result::NotPermitted;
  }
  const bool ok = key_store::rename(frame.args[0], frame.args + 1, frame.argsLength - 1);
  return ok ? Result::Ok : Result::BadCommand;
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
    case cmd::kInvite: {
      if (frame.argsLength != 0) {
        return Result::BadCommand;
      }
      uint8_t response[config::kInviteResponseLength];
      size_t responseLength = 0;
      pairing::createInvite(slot.key, response, responseLength);
      ble::setPairingResponse(connHandle, response, responseLength);
      memset(response, 0, sizeof(response));
      return Result::Ok;
    }
    case cmd::kRevokePhone:
      if (frame.argsLength != 1 || frame.args[0] == frame.keyId) {
        return Result::BadCommand;  // admins cannot revoke themselves
      }
      return key_store::revoke(frame.args[0]) ? Result::Ok : Result::BadCommand;
    case cmd::kLeave:
      return leave(frame, slot);
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
    case cmd::kListPhones:
      return listPhones(frame, connHandle);
    case cmd::kRenamePhone:
      return renamePhone(frame, slot);
    case cmd::kMakeAdmin:
      if (frame.argsLength != 1) {
        return Result::BadCommand;
      }
      return key_store::transferAdmin(frame.keyId, frame.args[0]) ? Result::Ok
                                                                    : Result::BadCommand;
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
  const uint8_t* nonce = ble::nonce(connHandle);
  if (slot == nullptr || nonce == nullptr || !auth::verifyFrame(slot->key, nonce, frame)) {
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
      ble::rotateChallenge(event.connHandle);
      break;

    case ble::Event::Type::Disconnected:
      key_store::dropPending(event.connHandle);
      // Nobody is left to hold the remote near the board.
      if (phoneLearning.active && phoneLearning.connHandle == event.connHandle) {
        phoneLearning.active = false;
        rf::cancelLearn();
      }
      break;

    case ble::Event::Type::BadCommand:
      ble::notifyStatus(event.connHandle, 0x00, Result::BadCommand);
      break;

    case ble::Event::Type::Command: {
      CommandFrame frame;
      parseFrame(event.data, event.length, frame);
      const Result result = handleCommand(frame, event.connHandle);
      Serial.printf("[CMD] result 0x%02X\n", static_cast<uint8_t>(result));
      // INFO first: the phone already sees a changed button list when STATUS arrives.
      updateInfo();
      ble::notifyStatus(event.connHandle, frame.command, result);
      ble::rotateChallenge(event.connHandle);
      break;
    }

    case ble::Event::Type::PairingRequest: {
      const uint8_t* nonce = ble::nonce(event.connHandle);
      if (nonce == nullptr) {
        break;  // the phone is already gone
      }
      uint8_t response[config::kPairingResponseLength];
      size_t responseLength = 0;
      const Result result = pairing::handleRequest(event.data, event.length, nonce,
                                                   event.connHandle, response, responseLength);
      Serial.printf("[PAIR] result 0x%02X\n", static_cast<uint8_t>(result));
      ble::setPairingResponse(event.connHandle, response, responseLength);
      ble::notifyStatus(event.connHandle, cmd::kPairing, result);
      ble::rotateChallenge(event.connHandle);
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

  power::poll();
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
