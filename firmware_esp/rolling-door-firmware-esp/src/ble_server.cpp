#include "ble_server.h"

#include <Arduino.h>
#include <NimBLEDevice.h>
#include <esp_random.h>
#include <string.h>

#include "config.h"

namespace ble {
namespace {

QueueHandle_t eventQueue = nullptr;
NimBLEServer* server = nullptr;
NimBLECharacteristic* challengeChar = nullptr;
NimBLECharacteristic* statusChar = nullptr;
NimBLECharacteristic* infoChar = nullptr;
NimBLECharacteristic* pairingChar = nullptr;
NimBLECharacteristic* buttonsChar = nullptr;
uint8_t currentNonce[config::kNonceLength];

void post(Event::Type type, uint16_t connHandle, const uint8_t* data = nullptr, size_t length = 0) {
  Event event = {};
  event.type = type;
  event.connHandle = connHandle;
  event.length = static_cast<uint8_t>(length);
  if (data != nullptr) {
    memcpy(event.data, data, length);
  }
  if (xQueueSend(eventQueue, &event, 0) != pdTRUE) {
    Serial.println("[BLE] event queue full, event dropped");
  }
}

class ServerCallbacks : public NimBLEServerCallbacks {
  // The phone picks the connection interval and changes it itself (~49ms at first, then 7.5ms
  // a few hundred ms later); asking from here only arrives after that, so the board just logs it.
  void onConnect(NimBLEServer*, NimBLEConnInfo& connInfo) override {
    Serial.printf("[BLE] connected %s, interval %.2fms\n", connInfo.getAddress().toString().c_str(),
                  connInfo.getConnInterval() * 1.25f);
    post(Event::Type::Connected, connInfo.getConnHandle());
  }

  void onConnParamsUpdate(NimBLEConnInfo& connInfo) override {
    Serial.printf("[BLE] interval %.2fms\n", connInfo.getConnInterval() * 1.25f);
  }

  void onDisconnect(NimBLEServer*, NimBLEConnInfo& connInfo, int reason) override {
    Serial.printf("[BLE] disconnected %s, reason 0x%X, advertising again\n",
                  connInfo.getAddress().toString().c_str(), reason);
    post(Event::Type::Disconnected, connInfo.getConnHandle());
  }

  void onMTUChange(uint16_t mtu, NimBLEConnInfo&) override {
    Serial.printf("[BLE] MTU %u\n", mtu);
  }
};

// Write callbacks run on the NimBLE host task: only queue the data, never do slow work here.
class CommandCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* characteristic, NimBLEConnInfo& connInfo) override {
    const NimBLEAttValue& value = characteristic->getValue();
    if (value.size() < config::kMinFrameLength || value.size() > config::kMaxFrameLength) {
      Serial.printf("[BLE] COMMAND write %u bytes, expected %u-%u, rejected\n",
                    static_cast<unsigned>(value.size()),
                    static_cast<unsigned>(config::kMinFrameLength),
                    static_cast<unsigned>(config::kMaxFrameLength));
      post(Event::Type::BadCommand, connInfo.getConnHandle());
      return;
    }
    post(Event::Type::Command, connInfo.getConnHandle(), value.data(), value.size());
  }
};

class PairingCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* characteristic, NimBLEConnInfo& connInfo) override {
    const NimBLEAttValue& value = characteristic->getValue();
    const size_t length = value.size() > config::kPairingRequestLength
                              ? 0  // too long: forward an empty request, rejected as BadCommand
                              : value.size();
    post(Event::Type::PairingRequest, connInfo.getConnHandle(), value.data(), length);
  }
};

void applyScanResponse(bool pairingOpen) {
  NimBLEAdvertisementData scanResponse;
  if (pairingOpen) {
    const uint8_t data[] = {config::kManufacturerId & 0xFF, config::kManufacturerId >> 8, 0x01};
    scanResponse.setManufacturerData(data, sizeof(data));
  }
  NimBLEAdvertising* advertising = NimBLEDevice::getAdvertising();
  const bool wasAdvertising = advertising->isAdvertising();
  if (wasAdvertising) {
    advertising->stop();
  }
  advertising->setScanResponseData(scanResponse);
  if (wasAdvertising) {
    advertising->start();
  }
}

}  // namespace

