#include "ble_server.h"

#include <Arduino.h>
#include <NimBLEDevice.h>
#include <esp_random.h>

#include "config.h"

namespace ble {
namespace {

QueueHandle_t commandQueue = nullptr;
NimBLECharacteristic* challengeChar = nullptr;
NimBLECharacteristic* statusChar = nullptr;

class ServerCallbacks : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer*, NimBLEConnInfo& connInfo) override {
    Serial.printf("[BLE] connected %s\n", connInfo.getAddress().toString().c_str());
    rotateChallenge();
  }

  void onDisconnect(NimBLEServer*, NimBLEConnInfo& connInfo, int reason) override {
    Serial.printf("[BLE] disconnected %s, reason 0x%X, advertising again\n",
                  connInfo.getAddress().toString().c_str(), reason);
  }

  void onMTUChange(uint16_t mtu, NimBLEConnInfo&) override {
    Serial.printf("[BLE] MTU %u\n", mtu);
  }
};

class CommandCallbacks : public NimBLECharacteristicCallbacks {
  // Runs on the NimBLE host task: only queue the frame, never do slow work here.
  void onWrite(NimBLECharacteristic* characteristic, NimBLEConnInfo&) override {
    const NimBLEAttValue& value = characteristic->getValue();
    CommandFrame frame;
    if (!parseFrame(value.data(), value.size(), frame)) {
      Serial.printf("[BLE] write %u bytes, expected %u, rejected\n",
                    static_cast<unsigned>(value.size()),
                    static_cast<unsigned>(config::kFrameLength));
      notifyStatus(0x00, Result::BadCommand);
      return;
    }
    Serial.printf("[BLE] write %u bytes, key=%u\n",
                  static_cast<unsigned>(value.size()), frame.keyId);
    if (xQueueSend(commandQueue, &frame, 0) != pdTRUE) {
      Serial.println("[BLE] command queue full, frame dropped");
    }
  }
};

}  // namespace

void begin() {
  commandQueue = xQueueCreate(config::kCommandQueueDepth, sizeof(CommandFrame));

  NimBLEDevice::init(config::kDeviceName);

  NimBLEServer* server = NimBLEDevice::createServer();
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
  // Static until phase 4 (fake values) and phase 6 (real measurements).
  NimBLECharacteristic* infoChar = service->createCharacteristic(
      config::kInfoUuid, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
  const uint8_t info[] = {0x00, 0xFF};
  infoChar->setValue(info, sizeof(info));

  rotateChallenge();
  server->start();

  // Service UUID goes in the advertising packet so the app can scan by it;
  // the name goes in the scan response (scan response must be enabled first).
  NimBLEAdvertising* advertising = NimBLEDevice::getAdvertising();
  advertising->addServiceUUID(config::kServiceUuid);
  advertising->enableScanResponse(true);
  advertising->setName(config::kDeviceName);
  advertising->setMinInterval(config::kAdvMinInterval);
  advertising->setMaxInterval(config::kAdvMaxInterval);
  advertising->start();

  Serial.printf("[BLE] advertising as \"%s\", address %s\n", config::kDeviceName,
                NimBLEDevice::getAddress().toString().c_str());
}

bool receive(CommandFrame& out) {
  return xQueueReceive(commandQueue, &out, 0) == pdTRUE;
}

void notifyStatus(uint8_t command, Result result) {
  const uint8_t status[] = {command, static_cast<uint8_t>(result)};
  statusChar->setValue(status, sizeof(status));
  statusChar->notify();
}

void rotateChallenge() {
  uint8_t nonce[config::kNonceLength];
  esp_fill_random(nonce, sizeof(nonce));
  challengeChar->setValue(nonce, sizeof(nonce));
  challengeChar->notify();
}

}  // namespace ble
