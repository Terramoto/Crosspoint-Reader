#include "BleCompanionServer.h"

#include <BookIdentity.h>
#include <Epub.h>
#include <HalStorage.h>
#include <Logging.h>
#include <NimBLEDevice.h>
#include <PhoneNotificationStore.h>
#include <esp_random.h>
#include <host/ble_store.h>

#include <algorithm>
#include <cstring>

namespace {
constexpr const char* TAG = "BLECOMP";

std::string hexEncode(const std::string& value) {
  static constexpr char HEX_DIGITS[] = "0123456789abcdef";
  std::string output;
  output.reserve(value.size() * 2);
  for (const uint8_t byte : value) {
    output.push_back(HEX_DIGITS[byte >> 4]);
    output.push_back(HEX_DIGITS[byte & 0x0f]);
  }
  return output;
}

bool hexDecode(const std::string& value, std::string& output) {
  if (value.size() % 2 != 0) return false;
  auto nibble = [](const char value) -> int {
    if (value >= '0' && value <= '9') return value - '0';
    if (value >= 'a' && value <= 'f') return value - 'a' + 10;
    if (value >= 'A' && value <= 'F') return value - 'A' + 10;
    return -1;
  };
  output.clear();
  output.reserve(value.size() / 2);
  for (size_t index = 0; index < value.size(); index += 2) {
    const int high = nibble(value[index]);
    const int low = nibble(value[index + 1]);
    if (high < 0 || low < 0) return false;
    output.push_back(static_cast<char>((high << 4) | low));
  }
  return true;
}

std::string revisionForPath(const std::string& path) {
  const size_t slash = path.find_last_of('/');
  std::string revision = slash == std::string::npos ? path : path.substr(slash + 1);
  const size_t extension = revision.rfind(".epub");
  if (extension != std::string::npos) revision.resize(extension);
  return revision;
}

class ServerCallbacks final : public NimBLEServerCallbacks {
 public:
  explicit ServerCallbacks(BleCompanionServer& owner) : owner(owner) {}

  void onConnect(NimBLEServer* server, NimBLEConnInfo& info) override {
    server->updateConnParams(info.getConnHandle(), 12, 24, 0, 300);
    owner.onConnected();
  }
  void onDisconnect(NimBLEServer*, NimBLEConnInfo&, int) override { owner.onDisconnected(); }
  uint32_t onPassKeyDisplay() override { return owner.passkey(); }
  void onAuthenticationComplete(NimBLEConnInfo& info) override {
    if (!info.isEncrypted()) {
      NimBLEDevice::getServer()->disconnect(info.getConnHandle());
      return;
    }
    if (NimBLEDevice::getNumBonds() > 1) {
      NimBLEDevice::deleteBond(info.getIdAddress());
      NimBLEDevice::getServer()->disconnect(info.getConnHandle());
      return;
    }
    owner.onAuthenticated();
  }

 private:
  BleCompanionServer& owner;
};

class ControlCallbacks final : public NimBLECharacteristicCallbacks {
 public:
  explicit ControlCallbacks(BleCompanionServer& owner) : owner(owner) {}
  void onWrite(NimBLECharacteristic* characteristic, NimBLEConnInfo&) override {
    const std::string value = characteristic->getValue();
    owner.enqueueControl(reinterpret_cast<const uint8_t*>(value.data()), value.size());
  }

 private:
  BleCompanionServer& owner;
};
}  // namespace

BleCompanionServer::~BleCompanionServer() { stop(); }

