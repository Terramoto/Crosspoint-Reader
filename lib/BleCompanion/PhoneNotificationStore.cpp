#include "PhoneNotificationStore.h"

#include <ArduinoJson.h>
#include <HalStorage.h>
#include <Logging.h>

#include <cstring>
#include <utility>

namespace {
constexpr char SETTINGS_PATH[] = "/.crosspoint/phone-notifications-settings.json";
constexpr char SNAPSHOT_PATH[] = "/.crosspoint/phone-notifications.json";
constexpr char DIAGNOSTICS_PATH[] = "/.crosspoint/phone-notifications-diagnostics.json";
constexpr size_t MAX_NOTIFICATIONS = 10;

bool validMinutes(const uint8_t value) {
  return value == 0 || value == 1 || value == 5 || value == 10 || value == 15 || value == 30;
}
}  // namespace

bool PhoneNotificationStore::recordWakeDiagnostics(const char* wakeCause,
                                                   const uint32_t armedTimerSeconds,
                                                   const int32_t timerArmResult) {
  JsonDocument doc;
  if (Storage.exists(DIAGNOSTICS_PATH)) {
    const String existing = Storage.readFile(DIAGNOSTICS_PATH);
    deserializeJson(doc, existing);
  }
  doc["wakeCount"] = (doc["wakeCount"] | 0U) + 1U;
  if (wakeCause && strcmp(wakeCause, "timer") == 0) {
    doc["timerWakeCount"] = (doc["timerWakeCount"] | 0U) + 1U;
  }
  doc["lastWakeCause"] = wakeCause ? wakeCause : "unknown";
  doc["lastArmedTimerSeconds"] = armedTimerSeconds;
  doc["lastTimerArmResult"] = timerArmResult;
  doc["lastTimerArmOk"] = timerArmResult == 0;
  Storage.mkdir("/.crosspoint");
  String encoded;
  serializeJson(doc, encoded);
  return Storage.writeFile(DIAGNOSTICS_PATH, encoded);
}

uint8_t PhoneNotificationStore::loadPollMinutes() {
  if (!Storage.exists(SETTINGS_PATH)) return 0;
  JsonDocument doc;
  const String encoded = Storage.readFile(SETTINGS_PATH);
  if (deserializeJson(doc, encoded)) return 0;
  const uint8_t minutes = doc["pollMinutes"] | 0;
  return validMinutes(minutes) ? minutes : 0;
}

bool PhoneNotificationStore::savePollMinutes(const uint8_t minutes) {
  if (!validMinutes(minutes)) return false;
  if (loadPollMinutes() == minutes && Storage.exists(SETTINGS_PATH)) return true;
  Storage.mkdir("/.crosspoint");
  JsonDocument doc;
  doc["pollMinutes"] = minutes;
  String encoded;
  serializeJson(doc, encoded);
  return Storage.writeFile(SETTINGS_PATH, encoded);
}

bool PhoneNotificationStore::load(std::vector<PhoneNotification>& notifications) {
  notifications.clear();
  if (!Storage.exists(SNAPSHOT_PATH)) return true;
  JsonDocument doc;
  const String encoded = Storage.readFile(SNAPSHOT_PATH);
  if (deserializeJson(doc, encoded)) return false;
  for (const JsonObject item : doc["notifications"].as<JsonArray>()) {
    if (notifications.size() >= MAX_NOTIFICATIONS) break;
    PhoneNotification notification;
    notification.key = item["key"] | "";
    notification.app = item["app"] | "";
    notification.title = item["title"] | "";
    notification.text = item["text"] | "";
    notification.timestamp = item["timestamp"] | 0ULL;
    notifications.push_back(std::move(notification));
  }
  return true;
}

bool PhoneNotificationStore::save(const std::vector<PhoneNotification>& notifications) {
  Storage.mkdir("/.crosspoint");
  JsonDocument doc;
  JsonArray array = doc["notifications"].to<JsonArray>();
  for (size_t index = 0; index < notifications.size() && index < MAX_NOTIFICATIONS; index++) {
    const auto& notification = notifications[index];
    JsonObject item = array.add<JsonObject>();
    item["key"] = notification.key;
    item["app"] = notification.app;
    item["title"] = notification.title;
    item["text"] = notification.text;
    item["timestamp"] = notification.timestamp;
  }
  String encoded;
  serializeJson(doc, encoded);
  return Storage.writeFile(SNAPSHOT_PATH, encoded);
}

bool PhoneNotificationStore::equal(const std::vector<PhoneNotification>& left,
                                   const std::vector<PhoneNotification>& right) {
  if (left.size() != right.size()) return false;
  for (size_t index = 0; index < left.size(); index++) {
    const auto& a = left[index];
    const auto& b = right[index];
    if (a.key != b.key || a.app != b.app || a.title != b.title || a.text != b.text ||
        a.timestamp != b.timestamp) {
      return false;
    }
  }
  return true;
}
