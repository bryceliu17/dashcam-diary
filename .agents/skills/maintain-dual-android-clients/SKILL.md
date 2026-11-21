---
name: maintain-dual-android-clients
description: Change, compare, or port behavior between the current and Android 5-compatible clients in the Dashcam Diary repository without losing platform-specific behavior.
---

# Maintain Both Android Clients

Use the maintained paths and branch rules in the repository's `AGENTS.md`. Compare only the relevant code paths, share the smallest behavior change, and verify each changed client with its own `gradlew.bat :app:assembleDebug` before reviewing the diff.

## Platform boundaries

- Keep both application IDs and shared database/API contracts compatible. Do not align dependency versions or SDK floors across clients as part of a feature port.
- Both clients use CameraX for foreground video and Camera2 for background video. The current client's live camera and torch use Camera2; the legacy client's live camera and torch use `android.hardware.Camera` on API 21/22 and Camera2 on later versions. Check `recording/BackgroundRecordingService.kt` and `live/LiveAccessService.kt` for the actual paths.
- In the legacy client, preserve the camera release delay, version-gated notification and service calls, UTC serialization, and network/upload workarounds. Guard APIs newer than 21.
- Preserve the local video archive defaults in `StoragePolicy`: 25 GiB for the current client and 5.5 GiB for the legacy client. Defaults are not hard ceilings; keep support for the user's configured limit.

For video cleanup, distinguish the free-space trigger (one oldest unlocked video at the start of the check) from the configured archive limit (keep deleting the oldest unlocked video until below the limit). Do not start the next segment if a required deletion cannot be completed. Verify the current behavior in `recording/StoragePolicy.kt` before changing it.
