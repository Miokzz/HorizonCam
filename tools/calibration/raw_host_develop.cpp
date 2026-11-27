// Host replay entry point for the same laboratory renderer called from Android JNI.
#include "raw_device/src/main/cpp/raw_develop.h"

#ifdef _WIN32
#define HC_EXPORT __declspec(dllexport)
#else
#define HC_EXPORT __attribute__((visibility("default")))
#endif

extern "C" HC_EXPORT void develop_tele_frame(const float* restored, const float* parameters,
                                              float* rowScratch, const uint8_t* lookup, uint8_t* rgb) {
    raw_develop::render(restored, parameters, rowScratch, lookup, rgb, nullptr);
}
