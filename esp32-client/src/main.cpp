#include <Arduino.h>
#include <ArduinoJson.h>
#include <HTTPClient.h>
#include <WebSocketsClient.h>
#include <WiFi.h>
#include <esp_camera.h>

#if __has_include("dashcam_config.h")
#include "dashcam_config.h"
#else
#include "dashcam_config.example.h"
#endif

static_assert(LIVE_FPS >= 1 && LIVE_FPS <= 10, "LIVE_FPS must be 1..10");

namespace {
constexpr char VERSION[] = "0.1.0";
constexpr int STATUS_LED = 21; // Active-low LED; SD is unused in this version.
constexpr uint32_t STATUS_MS = 60000;
constexpr uint32_t VIEWER_CHECK_MS = 5000;
constexpr uint32_t HTTP_TIMEOUT_MS = 3000;
WebSocketsClient socket;
String deviceId;
String apiBase;
String cameraError;
bool registered = false;
bool socketStarted = false;
bool socketConnected = false;
bool liveWanted = false;
bool cameraReady = false;
bool frameDelivered = false;
bool statusDirty = true;
uint8_t frameFailures = 0;
uint32_t lastStatus = 0;
uint32_t lastHttpStatus = 0;
uint32_t lastFrame = 0;
uint32_t cameraRetryAt = 0;
uint32_t lastWifiRetry = 0;

bool elapsed(uint32_t previous, uint32_t interval) {
  return static_cast<uint32_t>(millis() - previous) >= interval;
}

void setWanted(bool enabled) {
  enabled = enabled && LIVE_ACCESS_ENABLED;
  if (liveWanted == enabled) return;
  liveWanted = enabled;
  cameraRetryAt = 0;
  cameraError = "";
  statusDirty = true;
  Serial.println(enabled ? "Live view requested" : "Live view stopped");
}

void stopCamera() {
  if (cameraReady) {
    esp_camera_deinit();
    Serial.println("Camera released");
  }
  cameraReady = false;
  frameDelivered = false;
  frameFailures = 0;
  digitalWrite(STATUS_LED, HIGH);
}

void fillStatus(JsonObject status) {
  status["deviceId"] = deviceId;
  status["deviceName"] = DEVICE_NAME;
  status["clientType"] = "esp32";
  status["manufacturer"] = "Seeed Studio";
  status["model"] = "XIAO ESP32-S3 Sense";
  status["androidVersion"] = "";
  status["appVersion"] = VERSION;
  status["ipAddress"] = WiFi.localIP().toString();
  // Protocol compatibility only. The UI marks these values as unmeasured.
  status["batteryLevel"] = 0;
  status["isCharging"] = false;
  status["chargingSource"] = "Unknown";
  status["liveAccessEnabled"] = LIVE_ACCESS_ENABLED;
  status["remoteControlEnabled"] = false;
  status["videoRecordingActive"] = false;
  status["audioRecordingActive"] = false;
  status["liveStreaming"] = cameraReady && frameDelivered && liveWanted;
  status["liveError"] = cameraError;
}

void sendSocketStatus() {
  if (!socketConnected) return;
  JsonDocument document;
  document["type"] = "device_status";
  fillStatus(document["status"].to<JsonObject>());
  String payload;
  serializeJson(document, payload);
  if (socket.sendTXT(payload)) {
    statusDirty = false;
    lastStatus = millis();
  }
}

bool sendHttpStatus() {
  lastHttpStatus = millis();
  JsonDocument document;
  fillStatus(document.to<JsonObject>());
  String payload;
  serializeJson(document, payload);
  WiFiClient client;
  HTTPClient http;
  http.setConnectTimeout(HTTP_TIMEOUT_MS);
  http.setTimeout(HTTP_TIMEOUT_MS);
  if (!http.begin(client, apiBase + "/api/devices/heartbeat")) return false;
  http.addHeader("Content-Type", "application/json");
  const int code = http.POST(payload);
  bool success = false;
  if (code == 200) {
    JsonDocument response;
    if (!deserializeJson(response, http.getString()) && response["liveRequested"].is<bool>()) {
      registered = true;
      // HTTP reconciles a missed stop and expires abandoned viewers. The
      // WebSocket must still be connected before the camera can run.
      setWanted(socketConnected && response["liveRequested"].as<bool>());
      success = true;
    }
  }
  if (!success) Serial.printf("Heartbeat failed: HTTP %d\n", code);
  http.end();
  return success;
}

bool startCamera() {
  if (!psramFound()) {
    cameraError = "PSRAM unavailable; check the board selection and PSRAM setting";
    return false;
  }
  camera_config_t config = {};
  config.ledc_channel = LEDC_CHANNEL_0;
  config.ledc_timer = LEDC_TIMER_0;
  // Seeed XIAO ESP32-S3 Sense expansion-board pinout.
  config.pin_d0 = 15;
  config.pin_d1 = 17;
  config.pin_d2 = 18;
  config.pin_d3 = 16;
  config.pin_d4 = 14;
  config.pin_d5 = 12;
  config.pin_d6 = 11;
  config.pin_d7 = 48;
  config.pin_xclk = 10;
  config.pin_pclk = 13;
  config.pin_vsync = 38;
  config.pin_href = 47;
  config.pin_sccb_sda = 40;
  config.pin_sccb_scl = 39;
  config.pin_pwdn = -1;
  config.pin_reset = -1;
  config.xclk_freq_hz = 20000000;
  config.pixel_format = PIXFORMAT_JPEG;
  config.frame_size = FRAMESIZE_HD;
  config.jpeg_quality = 16;
  config.fb_count = 2;
  config.fb_location = CAMERA_FB_IN_PSRAM;
  config.grab_mode = CAMERA_GRAB_LATEST;
  const esp_err_t result = esp_camera_init(&config);
  if (result != ESP_OK) {
    cameraError = "Camera initialization failed: " + String(result, HEX);
    Serial.println(cameraError);
    return false;
  }
  sensor_t *sensor = esp_camera_sensor_get();
  if (sensor) {
    sensor->set_vflip(sensor, CAMERA_VFLIP);
    sensor->set_hmirror(sensor, CAMERA_HMIRROR);
  }
  cameraReady = true;
  frameDelivered = false;
  frameFailures = 0;
  cameraError = "";
  digitalWrite(STATUS_LED, LOW);
  Serial.printf("Camera started: JPEG 1280x720, sensor PID 0x%04x\n", sensor ? sensor->id.PID : 0);
  return true;
}

void sendFrame() {
  lastFrame = millis();
  camera_fb_t *frame = esp_camera_fb_get();
  int code = 0;
  if (frame) {
    WiFiClient client;
    HTTPClient http;
    http.setConnectTimeout(HTTP_TIMEOUT_MS);
    http.setTimeout(HTTP_TIMEOUT_MS);
    if (http.begin(client, apiBase + "/api/devices/" + deviceId + "/live/frame")) {
      http.addHeader("Content-Type", "image/jpeg");
      code = http.POST(frame->buf, frame->len);
      http.end();
    }
    esp_camera_fb_return(frame);
  }
  if (code == 204) {
    frameFailures = 0;
    if (!frameDelivered) {
      frameDelivered = true;
      statusDirty = true;
    }
  } else if (code == 409 || code == 404) {
    setWanted(false);
    stopCamera();
    statusDirty = true;
  } else if (++frameFailures >= 3) {
    stopCamera();
    cameraError = "Frame upload failed; retrying camera in 10 seconds";
    cameraRetryAt = millis() + 10000;
    statusDirty = true;
    Serial.printf("Frame upload failed: HTTP %d\n", code);
  }
}

void onSocketEvent(WStype_t type, uint8_t *payload, size_t length) {
  if (type == WStype_CONNECTED) {
    socketConnected = true;
    statusDirty = true;
    Serial.println("Control WebSocket connected");
  } else if (type == WStype_DISCONNECTED) {
    socketConnected = false;
    setWanted(false);
    stopCamera();
    Serial.println("Control WebSocket disconnected");
  } else if (type == WStype_TEXT) {
    JsonDocument message;
    if (!deserializeJson(message, payload, length) && message["type"] == "live_request") {
      setWanted(message["enabled"] == true);
    }
  }
}
} // namespace