bool BleCompanionServer::begin() {
  if (currentState != State::Stopped) return true;
  pendingWifiActions = 0;
  phoneRequestedWifi = false;
  phoneWifiDecision = false;
  phoneJoinedWifi = false;
  phoneChangesComplete = false;
  syncComplete = false;
  syncBookPath.clear();
  syncBookSha256.clear();
  syncAnnotations.clear();
  receivedNotifications.clear();
  notificationActive = false;
  notificationComplete = false;
  pairingPasskey = 100000 + esp_random() % 900000;
  NimBLEDevice::init("CrossPoint Reader");
  bondedAtStart = NimBLEDevice::getNumBonds() > 0;
  NimBLEDevice::setMTU(517);
  NimBLEDevice::setSecurityIOCap(BLE_HS_IO_DISPLAY_ONLY);
  NimBLEDevice::setSecurityAuth(true, true, true);
  NimBLEDevice::setSecurityPasskey(pairingPasskey);

  server = NimBLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks(*this), true);
  server->advertiseOnDisconnect(true);
  NimBLEService* service = server->createService(BleCompanionProtocol::SERVICE_UUID);
  auto* control = service->createCharacteristic(
      BleCompanionProtocol::CONTROL_UUID, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_ENC,
      BleCompanionProtocol::MAX_CONTROL_BYTES);
  eventCharacteristic = service->createCharacteristic(
      BleCompanionProtocol::EVENT_UUID, NIMBLE_PROPERTY::NOTIFY | NIMBLE_PROPERTY::READ_ENC,
      BleCompanionProtocol::MAX_CONTROL_BYTES);
  controlCallbacks = new ControlCallbacks(*this);
  control->setCallbacks(controlCallbacks);
  server->start();

  NimBLEAdvertising* advertising = NimBLEDevice::getAdvertising();
  advertising->setName("CrossPoint Reader");
  advertising->addServiceUUID(BleCompanionProtocol::SERVICE_UUID);
  advertising->enableScanResponse(true);
  if (!advertising->start()) {
    stop();
    return false;
  }
  setState(State::Advertising);
  return true;
}

void BleCompanionServer::stop() {
  if (NimBLEDevice::isInitialized()) NimBLEDevice::deinit(true);
  delete controlCallbacks;
  controlCallbacks = nullptr;
  server = nullptr;
  eventCharacteristic = nullptr;
  isConnected = false;
  isAuthenticated = false;
  clientReady = false;
  pendingWifiActions = 0;
  phoneRequestedWifi = false;
  phoneWifiDecision = false;
  phoneJoinedWifi = false;
  setState(State::Stopped);
  portENTER_CRITICAL(&queueMux);
  queueHead = 0;
  queueTail = 0;
  queueOverflow = false;
  portEXIT_CRITICAL(&queueMux);
}

bool BleCompanionServer::forgetBond() {
  if (!NimBLEDevice::isInitialized() && !begin()) return false;
  if (server) {
    while (server->getConnectedCount() > 0) {
      const auto peer = server->getPeerInfo(0);
      server->disconnect(peer);
      delay(50);
    }
  }
  if (!NimBLEDevice::deleteAllBonds()) {
    ESP_LOGW(TAG, "Normal bond deletion failed; clearing the NimBLE bond store");
    if (ble_store_clear() != 0) return false;
  }
  bondedAtStart = false;
  return true;
}

void BleCompanionServer::onConnected() {
  isConnected = true;
  isAuthenticated = false;
  clientReady = false;
  setState(State::AwaitingAuthentication);
}

void BleCompanionServer::onAuthenticated() {
  isAuthenticated = true;
  bondedAtStart = true;
  setState(State::Connected);
}

void BleCompanionServer::onDisconnected() {
  isConnected = false;
  isAuthenticated = false;
  clientReady = false;
  if (currentState != State::Error) setState(State::Advertising);
}

void BleCompanionServer::setState(const State state) {
  if (currentState == state) return;
  currentState = state;
  stateRevision++;
}

bool BleCompanionServer::enqueue(const uint8_t* data, const size_t length) {
  if (!data || length == 0 || length > queue[0].data.size()) return false;
  bool accepted = false;
  portENTER_CRITICAL(&queueMux);
  const uint8_t next = (queueHead + 1) % QUEUE_SIZE;
  if (next != queueTail) {
    Packet& packet = queue[queueHead];
    packet.length = length;
    memcpy(packet.data.data(), data, length);
    queueHead = next;
    accepted = true;
  }
  portEXIT_CRITICAL(&queueMux);
  return accepted;
}

