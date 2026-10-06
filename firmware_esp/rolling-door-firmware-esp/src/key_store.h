#pragma once

#include <stddef.h>
#include <stdint.h>

#include "protocol.h"

// Per-phone key table, stored in NVS (namespace "keys", one blob per slot). Phone names are
// kept in the same namespace under their own keys, so the slot blob keeps its size.
namespace key_store {

struct Slot {
  uint8_t used;
  uint8_t role;  // Role
  char name[20];  // unused, kept so saved slots still load; names are kept apart
  uint8_t key[config::kKeyLength];
};

// PHONES value: [count] then, per phone by key id, [key id][role][name length][name UTF-8].
constexpr size_t kSerializedMax = 1 + config::kSlotCount * (3 + config::kPhoneNameLength);

void begin();

// Number of saved (confirmed) phones.
size_t count();

// Saved slot, or the pending slot of this connection. nullptr if neither.
const Slot* find(uint8_t id, uint16_t connHandle, bool* isPending = nullptr);

// Reserves a free slot for a phone that is pairing on this connection, with a new random key.
// The first phone becomes admin. Kept in RAM until commitPending(). Returns -1 if full.
int allocatePending(uint16_t connHandle);
void commitPending();
void dropPending(uint16_t connHandle);

// Removes a saved phone and its name.
bool revoke(uint8_t id);
void wipe();

// Sets a saved phone's name. False if there is no such phone, or the name is too long or has
// control characters. An empty name lets the app show its own default.
bool rename(uint8_t id, const uint8_t* name, size_t nameLength);

// Makes `to` the admin and `from` a normal phone. False unless both are saved, differ and
// `from` is the admin.
bool transferAdmin(uint8_t from, uint8_t to);

// Writes the PHONES value; returns its length.
size_t serialize(uint8_t* out, size_t capacity);

// Logs the table without keys.
void print();

}  // namespace key_store
