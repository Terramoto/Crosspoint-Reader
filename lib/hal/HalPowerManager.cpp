#include "HalPowerManager.h"

#include <Logging.h>
#include <WiFi.h>
#include <esp_attr.h>
#include <esp_err.h>
#include <esp_sleep.h>

#include <cassert>

#include "HalGPIO.h"

HalPowerManager powerManager;  // Singleton instance

namespace {
RTC_DATA_ATTR int32_t retainedTimerArmResult = ESP_OK;
RTC_DATA_ATTR uint32_t retainedTimerSeconds = 0;
}  // namespace

void HalPowerManager::begin() {
  // A deep-sleep wake retains the global GPIO hold state. Release it before
  // normal operation; GPIO13 itself remains configured HIGH from standby.
  gpio_deep_sleep_hold_dis();
  pinMode(BAT_GPIO0, INPUT);
  normalFreq = getCpuFrequencyMhz();
  modeMutex = xSemaphoreCreateMutex();
  assert(modeMutex != nullptr);
}

void HalPowerManager::setPowerSaving(bool enabled) {
  if (normalFreq <= 0) {
    return;  // invalid state
  }

  auto wifiMode = WiFi.getMode();
  if (wifiMode != WIFI_MODE_NULL) {
    // Wifi is active, force disabling power saving
    enabled = false;
  }

  // Note: We don't use mutex here to avoid too much overhead,
  // it's not very important if we read a slightly stale value for currentLockMode
  const LockMode mode = currentLockMode;

  if (mode == None && enabled && !isLowPower) {
    LOG_DBG("PWR", "Going to low-power mode");
    if (!setCpuFrequencyMhz(LOW_POWER_FREQ)) {
      LOG_DBG("PWR", "Failed to set CPU frequency = %d MHz", LOW_POWER_FREQ);
      return;
    }
    isLowPower = true;

  } else if ((!enabled || mode != None) && isLowPower) {
    LOG_DBG("PWR", "Restoring normal CPU frequency");
    if (!setCpuFrequencyMhz(normalFreq)) {
      LOG_DBG("PWR", "Failed to set CPU frequency = %d MHz", normalFreq);
      return;
    }
    isLowPower = false;
  }

  // Otherwise, no change needed
}

void HalPowerManager::powerOff(HalGPIO& gpio) const { enterSleep(gpio, false, 0); }

void HalPowerManager::startNotificationStandby(HalGPIO& gpio, const uint32_t timerSeconds) const {
  enterSleep(gpio, true, timerSeconds);
}

void HalPowerManager::enterSleep(HalGPIO& gpio, const bool keepPowerLatched,
                                 const uint32_t timerSeconds) const {
  // Ensure that the power button has been released to avoid immediately turning back on if you're holding it
  while (gpio.isPressed(HalGPIO::BTN_POWER)) {
    delay(50);
    gpio.update();
  }

  // GPIO13 controls the battery latch. LOW gives the reader its original
  // complete power-off behavior. HIGH keeps the ESP32-C3 and its RTC domain
  // powered so timer wake can work during notification standby.
  constexpr gpio_num_t BATTERY_LATCH_PIN = GPIO_NUM_13;
  gpio_hold_dis(BATTERY_LATCH_PIN);
  gpio_deep_sleep_hold_dis();
  gpio_set_direction(BATTERY_LATCH_PIN, GPIO_MODE_OUTPUT);
  gpio_set_level(BATTERY_LATCH_PIN, keepPowerLatched ? 1 : 0);
  esp_sleep_config_gpio_isolate();
  gpio_deep_sleep_hold_en();
  gpio_hold_en(BATTERY_LATCH_PIN);

  pinMode(InputManager::POWER_BUTTON_PIN, INPUT_PULLUP);
  // Arm the wakeup trigger *after* the button is released
  const esp_err_t gpioResult =
      esp_deep_sleep_enable_gpio_wakeup(1ULL << InputManager::POWER_BUTTON_PIN, ESP_GPIO_WAKEUP_GPIO_LOW);
  if (gpioResult != ESP_OK) {
    LOG_ERR("PWR", "Could not arm deep-sleep button wake: %s", esp_err_to_name(gpioResult));
  }
  retainedTimerSeconds = keepPowerLatched ? timerSeconds : 0;
  retainedTimerArmResult = ESP_OK;
  if (keepPowerLatched && timerSeconds > 0) {
    retainedTimerArmResult =
        esp_sleep_enable_timer_wakeup(static_cast<uint64_t>(timerSeconds) * 1000000ULL);
    if (retainedTimerArmResult != ESP_OK) {
      LOG_ERR("PWR", "Could not arm %lu-second timer wake: %s", static_cast<unsigned long>(timerSeconds),
              esp_err_to_name(retainedTimerArmResult));
    } else {
      LOG_INF("PWR", "Armed deep-sleep timer for %lu seconds", static_cast<unsigned long>(timerSeconds));
    }
  }
  LOG_INF("PWR", "Entering %s", keepPowerLatched ? "notification standby" : "full power off");
  esp_deep_sleep_start();
}

int32_t HalPowerManager::getLastTimerArmResult() const { return retainedTimerArmResult; }

uint32_t HalPowerManager::getLastArmedTimerSeconds() const { return retainedTimerSeconds; }

uint16_t HalPowerManager::getBatteryPercentage() const {
  static const BatteryMonitor battery = BatteryMonitor(BAT_GPIO0);
  return battery.readPercentage();
}

HalPowerManager::Lock::Lock() {
  xSemaphoreTake(powerManager.modeMutex, portMAX_DELAY);
  // Current limitation: only one lock at a time
  if (powerManager.currentLockMode != None) {
    LOG_ERR("PWR", "Lock already held, ignore");
    valid = false;
  } else {
    powerManager.currentLockMode = NormalSpeed;
    valid = true;
  }
  xSemaphoreGive(powerManager.modeMutex);
  if (valid) {
    // Immediately restore normal CPU frequency if currently in low-power mode
    powerManager.setPowerSaving(false);
  }
}

HalPowerManager::Lock::~Lock() {
  xSemaphoreTake(powerManager.modeMutex, portMAX_DELAY);
  if (valid) {
    powerManager.currentLockMode = None;
  }
  xSemaphoreGive(powerManager.modeMutex);
}
