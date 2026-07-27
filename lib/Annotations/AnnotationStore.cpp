#include "AnnotationStore.h"

#include <ArduinoJson.h>
#include <BookIdentity.h>
#include <HalStorage.h>
#include <esp_random.h>

#include <algorithm>
#include <cstdio>

namespace {
constexpr size_t MAX_LINE = 12288;

bool readLine(HalFile& file, std::string& line) {
  line.clear();
  while (file.available()) {
    const int value = file.read();
    if (value < 0) break;
    if (value == '\n') return true;
    if (value != '\r') {
      if (line.size() >= MAX_LINE) return false;
      line.push_back(static_cast<char>(value));
    }
  }
  return !line.empty();
}
}  // namespace

AnnotationStore::AnnotationStore(std::string path, std::string cachePath, std::string revision)
    : bookPath(std::move(path)), bookRevision(std::move(revision)) {
  // Use Epub's stable cache key, but keep annotations outside the disposable
  // layout cache so "clear cache" never destroys user data.
  const size_t separator = cachePath.find_last_of('/');
  const std::string key = separator == std::string::npos ? cachePath : cachePath.substr(separator + 1);
  legacyLogPath = "/.crosspoint/annotations/" + key + ".jsonl";
  sha256 = BookIdentity::sha256ForBook(bookPath);
  logPath = sha256.empty() ? legacyLogPath : BookIdentity::annotationPath(bookPath, sha256);
  if (logPath != legacyLogPath && !Storage.exists(logPath.c_str()) && Storage.exists(legacyLogPath.c_str())) {
    Storage.rename(legacyLogPath.c_str(), logPath.c_str());
  }
}

bool AnnotationStore::load() {
  records.clear();
  nextSequence = 1;
  HalFile file;
  if (!Storage.openFileForRead("ANNOT", logPath, file)) return true;
  std::string line;
  while (readLine(file, line)) {
    JsonDocument doc;
    if (deserializeJson(doc, line)) continue;
    HighlightRecord record;
    record.id = doc["id"] | "";
    record.bookRevision = doc["revision"] | "";
    record.spineHref = doc["spine"] | "";
    record.start.blockOrdinal = doc["startBlock"] | 0U;
    record.start.offset = doc["startOffset"] | 0U;
    record.end.blockOrdinal = doc["endBlock"] | 0U;
    record.end.offset = doc["endOffset"] | 0U;
    record.quote = doc["quote"] | "";
    record.note = doc["note"] | "";
    record.page = doc["page"] | 0U;
    record.line = doc["line"] | 0U;
    record.createdAt = doc["createdAt"] | 0ULL;
    record.updatedAt = doc["updatedAt"] | 0ULL;
    record.sequence = doc["sequence"] | 0U;
    record.deleted = doc["deleted"] | false;
    if (record.id.empty()) continue;
    nextSequence = std::max(nextSequence, record.sequence + 1);
    const auto existing =
        std::find_if(records.begin(), records.end(), [&record](const auto& item) { return item.id == record.id; });
    if (existing == records.end()) {
      records.push_back(std::move(record));
    } else if (existing->sequence <= record.sequence) {
      *existing = std::move(record);
    }
  }
  file.close();
  return true;
}

std::string AnnotationStore::makeId() {
  char id[37];
  const uint32_t a = esp_random(), b = esp_random(), c = esp_random(), d = esp_random();
  snprintf(id, sizeof(id), "%08lx-%04lx-4%03lx-a%03lx-%08lx%04lx", static_cast<unsigned long>(a),
           static_cast<unsigned long>(b & 0xffff), static_cast<unsigned long>((b >> 16) & 0xfff),
           static_cast<unsigned long>(c & 0xfff), static_cast<unsigned long>(d),
           static_cast<unsigned long>((c >> 12) & 0xffff));
  return id;
}

bool AnnotationStore::add(HighlightRecord highlight, HighlightRecord* stored) {
  if (highlight.id.empty()) highlight.id = makeId();
  highlight.bookRevision = bookRevision;
  highlight.sequence = nextSequence++;
  highlight.deleted = false;
  if (!append(highlight)) return false;
  if (stored) *stored = highlight;
  records.push_back(std::move(highlight));
  return true;
}

