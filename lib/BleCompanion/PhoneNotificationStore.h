#pragma once

#include <cstdint>
#include <string>
#include <vector>

struct PhoneNotification {
  std::string key;
  std::string app;
  std::string title;
  std::string text;
  uint64_t timestamp = 0;
};

namespace PhoneNotificationStore {

uint8_t loadPollMinutes();
bool savePollMinutes(uint8_t minutes);
bool recordWakeDiagnostics(const char* wakeCause, uint32_t armedTimerSeconds, int32_t timerArmResult);
bool load(std::vector<PhoneNotification>& notifications);
bool save(const std::vector<PhoneNotification>& notifications);
bool equal(const std::vector<PhoneNotification>& left, const std::vector<PhoneNotification>& right);

}  // namespace PhoneNotificationStore
