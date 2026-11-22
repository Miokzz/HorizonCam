---
name: camera-ui-views
description: Implement or review HorizonCam camera UI using Android Views/ViewBinding, zoom controls, preview geometry, accessibility and recording status.
---

# Camera UI / Android Views
1. Inspect existing `CameraChromeLayout`, `MainActivity` UI methods, resources and ViewBinding. Keep existing stack, do not introduce Compose.
2. Preserve live preview geometry and overlays: ensure screen labels reflect `applied zoom`, active physical evidence and capture readiness; do not lie during rebinding.
3. Distinguish requested 30x from effective applied FOV; show failures and processing stage succinctly; no duplicate gallery thumbnails.
4. One-handed ergonomics, high contrast, talkback labels, minimum touch area, safe insets, landscape/presentation orientation.
5. Prevent flicker on bind/unbind, preserve interaction during AI worker load; measure response, don't substitute decorative animation for hardware state.
6. Test portrait/landscape and loading/error/recording states at least once when applicable.
References: https://developer.android.com/develop/ui/views ; https://github.com/android/skills.
