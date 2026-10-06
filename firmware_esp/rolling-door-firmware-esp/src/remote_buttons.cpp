#include "remote_buttons.h"

#include <Arduino.h>
#include <Preferences.h>
#include <esp_random.h>
#include <string.h>

namespace remote_buttons {
namespace {

constexpr const char* kNamespace = "buttons";
constexpr const char* kTableKey = "table";

struct Button {
  uint8_t id;
  uint8_t icon;
  uint8_t nameLength;
  uint8_t name[config::kButtonNameLength];
};

// Saved as one blob: changes are rare and the whole table is under 300 bytes.
struct Table {
  uint8_t revision;
  uint8_t count;
  Button buttons[config::kMaxButtons];
} table = {};

void save() {
  Preferences prefs;
  prefs.begin(kNamespace, false);
  prefs.putBytes(kTableKey, &table, sizeof(table));
  prefs.end();
}

int indexOf(uint8_t id) {
  for (uint8_t i = 0; i < table.count; i++) {
    if (table.buttons[i].id == id) {
      return i;
    }
  }
  return -1;
}

void setDefaults() {
  table = {};
  // Random, so a phone that cached the list of an erased board does not take it as current.
  table.revision = static_cast<uint8_t>(esp_random());
  const uint8_t icons[] = {kIconUp, kIconDown, kIconLock, kIconUnlock};
  for (uint8_t i = 0; i < sizeof(icons); i++) {
    table.buttons[i] = {static_cast<uint8_t>(i + 1), icons[i], 0, {}};
  }
  table.count = sizeof(icons);
}

}  // namespace

void begin() {
  Preferences prefs;
  prefs.begin(kNamespace, true);
  const bool found = prefs.isKey(kTableKey) && prefs.getBytesLength(kTableKey) == sizeof(table);
  if (found) {
    prefs.getBytes(kTableKey, &table, sizeof(table));
  }
  prefs.end();
  if (!found || table.count > config::kMaxButtons) {
    setDefaults();
    save();
    Serial.println("[BTN] no button list, created Up, Down, Lock, Unlock");
  }
  Serial.printf("[BTN] %u button(s), revision %u\n", table.count, table.revision);
}

bool exists(uint8_t id) {
  return id != 0 && indexOf(id) >= 0;
}

bool set(uint8_t id, uint8_t icon, const uint8_t* name, size_t nameLength) {
  if (id < 1 || id > config::kMaxButtons || icon >= kIconCount ||
      nameLength > config::kButtonNameLength) {
    return false;
  }
  for (size_t i = 0; i < nameLength; i++) {
    if (name[i] < 0x20 || name[i] == 0x7F) {
      return false;
    }
  }
  int index = indexOf(id);
  if (index < 0) {
    index = table.count++;  // ids are 1-8, so a missing id always has a free place
  }
  Button& button = table.buttons[index];
  button.id = id;
  button.icon = icon;
  button.nameLength = static_cast<uint8_t>(nameLength);
  memset(button.name, 0, sizeof(button.name));
  memcpy(button.name, name, nameLength);
  table.revision++;
  save();
  Serial.printf("[BTN] button %u set: icon %u, name %u byte(s)\n", id, icon,
                static_cast<unsigned>(nameLength));
  return true;
}

bool remove(uint8_t id) {
  const int index = indexOf(id);
  if (index < 0) {
    return false;
  }
  memmove(&table.buttons[index], &table.buttons[index + 1],
          (table.count - index - 1) * sizeof(Button));
  table.count--;
  table.buttons[table.count] = {};
  table.revision++;
  save();
  Serial.printf("[BTN] button %u deleted\n", id);
  return true;
}

uint8_t revision() {
  return table.revision;
}

size_t serialize(uint8_t* out, size_t capacity) {
  if (capacity < kSerializedMax) {
    return 0;
  }
  size_t length = 0;
  out[length++] = table.revision;
  for (uint8_t i = 0; i < table.count; i++) {
    const Button& button = table.buttons[i];
    out[length++] = button.id;
    out[length++] = button.icon;
    out[length++] = button.nameLength;
    memcpy(out + length, button.name, button.nameLength);
    length += button.nameLength;
  }
  return length;
}

void print() {
  Serial.printf("[BTN] revision %u\n", table.revision);
  for (uint8_t i = 0; i < table.count; i++) {
    const Button& button = table.buttons[i];
    Serial.printf("[BTN] button %u: icon %u, name \"%.*s\"\n", button.id, button.icon,
                  button.nameLength, reinterpret_cast<const char*>(button.name));
  }
}

}  // namespace remote_buttons
