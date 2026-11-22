---
name: android-kotlin-lifecycle
description: HorizonCam Android Kotlin lifecycle, CameraX bind/unbind, WorkManager, callbacks, cancellation, threads or memory leaks. Read for lifecycle or asynchronous bugs.
---

# Kotlin lifecycle / reliability
1. Follow active component owner: `MainActivity`, `PhotoEngine`, `VideoEngine`, `AutoPhotos`, workers. Do not invent scopes or migrate to Compose/coroutines unless needed.
2. Trace ownership and cancellation from callback producer through executor/thread to lifecycle release. Record trigger, state, generation/session token and observed completion.
3. Check for stale CameraX callbacks after rebind, UI writes after destroy, listener leaks, duplicate WorkManager jobs and unreleased buffers/Interpreters.
4. Preserve `AutoPhotos`: private capture, idempotent unique publication, one final public JPEG, safe retry and original fallback. Do not let retry duplicate a MediaStore row.
5. Reproduce with focused unit/instrumented test. For asynchronous behavior use condition-based waits and bounded timeouts, not arbitrary sleeps.
6. Measure first, modify smallest correct boundary, verify no photo/video regressions.
References: https://developer.android.com/topic/libraries/architecture/workmanager ; https://github.com/rcosteira79/android-skills (kotlin-coroutines, android-debugging).
