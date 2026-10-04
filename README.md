# Horizon Cam

Android camera prototype for the Galaxy S24 that recreates Horizon Lock in a separate app using public Android camera APIs.

## Goal

- Horizon correction on preview and recorded video
- 1080p / 4K
- 30 / 60 fps when supported by the camera/effect pipeline
- Audio recording
- Dynamic crop to hide black corners
- Saves to `Movies/HorizonCam`

## Build APK with GitHub Actions

Every push to `main` triggers **Build HorizonCam APK**.

After a successful run:

**Actions → latest Build HorizonCam APK → Artifacts → HorizonCam-S24-debug**

Start testing at **1080p / 30 fps** on the S24, then move to 4K30 and 60 fps modes.
