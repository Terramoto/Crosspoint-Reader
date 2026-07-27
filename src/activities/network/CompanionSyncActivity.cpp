#include "CompanionSyncActivity.h"

#include <GfxRenderer.h>
#include <WiFi.h>
#include <esp_random.h>
#include <esp_task_wdt.h>

#include "MappedInputManager.h"
#include "components/UITheme.h"
#include "fontIds.h"

namespace {
constexpr unsigned long WIFI_JOIN_TIMEOUT_MS = 60000;
constexpr unsigned long TRANSFER_TIMEOUT_MS = 5 * 60 * 1000;
constexpr unsigned long CLOSE_SYNC_TIMEOUT_MS = 15000;
constexpr unsigned long BLE_SYNC_TIMEOUT_MS = 60000;
constexpr unsigned long BLE_RETRY_MS = 1500;
constexpr unsigned long PAIRING_RESET_HOLD_MS = 1500;
}  // namespace

std::string CompanionSyncActivity::randomHex(const size_t bytes) {
  static constexpr char HEX_DIGITS[] = "0123456789abcdef";
  std::string value;
  value.reserve(bytes * 2);
  for (size_t i = 0; i < bytes; i++) {
    const uint8_t byte = static_cast<uint8_t>(esp_random());
    value.push_back(HEX_DIGITS[byte >> 4]);
    value.push_back(HEX_DIGITS[byte & 0x0f]);
  }
  return value;
}

void CompanionSyncActivity::onEnter() {
  Activity::onEnter();
  phase = Phase::Bluetooth;
  phaseStartedAt = millis();
  annotationStarted = false;
  wifiOfferSent = false;
  resetInputArmed = false;
  if (!ble.begin()) {
    phase = Phase::Error;
    error = "Could not start Bluetooth";
  }
  observedRevision = ble.revision();
  requestUpdate();
}

void CompanionSyncActivity::onExit() {
  stopRadios();
  Activity::onExit();
}

void CompanionSyncActivity::stopRadios() {
  ble.stop();
  if (webServer) webServer->stop();
  webServer.reset();
  WiFi.softAPdisconnect(true);
  WiFi.mode(WIFI_OFF);
}

bool CompanionSyncActivity::startHotspot() {
  phase = Phase::StartingWifi;
  requestUpdate();

  const uint64_t mac = ESP.getEfuseMac();
  char suffix[5];
  snprintf(suffix, sizeof(suffix), "%04llX", static_cast<unsigned long long>(mac & 0xffff));
  ssid = "CrossPoint-" + std::string(suffix);
  password = randomHex(12);
  token = randomHex(16);

  WiFi.mode(WIFI_AP);
  delay(80);
  if (!WiFi.softAP(ssid.c_str(), password.c_str(), 1, false, 1)) {
    error = "Could not start companion hotspot";
    phase = Phase::Error;
    return false;
  }
  delay(80);
  const std::string ip = WiFi.softAPIP().toString().c_str();
  ble.sendWifiOffer(ssid, password, ip, token);
  wifiOfferSent = true;
  phase = Phase::WaitingForWifi;
  phaseStartedAt = millis();
  requestUpdate();
  return true;
}

bool CompanionSyncActivity::startWebServer() {
  ble.stop();
  delay(50);
  webServer = std::make_unique<CrossPointWebServer>();
  webServer->configureCompanionSession(token);
  webServer->begin();
  if (!webServer->isRunning()) {
    error = "Could not start companion transfer server";
    phase = Phase::Error;
    return false;
  }
  phase = Phase::Transferring;
  phaseStartedAt = millis();
  requestUpdate();
  return true;
}

void CompanionSyncActivity::finishSession(const bool cancelled) {
  ActivityResult result;
  result.isCancelled = cancelled;
  setResult(std::move(result));
  finish();
}

