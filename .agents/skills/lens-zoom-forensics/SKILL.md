---
name: lens-zoom-forensics
description: Diagnose HorizonCam Galaxy S24 0.6x/1x/3x lens selection, zoom readout vs real preview/JPEG crop, 30x FOV mismatch, focus and Camera2 logical-physical routing. Use for lens, zoom or camera-open bugs.
---
# Lens and zoom forensics

1. Read `camera/ZoomRouter.kt`, `camera/RearLensManager.kt`, `camera/LensGeometry.kt`, `camera/StillFraming.kt` and targeted parts of `MainActivity.kt`.
2. Trace requested total zoom -> route choice -> actual CameraX/Camera2 control -> preview geometry -> JPEG centerCrop -> saved EXIF.
3. Check active physical camera ID only when HAL reports it; requested physical ID alone is not evidence. Inspect bind failures/fallbacks before claiming success.
4. Enforce 1x-relative zoom: desired 0.6–<1 ultrawide, 1–<3 main, 3–30 tele if public API permits; actual tele crop approx total/3, not total*3.
5. Test 0.6,1,2,2.9,3,5,10,20,30 on fixed distant target; compare pixel spans in preview and exported JPEG and Samsung reference when available. Separate crop, AF and foreground-object problems.
6. Provide minimal change, unit tests of routing and one physical validation; no arbitrary correction constant without FOV measurement.
