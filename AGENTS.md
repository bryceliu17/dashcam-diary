# Dashcam Repository Instructions

## Maintained Sources

- `main` contains every maintained component.
- `android-app/` is the current Android client for API 26+.
- `android-app-legacy/` is the Android 5-compatible client for API 21+.
- `server/`, `web-dashboard/`, `transcription-worker/`, and deployment files are shared by both clients.
- `android-5-compatible` is a historical backup branch. Do not develop, deploy, or infer freshness from it.

## Maintaining Both Android Clients

- Use `.agents/skills/maintain-dual-android-clients/SKILL.md` whenever a task changes, ports, or compares Android behavior across these two directories.
- Apply shared behavior to both clients unless the user explicitly limits the task to one client.
- Compare the two implementations and port the smallest required business-logic changes. Never replace whole files when they contain platform-specific implementation details.
- Preserve all Android 5 compatibility code unless the user explicitly authorizes a compatibility change.
- Keep the Android 5 local video archive limit at exactly 5.5 GiB: `11L * 1024 * 1024 * 1024 / 2`.
- Keep the current client local video archive limit at 25 GiB. Never exchange either client's storage limit, dependencies, or camera implementation with the other.
- Build every changed Android client and inspect the final diff before committing or pushing.