void setup() {
  Serial.begin(115200);
  pinMode(STATUS_LED, OUTPUT);
  digitalWrite(STATUS_LED, HIGH);
  if (String(WIFI_SSID) == "YOUR_WIFI_SSID") {
    Serial.println("Copy include/dashcam_config.example.h to include/dashcam_config.h and set Wi-Fi/server values first.");
    while (true) delay(1000);
  }
  WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(true);
  deviceId = "esp32-" + WiFi.macAddress();
  deviceId.replace(":", "");
  deviceId.toLowerCase();
  apiBase = "http://" + String(SERVER_HOST) + ":" + String(SERVER_PORT);
  Serial.printf("Dashcam Diary ESP32 %s\nDevice: %s\nServer: %s\n", VERSION, deviceId.c_str(), apiBase.c_str());
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
}

void loop() {
  if (WiFi.status() != WL_CONNECTED) {
    if (socketConnected) socket.disconnect();
    setWanted(false);
    stopCamera();
    registered = false;
    if (elapsed(lastWifiRetry, 15000)) {
      lastWifiRetry = millis();
      WiFi.reconnect();
    }
    delay(20);
    return;
  }
  if (!registered) {
    if (lastHttpStatus == 0 || elapsed(lastHttpStatus, 5000)) sendHttpStatus();
    if (!registered) { delay(10); return; }
  }
  if (!socketStarted) {
    socket.onEvent(onSocketEvent);
    socket.begin(SERVER_HOST, SERVER_PORT, "/api/devices/socket?deviceId=" + deviceId);
    socket.setReconnectInterval(5000);
    socket.enableHeartbeat(30000, 10000, 2);
    socketStarted = true;
  }
  socket.loop();
  if (!liveWanted || !socketConnected) {
    stopCamera();
  } else {
    if (!cameraReady && (cameraRetryAt == 0 || static_cast<int32_t>(millis() - cameraRetryAt) >= 0)) {
      if (!startCamera()) cameraRetryAt = millis() + 10000;
      statusDirty = true;
    }
    if (cameraReady && elapsed(lastFrame, 1000 / LIVE_FPS)) sendFrame();
  }
  // Refresh the viewer lease even if its browser disappears without a stop.
  if (liveWanted && elapsed(lastHttpStatus, VIEWER_CHECK_MS)) {
    if (!sendHttpStatus()) {
      setWanted(false);
      stopCamera();
    }
  } else if (!socketConnected && elapsed(lastHttpStatus, STATUS_MS)) {
    sendHttpStatus();
  }
  if (socketConnected && (statusDirty || elapsed(lastStatus, STATUS_MS))) sendSocketStatus();
  delay(2);
}
