#pragma once

// Copy this file to dashcam_config.h (gitignored), then enter your own values.
// XIAO ESP32-S3 uses 2.4 GHz Wi-Fi. Do not commit Wi-Fi credentials.
constexpr char WIFI_SSID[] = "YOUR_WIFI_SSID";
constexpr char WIFI_PASSWORD[] = "YOUR_WIFI_PASSWORD";
// Host only: no http://, slash, or port. Use the PC's LAN IP, not localhost.
constexpr char SERVER_HOST[] = "192.168.1.100";
constexpr uint16_t SERVER_PORT = 5000; // API: 5000; Docker dashboard proxy: 8080
constexpr char DEVICE_NAME[] = "XIAO Sense camera";
constexpr bool LIVE_ACCESS_ENABLED = true;
constexpr uint8_t LIVE_FPS = 5; // 1..10; actual rate depends on Wi-Fi and exposure.
constexpr bool CAMERA_VFLIP = false;
constexpr bool CAMERA_HMIRROR = false;
