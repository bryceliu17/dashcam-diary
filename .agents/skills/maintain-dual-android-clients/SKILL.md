---
name: maintain-dual-android-clients
description: Safely change, compare, or synchronize the current and Android 5-compatible clients in the dashcam repository while preserving each directory's platform-specific camera, dependency, and storage behavior.
---

# Maintain Both Android Clients

Both maintained clients live on `main`:

- `android-app/`: API 26+, modern implementation.
- `android-app-legacy/`: API 21+, Android 5-compatible implementation.

The `android-5-compatible` branch is only a historical backup. Do not use it as the source for new development or deployment.

## Workflow

1. Confirm which client behavior the user wants changed. Shared product behavior normally applies to both directories.
2. Compare the relevant implementations before editing; do not copy whole files across clients when platform-specific code is present.
3. Port only the shared business behavior and retain the target client's compatibility implementation.
4. Build every changed client with its own `gradlew.bat :app:assembleDebug` and inspect the final diff.

## Invariants

- Both clients keep `applicationId = "com.example.dashcam"` and compatible database/API payload contracts.
- `android-app/` keeps `minSdk = 26`, its current dependency versions, CameraX foreground recording, and Camera2 background/live/torch implementation.
- `android-app/` keeps `MAX_VIDEO_BYTES = 25L * 1024 * 1024 * 1024`.
- `android-app-legacy/` keeps `minSdk = 21`, CameraX 1.3.4, WorkManager 2.9.1, and its pinned AndroidX/Lifecycle versions.
- `android-app-legacy/` keeps `MAX_VIDEO_BYTES = 11L * 1024 * 1024 * 1024 / 2` (5.5 GiB).
- Preserve its API 21/22 legacy `android.hardware.Camera` paths for background recording, live frames, and torch control through `Camera.Parameters.FLASH_MODE_TORCH`.
- Preserve its release delay, version-gated notifications, `PendingIntent`/service fallbacks, `SimpleDateFormat` UTC serialization, and networking/upload compatibility workarounds.
- Do not introduce unguarded APIs above API 21 into `android-app-legacy/`.
- Cleanup deletes at most one oldest unlocked video per required check. If none can be deleted, do not start the next segment.
- Server/dashboard changes belong to the shared main implementation and must not be duplicated inside either Android project.
