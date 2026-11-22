---
name: horizon-lock-stabilization
description: Improve HorizonCam 360-degree Horizon Lock video with ultrawide 0.6x and reduced shake without breaking existing roll correction, OpenGL geometry or MP4 orientation.
---
# Horizon Lock and EIS

1. Identify previously approved lock math and regression fixtures; preserve roll correction and timing as invariant.
2. Test ultrawide 0.6x stream selection explicitly, including 1080p/4K and 30/60 fps HAL support and video session bind failures.
3. Distinguish gyro roll from yaw/pitch and translation; EIS needs real motion compensation, not only a slower roll filter. Avoid double-stabilization unless verified.
4. Check fixed crop for 360-degree roll, dynamic EIS crop margin, aspect ratio, SurfaceOutput transform and shared preview/encoder geometry.
5. Physically test stationary 0/90/180/360°, walking, panning and foreground detail; validate video pixels, not just UI badge. Quantify motion smoothness and crop, inspect rolling shutter and blur limitations.
6. Return regression test results and unsupported combinations honestly.
