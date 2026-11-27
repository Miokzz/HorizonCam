#pragma once

#include "raw_fidelity.h"
#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdint>

namespace raw_develop {
constexpr int packedWidth = 1824;
constexpr int packedHeight = 1368;
constexpr int width = 2 * packedWidth;
constexpr int height = 2 * packedHeight;
constexpr float exposure = 2.8284271247461903f;

inline int reflect(int p, int size) {
    return p < 0 ? -p : std::min(p, 2 * size - 2 - p);
}

inline int64_t nanos() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
}

inline float lerp(float a, float b, float t) {
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

inline GainMap readMap(const float*& fields) {
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

inline void applyGainRow(const float* restored, const std::array<GainMap, 4>& maps, int y, float* out) {
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

inline uint8_t encode(float exposed, const uint8_t* lut) {
    const float scaled = exposed * 65535.0f;
    const int offset = static_cast<int>(scaled);
    // Float Math.round semantics, including values immediately below a half.
    return lut[offset + (scaled - static_cast<float>(offset) >= 0.5f)];
}

inline void demosaicRow(const std::array<const float*, 5>& rows, int y, const float* matrix,
                 const uint8_t* lut, uint8_t* output, float* trace) {
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
        const int at = trace ? fidelity::displayIndex(x, y) : -1;
        if (at >= 0) {
            trace[at] = c;
            trace[at + 1] = r;
            trace[at + 2] = g;
            trace[at + 3] = b;
        }
        for (int channel = 0; channel < 3; channel++) {
            const float* weights = matrix + 3 * channel;
            const float linear = weights[0] * r + weights[1] * g + weights[2] * b;
            const float exposed = std::clamp(linear * exposure, 0.0f, 1.0f);
            if (at >= 0) {
                trace[at + 4 + channel] = linear;
                trace[at + 7 + channel] = exposed;
            }
            output[x * 3 + channel] = encode(exposed, lut);
        }
    }
}
inline int64_t render(const float* restored, const float* matrix, float* rows, const uint8_t* lut,
                      uint8_t* rgb, float* trace) {
    const float* fields = matrix + 9;
    std::array<GainMap, 4> maps{};
    for (auto& map : maps) map = readMap(fields);
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
        demosaicRow(neighborhood, y, matrix, lut, rgb + y * width * 3, trace);
    }
    return gainNs;
}
}  // namespace raw_develop