bool BleCompanionServer::dequeue(Packet& packet) {
  bool available = false;
  portENTER_CRITICAL(&queueMux);
  if (queueTail != queueHead) {
    packet = queue[queueTail];
    queueTail = (queueTail + 1) % QUEUE_SIZE;
    available = true;
  }
  portEXIT_CRITICAL(&queueMux);
  return available;
}

void BleCompanionServer::enqueueControl(const uint8_t* data, const size_t length) {
  if (!enqueue(data, length)) queueOverflow = true;
}

void BleCompanionServer::loop() {
  if (queueOverflow) {
    portENTER_CRITICAL(&queueMux);
    queueOverflow = false;
    portEXIT_CRITICAL(&queueMux);
    fail("queue_full", "Bluetooth receive queue is full");
    return;
  }
  Packet packet;
  for (int processed = 0; processed < 3 && dequeue(packet); processed++) {
    processControl(packet.data.data(), packet.length);
  }
}

void BleCompanionServer::notifyJson(const char* operation,
                                    const std::function<void(JsonDocument&)>& populate) {
  if (!isConnected || !eventCharacteristic) return;
  JsonDocument doc;
  doc["op"] = operation;
  doc["version"] = BleCompanionProtocol::VERSION;
  if (populate) populate(doc);
  std::string output;
  serializeJson(doc, output);
  if (output.size() <= BleCompanionProtocol::MAX_CONTROL_BYTES) eventCharacteristic->notify(output);
}

bool BleCompanionServer::validBookPath(const std::string& path) {
  if (path.empty() || path[0] != '/' || path.find("..") != std::string::npos) return false;
  if (path.rfind("/.crosspoint", 0) == 0) return false;
  return path.size() > 5 && path.substr(path.size() - 5) == ".epub" && Storage.exists(path.c_str());
}

bool BleCompanionServer::startAnnotationSync(const std::string& bookPath) {
  if (!ready() || currentState != State::Connected) return false;
  syncBookPath = validBookPath(bookPath) ? bookPath : "";
  syncBookSha256 = syncBookPath.empty() ? "" : BookIdentity::sha256ForBook(syncBookPath);
  syncRevision = revisionForPath(syncBookPath);
  prepareAnnotationSync();
  return true;
}

bool BleCompanionServer::startNotificationPoll(const uint8_t limit) {
  if (!ready() || currentState != State::Connected) return false;
  notificationLimit = std::min<uint8_t>(limit, 10);
  expectedNotifications = 0;
  notificationTitleBytes = 0;
  notificationTextBytes = 0;
  notificationActive = false;
  notificationComplete = false;
  currentNotification = {};
  receivedNotifications.clear();
  setState(State::PollingNotifications);
  notifyJson("notification_poll",
             [this](JsonDocument& doc) { doc["limit"] = notificationLimit; });
  return true;
}

void BleCompanionServer::retryNotificationPoll() {
  if (currentState != State::PollingNotifications || notificationComplete) return;
  if (expectedNotifications == 0 && receivedNotifications.empty() && !notificationActive) {
    notifyJson("notification_poll",
               [this](JsonDocument& doc) { doc["limit"] = notificationLimit; });
  } else if (notificationActive) {
    if (currentNotification.title.size() < notificationTitleBytes) {
      requestNotificationChunk("title", currentNotification.title.size());
    } else if (currentNotification.text.size() < notificationTextBytes) {
      requestNotificationChunk("text", currentNotification.text.size());
    } else {
      finishNotification();
    }
  } else {
    requestNotification(receivedNotifications.size());
  }
}

void BleCompanionServer::retryAnnotationSync() {
  if (currentState != State::SyncingAnnotations || syncComplete) return;
  if (!phoneChangesComplete) {
    notifyJson("sync_begin", [this](JsonDocument& doc) {
      doc["bookPath"] = syncBookPath;
      doc["bookSha256"] = syncBookSha256;
    });
  } else {
    notifyCurrentAnnotation();
  }
}

