// HIFAKI field prototype — ESP32-C3 SuperMini, Arduino-ESP32 3.x.
// Sensor: DS18B20 DQ=GPIO3 + pull-up 4.7k to 3V3;
// MAX30102 and BMP280 share I2C: SDA=GPIO6, SCL=GPIO7, common GND.
// GPS and camera live on the Android phone; firmware sends sensor data over BLE.
// BLE works without an internet connection. No Wi-Fi credentials are required.
#include <Arduino.h>
#include <Wire.h>
#include <OneWire.h>
#include <DallasTemperature.h>
#include <MAX30105.h>
#include <heartRate.h>
#include <Adafruit_BMP280.h>
#include <NimBLEDevice.h> // NimBLE-Arduino 2.x (h2zero)
#include <Preferences.h>

#if defined(CONFIG_IDF_TARGET_ESP32C3)
constexpr int PIN_TEMP = 3;
constexpr int PIN_SDA = 6;
constexpr int PIN_SCL = 7;
constexpr int PIN_BATTERY = 0;
#else // ESP32 NodeMCU Amica (ESP32-WROOM-32 / DevKit)
constexpr int PIN_TEMP = 4;      // DS18B20 DQ / Data pada GPIO 4
constexpr int PIN_SDA = 21;     // I2C SDA pada GPIO 21
constexpr int PIN_SCL = 22;     // I2C SCL pada GPIO 22
constexpr int PIN_BATTERY = 34;    // Analog ADC1 pada GPIO 34
#endif
constexpr bool HAS_BATTERY_DIVIDER = false; // Set true only after wiring two 100 kΩ resistors.
constexpr float SEA_LEVEL_HPA = 1013.25f;  // Calibrate against local pressure at sea level.
constexpr char SERVICE_UUID[] = "c7e2b450-8a9f-4b55-9c57-6741f3a8d001";
constexpr char DATA_UUID[]    = "c7e2b450-8a9f-4b55-9c57-6741f3a8d002";

OneWire oneWire(PIN_TEMP);
DallasTemperature thermometer(&oneWire);
MAX30105 optical;
Adafruit_BMP280 barometer;
NimBLECharacteristic* dataCharacteristic = nullptr;
NimBLEServer* server = nullptr;
volatile uint16_t secureHandle = 0xffff;
bool opticalReady = false;
bool barometerReady = false;
bool temperatureReady = false;
bool fingerPresent = false;
float skinC = NAN;
float heartBpm = NAN;
float altitudeM = NAN;
uint8_t batteryPct = 255;
uint16_t sequenceNumber = 0;
uint32_t tempRequestedAt = 0;
uint32_t lastTempAt = 0;
uint32_t lastBarometerAt = 0;
uint32_t lastBatteryAt = 0;
uint32_t lastNotifyAt = 0;
uint32_t lastBeatAt = 0;
float rates[4] = {0};
uint8_t rateIndex = 0;
uint8_t rateCount = 0;

class SecurityCallbacks : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer* s, NimBLEConnInfo& info) override {
    if (info.isAuthenticated() && info.isEncrypted()) secureHandle = info.getConnHandle();
  }
  void onAuthenticationComplete(NimBLEConnInfo& info) override {
    if (info.isAuthenticated() && info.isEncrypted()) secureHandle = info.getConnHandle();
    else server->disconnect(info.getConnHandle());
  }
  void onDisconnect(NimBLEServer* s, NimBLEConnInfo& info, int reason) override {
    if (secureHandle == info.getConnHandle()) secureHandle = 0xffff;
  }
} securityCallbacks;

void put16(uint8_t* bytes, int offset, uint16_t value) {
  bytes[offset] = value & 0xff;
  bytes[offset + 1] = value >> 8;
}

void scanI2C() {
  Serial.print("I2C scan:");
  bool found = false;
  for (uint8_t address = 0x08; address < 0x78; ++address) {
    Wire.beginTransmission(address);
    if (Wire.endTransmission() == 0) {
      Serial.printf(" 0x%02X", address);
      found = true;
    }
  }
  if (!found) Serial.print(" none — check 3V3, GND, SDA and SCL");
  Serial.println();
  Serial.println("Expected: MAX30102 0x57; BMP280 0x76 or 0x77");
}

void publish() {
  uint8_t packet[12] = {};
  packet[0] = 1; // version
  const bool validTemperature = temperatureReady && isfinite(skinC) && skinC >= -20 && skinC <= 60;
  const bool validPulse = opticalReady && fingerPresent && isfinite(heartBpm) && heartBpm >= 30 && heartBpm <= 220;
  const bool validAltitude = barometerReady && isfinite(altitudeM) && altitudeM >= -500 && altitudeM <= 9000;
  put16(packet, 1, validTemperature ? (int16_t)lroundf(skinC * 100) : 0x7fff);
  put16(packet, 3, validPulse ? (uint16_t)lroundf(heartBpm) : 0xffff);
  put16(packet, 5, validAltitude ? (int16_t)lroundf(altitudeM) : 0x7fff);
  packet[7] = (validTemperature ? 1 : 0) | (validPulse ? 2 : 0) |
              (validAltitude ? 4 : 0) | (fingerPresent ? 8 : 0);
  packet[8] = batteryPct;
  put16(packet, 9, sequenceNumber++);
  for (int i = 0; i < 11; ++i) packet[11] ^= packet[i];
  dataCharacteristic->setValue(packet, sizeof(packet));
  // Send only to the link confirmed encrypted and authenticated.
  if (secureHandle != 0xffff) dataCharacteristic->notify(secureHandle);
  Serial.printf("temp=%s bpm=%s alt=%s battery=%s\n",
                validTemperature ? String(skinC, 2).c_str() : "invalid",
                validPulse ? String(heartBpm, 0).c_str() : "invalid",
                validAltitude ? String(altitudeM, 0).c_str() : "invalid",
                batteryPct <= 100 ? String(batteryPct).c_str() : "unknown");
}

