#include "BookIdentity.h"

#include <ArduinoJson.h>
#include <HalStorage.h>
#include <Logging.h>
#include <SHA2Builder.h>
#include <esp_task_wdt.h>

#include <algorithm>
#include <cctype>
#include <functional>

namespace {

std::string identityPath(const std::string& bookPath) {
  return "/.crosspoint/book-identities/" + std::to_string(std::hash<std::string>{}(bookPath)) + ".json";
}

bool readRemembered(const std::string& bookPath, const size_t size, std::string& sha256) {
  const String json = Storage.readFile(identityPath(bookPath).c_str());
  if (json.isEmpty()) return false;
  JsonDocument doc;
  if (deserializeJson(doc, json)) return false;
  const std::string storedPath = doc["path"] | "";
  const std::string storedHash = doc["sha256"] | "";
  const size_t storedSize = doc["size"] | 0U;
  if (storedPath != bookPath || storedSize != size || !BookIdentity::isSha256(storedHash)) return false;
  sha256 = storedHash;
  return true;
}

}  // namespace

namespace BookIdentity {

bool isSha256(const std::string& value) {
  return value.size() == 64 &&
         std::all_of(value.begin(), value.end(), [](const unsigned char c) { return std::isxdigit(c) != 0; });
}

bool remember(const std::string& bookPath, const std::string& sha256, const size_t size) {
  if (bookPath.empty() || !isSha256(sha256)) return false;
  Storage.ensureDirectoryExists("/.crosspoint");
  Storage.ensureDirectoryExists("/.crosspoint/book-identities");
  JsonDocument doc;
  doc["path"] = bookPath;
  doc["sha256"] = sha256;
  doc["size"] = size;
  String json;
  serializeJson(doc, json);
  return Storage.writeFile(identityPath(bookPath).c_str(), json);
}

void invalidate(const std::string& bookPath) { Storage.remove(identityPath(bookPath).c_str()); }

std::string sha256ForBook(const std::string& bookPath) {
  HalFile file;
  if (!Storage.openFileForRead("BOOKID", bookPath, file)) return {};
  const size_t size = file.size();
  std::string remembered;
  if (readRemembered(bookPath, size, remembered)) {
    file.close();
    return remembered;
  }

  SHA256Builder digest;
  digest.begin();
  uint8_t buffer[4096];
  while (file.available()) {
    const int count = file.read(buffer, sizeof(buffer));
    if (count <= 0) {
      file.close();
      return {};
    }
    digest.add(buffer, static_cast<size_t>(count));
    esp_task_wdt_reset();
    yield();
  }
  file.close();
  digest.calculate();
  const String value = digest.toString();
  const std::string sha256 = value.c_str();
  if (!remember(bookPath, sha256, size)) {
    LOG_INF("BOOKID", "Could not cache identity for %s", bookPath.c_str());
  }
  return sha256;
}

std::string annotationPath(const std::string& bookPath, const std::string& sha256) {
  const size_t slash = bookPath.find_last_of('/');
  const std::string folder = slash == std::string::npos || slash == 0 ? "/" : bookPath.substr(0, slash);
  return folder + (folder == "/" ? "" : "/") + "." + sha256 + ".crosspoint-annotations.jsonl";
}

}  // namespace BookIdentity
