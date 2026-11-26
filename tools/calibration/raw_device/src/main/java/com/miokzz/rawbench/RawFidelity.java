package com.miokzz.rawbench;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

final class RawFidelity {
    static final int[] TILES = {0, 16, 69, 82, 89, 164};
    static final int CELLS = RawEngine.EXTENT * RawEngine.EXTENT;
    static final int NOISE = CELLS * 12 * Float.BYTES;
    static final int INPUT = CELLS * 8 * Float.BYTES;
    static final int PREDICTION = CELLS * 4 * Float.BYTES;
    static final int CORRECTION = RawEngine.TILE * RawEngine.TILE * 12 * Float.BYTES;
    static final int TILE_BYTES = NOISE + INPUT + PREDICTION + CORRECTION;
    static final int RENDER_BYTES = 6 * 16 * 16 * 10 * Float.BYTES;
    final ByteBuffer data = ByteBuffer.allocateDirect(TILES.length * TILE_BYTES + RENDER_BYTES)
            .order(ByteOrder.nativeOrder());

    static int tileSlot(int tileIndex) {
        int slot = Arrays.binarySearch(TILES, tileIndex);
        return slot < 0 ? -1 : slot;
    }

    ByteBuffer noise(int slot) { return part(slot * TILE_BYTES, NOISE); }
    ByteBuffer input(int slot) { return part(slot * TILE_BYTES + NOISE, INPUT); }
    ByteBuffer prediction(int slot) { return part(slot * TILE_BYTES + NOISE + INPUT, PREDICTION); }
    ByteBuffer correction(int slot) { return part(slot * TILE_BYTES + NOISE + INPUT + PREDICTION, CORRECTION); }
    ByteBuffer display() { return part(TILES.length * TILE_BYTES, RENDER_BYTES); }

    static ByteBuffer full(ByteBuffer data) {
        ByteBuffer copy = data.asReadOnlyBuffer();
        copy.clear();
        return copy;
    }

    private ByteBuffer part(int offset, int bytes) {
        return data.slice(offset, bytes).order(data.order());
    }
}
