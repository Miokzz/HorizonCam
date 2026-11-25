package com.miokzz.rawbench;

final class RawIsp {
    static final int H = RawFrame.HEIGHT;
    static final int W = RawFrame.WIDTH;
    static final float EXPOSURE = (float) Math.pow(2, 1.5);
    private static final byte[] SRGB = buildTable();

    static void copyLookup(java.nio.ByteBuffer destination) {
        destination.put(SRGB).rewind();
    }

    static int reflect(int position, int size) {
        if (position < 0) return -position;
        return Math.min(position, 2 * size - 2 - position);
    }

    static void noise(RawFrame frame, float[] sigma) {
        float[] image = frame.source;
        for (int y = 0; y < H; y++) {
            int up = reflect(y - 1, H) * W;
            int middle = y * W;
            int down = reflect(y + 1, H) * W;
            for (int x = 0; x < W; x++) {
                int left = reflect(x - 1, W);
                int right = reflect(x + 1, W);
                for (int c = 0; c < 4; c++) {
                    float sum = image[(up + left) * 4 + c] + image[(up + x) * 4 + c] +
                            image[(up + right) * 4 + c] + image[(middle + left) * 4 + c] +
                            image[(middle + x) * 4 + c] + image[(middle + right) * 4 + c] +
                            image[(down + left) * 4 + c] + image[(down + x) * 4 + c] + image[(down + right) * 4 + c];
                    sigma[(middle + x) * 4 + c] = (float) Math.sqrt(
                            Math.max(0, sum / 9) * frame.profile[c * 2] + frame.profile[c * 2 + 1]);
                }
            }
        }
    }

    static float corrected(float source, float sigma, float prediction) {
        if (!Float.isFinite(prediction)) throw new IllegalArgumentException("Nonfinite neural prediction");
        float weight = Math.max(0, Math.min(1, (.99f - source) / .09f));
        return source + Math.max(-3, Math.min(3, prediction)) * sigma * weight;
    }

    static void gain(RawFrame frame, float[] packed, float[] raster) {
        for (int c = 0; c < 4; c++) {
            RawFrame.GainMap map = frame.maps[c];
            for (int y = 0; y < H; y++) {
                int row = (y * 2 + c / 2) * W * 2 + c % 2;
                for (int x = 0; x < W; x++) {
                    raster[row + 2 * x] = packed[(y * W + x) * 4 + c] * map.sample(y, x);
                }
            }
        }
    }

    static void display(float[] raster, float[] matrix, byte[] image) {
        int width = W * 2;
        int height = H * 2;
        for (int y = 0; y < height; y++) {
            int row = y * width;
            int up = reflect(y - 1, height) * width;
            int down = reflect(y + 1, height) * width;
            int up2 = reflect(y - 2, height) * width;
            int down2 = reflect(y + 2, height) * width;
            for (int x = 0; x < width; x++) {
                int left = reflect(x - 1, width);
                int right = reflect(x + 1, width);
                float c = raster[row + x];
                float horizontal = raster[row + left] + raster[row + right];
                float vertical = raster[up + x] + raster[down + x];
                float horizontal2 = raster[row + reflect(x - 2, width)] + raster[row + reflect(x + 2, width)];
                float vertical2 = raster[up2 + x] + raster[down2 + x];
                float diagonal = raster[up + left] + raster[up + right] + raster[down + left] + raster[down + right];
                float red, green, blue;
                if (((y ^ x) & 1) == 0) {
                    float h = (5 * c + 4 * horizontal - horizontal2 - diagonal + .5f * vertical2) * .125f;
                    float v = (5 * c + 4 * vertical - vertical2 - diagonal + .5f * horizontal2) * .125f;
                    green = c;
                    red = (y & 1) == 0 ? v : h;
                    blue = (y & 1) == 0 ? h : v;
                } else {
                    green = (4 * c + 2 * (horizontal + vertical) - horizontal2 - vertical2) * .125f;
                    float opposite = (6 * c + 2 * diagonal - 1.5f * (horizontal2 + vertical2)) * .125f;
                    red = (y & 1) == 0 ? opposite : c;
                    blue = (y & 1) == 0 ? c : opposite;
                }
                int index = (row + x) * 3;
                image[index] = encode(matrix[0] * red + matrix[1] * green + matrix[2] * blue);
                image[index + 1] = encode(matrix[3] * red + matrix[4] * green + matrix[5] * blue);
                image[index + 2] = encode(matrix[6] * red + matrix[7] * green + matrix[8] * blue);
            }
        }
    }

    private static byte encode(float linear) {
        int entry = Math.round(Math.max(0, Math.min(1, linear * EXPOSURE)) * (SRGB.length - 1));
        return SRGB[entry];
    }

    private static byte[] buildTable() {
        byte[] table = new byte[65536];
        for (int i = 0; i < table.length; i++) {
            double t = i / (double) (table.length - 1);
            double encoded = t <= .0031308 ? 12.92 * t : 1.055 * Math.pow(t, 1.0 / 2.4) - .055;
            table[i] = (byte) Math.rint(encoded * 255);
        }
        return table;
    }
}
