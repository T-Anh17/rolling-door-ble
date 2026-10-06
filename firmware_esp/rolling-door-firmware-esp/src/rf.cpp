#include "rf.h"

#include <Arduino.h>
#include <Preferences.h>
#include <RCSwitch.h>
#include <string.h>

#include "config.h"

namespace rf {
namespace {

constexpr const char* kNamespace = "rf";
constexpr uint8_t kButtonCount = config::kMaxButtons;
// Shorter codes are receiver noise, not a remote.
constexpr uint8_t kMinBits = 8;
constexpr uint8_t kMaxBits = 32;

struct Code {
  uint32_t value;
  uint16_t pulseUs;
  uint8_t protocol;
  uint8_t bits;  // 0 = not learned
};

// rc-switch only transmits. Its receiver drops a whole frame on a single bad pulse, and the
// XY-MK-5V receiver loses one short pulse in every frame of the remote, so frames are
// captured and decoded here instead.
RCSwitch radio;
Code codes[kButtonCount];

struct Learning {
  bool active;
  uint8_t index;
  uint32_t deadline;
  uint32_t rejected;  // frames that did not decode, reported on timeout
  Code candidate;     // last code decoded; saved when the next one matches
} learning = {};

// "rf scan": counts edges on the receiver pin to check the wiring and the remote's timing.
constexpr uint32_t kScanMs = 10 * 1000;

struct Scan {
  bool active;
  uint32_t deadline;
  uint32_t nextReport;
} scan = {};

volatile uint32_t scanEdges = 0;
volatile uint32_t scanShort = 0;  // 150-600us: a short pulse of most fixed-code remotes
volatile uint32_t scanLong = 0;   // 600-1600us: a long pulse
volatile uint32_t scanGaps = 0;   // over 5ms: a sync gap between frames
volatile uint32_t lastEdge = 0;

// Pulses between two sync gaps. The ISR fills `collecting` and copies it to `frame` when the
// next gap arrives and the previous frame has been consumed.
constexpr uint32_t kGapUs = 5000;
constexpr size_t kMaxFramePulses = 100;
constexpr size_t kMinFramePulses = 16;
uint16_t collecting[kMaxFramePulses];
volatile size_t collectingLength = 0;
volatile bool collectingOn = false;
volatile int collectingLevel = 0;  // level of the first pulse after the gap
uint16_t frame[kMaxFramePulses];
volatile size_t frameLength = 0;
volatile uint32_t frameGap = 0;
volatile int frameLevel = 0;
volatile bool frameReady = false;

void IRAM_ATTR onEdge() {
  const uint32_t now = micros();
  const uint32_t width = now - lastEdge;
  lastEdge = now;
  scanEdges++;
  if (width >= 150 && width < 600) {
    scanShort++;
  } else if (width >= 600 && width < 1600) {
    scanLong++;
  }

  if (width >= kGapUs) {
    scanGaps++;
    if (collectingOn && !frameReady && collectingLength >= kMinFramePulses) {
      memcpy(frame, collecting, collectingLength * sizeof(uint16_t));
      frameLength = collectingLength;
      frameGap = width;
      frameLevel = collectingLevel;
      frameReady = true;
    }
    collectingLength = 0;
    collectingOn = true;
    collectingLevel = digitalRead(config::kRfRxPin);
  } else if (collectingOn) {
    if (collectingLength < kMaxFramePulses) {
      collecting[collectingLength++] = static_cast<uint16_t>(width);
    } else {
      collectingOn = false;
    }
  }
}

void startCapture() {
  noInterrupts();
  scanEdges = scanShort = scanLong = scanGaps = 0;
  collectingOn = false;
  frameReady = false;
  lastEdge = micros();
  interrupts();
  attachInterrupt(digitalPinToInterrupt(config::kRfRxPin), onEdge, CHANGE);
}

void stopCapture() {
  detachInterrupt(digitalPinToInterrupt(config::kRfRxPin));
}

// rc-switch protocol 1 (PT2262/EV1527 style): each bit is a high and a low pulse,
// 0 = 1T high + 3T low, 1 = 3T high + 1T low, then a sync of 1T high + 31T low.
// The frame starts after the sync low (the gap) and ends with the sync high.
// A 7T low is a lost 1T high between two 3T lows; it is put back.
// Returns nullptr on success, or why the frame was rejected.
const char* decodeFrame(const uint16_t* pulses, size_t length, uint32_t gap, int firstLevel,
                        Code& out, uint8_t& repaired) {
  if (firstLevel != HIGH) {
    return "starts low";
  }
  const uint32_t unit = gap / 31;
  if (unit < 100 || unit > 1000) {
    return "sync gap out of range";
  }
  uint8_t units[kMaxFramePulses + 2 * 4];
  size_t count = 0;
  repaired = 0;
  for (size_t i = 0; i < length; i++) {
    const uint32_t multiple = (pulses[i] + unit / 2) / unit;
    const bool low = (i % 2) == 1;
    if (multiple == 1 || multiple == 3) {
      if (count >= sizeof(units)) {
        return "too long";
      }
      units[count++] = static_cast<uint8_t>(multiple);
    } else if (low && multiple >= 6 && multiple <= 8 && repaired < 4) {
      if (count + 3 > sizeof(units)) {
        return "too long";
      }
      units[count++] = 3;
      units[count++] = 1;
      units[count++] = 3;
      repaired++;
    } else {
      return "pulse not 1T or 3T";
    }
  }
  if (count % 2 == 0 || units[count - 1] != 1) {
    return "no sync pulse at the end";
  }
  const size_t bits = (count - 1) / 2;
  if (bits < kMinBits || bits > kMaxBits) {
    return "bit count out of range";
  }
  uint32_t value = 0;
  for (size_t i = 0; i + 1 < count; i += 2) {
    value <<= 1;
    if (units[i] == 3 && units[i + 1] == 1) {
      value |= 1;
    } else if (!(units[i] == 1 && units[i + 1] == 3)) {
      return "bit neither 0 nor 1";
    }
  }
  out = {value, static_cast<uint16_t>(unit), 1, static_cast<uint8_t>(bits)};
  return nullptr;
}

// Copies the captured frame out of the ISR buffers and decodes it. False if none is ready.
bool takeFrame(Code& out, const char*& error, uint8_t& repaired) {
  if (!frameReady) {
    return false;
  }
  uint16_t pulses[kMaxFramePulses];
  noInterrupts();
  const size_t length = frameLength;
  const uint32_t gap = frameGap;
  const int level = frameLevel;
  memcpy(pulses, frame, length * sizeof(uint16_t));
  frameReady = false;
  interrupts();
  error = decodeFrame(pulses, length, gap, level, out, repaired);
  return true;
}

bool validId(uint8_t id) {
  return id >= 1 && id <= kButtonCount;
}

// NVS key of button id: "c1" to "c8".
void keyOf(uint8_t id, char (&key)[4]) {
  snprintf(key, sizeof(key), "c%u", id);
}

void save(uint8_t index) {
  char key[4];
  keyOf(index + 1, key);
  Preferences prefs;
  prefs.begin(kNamespace, false);
  if (codes[index].bits == 0) {
    if (prefs.isKey(key)) {
      prefs.remove(key);
    }
  } else {
    prefs.putBytes(key, &codes[index], sizeof(Code));
  }
  prefs.end();
}

// Codes learned before buttons had ids were saved as "UP", "DOWN", "LOCK" and "UNLOCK".
void migrateOldKeys() {
  const char* oldKeys[] = {"UP", "DOWN", "LOCK", "UNLOCK"};
  Preferences prefs;
  prefs.begin(kNamespace, false);
  for (uint8_t i = 0; i < 4; i++) {
    if (!prefs.isKey(oldKeys[i])) {
      continue;
    }
    char key[4];
    keyOf(i + 1, key);
    if (prefs.getBytesLength(oldKeys[i]) == sizeof(Code) && !prefs.isKey(key)) {
      Code code;
      prefs.getBytes(oldKeys[i], &code, sizeof(Code));
      prefs.putBytes(key, &code, sizeof(Code));
    }
    prefs.remove(oldKeys[i]);
    Serial.printf("[RF] moved the code of %s to button %u\n", oldKeys[i], i + 1);
  }
  prefs.end();
}

void stopLearning() {
  stopCapture();
  learning.active = false;
}

LearnOutcome pollLearn() {
  if (static_cast<int32_t>(millis() - learning.deadline) >= 0) {
    stopLearning();
    Serial.printf("[RF] learning button %u timed out: no code decoded twice (%lu frame(s) "
                  "rejected)\n",
                  learning.index + 1, static_cast<unsigned long>(learning.rejected));
    return LearnOutcome::TimedOut;
  }
  Code received;
  const char* error = nullptr;
  uint8_t repaired = 0;
  if (!takeFrame(received, error, repaired)) {
    return LearnOutcome::None;
  }
  if (error != nullptr) {
    learning.rejected++;
    return LearnOutcome::None;
  }
  Serial.printf("[RF] heard protocol %u, %u bits, pulse %uus, %u pulse(s) repaired\n",
                received.protocol, received.bits, received.pulseUs, repaired);

  Code& candidate = learning.candidate;
  if (candidate.bits == received.bits && candidate.value == received.value) {
    received.pulseUs = static_cast<uint16_t>((candidate.pulseUs + received.pulseUs) / 2);
    const uint8_t index = learning.index;
    codes[index] = received;
    save(index);
    stopLearning();
    Serial.printf("[RF] learned button %u: protocol %u, %u bits, pulse %uus\n", index + 1,
                  received.protocol, received.bits, received.pulseUs);
    return LearnOutcome::Learned;
  }
  candidate = received;
  return LearnOutcome::None;
}

void pollScan() {
  const uint32_t now = millis();
  if (static_cast<int32_t>(now - scan.nextReport) < 0) {
    return;
  }
  noInterrupts();
  const uint32_t edges = scanEdges, shortPulses = scanShort, longPulses = scanLong,
                 gaps = scanGaps;
  scanEdges = scanShort = scanLong = scanGaps = 0;
  interrupts();
  Serial.printf("[RF] scan: %lu edges/s, short %lu, long %lu, sync gaps %lu\n",
                static_cast<unsigned long>(edges), static_cast<unsigned long>(shortPulses),
                static_cast<unsigned long>(longPulses), static_cast<unsigned long>(gaps));
  Code decoded;
  const char* error = nullptr;
  uint8_t repaired = 0;
  if (takeFrame(decoded, error, repaired) && error == nullptr) {
    Serial.printf("[RF] scan: frame decodes as protocol %u, %u bits, pulse %uus, %u repaired\n",
                  decoded.protocol, decoded.bits, decoded.pulseUs, repaired);
  }
  scan.nextReport += 1000;
  if (static_cast<int32_t>(now - scan.deadline) >= 0) {
    stopCapture();
    scan.active = false;
    Serial.println("[RF] scan done");
  }
}

}  // namespace

void begin() {
  memset(codes, 0, sizeof(codes));
  migrateOldKeys();
  Preferences prefs;
  prefs.begin(kNamespace, true);
  size_t learned = 0;
  for (uint8_t i = 0; i < kButtonCount; i++) {
    char key[4];
    keyOf(i + 1, key);
    if (prefs.isKey(key) && prefs.getBytesLength(key) == sizeof(Code)) {
      prefs.getBytes(key, &codes[i], sizeof(Code));
      learned += codes[i].bits > 0 ? 1 : 0;
    }
  }
  prefs.end();

  pinMode(config::kRfRxPin, INPUT);
  radio.enableTransmit(config::kRfTxPin);
  digitalWrite(config::kRfTxPin, LOW);
  radio.setRepeatTransmit(config::kRfRepeat);
  Serial.printf("[RF] TX GPIO%u, RX GPIO%u, %u/%u button(s) learned\n", config::kRfTxPin,
                config::kRfRxPin, static_cast<unsigned>(learned),
                static_cast<unsigned>(kButtonCount));
}

bool send(uint8_t id) {
  if (!validId(id)) {
    return false;
  }
  if (learning.active || scan.active) {
    Serial.printf("[RF] button %u refused: receiver in use\n", id);
    return false;
  }
  const Code& code = codes[id - 1];
  if (code.bits == 0) {
    Serial.printf("[RF] button %u has no code, learn it with \"rf learn\"\n", id);
    return false;
  }
  const uint32_t start = millis();
  radio.setProtocol(code.protocol, code.pulseUs);
  radio.send(code.value, code.bits);
  Serial.printf("[RF] sent button %u x%u in %lums\n", id, config::kRfRepeat,
                static_cast<unsigned long>(millis() - start));
  return true;
}

bool startLearn(uint8_t id) {
  if (!validId(id) || learning.active || scan.active) {
    return false;
  }
  learning = {};
  learning.active = true;
  learning.index = id - 1;
  learning.deadline = millis() + config::kRfLearnTimeoutMs;
  startCapture();
  Serial.printf("[RF] learning button %u: hold the remote button near the receiver (%lus)\n",
                id,
                static_cast<unsigned long>(config::kRfLearnTimeoutMs / 1000));
  return true;
}

void cancelLearn() {
  if (learning.active) {
    stopLearning();
    Serial.println("[RF] learning cancelled");
  }
}

bool isLearning() {
  return learning.active;
}

bool startScan() {
  if (learning.active || scan.active) {
    return false;
  }
  scan.active = true;
  scan.deadline = millis() + kScanMs;
  scan.nextReport = millis() + 1000;
  startCapture();
  Serial.printf("[RF] scanning GPIO%u for %lus: press a remote button during it\n",
                config::kRfRxPin, static_cast<unsigned long>(kScanMs / 1000));
  return true;
}

LearnOutcome poll() {
  if (scan.active) {
    pollScan();
  } else if (learning.active) {
    return pollLearn();
  }
  return LearnOutcome::None;
}

namespace {

struct Loopback {
  uint8_t frames;   // frames captured
  uint8_t matches;  // frames that decoded to the expected code
  uint8_t others;   // frames that decoded to a different code
};

// Sends the code with the board's own transmitter and decodes it with its own receiver.
// Each send of 3 frames completes one captured frame (the next send ends its sync gap).
Loopback loopback(const Code& code) {
  constexpr uint8_t kAttempts = 6;
  Loopback result = {};
  radio.setProtocol(code.protocol, code.pulseUs);
  radio.setRepeatTransmit(3);
  startCapture();
  for (uint8_t i = 0; i < kAttempts; i++) {
    radio.send(code.value, code.bits);
    Code decoded;
    const char* error = nullptr;
    uint8_t repaired = 0;
    if (takeFrame(decoded, error, repaired)) {
      result.frames++;
      if (error == nullptr && decoded.bits == code.bits && decoded.value == code.value) {
        result.matches++;
      } else if (error == nullptr) {
        result.others++;
      }
    }
  }
  stopCapture();
  radio.setRepeatTransmit(config::kRfRepeat);
  return result;
}

}  // namespace

bool selfTest() {
  if (learning.active || scan.active) {
    return false;
  }
  // A made-up code, not any remote's.
  const Code test = {0x5A5A5A, 300, 1, 24};
  const Loopback result = loopback(test);
  Serial.printf("[RF] selftest: %s (%u/%u frames decoded to the test code)\n",
                result.matches > 0 && result.others == 0 ? "OK" : "FAILED", result.matches,
                result.frames);
  return true;
}

bool verify() {
  if (learning.active || scan.active) {
    return false;
  }
  for (uint8_t i = 0; i < kButtonCount; i++) {
    const Code& code = codes[i];
    if (code.bits == 0) {
      Serial.printf("[RF] verify button %u not learned\n", i + 1);
      continue;
    }
    for (uint8_t j = 0; j < i; j++) {
      if (codes[j].bits == code.bits && codes[j].value == code.value) {
        Serial.printf("[RF] verify button %u WARNING: same code as button %u, learn one of "
                      "them again\n",
                      i + 1, j + 1);
      }
    }
    const Loopback result = loopback(code);
    const char* verdict = result.others > 0    ? "MISMATCH"
                          : result.matches > 0 ? "OK"
                                               : "NOT HEARD";
    Serial.printf("[RF] verify button %u %s (%u/%u frames match the learned code)\n", i + 1,
                  verdict, result.matches, result.frames);
  }
  return true;
}

void clear(uint8_t id) {
  if (!validId(id)) {
    return;
  }
  codes[id - 1] = {};
  save(id - 1);
  Serial.printf("[RF] button %u cleared\n", id);
}

void clearAll() {
  for (uint8_t id = 1; id <= kButtonCount; id++) {
    clear(id);
  }
}

uint8_t learnedMask() {
  uint8_t mask = 0;
  for (uint8_t i = 0; i < kButtonCount; i++) {
    if (codes[i].bits > 0) {
      mask |= 1 << i;
    }
  }
  return mask;
}

void print() {
  for (uint8_t i = 0; i < kButtonCount; i++) {
    const Code& code = codes[i];
    if (code.bits == 0) {
      Serial.printf("[RF] button %u not learned\n", i + 1);
    } else {
      Serial.printf("[RF] button %u protocol %u, %u bits, pulse %uus\n", i + 1, code.protocol,
                    code.bits, code.pulseUs);
    }
  }
}

}  // namespace rf