void begin() {
  eventQueue = xQueueCreate(config::kEventQueueDepth, sizeof(Event));

  // No device name, in advertising or in the GAP Device Name characteristic,
  // so a scan does not reveal what the board controls.
  NimBLEDevice::init("");

  server = NimBLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());
  server->advertiseOnDisconnect(true);

  NimBLEService* service = server->createService(config::kServiceUuid);

  challengeChar = service->createCharacteristic(
      config::kChallengeUuid, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);

  NimBLECharacteristic* commandChar =
      service->createCharacteristic(config::kCommandUuid, NIMBLE_PROPERTY::WRITE);
  commandChar->setCallbacks(new CommandCallbacks());

  statusChar = service->createCharacteristic(config::kStatusUuid, NIMBLE_PROPERTY::NOTIFY);

  // [power source: 0 = mains, 1 = battery][battery percent: 0xFF = not measured]
  // [learned RF buttons: bit n = button n + 1][button list revision]
  // main.cpp keeps it up to date with setInfo().
  infoChar = service->createCharacteristic(config::kInfoUuid,
                                           NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
  const uint8_t info[] = {0x00, 0xFF, 0x00, 0x00};
  infoChar->setValue(info, sizeof(info));

  // The button list, up to ~280 bytes: phones read it (a long read) when the revision in INFO
  // changes. main.cpp sets it with setButtons().
  buttonsChar = service->createCharacteristic(config::kButtonsUuid, NIMBLE_PROPERTY::READ);

  pairingChar = service->createCharacteristic(config::kPairingUuid,
                                              NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::WRITE);
  pairingChar->setCallbacks(new PairingCallbacks());

  rotateChallenge();
  server->start();

  // Service UUID goes in the advertising packet so the app can scan by it;
  // the pairing flag goes in the scan response.
  NimBLEAdvertising* advertising = NimBLEDevice::getAdvertising();
  advertising->addServiceUUID(config::kServiceUuid);
  advertising->enableScanResponse(true);
  advertising->setMinInterval(config::kAdvMinInterval);
  advertising->setMaxInterval(config::kAdvMaxInterval);
  applyScanResponse(false);
  advertising->start();

  Serial.printf("[BLE] advertising, address %s\n", NimBLEDevice::getAddress().toString().c_str());
}

bool nextEvent(Event& out) {
  return xQueueReceive(eventQueue, &out, 0) == pdTRUE;
}

const uint8_t* nonce() {
  return currentNonce;
}

void rotateChallenge() {
  esp_fill_random(currentNonce, sizeof(currentNonce));
  challengeChar->setValue(currentNonce, sizeof(currentNonce));
  challengeChar->notify();
}

void notifyStatus(uint8_t command, Result result) {
  const uint8_t status[] = {command, static_cast<uint8_t>(result)};
  statusChar->setValue(status, sizeof(status));
  statusChar->notify();
}

void setInfo(uint8_t powerSource, uint8_t batteryPercent, uint8_t learnedMask, uint8_t revision) {
  const uint8_t info[] = {powerSource, batteryPercent, learnedMask, revision};
  infoChar->setValue(info, sizeof(info));
  infoChar->notify();
}

void setButtons(const uint8_t* data, size_t length) {
  buttonsChar->setValue(data, length);
}

void setPairingResponse(const uint8_t* data, size_t length) {
  pairingChar->setValue(data, length);
}

void clearPairingResponse() {
  const uint8_t empty[1] = {0};
  pairingChar->setValue(empty, 0);
}

void setPairingAdvertised(bool open) {
  applyScanResponse(open);
}

void disconnect(uint16_t connHandle) {
  server->disconnect(connHandle);
}

}  // namespace ble