void BleCompanionServer::prepareAnnotationSync() {
  syncAnnotations.clear();
  syncAnnotationIndex = 0;
  if (!syncBookPath.empty()) {
    Epub epub(syncBookPath, "/.crosspoint");
    AnnotationStore annotations(syncBookPath, epub.getCachePath(), syncRevision);
    if (annotations.load()) syncAnnotations = annotations.highlights();
    std::sort(syncAnnotations.begin(), syncAnnotations.end(),
              [](const auto& left, const auto& right) { return left.sequence < right.sequence; });
  }
  phoneChangesComplete = false;
  phoneAnnotationActive = false;
  phoneAnnotation = {};
  phoneAnnotationBookPath.clear();
  phoneAnnotationQuoteBytes = 0;
  syncComplete = false;
  setState(State::SyncingAnnotations);
  notifyJson("sync_begin", [this](JsonDocument& doc) {
    doc["bookPath"] = syncBookPath;
    doc["bookSha256"] = syncBookSha256;
  });
}

void BleCompanionServer::processControl(const uint8_t* data, const size_t length) {
  JsonDocument doc;
  if (deserializeJson(doc, data, length)) {
    fail("bad_json", "Invalid control message");
    return;
  }
  const std::string operation = doc["op"] | "";
  if (operation == "hello") {
    clientReady = true;
    pendingWifiActions = std::min<uint16_t>(doc["pendingActions"] | 0U, 100U);
    const uint8_t notificationMinutes = doc["notificationPollMinutes"] | 0U;
    PhoneNotificationStore::savePollMinutes(notificationMinutes);
  } else if (operation == "notifications_begin") {
    if (currentState != State::PollingNotifications) return;
    expectedNotifications = std::min<size_t>(doc["count"] | 0U, notificationLimit);
    if (expectedNotifications == 0) {
      finishNotificationPoll();
    } else {
      requestNotification(0);
    }
  } else if (operation == "notification_begin") {
    const size_t index = doc["index"] | UINT32_MAX;
    const size_t titleBytes = doc["titleBytes"] | 0U;
    const size_t textBytes = doc["textBytes"] | 0U;
    if (currentState != State::PollingNotifications || notificationActive ||
        index != receivedNotifications.size() || index >= expectedNotifications ||
        titleBytes > 240 || textBytes > 600) {
      fail("bad_notification", "Invalid phone notification");
      return;
    }
    notificationActive = true;
    notificationTitleBytes = titleBytes;
    notificationTextBytes = textBytes;
    currentNotification = {};
    currentNotification.key = doc["key"] | "";
    currentNotification.app = doc["app"] | "";
    currentNotification.timestamp = doc["timestamp"] | 0ULL;
    if (currentNotification.key.size() > 80 || currentNotification.app.size() > 64) {
      fail("bad_notification", "Phone notification metadata is too large");
      return;
    }
    if (notificationTitleBytes > 0) {
      requestNotificationChunk("title", 0);
    } else if (notificationTextBytes > 0) {
      requestNotificationChunk("text", 0);
    } else {
      finishNotification();
    }
  } else if (operation == "notification_data") {
    const size_t index = doc["index"] | UINT32_MAX;
    const size_t offset = doc["offset"] | UINT32_MAX;
    const std::string field = doc["field"] | "";
    const std::string encoded = doc["hex"] | "";
    std::string chunk;
    std::string* destination = nullptr;
    size_t expectedBytes = 0;
    if (field == "title") {
      destination = &currentNotification.title;
      expectedBytes = notificationTitleBytes;
    } else if (field == "text") {
      destination = &currentNotification.text;
      expectedBytes = notificationTextBytes;
    }
    if (!notificationActive || index != receivedNotifications.size() || !destination ||
        offset != destination->size() || !hexDecode(encoded, chunk) ||
        destination->size() + chunk.size() > expectedBytes) {
      fail("bad_notification_chunk", "Invalid phone notification data");
      return;
    }
    *destination += chunk;
    if (destination->size() < expectedBytes) {
      requestNotificationChunk(field.c_str(), destination->size());
    } else if (field == "title" && notificationTextBytes > 0) {
      requestNotificationChunk("text", 0);
    } else {
      finishNotification();
    }
  } else if (operation == "annotation_next") {
    const std::string annotationId = doc["id"] | "";
    if (syncAnnotationIndex < syncAnnotations.size() &&
        annotationId == syncAnnotations[syncAnnotationIndex].id) {
      notifyAnnotationQuote(doc["offset"] | 0U);
    }
  } else if (operation == "annotation_note_next") {
    const std::string annotationId = doc["id"] | "";
    if (syncAnnotationIndex < syncAnnotations.size() &&
        annotationId == syncAnnotations[syncAnnotationIndex].id) {
      notifyAnnotationNote(doc["offset"] | 0U);
    }
  } else if (operation == "annotation_ack") {
    const std::string annotationId = doc["id"] | "";
    if (syncAnnotationIndex < syncAnnotations.size() &&
        annotationId == syncAnnotations[syncAnnotationIndex].id) {
      syncAnnotationIndex++;
      notifyCurrentAnnotation();
    }
  } else if (operation == "phone_changes_complete") {
    phoneChangesComplete = true;
    notifyCurrentAnnotation();
  } else if (operation == "annotation_put_begin") {
    const std::string bookPath = doc["bookPath"] | "";
    const size_t quoteBytes = doc["quoteBytes"] | 0U;
    const std::string annotationId = doc["id"] | "";
    if (currentState != State::SyncingAnnotations || !validBookPath(bookPath) || annotationId.empty() ||
        quoteBytes == 0 || quoteBytes > 8192) {
      fail("bad_annotation", "Invalid phone highlight");
      return;
    }
    phoneAnnotationActive = true;
    phoneAnnotationBookPath = bookPath;
    phoneAnnotationQuoteBytes = quoteBytes;
    phoneAnnotationNoteBytes = doc["noteBytes"] | 0U;
    if (phoneAnnotationNoteBytes > 8192) {
      fail("bad_annotation", "Phone note is too large");
      return;
    }
    phoneAnnotation = {};
    phoneAnnotation.id = annotationId;
    phoneAnnotation.bookRevision = doc["revision"] | "";
    phoneAnnotation.spineHref = doc["spine"] | "";
    phoneAnnotation.start.blockOrdinal = doc["startBlock"] | 0U;
    phoneAnnotation.start.offset = doc["startOffset"] | 0U;
    phoneAnnotation.end.blockOrdinal = doc["endBlock"] | 0U;
    phoneAnnotation.end.offset = doc["endOffset"] | 0U;
    phoneAnnotation.page = doc["page"] | 0U;
    phoneAnnotation.line = doc["line"] | 0U;
    phoneAnnotation.createdAt = doc["createdAt"] | 0ULL;
    phoneAnnotation.updatedAt = doc["updatedAt"] | 0ULL;
    phoneAnnotation.quote.clear();
    phoneAnnotation.note.clear();
    requestPhoneAnnotationQuote(0);
  } else if (operation == "annotation_put_quote") {
    const std::string annotationId = doc["id"] | "";
    const size_t offset = doc["offset"] | 0U;
    const std::string encoded = doc["hex"] | "";
    std::string chunk;
    if (!phoneAnnotationActive || annotationId != phoneAnnotation.id || offset != phoneAnnotation.quote.size() ||
        !hexDecode(encoded, chunk) || phoneAnnotation.quote.size() + chunk.size() > phoneAnnotationQuoteBytes) {
      fail("bad_annotation_chunk", "Invalid phone highlight data");
      return;
    }
    phoneAnnotation.quote += chunk;
    if (phoneAnnotation.quote.size() == phoneAnnotationQuoteBytes) {
      if (phoneAnnotationNoteBytes > 0) {
        requestPhoneAnnotationNote(0);
      } else {
        finishPhoneAnnotation();
      }
    } else {
      requestPhoneAnnotationQuote(phoneAnnotation.quote.size());
    }
  } else if (operation == "annotation_put_note") {
    const std::string annotationId = doc["id"] | "";
    const size_t offset = doc["offset"] | 0U;
    const std::string encoded = doc["hex"] | "";
    std::string chunk;
    if (!phoneAnnotationActive || annotationId != phoneAnnotation.id || offset != phoneAnnotation.note.size() ||
        !hexDecode(encoded, chunk) || phoneAnnotation.note.size() + chunk.size() > phoneAnnotationNoteBytes) {
      fail("bad_annotation_chunk", "Invalid phone note data");
      return;
    }
    phoneAnnotation.note += chunk;
    if (phoneAnnotation.note.size() == phoneAnnotationNoteBytes) {
      finishPhoneAnnotation();
    } else {
      requestPhoneAnnotationNote(phoneAnnotation.note.size());
    }
  } else if (operation == "wifi_request") {
    // Android refreshes its durable queue after annotation sync. The hello
    // count is only a display hint and may have changed during pairing.
    phoneRequestedWifi = true;
    phoneWifiDecision = true;
  } else if (operation == "wifi_skip") {
    phoneRequestedWifi = false;
    phoneWifiDecision = true;
  } else if (operation == "wifi_connected") {
    phoneJoinedWifi = true;
  } else if (operation == "annotation_delete") {
    const std::string bookPath = doc["bookPath"] | "";
    const std::string annotationId = doc["id"] | "";
    if (validBookPath(bookPath) && !annotationId.empty()) {
      Epub epub(bookPath, "/.crosspoint");
      AnnotationStore annotations(bookPath, epub.getCachePath(), revisionForPath(bookPath));
      if (annotations.load()) {
        annotations.remove(annotationId);
        notifyJson("annotation_delete_ack", [&bookPath, &annotationId](JsonDocument& response) {
          response["bookPath"] = bookPath;
          response["id"] = annotationId;
        });
      }
    }
  }
}

