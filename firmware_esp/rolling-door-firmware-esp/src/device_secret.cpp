#include "device_secret.h"

#include <Arduino.h>
#include <Preferences.h>
#include <esp_mac.h>
#include <esp_random.h>
#include <qrcode.h>

namespace device_secret {
namespace {

constexpr const char* kNamespace = "device";
constexpr const char* kSecretKey = "setup";

uint8_t setupSecret[config::kSecretLength];

// RFC 4648 base32, no padding: 16 bytes -> 26 characters.
String base32(const uint8_t* data, size_t length) {
  static const char kAlphabet[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  String out;
  uint32_t buffer = 0;
  int bits = 0;
  for (size_t i = 0; i < length; i++) {
    buffer = (buffer << 8) | data[i];
    bits += 8;
    while (bits >= 5) {
      out += kAlphabet[(buffer >> (bits - 5)) & 0x1F];
      bits -= 5;
    }
  }
  if (bits > 0) {
    out += kAlphabet[(buffer << (5 - bits)) & 0x1F];
  }
  return out;
}

String qrText() {
  uint8_t mac[6];
  esp_read_mac(mac, ESP_MAC_BT);
  char macHex[13];
  snprintf(macHex, sizeof(macHex), "%02X%02X%02X%02X%02X%02X", mac[0], mac[1], mac[2], mac[3],
           mac[4], mac[5]);
  return String("RDOOR1:") + macHex + ":" + base32(setupSecret, sizeof(setupSecret));
}

// Two QR rows per text line with half-block characters, plus a quiet zone.
void printToSerial(esp_qrcode_handle_t qrcode) {
  const int size = esp_qrcode_get_size(qrcode);
  const int border = 2;
  for (int y = -border; y < size + border; y += 2) {
    String line;
    for (int x = -border; x < size + border; x++) {
      const bool top = esp_qrcode_get_module(qrcode, x, y);
      const bool bottom = esp_qrcode_get_module(qrcode, x, y + 1);
      // Light background: dark modules are spaces, light modules are blocks.
      if (!top && !bottom) {
        line += "█";
      } else if (!top) {
        line += "▀";
      } else if (!bottom) {
        line += "▄";
      } else {
        line += " ";
      }
    }
    Serial.println(line);
  }
}

}  // namespace

void begin() {
  Preferences prefs;
  prefs.begin(kNamespace, false);
  if (prefs.isKey(kSecretKey) && prefs.getBytesLength(kSecretKey) == sizeof(setupSecret)) {
    prefs.getBytes(kSecretKey, setupSecret, sizeof(setupSecret));
  } else {
    esp_fill_random(setupSecret, sizeof(setupSecret));
    prefs.putBytes(kSecretKey, setupSecret, sizeof(setupSecret));
    Serial.println("[SETUP] new device secret created");
  }
  prefs.end();
}

const uint8_t* secret() {
  return setupSecret;
}

void printQr() {
  const String text = qrText();
  Serial.println("[SETUP] scan this QR code with the app to pair:");
  esp_qrcode_config_t cfg = ESP_QRCODE_CONFIG_DEFAULT();
  cfg.display_func = printToSerial;
  cfg.qrcode_ecc_level = ESP_QRCODE_ECC_MED;
  esp_qrcode_generate(&cfg, text.c_str());
  Serial.printf("[SETUP] QR text: %s\n", text.c_str());
}

}  // namespace device_secret
