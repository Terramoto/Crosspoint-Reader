#pragma once

#include <cstdint>
#include <string>
#include <vector>

struct SourcePoint {
  uint32_t blockOrdinal = 0;
  uint32_t offset = 0;
};

struct HighlightRecord {
  std::string id;
  std::string bookRevision;
  std::string spineHref;
  SourcePoint start;
  SourcePoint end;
  std::string quote;
  std::string note;
  uint32_t page = 0;
  uint32_t line = 0;
  uint64_t createdAt = 0;
  uint64_t updatedAt = 0;
  uint32_t sequence = 0;
  bool deleted = false;
};

class AnnotationStore {
 public:
  explicit AnnotationStore(std::string bookPath, std::string cachePath, std::string bookRevision = {});

  bool load();
  bool add(HighlightRecord highlight, HighlightRecord* stored = nullptr);
  bool import(const HighlightRecord& highlight, HighlightRecord* stored = nullptr);
  bool remove(const std::string& id);
  const std::vector<HighlightRecord>& highlights() const { return records; }
  std::vector<HighlightRecord> forSpine(const std::string& spineHref) const;
  uint32_t latestSequence() const { return nextSequence == 0 ? 0 : nextSequence - 1; }
  const std::string& bookSha256() const { return sha256; }
  const std::string& path() const { return logPath; }

 private:
  std::string bookPath;
  std::string logPath;
  std::string legacyLogPath;
  std::string sha256;
  std::string bookRevision;
  std::vector<HighlightRecord> records;
  uint32_t nextSequence = 1;

  bool append(const HighlightRecord& record);
  bool ensureManifest();
  static std::string makeId();
};
