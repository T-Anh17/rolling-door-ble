#include "ble_server.h"

#include <Arduino.h>
#include <NimBLEDevice.h>
#include <esp_random.h>
#include <string.h>

#include "config.h"
#include "key_store.h"

static_assert(config::kMaxConnections <= CONFIG_BT_NIMBLE_MAX_CONNECTIONS,
              "NimBLE must allow as many connections as the board takes");

namespace ble {
namespace {

QueueHandle_t eventQueue = nullptr;
NimBLEServer* server = nullptr;
NimBLECharacteristic* challengeChar = nullptr;
NimBLECharacteristic* statusChar = nullptr;
NimBLECharacteristic* infoChar = nullptr;
NimBLECharacteristic* pairingChar = nullptr;
NimBLECharacteristic* buttonsChar = nullptr;
NimBLECharacteristic* phonesChar = nullptr;

// One per connection. Taken in the NimBLE task (connect, reads) and changed in loop(), so
// every access holds peersLock.
struct Peer {
  uint16_t handle;  // BLE_HS_CONN_HANDLE_NONE when free
  uint8_t nonce[config::kNonceLength];
  uint8_t pairing[config::kPairingResponseLength];
  size_t pairingLength;
  uint8_t phones[key_store::kSerializedMax];
  size_t phonesLength;
};

Peer peers[config::kMaxConnections];
portMUX_TYPE peersLock = portMUX_INITIALIZER_UNLOCKED;

// Call with peersLock held.
Peer* findPeer(uint16_t handle) {
  for (Peer& peer : peers) {
    if (peer.handle == handle && handle != BLE_HS_CONN_HANDLE_NONE) {
      return &peer;
    }
  }
  return nullptr;
}

void addPeer(uint16_t handle) {
  uint8_t nonce[config::kNonceLength];
  esp_fill_random(nonce, sizeof(nonce));
  portENTER_CRITICAL(&peersLock);
  for (Peer& peer : peers) {
    if (peer.handle == BLE_HS_CONN_HANDLE_NONE) {
      peer.handle = handle;
      memcpy(peer.nonce, nonce, sizeof(nonce));
      peer.pairingLength = 0;
      peer.phonesLength = 0;
      break;
    }
  }
  portEXIT_CRITICAL(&peersLock);
}

void removePeer(uint16_t handle) {
  portENTER_CRITICAL(&peersLock);
  Peer* peer = findPeer(handle);
  if (peer != nullptr) {
    memset(peer, 0, sizeof(Peer));
    peer->handle = BLE_HS_CONN_HANDLE_NONE;
  }
  portEXIT_CRITICAL(&peersLock);
}

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
  //
  // The board asks for a large MTU itself, right away: it runs alongside the phone's service
  // discovery instead of before it, and discovery then needs fewer requests (one characteristic
  // with a 128-bit UUID per response at the default MTU).
  //
  // Advertising stops when a phone connects; it starts again while there is room for another.
  void onConnect(NimBLEServer* server, NimBLEConnInfo& connInfo) override {
    const int rc = ble_gattc_exchange_mtu(connInfo.getConnHandle(), nullptr, nullptr);
    if (rc != 0) {
      Serial.printf("[BLE] MTU exchange not started, rc %d\n", rc);
    }
    addPeer(connInfo.getConnHandle());
    const uint8_t connected = server->getConnectedCount();
    if (connected < config::kMaxConnections) {
      NimBLEDevice::startAdvertising();
    }
    Serial.printf("[BLE] connected %s, interval %.2fms, %u/%u connection(s)\n",
                  connInfo.getAddress().toString().c_str(), connInfo.getConnInterval() * 1.25f,
                  connected, static_cast<unsigned>(config::kMaxConnections));
    post(Event::Type::Connected, connInfo.getConnHandle());
  }

  void onConnParamsUpdate(NimBLEConnInfo& connInfo) override {
    Serial.printf("[BLE] interval %.2fms\n", connInfo.getConnInterval() * 1.25f);
  }