void BleCompanionServer::requestNotification(const size_t index) {
  notifyJson("notification_next",
             [index](JsonDocument& doc) { doc["index"] = index; });
}

void BleCompanionServer::requestNotificationChunk(const char* field, const size_t offset) {
  notifyJson("notification_chunk", [this, field, offset](JsonDocument& doc) {
    doc["index"] = receivedNotifications.size();
    doc["field"] = field;
    doc["offset"] = offset;
  });
}

void BleCompanionServer::finishNotification() {
  notificationActive = false;
  receivedNotifications.push_back(std::move(currentNotification));
  currentNotification = {};
  notificationTitleBytes = 0;
  notificationTextBytes = 0;
  if (receivedNotifications.size() >= expectedNotifications) {
    finishNotificationPoll();
  } else {
    requestNotification(receivedNotifications.size());
  }
}

void BleCompanionServer::finishNotificationPoll() {
  notificationComplete = true;
  notifyJson("notifications_ack", [this](JsonDocument& doc) {
    doc["count"] = receivedNotifications.size();
  });
  setState(State::Connected);
}

void BleCompanionServer::notifyCurrentAnnotation() {
  if (syncAnnotationIndex >= syncAnnotations.size()) {
    finishAnnotationSyncIfReady();
    return;
  }
  const auto& item = syncAnnotations[syncAnnotationIndex];
  notifyJson("annotation_begin", [this, &item](JsonDocument& doc) {
    doc["bookPath"] = syncBookPath;
    doc["bookSha256"] = syncBookSha256;
    doc["id"] = item.id;
    doc["revision"] = item.bookRevision;
    doc["spine"] = item.spineHref;
    doc["startBlock"] = item.start.blockOrdinal;
    doc["startOffset"] = item.start.offset;
    doc["endBlock"] = item.end.blockOrdinal;
    doc["endOffset"] = item.end.offset;
    doc["page"] = item.page;
    doc["line"] = item.line;
    doc["createdAt"] = item.createdAt;
    doc["updatedAt"] = item.updatedAt;
    doc["sequence"] = item.sequence;
    doc["deleted"] = item.deleted;
    doc["quoteBytes"] = item.quote.size();
    doc["noteBytes"] = item.note.size();
  });
}

