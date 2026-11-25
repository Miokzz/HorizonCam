#include <jni.h>
#include <arm_neon.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdint>

namespace {
constexpr int packedWidth = 1824;
constexpr int packedHeight = 1368;
constexpr int width = 2 * packedWidth;
constexpr int height = 2 * packedHeight;
constexpr int tile = 128;
constexpr int halo = 4;
constexpr int extent = tile + 2 * halo;
constexpr float exposure = 2.8284271247461903f;

template <typename T>
T* direct(JNIEnv* env, jobject buffer) {
    return static_cast<T*>(env->GetDirectBufferAddress(buffer));
}

int reflect(int p, int size) {
    return p < 0 ? -p : std::min(p, 2 * size - 2 - p);
}

int64_t nanos() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
}

float lerp(float a, float b, float t) {
    return a + (b - a) * t;
}

struct GainMap {
    const float* y;
    const float* x;
    const float* samples;
    int columns;

    float gain(const float* top, const float* bottom, int column, float t) const {
        const float* ax = x + 3 * column;
        const int left = static_cast<int>(ax[0]);
        const int right = static_cast<int>(ax[1]);
        return lerp(lerp(top[left], top[right], ax[2]), lerp(bottom[left], bottom[right], ax[2]), t);
    }
};

GainMap readMap(const float*& fields) {
    int columns = static_cast<int>(*fields++);
    int rows = static_cast<int>(*fields++);
    const float* samples = fields;
    fields += columns * rows;
    const float* y = fields;
    fields += 3 * packedHeight;
    const float* x = fields;
    fields += 3 * packedWidth;
    return {y, x, samples, columns};
}

void applyGainRow(const float* restored, const std::array<GainMap, 4>& maps, int y, float* out) {
    const float* source = restored + (y / 2) * packedWidth * 4;
    for (int xPhase = 0; xPhase < 2; xPhase++) {
        const int phase = 2 * (y & 1) + xPhase;
        const GainMap& map = maps[phase];
        const float* ay = map.y + 3 * (y / 2);
        const float* top = map.samples + static_cast<int>(ay[0]) * map.columns;
        const float* bottom = map.samples + static_cast<int>(ay[1]) * map.columns;
        for (int x = 0; x < packedWidth; x++) {
            out[2 * x + xPhase] = source[4 * x + phase] * map.gain(top, bottom, x, ay[2]);
        }
    }
}

uint8_t encode(float v, const uint8_t* lut) {
    const float scaled = std::clamp(v * exposure, 0.0f, 1.0f) * 65535.0f;
    const int offset = static_cast<int>(scaled);
    // Float Math.round semantics, including values immediately below a half.
    return lut[offset + (scaled - static_cast<float>(offset) >= 0.5f)];
}

void demosaicRow(const std::array<const float*, 5>& rows, int y, const float* matrix,
                 const uint8_t* lut, uint8_t* output) {
    const float* middle = rows[2];
    const float* above = rows[1];
    const float* below = rows[3];
    for (int x = 0; x < width; x++) {
        const int left = reflect(x - 1, width);
        const int right = reflect(x + 1, width);
        const float c = middle[x];
        const float h = middle[left] + middle[right];
        const float v = above[x] + below[x];
        const float h2 = middle[reflect(x - 2, width)] + middle[reflect(x + 2, width)];
        const float v2 = rows[0][x] + rows[4][x];
        const float diagonal = above[left] + above[right] + below[left] + below[right];
        float r, g, b;
        if (((x ^ y) & 1) == 0) {
            const float horizontal = (5 * c + 4 * h - h2 - diagonal + .5f * v2) * .125f;
            const float vertical = (5 * c + 4 * v - v2 - diagonal + .5f * h2) * .125f;
            g = c;
            r = (y & 1) == 0 ? vertical : horizontal;
            b = (y & 1) == 0 ? horizontal : vertical;
        } else {
            g = (4 * c + 2 * (h + v) - h2 - v2) * .125f;
            const float opposite = (6 * c + 2 * diagonal - 1.5f * (h2 + v2)) * .125f;
            r = (y & 1) == 0 ? opposite : c;
            b = (y & 1) == 0 ? c : opposite;
        }
        for (int channel = 0; channel < 3; channel++) {
            const float* weights = matrix + 3 * channel;
            output[x * 3 + channel] = encode(weights[0] * r + weights[1] * g + weights[2] * b, lut);
        }
    }
}
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_miokzz_rawbench_NativeIsp_nativePrepare(JNIEnv* env, jclass, jobject sourceBuffer,
                                               jfloatArray profileArray, jobject sigmaBuffer,
                                               jobject inputBuffer, jint y, jint x) {
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
            const float32x4_t mean = vmaxq_f32(zero, vdivq_f32(sum, nine));
            const float32x4_t variance = vaddq_f32(vmulq_f32(mean, coefficients.val[0]), coefficients.val[1]);
            vst1q_f32(result, vsqrtq_f32(variance));
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
                                               jobject restoredBuffer, jint y, jint x) {
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
            restored[dst + j] = raw + std::clamp(prediction, -3.0f, 3.0f) * sigma[start + j] * weight;
        }
    }
    return finite;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_miokzz_rawbench_NativeIsp_nativeRender(JNIEnv* env, jclass, jobject restoredBuffer,
                                              jobject fieldsBuffer, jobject rowsBuffer,
                                              jobject lutBuffer, jobject rgbBuffer) {
    const float* restored = direct<const float>(env, restoredBuffer);
    const float* matrix = direct<const float>(env, fieldsBuffer);
    const float* fields = matrix + 9;
    std::array<GainMap, 4> maps{};
    for (auto& map : maps) map = readMap(fields);
    float* rows = direct<float>(env, rowsBuffer);
    const uint8_t* lut = direct<const uint8_t>(env, lutBuffer);
    uint8_t* rgb = direct<uint8_t>(env, rgbBuffer);
    std::array<int, 5> ready{-1, -1, -1, -1, -1};
    int64_t gainNs = 0;
    for (int y = 0; y < height; y++) {
        std::array<const float*, 5> neighborhood{};
        for (int k = 0; k < 5; k++) {
            const int row = reflect(y + k - 2, height);
            const int slot = row % 5;
            float* raster = rows + slot * width;
            if (ready[slot] != row) {
                const int64_t before = nanos();
                applyGainRow(restored, maps, row, raster);
                gainNs += nanos() - before;
                ready[slot] = row;
            }
            neighborhood[k] = raster;
        }
        demosaicRow(neighborhood, y, matrix, lut, rgb + y * width * 3);
    }
    return gainNs;
}
