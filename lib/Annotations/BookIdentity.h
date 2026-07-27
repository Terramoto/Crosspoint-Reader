#pragma once

#include <cstddef>
#include <string>

namespace BookIdentity {

std::string sha256ForBook(const std::string& bookPath);
bool remember(const std::string& bookPath, const std::string& sha256, size_t size);
void invalidate(const std::string& bookPath);
std::string annotationPath(const std::string& bookPath, const std::string& sha256);
bool isSha256(const std::string& value);

}  // namespace BookIdentity
