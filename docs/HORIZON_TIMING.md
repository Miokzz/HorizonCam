# Horizon Lock timing refinement (V3)

## Observed implementation

Before this change, `GravityRollSensor` requested `SENSOR_DELAY_GAME` (the
Android implementation maps this to 20,000 microseconds, approximately 50 Hz).
`OrientationHistory` already interpolates the fused attitude at each camera
timestamp when the camera declares a comparable realtime timebase. The GL
processor uses one sampled roll for both preview and recording outputs.

A 50 Hz request undersamples a 60 fps stream. Interpolation cannot recover roll
changes between its samples exactly. This is an identified sampling limitation,
not proof that it caused the user's walking tremor.

## Bounded change

- Request 5,000 microseconds (200 Hz), or the sensor's slower `minDelay` limit.
- Request zero sensor batching latency. The rate is a hint, not a support claim.
- Keep the fused attitude, roll sign, gravity reference, timestamp interpolation,
  fixed crop, per-output aspect math, and encoder presentation timestamps intact.
- Do not smooth the correction angle: smoothing it can leave a moving horizon
  tilted and violate the approved 360 degree behavior.
- Do not enable native EIS alongside Horizon Lock without validating coexistence.

`HorizonCamSensor` logs source/type/request/registration and measures actual
event-timestamp frequency and longest gap every five seconds. `HorizonCamGL`
logs timestamp matches/frames requiring attitude every 300 rendered frames.
The GL counters restart for each input surface; the sensor counters restart
on each sensor start. Unknown camera timebases remain reported as `LATEST`,
never falsely as synchronized.

## Verification and limits

Added JVM regressions cover rate limits, actual timestamp measurement, duplicate
and reordered events, reset, and synthetic 8 Hz roll sampled for a 60 fps frame
stream. The synthetic comparison tests interpolation error at 5 ms versus 20 ms;
it is not a measurement of a physical camera or a claim of walking stabilization.
Existing tests cover both directions through 360 degrees, mount rotation,
circles, output aspects and black-wedge coverage.

At authoring time only `git diff --check` was run. Gradle and device checks are
coordinated by the parent task, so their results must be recorded separately.

Remaining device checks: verify delivered rate/max gap and timestamp-match ratio
on S24, record a horizontal line/circle at 0/90/180/270/360 degrees, compare
preview to MP4, then compare tripod/small roll tremor/walking recordings with
the same quality/FPS and physical lens. Inspect encoded FPS and A/V synchronization.
Pitch/yaw, translation, rolling shutter and full walking EIS are not implemented
by increasing attitude sampling rate. Thermal/power impact is unmeasured.

References: [SensorManager registration contract](https://developer.android.com/reference/android/hardware/SensorManager),
[Android framework GAME delay mapping](https://android.googlesource.com/platform/frameworks/base/+/e828beb3866e2275cd26acd2a58f9f8a3967ba30/core/java/android/hardware/SensorManager.java).
