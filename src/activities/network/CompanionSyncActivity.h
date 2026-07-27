#pragma once

#include <BleCompanionServer.h>

#include <memory>
#include <string>

#include "activities/Activity.h"
#include "network/CrossPointWebServer.h"

class CompanionSyncActivity final : public Activity {
 public:
  CompanionSyncActivity(GfxRenderer& renderer, MappedInputManager& mappedInput, std::string bookPath = {},
                        bool bestEffort = false, bool allowBookTransfer = true)
      : Activity("CompanionSync", renderer, mappedInput),
        bookPath(std::move(bookPath)),
        bestEffort(bestEffort),
        allowBookTransfer(allowBookTransfer) {}

  void onEnter() override;
  void onExit() override;
  void loop() override;
  void render(RenderLock&&) override;
  bool preventAutoSleep() override { return true; }
  bool skipLoopDelay() override { return webServer && webServer->isRunning(); }

 private:
  enum class Phase { Bluetooth, StartingWifi, WaitingForWifi, Transferring, Complete, Error };

  std::string bookPath;
  bool bestEffort = false;
  bool allowBookTransfer = true;
  BleCompanionServer ble;
  std::unique_ptr<CrossPointWebServer> webServer;
  Phase phase = Phase::Bluetooth;
  bool annotationStarted = false;
  bool wifiOfferSent = false;
  bool postTransferSync = false;
  bool restartAfterComplete = false;
  bool pairingWasReset = false;
  bool resetInputArmed = false;
  unsigned long phaseStartedAt = 0;
  unsigned long completedAt = 0;
  unsigned long annotationCompletedAt = 0;
  unsigned long annotationRetryAt = 0;
  uint32_t observedRevision = 0;
  std::string ssid;
  std::string password;
  std::string token;
  std::string error;

  bool startHotspot();
  bool startWebServer();
  void stopRadios();
  void finishSession(bool cancelled = false);
  static std::string randomHex(size_t bytes);
};