void BleCompanionServer::requestPhoneAnnotationQuote(const size_t offset) {
  notifyJson("annotation_put_next", [this, offset](JsonDocument& doc) {
    doc["id"] = phoneAnnotation.id;
    doc["offset"] = offset;
  });
}

void BleCompanionServer::requestPhoneAnnotationNote(const size_t offset) {
  notifyJson("annotation_put_note_next", [this, offset](JsonDocument& doc) {
    doc["id"] = phoneAnnotation.id;
    doc["offset"] = offset;
  });
}

void BleCompanionServer::finishPhoneAnnotation() {
  Epub epub(phoneAnnotationBookPath, "/.crosspoint");
  AnnotationStore annotations(phoneAnnotationBookPath, epub.getCachePath(), revisionForPath(phoneAnnotationBookPath));
  const bool stored = annotations.load() && annotations.import(phoneAnnotation);
  const std::string id = phoneAnnotation.id;
  phoneAnnotationActive = false;
  phoneAnnotation = {};
  phoneAnnotationBookPath.clear();
  phoneAnnotationQuoteBytes = 0;
  phoneAnnotationNoteBytes = 0;
  if (!stored) {
    fail("annotation_store_failed", "Could not store phone highlight");
    return;
  }
  notifyJson("annotation_put_ack", [&id](JsonDocument& doc) { doc["id"] = id; });
}

