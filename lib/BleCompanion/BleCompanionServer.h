#pragma once

#include <AnnotationStore.h>
#include <Arduino.h>
#include <ArduinoJson.h>
#include <PhoneNotificationStore.h>

#include <array>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <string>
#include <vector>

#include "BleCompanionProtocol.h"

class NimBLECharacteristic;
class NimBLECharacteristicCallbacks;
class NimBLEServer;

class BleCompanionServer {
 public:
  enum class State {
    Stopped,
    Advertising,
    AwaitingAuthentication,
    Connected,
    SyncingAnnotations,
    PollingNotifications,
    Error
  };

  BleCompanionServer() = default;
  ~BleCompanionServer();

  bool begin();
  void loop();
  void stop();
  bool forgetBond();
  bool startAnnotationSync(const std::string& bookPath);
  bool startNotificationPoll(uint8_t limit = 5);
  void retryNotificationPoll();
  void retryAnnotationSync();
  void sendWifiOffer(const std::string& ssid, const std::string& password, const std::string& ip,
                     const std::string& token);

  State state() const { return currentState; }
  bool connected() const { return isConnected; }
  bool ready() const { return isAuthenticated && clientReady; }
  bool hasBond() const { return bondedAtStart; }
  bool annotationSyncComplete() const { return syncComplete; }
  bool notificationPollComplete() const { return notificationComplete; }
  const std::vector<PhoneNotification>& notifications() const { return receivedNotifications; }
  bool wifiRequested() const { return phoneRequestedWifi; }
  bool wifiDecisionReceived() const { return phoneWifiDecision; }
  bool wifiLinkReady() const { return phoneJoinedWifi; }
  uint32_t revision() const { return stateRevision; }
  uint32_t passkey() const { return pairingPasskey; }
  const std::string& error() const { return lastError; }

  void enqueueControl(const uint8_t* data, size_t length);
  void onConnected();
  void onAuthenticated();
  void onDisconnected();

 private:
  struct Packet {
    uint16_t length = 0;
    std::array<uint8_t, BleCompanionProtocol::MAX_CONTROL_BYTES> data{};
  };

  static constexpr size_t QUEUE_SIZE = 6;
  std::array<Packet, QUEUE_SIZE> queue{};
  volatile uint8_t queueHead = 0;
  volatile uint8_t queueTail = 0;
  volatile bool queueOverflow = false;
  portMUX_TYPE queueMux = portMUX_INITIALIZER_UNLOCKED;

  NimBLEServer* server = nullptr;
  NimBLECharacteristic* eventCharacteristic = nullptr;
  NimBLECharacteristicCallbacks* controlCallbacks = nullptr;
  State currentState = State::Stopped;
  bool isConnected = false;
  bool isAuthenticated = false;
  bool clientReady = false;
  bool bondedAtStart = false;
  uint32_t stateRevision = 0;
  uint32_t pairingPasskey = 0;
  uint16_t pendingWifiActions = 0;
  bool phoneRequestedWifi = false;
  bool phoneWifiDecision = false;
  bool phoneJoinedWifi = false;
  std::string lastError;

  std::string syncBookPath;
  std::string syncBookSha256;
  std::string syncRevision;
  std::vector<HighlightRecord> syncAnnotations;
  size_t syncAnnotationIndex = 0;
  bool phoneChangesComplete = false;
  bool syncComplete = false;
  bool phoneAnnotationActive = false;
  size_t phoneAnnotationQuoteBytes = 0;
  size_t phoneAnnotationNoteBytes = 0;
  std::string phoneAnnotationBookPath;
  HighlightRecord phoneAnnotation;

  uint8_t notificationLimit = 5;
  size_t expectedNotifications = 0;
  size_t notificationTitleBytes = 0;
  size_t notificationTextBytes = 0;
  bool notificationActive = false;
  bool notificationComplete = false;
  PhoneNotification currentNotification;
  std::vector<PhoneNotification> receivedNotifications;

  bool enqueue(const uint8_t* data, size_t length);
  bool dequeue(Packet& packet);
  void processControl(const uint8_t* data, size_t length);
  void prepareAnnotationSync();
  void notifyCurrentAnnotation();
  void notifyAnnotationQuote(size_t offset);
  void notifyAnnotationNote(size_t offset);
  void requestPhoneAnnotationQuote(size_t offset);
  void requestPhoneAnnotationNote(size_t offset);
  void finishPhoneAnnotation();
  void finishAnnotationSyncIfReady();
  void requestNotification(size_t index);
  void requestNotificationChunk(const char* field, size_t offset);
  void finishNotification();
  void finishNotificationPoll();
  void notifyJson(const char* operation, const std::function<void(JsonDocument&)>& populate = nullptr);
  void setState(State state);
  void fail(const char* code, const std::string& message);
  static bool validBookPath(const std::string& path);
};
