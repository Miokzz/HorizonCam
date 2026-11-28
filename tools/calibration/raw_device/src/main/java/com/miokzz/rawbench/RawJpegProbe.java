package com.miokzz.rawbench;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.MediaStore;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.Objects;

/** Private fixture storage and a temporary, app-owned MediaStore publication. */
final class RawJpegProbe implements AutoCloseable {
    private static final int WIDTH = 2 * RawFrame.WIDTH;
    private static final int HEIGHT = 2 * RawFrame.HEIGHT;
    private final Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
    private final int[] row = new int[WIDTH];
    private final Context app;
    private final File encoded;
    private int sequence;

    RawJpegProbe(Context app, File output) {
        this.app = app;
        this.encoded = new File(output, "display.jpg");
    }

    JSONObject publish(RawPipeline.Output output, long frameStarted) throws Exception {
        long start = System.nanoTime();
        ByteBuffer rgb = output.directRgb() == null
                ? ByteBuffer.wrap(output.rgb()) : output.directRgb().asReadOnlyBuffer();
        rgb.rewind();
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                row[x] = 0xff000000 | ((rgb.get() & 255) << 16) | ((rgb.get() & 255) << 8) | (rgb.get() & 255);
            }
            bitmap.setPixels(row, 0, WIDTH, 0, y, WIDTH, 1);
        }
        long converted = System.nanoTime();
        try (var stream = new FileOutputStream(encoded)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 96, stream)) {
                throw new IllegalStateException("Cannot encode the RAW laboratory JPEG");
            }
        }
        long encodedAt = System.nanoTime();
        var resolver = app.getContentResolver();
        String name = "HC_RAW_LAB_" + encoded.getParentFile().getName() + "_" + (++sequence) + ".jpg";
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/HorizonCam RAW lab/");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri item = Objects.requireNonNull(resolver.insert(collection, values), "MediaStore did not reserve a row");
        try {
            try (var stream = Objects.requireNonNull(resolver.openOutputStream(item, "w"))) {
                Files.copy(encoded.toPath(), stream);
            }
            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (resolver.update(item, values, null, null) != 1) {
                throw new IllegalStateException("Cannot clear IS_PENDING");
            }
            long published = System.nanoTime();
            try (var cursor = Objects.requireNonNull(resolver.query(collection,
                    new String[]{MediaStore.MediaColumns.IS_PENDING},
                    MediaStore.MediaColumns.DISPLAY_NAME + " = ?", new String[]{name}, null))) {
                if (cursor.getCount() != 1 || !cursor.moveToFirst() || cursor.getInt(0) != 0) {
                    throw new IllegalStateException("Expected one visible laboratory JPEG");
                }
            }
            return new JSONObject().put("rgbToBitmapNs", converted - start).put("jpegEncodeNs", encodedAt - converted)
                    .put("galleryPublishNs", published - encodedAt).put("ispToGalleryNs", published - frameStarted)
                    .put("jpegBytes", encoded.length()).put("rows", 1);
        } finally {
            resolver.delete(item, null, null); // Only the disposable row created by this call.
        }
    }

    @Override public void close() {
        bitmap.recycle();
    }
}
