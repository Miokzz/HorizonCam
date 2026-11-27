#include <jni.h>
#include "raw_develop.h"
#include <arm_neon.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdint>

namespace {
using raw_develop::packedWidth;
using raw_develop::packedHeight;
using raw_develop::reflect;
using raw_develop::nanos;
constexpr int tile = 128;
constexpr int halo = 4;
constexpr int extent = tile + 2 * halo;
template <typename T>
T* direct(JNIEnv* env, jobject buffer) {
    return static_cast<T*>(env->GetDirectBufferAddress(buffer));
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_miokzz_rawbench_NativeIsp_nativePrepare(JNIEnv* env, jclass, jobject sourceBuffer,
                                               jfloatArray profileArray, jobject sigmaBuffer,
                                               jobject inputBuffer, jint y, jint x, jobject traceBuffer) {
    float* trace = traceBuffer ? direct<float>(env, traceBuffer) : nullptr;
    const float* source = direct<const float>(env, sourceBuffer);
    float* sigma = direct<float>(env, sigmaBuffer);
    float* input = direct<float>(env, inputBuffer);
    float profile[8];
    env->GetFloatArrayRegion(profileArray, 0, 8, profile);
    const float32x4x2_t coefficients = vld2q_f32(profile);
    const float32x4_t nine = vdupq_n_f32(9.0f);
    const float32x4_t zero = vdupq_n_f32(0.0f);
    const int top = y - halo;
    const int left = x - halo;
    const int firstRow = std::max(0, -top);
    const int lastRow = std::min(extent, packedHeight - top);
    const int firstColumn = std::max(0, -left);
    const int lastColumn = std::min(extent, packedWidth - left);
    const int64_t start = nanos();
    for (int i = firstRow; i < lastRow; i++) {
        const float* above = source + 4 * reflect(top + i - 1, packedHeight) * packedWidth;
        const float* current = source + 4 * (top + i) * packedWidth;
        const float* below = source + 4 * reflect(top + i + 1, packedHeight) * packedWidth;
        for (int j = firstColumn; j < lastColumn; j++) {
            const int prev = 4 * reflect(left + j - 1, packedWidth);
            const int col = 4 * (left + j);
            const int next = 4 * reflect(left + j + 1, packedWidth);
            float* result = sigma + 4 * (i * extent + j);
            // Each lane is one Bayer phase. Keep the reference summation order.
            float32x4_t sum = vaddq_f32(vld1q_f32(above + prev), vld1q_f32(above + col));
            sum = vaddq_f32(sum, vld1q_f32(above + next));
            sum = vaddq_f32(sum, vld1q_f32(current + prev));
            sum = vaddq_f32(sum, vld1q_f32(current + col));
            sum = vaddq_f32(sum, vld1q_f32(current + next));
            sum = vaddq_f32(sum, vld1q_f32(below + prev));
            sum = vaddq_f32(sum, vld1q_f32(below + col));
            sum = vaddq_f32(sum, vld1q_f32(below + next));
            const float32x4_t quotient = vdivq_f32(sum, nine);
            const float32x4_t mean = vmaxq_f32(zero, quotient);
            const float32x4_t variance = vaddq_f32(vmulq_f32(mean, coefficients.val[0]), coefficients.val[1]);
            vst1q_f32(result, vsqrtq_f32(variance));
            if (trace) {
                float* at = trace + 12 * (i * extent + j);
                vst1q_f32(at, sum);
                vst1q_f32(at + 4, quotient);
                vst1q_f32(at + 8, variance);
            }
        }
    }
    const int64_t noiseNs = nanos() - start;
    if (firstRow != 0 || firstColumn != 0 || lastRow != extent || lastColumn != extent) {
        std::fill_n(input, extent * extent * 8, 0.0f);
    }
    for (int i = firstRow; i < lastRow; i++) {
        const float* row = source + 4 * ((top + i) * packedWidth + left + firstColumn);
        for (int j = firstColumn; j < lastColumn; j++, row += 4) {
            const int cell = i * extent + j;
            vst1q_f32(input + cell * 8, vmulq_n_f32(vld1q_f32(row), 2.0f));
            vst1q_f32(input + cell * 8 + 4, vmulq_n_f32(vld1q_f32(sigma + cell * 4), 32.0f));
        }
    }
    return noiseNs;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_miokzz_rawbench_NativeIsp_nativeRestore(JNIEnv* env, jclass, jobject sourceBuffer,
                                               jobject sigmaBuffer, jobject outputBuffer,
                                               jobject restoredBuffer, jint y, jint x, jobject traceBuffer) {
    float* trace = traceBuffer ? direct<float>(env, traceBuffer) : nullptr;
    const float* source = direct<const float>(env, sourceBuffer);
    const float* sigma = direct<const float>(env, sigmaBuffer);
    const float* output = direct<const float>(env, outputBuffer);
    float* restored = direct<float>(env, restoredBuffer);
    const int rows = std::min(tile, packedHeight - y);
    const int columns = std::min(tile, packedWidth - x) * 4;
    bool finite = true;
    for (int i = 0; i < rows; i++) {
        const int dst = ((y + i) * packedWidth + x) * 4;
        const int start = ((i + halo) * extent + halo) * 4;
        for (int j = 0; j < columns; j++) {
            const float prediction = output[start + j];
            finite &= std::isfinite(prediction);
            const float raw = source[dst + j];
            const float weight = std::clamp((.99f - raw) / .09f, 0.0f, 1.0f);
            const float clamped = std::clamp(prediction, -3.0f, 3.0f);
            const float delta = clamped * sigma[start + j] * weight;
            restored[dst + j] = raw + delta;
            if (trace) {
                float* at = trace + (i * tile * 4 + j) * 3;
                at[0] = clamped;
                at[1] = weight;
                at[2] = delta;
            }
        }
    }
    return finite;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_miokzz_rawbench_NativeIsp_nativeRender(JNIEnv* env, jclass, jobject restoredBuffer,
                                              jobject fieldsBuffer, jobject rowsBuffer,
                                              jobject lutBuffer, jobject rgbBuffer, jobject traceBuffer) {
    float* trace = traceBuffer ? direct<float>(env, traceBuffer) : nullptr;
    const float* restored = direct<const float>(env, restoredBuffer);
    const float* matrix = direct<const float>(env, fieldsBuffer);
    return raw_develop::render(restored, matrix, direct<float>(env, rowsBuffer),
                               direct<const uint8_t>(env, lutBuffer), direct<uint8_t>(env, rgbBuffer), trace);
}
