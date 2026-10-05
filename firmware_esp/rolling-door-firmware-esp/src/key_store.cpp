#include "key_store.h"

#include <Arduino.h>
#include <Preferences.h>
#include <esp_random.h>
#include <string.h>

namespace key_store {
namespace {

constexpr const char* kNamespace = "keys";

Slot slots[config::kSlotCount];

struct Pending {
  bool active;
  uint16_t connHandle;
  uint8_t id;
  Slot slot;
} pending = {};

void slotKey(uint8_t id, char out[4]) {
  snprintf(out, 4, "s%u", id);
}

void clearPending() {
  memset(&pending, 0, sizeof(pending));
}

}  // namespace

void begin() {
  memset(slots, 0, sizeof(slots));
  Preferences prefs;
  prefs.begin(kNamespace, true);
  for (uint8_t id = 0; id < config::kSlotCount; id++) {
    char name[4];
    slotKey(id, name);
    if (prefs.isKey(name) && prefs.getBytesLength(name) == sizeof(Slot)) {
      prefs.getBytes(name, &slots[id], sizeof(Slot));
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
    snprintf(pending.slot.name, sizeof(pending.slot.name), "Phone %u", id + 1);
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
  Preferences prefs;
  prefs.begin(kNamespace, false);
  char name[4];
  slotKey(pending.id, name);
  prefs.putBytes(name, &slots[pending.id], sizeof(Slot));
  prefs.end();
  Serial.printf("[KEYS] saved slot %u (%s)\n", pending.id,
                slots[pending.id].role == static_cast<uint8_t>(Role::Admin) ? "admin" : "normal");
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
  Preferences prefs;
  prefs.begin(kNamespace, false);
  char name[4];
  slotKey(id, name);
  prefs.remove(name);
  prefs.end();
  Serial.printf("[KEYS] revoked slot %u\n", id);
  return true;
}

void wipe() {
  memset(slots, 0, sizeof(slots));
  clearPending();
  Preferences prefs;
  prefs.begin(kNamespace, false);
  prefs.clear();
  prefs.end();
  Serial.println("[KEYS] all phone keys erased");
}

void print() {
  Serial.printf("[KEYS] %u/%u slots used\n", static_cast<unsigned>(count()),
                static_cast<unsigned>(config::kSlotCount));
  for (uint8_t id = 0; id < config::kSlotCount; id++) {
    if (slots[id].used) {
      Serial.printf("  slot %u: %-6s \"%s\"\n", id,
                    slots[id].role == static_cast<uint8_t>(Role::Admin) ? "admin" : "normal",
                    slots[id].name);
    }
  }
  if (pending.active) {
    Serial.printf("  slot %u: pending confirmation\n", pending.id);
  }
}

}  // namespace key_store