bool AnnotationStore::import(const HighlightRecord& highlight, HighlightRecord* stored) {
  if (highlight.id.empty() || highlight.deleted || highlight.spineHref.empty() || highlight.quote.empty()) {
    return false;
  }
  const auto found =
      std::find_if(records.begin(), records.end(), [&highlight](const auto& item) { return item.id == highlight.id; });
  // A matching record (including a tombstone) is authoritative on the reader.
  // Replaying a phone backup must not resurrect a deliberately removed highlight.
  if (found != records.end()) {
    if (!found->deleted && highlight.updatedAt > found->updatedAt &&
        (highlight.note != found->note || highlight.createdAt != found->createdAt)) {
      HighlightRecord updated = *found;
      updated.note = highlight.note;
      updated.createdAt = highlight.createdAt;
      updated.updatedAt = highlight.updatedAt;
      updated.sequence = nextSequence++;
      if (!append(updated)) return false;
      *found = std::move(updated);
    }
    if (stored) *stored = *found;
    return true;
  }
  HighlightRecord imported = highlight;
  imported.bookRevision = bookRevision;
  imported.sequence = nextSequence++;
  imported.deleted = false;
  if (!append(imported)) return false;
  if (stored) *stored = imported;
  records.push_back(std::move(imported));
  return true;
}

bool AnnotationStore::remove(const std::string& id) {
  const auto found = std::find_if(records.begin(), records.end(), [&id](const auto& item) { return item.id == id; });
  if (found == records.end() || found->deleted) return false;
  HighlightRecord tombstone = *found;
  tombstone.sequence = nextSequence++;
  tombstone.deleted = true;
  tombstone.quote.clear();
  tombstone.note.clear();
  if (!append(tombstone)) return false;
  *found = std::move(tombstone);
  return true;
}

std::vector<HighlightRecord> AnnotationStore::forSpine(const std::string& spineHref) const {
  std::vector<HighlightRecord> result;
  for (const auto& record : records) {
    if (!record.deleted && record.spineHref == spineHref) result.push_back(record);
  }
  return result;
}

bool AnnotationStore::append(const HighlightRecord& record) {
  if (!ensureManifest()) return false;
  HalFile file = Storage.open(logPath.c_str(), O_RDWR | O_CREAT | O_AT_END);
  if (!file) return false;
  JsonDocument doc;
  doc["id"] = record.id;
  doc["revision"] = record.bookRevision;
  doc["spine"] = record.spineHref;
  doc["startBlock"] = record.start.blockOrdinal;
  doc["startOffset"] = record.start.offset;
  doc["endBlock"] = record.end.blockOrdinal;
  doc["endOffset"] = record.end.offset;
  doc["quote"] = record.quote;
  doc["note"] = record.note;
  doc["page"] = record.page;
  doc["line"] = record.line;
  doc["createdAt"] = record.createdAt;
  doc["updatedAt"] = record.updatedAt;
  doc["sequence"] = record.sequence;
  doc["deleted"] = record.deleted;
  const bool ok = serializeJson(doc, file) > 0 && file.write(static_cast<uint8_t>('\n')) == 1;
  file.flush();
  file.close();
  return ok;
}

bool AnnotationStore::ensureManifest() {
  if (Storage.exists(logPath.c_str())) return true;
  const size_t slash = logPath.find_last_of('/');
  if (slash != std::string::npos) Storage.ensureDirectoryExists(logPath.substr(0, slash).c_str());
  HalFile file = Storage.open(logPath.c_str(), O_RDWR | O_CREAT | O_AT_END);
  if (!file) return false;
  JsonDocument doc;
  doc["type"] = "manifest";
  doc["schema"] = 2;
  doc["bookSha256"] = sha256;
  doc["bookPath"] = bookPath;
  doc["revision"] = bookRevision;
  const bool ok = serializeJson(doc, file) > 0 && file.write(static_cast<uint8_t>('\n')) == 1;
  file.flush();
  file.close();
  return ok;
}