void updatePulse() {
  if (!opticalReady) return;
  const uint32_t ir = optical.getIR();
  if (ir < 50000) { // Finger absent. Tune this for the breakout and finger placement.
    fingerPresent = false;
    heartBpm = NAN;
    lastBeatAt = 0;
    rateCount = 0;
    rateIndex = 0;
    return;
  }
  fingerPresent = true;
  const uint32_t now = millis();
  if (checkForBeat(ir)) {
    if (lastBeatAt != 0) {
      uint32_t elapsed = now - lastBeatAt;
      float candidate = 60000.0f / elapsed;
      if (elapsed >= 280 && elapsed <= 1700 && candidate >= 35 && candidate <= 210) {
        rates[rateIndex] = candidate;
        rateIndex = (rateIndex + 1) % 4;
        if (rateCount < 4) rateCount++;
        if (rateCount >= 3) {
          float total = 0;
          for (int i = 0; i < rateCount; i++) total += rates[i];
          heartBpm = total / rateCount;
        }
      }
    }
    lastBeatAt = now;
  }
  if (lastBeatAt == 0 || now - lastBeatAt > 10000) {
    heartBpm = NAN;
    rateCount = 0;
    rateIndex = 0;
  }
}

void updateTemperature(uint32_t now) {
  if (tempRequestedAt != 0 && now - tempRequestedAt >= 800) {
    float value = thermometer.getTempCByIndex(0);
    // DallasTemperature returns DEVICE_DISCONNECTED_C (-127 °C) for failed reads.
    skinC = (value == DEVICE_DISCONNECTED_C || value == 85.0f || value < -20 || value > 60)
            ? NAN : value;
    temperatureReady = isfinite(skinC);
    tempRequestedAt = 0;
    lastTempAt = now;
  }
  if (tempRequestedAt == 0 && now - lastTempAt >= 2000) {
    thermometer.begin(); // Allows reconnection of a probe after startup.
    thermometer.requestTemperatures();
    tempRequestedAt = now;
  }
}

void setup() {
  Serial.begin(115200);
  Wire.begin(PIN_SDA, PIN_SCL);
  Wire.setClock(100000); // Lebih toleran untuk kabel prototipe yang pendek.
  scanI2C();
  thermometer.begin();
  thermometer.setResolution(12);
  thermometer.setWaitForConversion(false);
  thermometer.requestTemperatures();
  tempRequestedAt = millis();
  opticalReady = optical.begin(Wire, I2C_SPEED_STANDARD);
  if (opticalReady) optical.setup(0x1f, 4, 2, 100, 411, 4096);
  barometerReady = barometer.begin(0x76) || barometer.begin(0x77);
  if (HAS_BATTERY_DIVIDER) analogReadResolution(12);

  Preferences prefs;
  prefs.begin("hifaki", false);
  uint32_t passkey = prefs.getUInt("pin", 0);
  if (passkey < 100000 || passkey > 999999) {
    passkey = 100000 + (esp_random() % 900000);
    prefs.putUInt("pin", passkey);
  }
  prefs.end();
  Serial.printf("HIFAKI pairing code: %06lu. Pair in the workshop and label the case.\n", (unsigned long)passkey);
  Serial.printf("Sensors: DS18B20=%s MAX30102=%s BMP280=%s\n",
                thermometer.getDeviceCount() ? "found" : "missing",
                opticalReady ? "found" : "missing", barometerReady ? "found" : "missing");

  NimBLEDevice::init("HIFAKI-C3");
  NimBLEDevice::setSecurityAuth(true, true, true);
  NimBLEDevice::setSecurityIOCap(BLE_HS_IO_DISPLAY_ONLY);
  NimBLEDevice::setSecurityPasskey(passkey);
  server = NimBLEDevice::createServer();
  server->setCallbacks(&securityCallbacks);
  server->advertiseOnDisconnect(true);
  NimBLEService* service = server->createService(SERVICE_UUID);
  dataCharacteristic = service->createCharacteristic(DATA_UUID,
      NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::READ_AUTHEN | NIMBLE_PROPERTY::NOTIFY);
  service->start();
  NimBLEAdvertising* advertising = NimBLEDevice::getAdvertising();
  advertising->addServiceUUID(SERVICE_UUID);
  advertising->setName("HIFAKI-C3");
  advertising->enableScanResponse(true);
  advertising->start();
}

void loop() {
  const uint32_t now = millis();
  updatePulse();
  updateTemperature(now);
  if (now - lastBarometerAt >= 2000) {
    lastBarometerAt = now;
    if (barometerReady) altitudeM = barometer.readAltitude(SEA_LEVEL_HPA);
  }
  if (HAS_BATTERY_DIVIDER && now - lastBatteryAt >= 10000) {
    lastBatteryAt = now;
    const float volts = analogReadMilliVolts(PIN_BATTERY) * 0.002f; // 100k/100k divider
    batteryPct = (uint8_t)constrain(lroundf((volts - 3.2f) * 100.0f), 0, 100);
  }
  if (now - lastNotifyAt >= 2000) {
    lastNotifyAt = now;
    publish();
  }
  delay(10);
}
