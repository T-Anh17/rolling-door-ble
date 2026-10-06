#include "key_store.h"

#include <Arduino.h>
#include <Preferences.h>
#include <esp_random.h>
#include <string.h>

namespace key_store {
namespace {

constexpr const char* kNamespace = "keys";

Slot slots[config::kSlotCount];

struct Name {
  uint8_t length;
  uint8_t bytes[config::kPhoneNameLength];
};

Name names[config::kSlotCount];

struct Pending {
  bool active;
  uint16_t connHandle;
  uint8_t id;
  Slot slot;
} pending = {};

// "s0".."s7" hold the slots, "n0".."n7" their names.
void slotKey(uint8_t id, char out[4]) {
  snprintf(out, 4, "s%u", id);
}

void nameKey(uint8_t id, char out[4]) {
  snprintf(out, 4, "n%u", id);
}

void clearPending() {
  memset(&pending, 0, sizeof(pending));
}

void saveSlot(Preferences& prefs, uint8_t id) {
  char key[4];
  slotKey(id, key);
  prefs.putBytes(key, &slots[id], sizeof(Slot));
}

bool isAdmin(uint8_t id) {
  return slots[id].role == static_cast<uint8_t>(Role::Admin);
}

}  // namespace

void begin() {
  memset(slots, 0, sizeof(slots));
  memset(names, 0, sizeof(names));
  Preferences prefs;
  prefs.begin(kNamespace, true);
  for (uint8_t id = 0; id < config::kSlotCount; id++) {
    char key[4];
    slotKey(id, key);
    if (prefs.isKey(key) && prefs.getBytesLength(key) == sizeof(Slot)) {
      prefs.getBytes(key, &slots[id], sizeof(Slot));
    }
    nameKey(id, key);
    const size_t length = prefs.isKey(key) ? prefs.getBytesLength(key) : 0;
    if (slots[id].used && length > 0 && length <= config::kPhoneNameLength) {
      names[id].length = static_cast<uint8_t>(prefs.getBytes(key, names[id].bytes, length));
    }
  }
  prefs.end();
  Serial.printf("[KEYS] %u phone(s) paired\n", static_cast<unsigned>(count()));
}

size_t count() {
  size_t n = 0;
  for (const Slot& slot : slots) {
    n += slot.used ? 1 : 0;
  }
  return n;
}

const Slot* find(uint8_t id, uint16_t connHandle, bool* isPending) {
  if (isPending != nullptr) {
    *isPending = false;
  }
  if (pending.active && pending.id == id && pending.connHandle == connHandle) {
    if (isPending != nullptr) {
      *isPending = true;
    }
    return &pending.slot;
  }
  if (id < config::kSlotCount && slots[id].used) {
    return &slots[id];
  }
  return nullptr;
}

int allocatePending(uint16_t connHandle) {
  clearPending();
  for (uint8_t id = 0; id < config::kSlotCount; id++) {
    if (slots[id].used) {
      continue;
    }
    pending.active = true;
    pending.connHandle = connHandle;
    pending.id = id;
    pending.slot.used = 1;
    pending.slot.role = static_cast<uint8_t>(count() == 0 ? Role::Admin : Role::Normal);
    esp_fill_random(pending.slot.key, sizeof(pending.slot.key));
    return id;
  }
  return -1;
}

void commitPending() {
  if (!pending.active) {
    return;
  }
  slots[pending.id] = pending.slot;
  names[pending.id] = {};
  Preferences prefs;
  prefs.begin(kNamespace, false);
  saveSlot(prefs, pending.id);
  char key[4];
  nameKey(pending.id, key);
  prefs.remove(key);
  prefs.end();
  Serial.printf("[KEYS] saved slot %u (%s)\n", pending.id,
                isAdmin(pending.id) ? "admin" : "normal");
  clearPending();
}

void dropPending(uint16_t connHandle) {
  if (pending.active && pending.connHandle == connHandle) {
    Serial.printf("[KEYS] pending slot %u dropped (not confirmed)\n", pending.id);
    clearPending();
  }
}

bool revoke(uint8_t id) {
  if (id >= config::kSlotCount || !slots[id].used) {
    return false;
  }
  memset(&slots[id], 0, sizeof(Slot));
  names[id] = {};
  Preferences prefs;
  prefs.begin(kNamespace, false);
  char key[4];
  slotKey(id, key);
  prefs.remove(key);
  nameKey(id, key);
  prefs.remove(key);
  prefs.end();
  Serial.printf("[KEYS] removed slot %u\n", id);
  return true;
}

void wipe() {
  memset(slots, 0, sizeof(slots));
  memset(names, 0, sizeof(names));
  clearPending();
  Preferences prefs;
  prefs.begin(kNamespace, false);
  prefs.clear();
  prefs.end();
  Serial.println("[KEYS] all phone keys erased");
}

bool rename(uint8_t id, const uint8_t* name, size_t nameLength) {
  if (id >= config::kSlotCount || !slots[id].used || nameLength > config::kPhoneNameLength) {
    return false;
  }
  for (size_t i = 0; i < nameLength; i++) {
    if (name[i] < 0x20 || name[i] == 0x7F) {
      return false;
    }
  }
  names[id].length = static_cast<uint8_t>(nameLength);
  memset(names[id].bytes, 0, sizeof(names[id].bytes));
  memcpy(names[id].bytes, name, nameLength);
  Preferences prefs;
  prefs.begin(kNamespace, false);
  char key[4];
  nameKey(id, key);
  if (nameLength == 0) {
    prefs.remove(key);
  } else {
    prefs.putBytes(key, name, nameLength);
  }
  prefs.end();
  Serial.printf("[KEYS] slot %u renamed, %u byte(s)\n", id, static_cast<unsigned>(nameLength));
  return true;
}

bool transferAdmin(uint8_t from, uint8_t to) {
  if (from >= config::kSlotCount || to >= config::kSlotCount || from == to ||
      !slots[from].used || !slots[to].used || !isAdmin(from)) {
    return false;
  }
  slots[to].role = static_cast<uint8_t>(Role::Admin);
  slots[from].role = static_cast<uint8_t>(Role::Normal);
  Preferences prefs;
  prefs.begin(kNamespace, false);
  // The new admin first: a reset in between leaves two admins rather than none.
  saveSlot(prefs, to);
  saveSlot(prefs, from);
  prefs.end();
  Serial.printf("[KEYS] admin moved from slot %u to slot %u\n", from, to);
  return true;
}

size_t serialize(uint8_t* out, size_t capacity) {
  if (capacity < kSerializedMax) {
    return 0;
  }
  size_t length = 0;
  out[length++] = static_cast<uint8_t>(count());
  for (uint8_t id = 0; id < config::kSlotCount; id++) {
    if (!slots[id].used) {
      continue;
    }
    out[length++] = id;
    out[length++] = slots[id].role;
    out[length++] = names[id].length;
    memcpy(out + length, names[id].bytes, names[id].length);
    length += names[id].length;
  }
  return length;
}

void print() {
  Serial.printf("[KEYS] %u/%u slots used\n", static_cast<unsigned>(count()),
                static_cast<unsigned>(config::kSlotCount));
  for (uint8_t id = 0; id < config::kSlotCount; id++) {
    if (slots[id].used) {
      Serial.printf("  slot %u: %-6s \"%.*s\"\n", id, isAdmin(id) ? "admin" : "normal",
                    names[id].length, reinterpret_cast<const char*>(names[id].bytes));
    }
  }
  if (pending.active) {
    Serial.printf("  slot %u: pending confirmation\n", pending.id);
  }
}

}  // namespace key_store