void CompanionSyncActivity::loop() {
  const bool anyButtonPressed =
      mappedInput.isPressed(MappedInputManager::Button::Back) ||
      mappedInput.isPressed(MappedInputManager::Button::Confirm) ||
      mappedInput.isPressed(MappedInputManager::Button::Left) ||
      mappedInput.isPressed(MappedInputManager::Button::Right) ||
      mappedInput.isPressed(MappedInputManager::Button::Up) ||
      mappedInput.isPressed(MappedInputManager::Button::Down) ||
      mappedInput.isPressed(MappedInputManager::Button::Power);
  if (!resetInputArmed && !anyButtonPressed && !mappedInput.wasAnyReleased()) {
    // Do not accept a release inherited from the menu that launched this
    // activity. Pairing reset is armed only after a completely neutral frame.
    resetInputArmed = true;
  }

  const bool pairingResetAvailable =
      !bestEffort && (phase == Phase::Bluetooth || phase == Phase::Error);
  const bool pairingResetButtonDown =
      mappedInput.isPressed(MappedInputManager::Button::Down) ||
      mappedInput.isPressed(MappedInputManager::Button::Confirm);
  const bool pairingResetButtonReleased =
      mappedInput.wasReleased(MappedInputManager::Button::Down) ||
      mappedInput.wasReleased(MappedInputManager::Button::Confirm);
  if (pairingResetAvailable && resetInputArmed &&
      (pairingResetButtonDown || pairingResetButtonReleased) &&
      mappedInput.getHeldTime() >= PAIRING_RESET_HOLD_MS) {
    if (!ble.forgetBond()) {
      error = "Could not reset Bluetooth pairing";
      phase = Phase::Error;
      requestUpdate();
      return;
    }
    ble.stop();
    error.clear();
    phase = Phase::Bluetooth;
    if (!ble.begin()) {
      error = "Could not restart Bluetooth";
      phase = Phase::Error;
    }
    phaseStartedAt = millis();
    annotationStarted = false;
    annotationCompletedAt = 0;
    pairingWasReset = true;
    observedRevision = ble.revision();
    requestUpdate();
    return;
  }
  if (mappedInput.wasReleased(MappedInputManager::Button::Back)) {
    finishSession(true);
    return;
  }

  if (phase == Phase::Bluetooth || phase == Phase::WaitingForWifi) {
    ble.loop();
    if (ble.revision() != observedRevision) {
      observedRevision = ble.revision();
      requestUpdate();
    }
  }

  if (phase == Phase::Bluetooth) {
    const unsigned long elapsed = millis() - phaseStartedAt;
    if (ble.ready() && !annotationStarted) {
      annotationStarted = ble.startAnnotationSync(bookPath);
      annotationRetryAt = millis();
    }
    if (annotationStarted && !ble.annotationSyncComplete() &&
        millis() - annotationRetryAt >= BLE_RETRY_MS) {
      ble.retryAnnotationSync();
      annotationRetryAt = millis();
    }
    if (annotationStarted && ble.annotationSyncComplete()) {
      if (ble.wifiRequested() && allowBookTransfer) {
        startHotspot();
      } else if (ble.wifiDecisionReceived()) {
        phase = Phase::Complete;
        completedAt = millis();
        requestUpdate();
      } else if (annotationCompletedAt == 0) {
        annotationCompletedAt = millis();
      } else if (millis() - annotationCompletedAt >= 3000) {
        phase = Phase::Complete;
        completedAt = millis();
        requestUpdate();
      }
    }
    if (bestEffort && !ble.connected() && elapsed >= 3000) {
      // Close-time sync is best effort. The append-only annotation log remains
      // authoritative and will be replayed on the next companion session.
      finishSession(true);
      return;
    }
    if (bestEffort && elapsed >= CLOSE_SYNC_TIMEOUT_MS) {
      finishSession(true);
      return;
    }
    if (!bestEffort && annotationStarted && elapsed >= BLE_SYNC_TIMEOUT_MS) {
      error = "Bluetooth synchronization timed out";
      phase = Phase::Error;
      requestUpdate();
    }
  } else if (phase == Phase::WaitingForWifi) {
    if (ble.wifiLinkReady()) {
      startWebServer();
    } else if (millis() - phaseStartedAt >= WIFI_JOIN_TIMEOUT_MS) {
      error = "Phone did not join the companion hotspot";
      phase = Phase::Error;
      requestUpdate();
    }
  } else if (phase == Phase::Transferring) {
    esp_task_wdt_reset();
    webServer->handleClient();
    if (webServer->companionComplete()) {
      // The upload commits phone highlights to its durable reader queue only
      // after the EPUB exists. Return to BLE once so those highlights become
      // part of the same user-initiated synchronization session.
      restartAfterComplete = webServer->companionRestartRequired();
      webServer->stop();
      webServer.reset();
      WiFi.softAPdisconnect(true);
      WiFi.mode(WIFI_OFF);
      delay(50);
      allowBookTransfer = false;
      postTransferSync = true;
      annotationStarted = false;
      annotationCompletedAt = 0;
      phaseStartedAt = millis();
      if (ble.begin()) {
        phase = Phase::Bluetooth;
        observedRevision = ble.revision();
      } else {
        error = "Could not restart Bluetooth after transfer";
        phase = Phase::Error;
      }
      requestUpdate();
    } else if (millis() - phaseStartedAt >= TRANSFER_TIMEOUT_MS) {
      error = "Companion transfer timed out";
      phase = Phase::Error;
      requestUpdate();
    }
  } else if (phase == Phase::Complete && millis() - completedAt >= 750) {
    if (restartAfterComplete) {
      stopRadios();
      delay(150);
      ESP.restart();
      return;
    }
    finishSession();
  }
}

