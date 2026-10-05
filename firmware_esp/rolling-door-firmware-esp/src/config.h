#pragma once

#include <stddef.h>
#include <stdint.h>

namespace config {

constexpr const char* kDeviceName = "RollingDoor";

// One random base UUID; only the second 16-bit group of the first field changes.
constexpr const char* kServiceUuid   = "a7930001-966e-4240-b881-5c2e2f2203a8";
constexpr const char* kChallengeUuid = "a7930002-966e-4240-b881-5c2e2f2203a8";
constexpr const char* kCommandUuid   = "a7930003-966e-4240-b881-5c2e2f2203a8";
constexpr const char* kStatusUuid    = "a7930004-966e-4240-b881-5c2e2f2203a8";
constexpr const char* kInfoUuid      = "a7930005-966e-4240-b881-5c2e2f2203a8";

// Advertising interval in units of 0.625ms: 160 = 100ms, 320 = 200ms.
constexpr uint16_t kAdvMinInterval = 160;
constexpr uint16_t kAdvMaxInterval = 320;

constexpr size_t kNonceLength = 16;
constexpr size_t kMacLength = 16;
constexpr size_t kFrameLength = 2 + kMacLength;  // key id + command + truncated HMAC

constexpr size_t kCommandQueueDepth = 4;

}  // namespace config