void BleCompanionServer::notifyAnnotationQuote(const size_t offset) {
  if (syncAnnotationIndex >= syncAnnotations.size()) return;
  const auto& item = syncAnnotations[syncAnnotationIndex];
  if (offset > item.quote.size()) return;
  constexpr size_t CHUNK_SIZE = 128;
  const std::string chunk = item.quote.substr(offset, CHUNK_SIZE);
  const std::string encoded = hexEncode(chunk);
  notifyJson("annotation_quote", [this, &item, offset, &chunk, &encoded](JsonDocument& doc) {
    doc["bookPath"] = syncBookPath;
    doc["id"] = item.id;
    doc["offset"] = offset;
    doc["hex"] = encoded;
    doc["done"] = offset + chunk.size() >= item.quote.size();
  });
}

void BleCompanionServer::notifyAnnotationNote(const size_t offset) {
  if (syncAnnotationIndex >= syncAnnotations.size()) return;
  const auto& item = syncAnnotations[syncAnnotationIndex];
  if (offset > item.note.size()) return;
  constexpr size_t CHUNK_SIZE = 128;
  const std::string chunk = item.note.substr(offset, CHUNK_SIZE);
  const std::string encoded = hexEncode(chunk);
  notifyJson("annotation_note", [this, &item, offset, &chunk, &encoded](JsonDocument& doc) {
    doc["bookPath"] = syncBookPath;
    doc["bookSha256"] = syncBookSha256;
    doc["id"] = item.id;
    doc["offset"] = offset;
    doc["hex"] = encoded;
    doc["done"] = offset + chunk.size() >= item.note.size();
  });
}

void BleCompanionServer::finishAnnotationSyncIfReady() {
  if (syncAnnotationIndex < syncAnnotations.size() || !phoneChangesComplete || syncComplete) return;
  syncComplete = true;
  notifyJson("sync_complete");
  setState(State::Connected);
}

void BleCompanionServer::sendWifiOffer(const std::string& ssid, const std::string& password,
                                       const std::string& ip, const std::string& token) {
  notifyJson("wifi_offer", [&ssid, &password, &ip, &token](JsonDocument& doc) {
    doc["ssid"] = ssid;
    doc["password"] = password;
    doc["ip"] = ip;
    doc["port"] = 80;
    doc["token"] = token;
    doc["expiresSeconds"] = 300;
  });
}

void BleCompanionServer::fail(const char* code, const std::string& message) {
  lastError = message;
  setState(State::Error);
  notifyJson("error", [code, &message](JsonDocument& doc) {
    doc["code"] = code;
    doc["message"] = message;
  });
  ESP_LOGE(TAG, "%s: %s", code, message.c_str());
}
