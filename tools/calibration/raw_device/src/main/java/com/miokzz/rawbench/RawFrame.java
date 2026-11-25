package com.miokzz.rawbench;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;

final class RawFrame {
    static final int HEIGHT = 1368;
    static final int WIDTH = 1824;
    static final int SAMPLES = HEIGHT * WIDTH * 4;
    final float[] source = new float[SAMPLES];
    final float[] profile = new float[8];
    final float[] matrix = new float[9];
    final GainMap[] maps = new GainMap[4];

    RawFrame(File file) throws IOException {
        try (FileInputStream stream = new FileInputStream(file)) {
            FileChannel channel = stream.getChannel();
            ByteBuffer reader = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
                    .order(ByteOrder.LITTLE_ENDIAN);
            if (reader.getInt() != 0x31575248 || reader.getInt() != HEIGHT ||
                    reader.getInt() != WIDTH || reader.getInt() != 1) {
                throw new IOException("Expected HRW1 full tele raster and normal orientation");
            }
            for (int i = 0; i < profile.length; i++) profile[i] = reader.getFloat();
            for (int i = 0; i < matrix.length; i++) matrix[i] = reader.getFloat();
            for (int i = 0; i < maps.length; i++) maps[i] = new GainMap(reader, i);
            if (reader.remaining() != source.length * Float.BYTES) throw new IOException("Truncated raster");
            reader.asFloatBuffer().get(source);
        }
    }

    static final class GainMap {
        final Axis y;
        final Axis x;
        final float[] gains;
        final int columns;

        GainMap(ByteBuffer reader, int phase) throws IOException {
            int rows = reader.getInt();
            columns = reader.getInt();
            if (rows <= 0 || rows > 256 || columns <= 0 || columns > 256) {
                throw new IOException("Unexpected GainMap dimensions");
            }
            double spacingY = reader.getDouble();
            double spacingX = reader.getDouble();
            double originY = reader.getDouble();
            double originX = reader.getDouble();
            y = new Axis(HEIGHT, rows, phase / 2, spacingY, originY);
            x = new Axis(WIDTH, columns, phase % 2, spacingX, originX);
            gains = new float[rows * columns];
            for (int i = 0; i < gains.length; i++) gains[i] = reader.getFloat();
        }

        float sample(int row, int column) {
            int upper = y.low[row] * columns;
            int lower = y.high[row] * columns;
            int left = x.low[column];
            int right = x.high[column];
            float t = x.weight[column];
            float a = interpolate(gains[upper + left], gains[upper + right], t);
            float b = interpolate(gains[lower + left], gains[lower + right], t);
            return interpolate(a, b, y.weight[row]);
        }
    }

    static final class Axis {
        final int[] low;
        final int[] high;
        final float[] weight;

        Axis(int size, int samples, int phase, double spacing, double origin) throws IOException {
            if (!Double.isFinite(origin) || !(spacing > 0) || !Double.isFinite(spacing)) {
                throw new IOException("Invalid GainMap coordinates");
            }
            low = new int[size];
            high = new int[size];
            weight = new float[size];
            for (int i = 0; i < size; i++) {
                double center = (2 * i + phase + .5) / (2 * size);
                double position = Math.max(0, Math.min((samples - 1) * spacing, center - origin)) / spacing;
                low[i] = Math.min(samples - 1, (int) position);
                high[i] = Math.min(samples - 1, low[i] + 1);
                weight[i] = (float) (position - low[i]);
            }
        }
    }

    static float interpolate(float a, float b, float fraction) {
        return a + (b - a) * fraction;
    }
}
