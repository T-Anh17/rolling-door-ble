#pragma once

#include <stddef.h>
#include <stdint.h>

#include "config.h"

// The buttons the app shows: id 1-8, an icon and a name, in the order the admin added them.
// Kept in NVS (namespace "buttons"); each button's RF code is kept by rf.h under the same id.
// A new board starts with Up, Down, Lock and Unlock as buttons 1-4 with empty names: the app
// then shows the icon's own name in the phone's language.
namespace remote_buttons {

// Icon ids are shared with the app, which draws them; the board only checks the range.
constexpr uint8_t kIconUp = 0;
constexpr uint8_t kIconDown = 1;
constexpr uint8_t kIconLock = 2;
constexpr uint8_t kIconUnlock = 3;
constexpr uint8_t kIconCount = 10;

// BUTTONS value: [revision] then, per button in order, [id][icon][name length][name UTF-8].
constexpr size_t kSerializedMax = 1 + config::kMaxButtons * (3 + config::kButtonNameLength);

void begin();

bool exists(uint8_t id);

// Adds the button at the end, or changes its icon and name. False if the id or icon is out of
// range or the name is too long or has control characters.
bool set(uint8_t id, uint8_t icon, const uint8_t* name, size_t nameLength);

// False if there is no such button. Its RF code is cleared by the caller.
bool remove(uint8_t id);

// Changes on every set() and remove(). INFO reports it so the app reads BUTTONS only when the
// list changed.
uint8_t revision();

// Writes the BUTTONS value; returns its length.
size_t serialize(uint8_t* out, size_t capacity);

// Lists the buttons with icon and name.
void print();

}  // namespace remote_buttons
