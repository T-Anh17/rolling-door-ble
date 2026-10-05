#pragma once

#include <stddef.h>
#include <stdint.h>

#include "protocol.h"

// Per-phone key table, stored in NVS (namespace "keys", one blob per slot).
namespace key_store {

struct Slot {
  uint8_t used;
  uint8_t role;  // Role
  char name[20];
  uint8_t key[config::kKeyLength];
};

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

bool revoke(uint8_t id);
void wipe();

// Logs the table without keys.
void print();

}  // namespace key_store
