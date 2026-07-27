#pragma once

#include <cstddef>
#include <cstdint>

namespace BleCompanionProtocol {

constexpr uint8_t VERSION = 6;
constexpr size_t MAX_CONTROL_BYTES = 512;

constexpr const char* SERVICE_UUID = "7b1f0001-6f70-4f69-8f31-63726f737370";
constexpr const char* CONTROL_UUID = "7b1f0002-6f70-4f69-8f31-63726f737370";
constexpr const char* EVENT_UUID = "7b1f0003-6f70-4f69-8f31-63726f737370";

}  // namespace BleCompanionProtocol
