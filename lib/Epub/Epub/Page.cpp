#include "Page.h"

#include <GfxRenderer.h>
#include <Logging.h>
#include <Serialization.h>

void PageLine::render(GfxRenderer& renderer, const int fontId, const int xOffset, const int yOffset) {
  block->render(renderer, fontId, xPos + xOffset, yPos + yOffset);
}

bool PageLine::serialize(FsFile& file) {
  serialization::writePod(file, xPos);
  serialization::writePod(file, yPos);

  // serialize TextBlock pointed to by PageLine
  return block->serialize(file);
}

std::unique_ptr<PageLine> PageLine::deserialize(FsFile& file) {
  int16_t xPos;
  int16_t yPos;
  serialization::readPod(file, xPos);
  serialization::readPod(file, yPos);

  auto tb = TextBlock::deserialize(file);
  return std::unique_ptr<PageLine>(new PageLine(std::move(tb), xPos, yPos));
}

void PageImage::render(GfxRenderer& renderer, const int fontId, const int xOffset, const int yOffset) {
  // Images don't use fontId or text rendering
  imageBlock->render(renderer, xPos + xOffset, yPos + yOffset);
}

bool PageImage::serialize(FsFile& file) {
  serialization::writePod(file, xPos);
  serialization::writePod(file, yPos);

  // serialize ImageBlock
  return imageBlock->serialize(file);
}

std::unique_ptr<PageImage> PageImage::deserialize(FsFile& file) {
  int16_t xPos;
  int16_t yPos;
  serialization::readPod(file, xPos);
  serialization::readPod(file, yPos);

  auto ib = ImageBlock::deserialize(file);
  return std::unique_ptr<PageImage>(new PageImage(std::move(ib), xPos, yPos));
}

namespace {
bool wordIsHighlighted(const SourceWordRange& word, const HighlightRecord& highlight) {
  if (word.blockOrdinal == UINT32_MAX || highlight.deleted) return false;
  if (word.blockOrdinal < highlight.start.blockOrdinal || word.blockOrdinal > highlight.end.blockOrdinal) return false;
  if (word.blockOrdinal == highlight.start.blockOrdinal && word.endOffset <= highlight.start.offset) return false;
  if (word.blockOrdinal == highlight.end.blockOrdinal && word.startOffset >= highlight.end.offset) return false;
  return true;
}
}  // namespace

void Page::render(GfxRenderer& renderer, const int fontId, const int xOffset, const int yOffset,
                  const std::vector<HighlightRecord>* highlights) const {
  if (highlights && !highlights->empty()) {
    for (const auto& element : elements) {
      if (element->getTag() != TAG_PageLine) continue;
      const auto& line = static_cast<const PageLine&>(*element);
      const auto& block = line.getBlock();
      if (!block) continue;
      const auto& words = block->getWords();
      const auto& positions = block->getWordXPositions();
      const auto& styles = block->getWordStyles();
      const auto& ranges = block->getSourceRanges();
      for (size_t i = 0; i < words.size() && i < positions.size() && i < styles.size() && i < ranges.size(); i++) {
        const bool selected = std::any_of(highlights->begin(), highlights->end(),
                                          [&ranges, i](const auto& value) { return wordIsHighlighted(ranges[i], value); });
        if (!selected) continue;
        const int width = renderer.getTextAdvanceX(fontId, words[i].c_str(), styles[i]);
        renderer.fillRectDither(line.xPos + positions[i] + xOffset - 1, line.yPos + yOffset, width + 2,
                                renderer.getLineHeight(fontId), Color::LightGray);
      }
    }
  }
  for (auto& element : elements) {
    element->render(renderer, fontId, xOffset, yOffset);
  }
}

std::vector<Page::SelectableWord> Page::getSelectableWords(const GfxRenderer& renderer, const int fontId,
                                                            const int xOffset, const int yOffset) const {
  std::vector<SelectableWord> result;
  for (const auto& element : elements) {
    if (element->getTag() != TAG_PageLine) continue;
    const auto& line = static_cast<const PageLine&>(*element);
    const auto& block = line.getBlock();
    if (!block) continue;
    const auto& words = block->getWords();
    const auto& positions = block->getWordXPositions();
    const auto& styles = block->getWordStyles();
    const auto& ranges = block->getSourceRanges();
    for (size_t i = 0; i < words.size() && i < positions.size() && i < styles.size() && i < ranges.size(); i++) {
      if (ranges[i].blockOrdinal == UINT32_MAX) continue;
      result.push_back({words[i], ranges[i], static_cast<int16_t>(line.xPos + positions[i] + xOffset),
                        static_cast<int16_t>(line.yPos + yOffset),
                        static_cast<int16_t>(renderer.getTextAdvanceX(fontId, words[i].c_str(), styles[i])),
                        static_cast<int16_t>(renderer.getLineHeight(fontId))});
    }
  }
  return result;
}

bool Page::serialize(FsFile& file) const {
  const uint16_t count = elements.size();
  serialization::writePod(file, count);

  for (const auto& el : elements) {
    // Use getTag() method to determine type
    serialization::writePod(file, static_cast<uint8_t>(el->getTag()));

    if (!el->serialize(file)) {
      return false;
    }
  }

  // Serialize footnotes (clamp to MAX_FOOTNOTES_PER_PAGE to match addFootnote/deserialize limits)
  const uint16_t fnCount = std::min<uint16_t>(footnotes.size(), MAX_FOOTNOTES_PER_PAGE);
  serialization::writePod(file, fnCount);
  for (uint16_t i = 0; i < fnCount; i++) {
    const auto& fn = footnotes[i];
    if (file.write(fn.number, sizeof(fn.number)) != sizeof(fn.number) ||
        file.write(fn.href, sizeof(fn.href)) != sizeof(fn.href)) {
      LOG_ERR("PGE", "Failed to write footnote");
      return false;
    }
  }

  return true;
}

std::unique_ptr<Page> Page::deserialize(FsFile& file) {
  auto page = std::unique_ptr<Page>(new Page());

  uint16_t count;
  serialization::readPod(file, count);

  for (uint16_t i = 0; i < count; i++) {
    uint8_t tag;
    serialization::readPod(file, tag);

    if (tag == TAG_PageLine) {
      auto pl = PageLine::deserialize(file);
      page->elements.push_back(std::move(pl));
    } else if (tag == TAG_PageImage) {
      auto pi = PageImage::deserialize(file);
      page->elements.push_back(std::move(pi));
    } else {
      LOG_ERR("PGE", "Deserialization failed: Unknown tag %u", tag);
      return nullptr;
    }
  }

  // Deserialize footnotes
  uint16_t fnCount;
  serialization::readPod(file, fnCount);
  if (fnCount > MAX_FOOTNOTES_PER_PAGE) {
    LOG_ERR("PGE", "Invalid footnote count %u", fnCount);
    return nullptr;
  }
  page->footnotes.resize(fnCount);
  for (uint16_t i = 0; i < fnCount; i++) {
    auto& entry = page->footnotes[i];
    if (file.read(entry.number, sizeof(entry.number)) != sizeof(entry.number) ||
        file.read(entry.href, sizeof(entry.href)) != sizeof(entry.href)) {
      LOG_ERR("PGE", "Failed to read footnote %u", i);
      return nullptr;
    }
    entry.number[sizeof(entry.number) - 1] = '\0';
    entry.href[sizeof(entry.href) - 1] = '\0';
  }

  return page;
}