void CompanionSyncActivity::render(RenderLock&&) {
  renderer.clearScreen();
  const auto& metrics = UITheme::getInstance().getMetrics();
  const int width = renderer.getScreenWidth();
  const int top = metrics.topPadding + metrics.headerHeight + metrics.verticalSpacing;
  GUI.drawHeader(renderer, Rect{0, metrics.topPadding, width, metrics.headerHeight}, "Companion Sync");

  std::string message;
  if (phase == Phase::Bluetooth) {
    if (!ble.hasBond()) {
      message = (pairingWasReset ? "Pairing reset. Code: " : "Pairing code: ") +
                std::to_string(ble.passkey());
    } else if (ble.state() == BleCompanionServer::State::SyncingAnnotations) {
      message = postTransferSync ? "Restoring book highlights..." : "Syncing highlights...";
    } else {
      message = "Connecting to paired phone...";
    }
  } else if (phase == Phase::StartingWifi) {
    message = "Starting secure hotspot...";
  } else if (phase == Phase::WaitingForWifi) {
    message = "Connecting phone to " + ssid + "...";
  } else if (phase == Phase::Transferring) {
    message = "Synchronizing device library...";
  } else if (phase == Phase::Complete) {
    message = restartAfterComplete ? "Library updated. Restarting..." : "Synchronization complete";
  } else {
    message = error.empty() ? ble.error() : error;
  }
  renderer.drawCenteredText(UI_12_FONT_ID, top + 70, message.c_str(), true,
                            phase == Phase::Error ? EpdFontFamily::BOLD : EpdFontFamily::REGULAR);
  const bool pairingResetAvailable =
      !bestEffort && (phase == Phase::Bluetooth || phase == Phase::Error);
  if (pairingResetAvailable) {
    renderer.drawCenteredText(UI_10_FONT_ID, top + 105, "Hold Select or lower side 1.5s to reset", true);
  }
  const auto labels = mappedInput.mapLabels("Cancel", pairingResetAvailable ? "Hold: Reset" : "", "", "");
  GUI.drawButtonHints(renderer, labels.btn1, labels.btn2, labels.btn3, labels.btn4);
  renderer.displayBuffer();
}
