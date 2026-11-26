#pragma once

#include <array>
#include <cstddef>

namespace fidelity {
constexpr int extent = 136, tile = 128, patch = 16;
constexpr std::array<int, 6> indices{0, 16, 69, 82, 89, 164};
constexpr int patches = 6, displayFields = 10;
struct Tile {
    float noise[extent * extent * 12];
    float input[extent * extent * 8];
    float prediction[extent * extent * 4];
    float correction[tile * tile * 12];
};
struct Trace {
    Tile tiles[indices.size()];
    float display[patches * patch * patch * displayFields];
};

inline int slot(int tileIndex) {
    for (unsigned i = 0; i < indices.size(); ++i) {
        if (indices[i] == tileIndex) return static_cast<int>(i);
    }
    return -1;
}

inline int displayIndex(int x, int y) {
    constexpr int origins[][2] = {{0, 0}, {248, 248}, {1664, 1344}, {3632, 0}, {0, 2720}, {3632, 2720}};
    for (int i = 0; i < patches; i++) {
        const int col = x - origins[i][0], row = y - origins[i][1];
        if (col >= 0 && col < patch && row >= 0 && row < patch) {
            return (i * patch * patch + row * patch + col) * displayFields;
        }
    }
    return -1;
}
}
