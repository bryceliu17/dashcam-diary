# ESP32 live camera / ESP32 实时摄像头

## English

This client targets the **Seeed Studio XIAO ESP32-S3 Sense** with its camera expansion board and PSRAM. It is not a generic pinout for every ESP32 camera board.

Version 0.1 provides **silent live video only**: no microphone capture, SD recording, uploads to the archive, GPS, or remote recording commands. It uses the existing dashboard's device list and **View** button. Default frames are JPEG, 1280 × 720, at up to 5 fps; actual frame rate depends on exposure and network speed. The user LED is on while the camera is active. The camera's sensor PID is printed to the serial log when live viewing starts.

### Configure, build, and flash

1. Install [PlatformIO Core](https://docs.platformio.org/en/latest/core/installation/index.html), or the PlatformIO extension for VS Code.
2. Copy `include/dashcam_config.example.h` to `include/dashcam_config.h`. The latter is gitignored and excluded from Docker build contexts.
3. Set the 2.4 GHz Wi-Fi name/password, the server PC's LAN IP, and its API port. `SERVER_HOST` is a bare host, for example `192.168.1.20`; `SERVER_PORT` is usually `5000`, or `8080` through the Docker dashboard proxy. Do not use `localhost`. This version uses HTTP/WebSocket on a trusted local network, not HTTPS/WSS.
4. From this directory run:

   ```sh
   pio run
   pio run --target upload --upload-port COM4
   pio device monitor --port COM4 --baud 115200
   ```

   Replace `COM4` with the actual port (`/dev/ttyACM0`, etc. on Linux/macOS). Flashing replaces the existing firmware. If automatic upload fails, hold BOOT while connecting USB, then release it and retry. The checked-in example configuration compiles, but will not connect until you provide your own settings.

5. Build/restart the API and web containers with the updated source (`docker compose up -d --build --no-deps api web` from the repository root). After the ESP connects, open the dashboard and select **View** beside its name. A stable `esp32-<MAC>` ID keeps it distinct from other boards. Give each board a different display name.

### Connection and camera lifecycle

- HTTP heartbeat registers the device. A WebSocket receives existing `live_request` start/stop commands and sends status immediately on changes, otherwise every 60 seconds.
- The camera initializes only for an active viewing request. Closing/hiding the viewer sends a stop. A dropped control connection or Wi-Fi releases the camera. Wi-Fi and WebSocket connections retry automatically.
- While a view is requested, a small HTTP status check every 5 seconds reconciles the request with the server. The server expires a viewer after 10 seconds without frame polling. This stops an abandoned stream on a subsequent successful check; network timeouts can add delay.
- Frames use the existing `POST /api/devices/{id}/live/frame` endpoint. The server holds the latest JPEG in memory; live frames are not saved to the archive or database.
- The dashboard retains rotation and fullscreen, and hides phone-only controls and unmeasured battery data. Only device identity/status is stored in SQLite.
- No accurate wall clock or RTC module is needed for this live-only client. Server timestamps describe receipt time, not the exact instant of sensor exposure.

If the camera fails, check the camera ribbon/expansion-board connection, power supply, PSRAM, serial log, and server address. Attach the supplied Wi-Fi antenna. Rotate the dashboard view or set `CAMERA_VFLIP` / `CAMERA_HMIRROR` if necessary. Pin definitions follow [Seeed's Sense camera documentation](https://wiki.seeedstudio.com/xiao_esp32s3_camera_usage/).

## 中文

本客户端针对 **Seeed Studio XIAO ESP32-S3 Sense**（带摄像头扩展板和 PSRAM），其他 ESP 摄像头板不能直接照搬引脚。

第一版只提供**无声实时画面**，默认 1280 × 720、最高约 5 帧/秒。不会录音、写 SD 卡、保存视频、记录 GPS 或接受远程录像指令。网页沿用设备列表中的 **View**；观看时用户指示灯亮起，串口日志会输出实际摄像头的传感器 PID。

把 `include/dashcam_config.example.h` 复制为 `include/dashcam_config.h`，填入 2.4 GHz Wi-Fi 信息、电脑局域网 IP 及端口。通常 API 端口为 `5000`，也可以使用 Docker 网页代理端口 `8080`。主机名不要带 `http://` 或端口，不要填 `localhost`。本版使用局域网 HTTP/WebSocket。配置文件不会提交到 Git，也不会进入 Docker 构建上下文。

安装 PlatformIO 后，在本目录执行上面的编译、烧写和串口监视命令，替换实际串口号。烧写会替换板子原来的固件。随后在项目根目录重新构建 API 和网页容器；ESP 联网后会自动出现在设备列表。

只有网页请求观看时才初始化摄像头。关闭直播、网页切到后台、控制连接断开或 Wi-Fi 断开时释放相机。观看期间每 5 秒向服务端核对观看状态；浏览器意外退出后，服务端在超过 10 秒没有观众请求时撤销直播，下一次核对后停播。网络超时可能增加等待时间。

直播画面只保留最新一帧在服务端内存里，不进入视频归档。数据库仅保存设备身份和状态。电池信息显示未测量，隐藏手电、前后摄像头切换、温度历史和录像控制；旋转、全屏仍然可用。此版本不需要额外时钟模块，服务端标记的是收帧时间。