  void onDisconnect(NimBLEServer*, NimBLEConnInfo& connInfo, int reason) override {
    Serial.printf("[BLE] disconnected %s, reason 0x%X, advertising again\n",
                  connInfo.getAddress().toString().c_str(), reason);
    removePeer(connInfo.getConnHandle());
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

// Values that differ per connection: the value is set to the reader's own just before NimBLE
// answers. A long read's follow-up requests get no callback and use the value as it is then;
// only PAIRING and PHONES are long, and two phones reading them at the same moment is rare (a
// mixed read fails the app's checks and is read again).
class PerPeerReadCallbacks : public NimBLECharacteristicCallbacks {
 public:
  enum class Kind { Challenge, Pairing, Phones };
  explicit PerPeerReadCallbacks(Kind kind) : kind(kind) {}

  void onRead(NimBLECharacteristic* characteristic, NimBLEConnInfo& connInfo) override {
    uint8_t value[key_store::kSerializedMax];
    size_t length = 0;
    portENTER_CRITICAL(&peersLock);
    const Peer* peer = findPeer(connInfo.getConnHandle());
    if (peer != nullptr) {
      switch (kind) {
        case Kind::Challenge:
          length = sizeof(peer->nonce);
          memcpy(value, peer->nonce, length);
          break;
        case Kind::Pairing:
          length = peer->pairingLength;
          memcpy(value, peer->pairing, length);
          break;
        case Kind::Phones:
          length = peer->phonesLength;
          memcpy(value, peer->phones, length);
          break;
      }
    }
    portEXIT_CRITICAL(&peersLock);
    characteristic->setValue(value, length);
  }

  // PAIRING is also written; the request goes to loop() like a command.
  void onWrite(NimBLECharacteristic* characteristic, NimBLEConnInfo& connInfo) override {
    if (kind != Kind::Pairing) {
      return;
    }
    const NimBLEAttValue& value = characteristic->getValue();
    const size_t length = value.size() > config::kPairingRequestLength
                              ? 0  // too long: forward an empty request, rejected as BadCommand
                              : value.size();
    post(Event::Type::PairingRequest, connInfo.getConnHandle(), value.data(), length);
  }

 private:
  Kind kind;
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

// Copies data into this connection's slot for PAIRING or PHONES.
void setPeerValue(uint16_t connHandle, bool pairing, const uint8_t* data, size_t length) {
  portENTER_CRITICAL(&peersLock);
  Peer* peer = findPeer(connHandle);
  if (peer != nullptr) {
    uint8_t* target = pairing ? peer->pairing : peer->phones;
    const size_t capacity = pairing ? sizeof(peer->pairing) : sizeof(peer->phones);
    const size_t copied = length > capacity ? capacity : length;
    memcpy(target, data, copied);
    (pairing ? peer->pairingLength : peer->phonesLength) = copied;
  }
  portEXIT_CRITICAL(&peersLock);
}

}  // namespace

void begin() {
  eventQueue = xQueueCreate(config::kEventQueueDepth, sizeof(Event));
  for (Peer& peer : peers) {
    peer.handle = BLE_HS_CONN_HANDLE_NONE;
  }

  // No device name, in advertising or in the GAP Device Name characteristic,
  // so a scan does not reveal what the board controls.
  NimBLEDevice::init("");

  server = NimBLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());
  server->advertiseOnDisconnect(true);

  NimBLEService* service = server->createService(config::kServiceUuid);

  // Each connection reads and is notified its own nonce.
  challengeChar = service->createCharacteristic(
      config::kChallengeUuid, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
  challengeChar->setCallbacks(new PerPeerReadCallbacks(PerPeerReadCallbacks::Kind::Challenge));

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

  // The phone list: empty until this connection sends LIST_PHONES.
  phonesChar = service->createCharacteristic(config::kPhonesUuid, NIMBLE_PROPERTY::READ);
  phonesChar->setCallbacks(new PerPeerReadCallbacks(PerPeerReadCallbacks::Kind::Phones));

  pairingChar = service->createCharacteristic(config::kPairingUuid,
                                              NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::WRITE);
  pairingChar->setCallbacks(new PerPeerReadCallbacks(PerPeerReadCallbacks::Kind::Pairing));

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

// Only loop() changes a nonce, so the pointer stays valid until loop() rotates it or the
// connection goes.
const uint8_t* nonce(uint16_t connHandle) {
  portENTER_CRITICAL(&peersLock);
  const Peer* peer = findPeer(connHandle);
  portEXIT_CRITICAL(&peersLock);
  return peer != nullptr ? peer->nonce : nullptr;
}

void rotateChallenge(uint16_t connHandle) {
  uint8_t nonce[config::kNonceLength];
  esp_fill_random(nonce, sizeof(nonce));
  portENTER_CRITICAL(&peersLock);
  Peer* peer = findPeer(connHandle);
  if (peer != nullptr) {
    memcpy(peer->nonce, nonce, sizeof(nonce));
  }
  portEXIT_CRITICAL(&peersLock);
  if (peer != nullptr) {
    challengeChar->notify(nonce, sizeof(nonce), connHandle);
  }
}

void notifyStatus(uint16_t connHandle, uint8_t command, Result result) {
  const uint8_t status[] = {command, static_cast<uint8_t>(result)};
  statusChar->notify(status, sizeof(status), connHandle);
}

void setInfo(uint8_t powerSource, uint8_t batteryPercent, uint8_t learnedMask, uint8_t revision) {
  const uint8_t info[] = {powerSource, batteryPercent, learnedMask, revision};
  infoChar->setValue(info, sizeof(info));
  infoChar->notify();
}

void setButtons(const uint8_t* data, size_t length) {
  buttonsChar->setValue(data, length);
}

void setPhones(uint16_t connHandle, const uint8_t* data, size_t length) {
  setPeerValue(connHandle, false, data, length);
}

void setPairingResponse(uint16_t connHandle, const uint8_t* data, size_t length) {
  setPeerValue(connHandle, true, data, length);
}

void setPairingAdvertised(bool open) {
  applyScanResponse(open);
}

void disconnect(uint16_t connHandle) {
  server->disconnect(connHandle);
}

}  // namespace ble
