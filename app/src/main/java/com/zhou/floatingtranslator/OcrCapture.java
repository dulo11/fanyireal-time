package com.zhou.floatingtranslator;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.WindowManager;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.nio.ByteBuffer;

/** Periodically captures MediaProjection frames and performs fully on-device ML Kit OCR. */
public final class OcrCapture {
    public interface Callback {
        void onFrame(int width, int height);
        void onText(String text);
        void onError(Exception error);
    }

    private static final long OCR_INTERVAL_MS = 1200L;
    private static final int MAX_OCR_WIDTH = 1080;

    private final Context context;
    private final Callback callback;
    private final TextRecognizer recognizer;
    private final HandlerThread thread;
    private final Handler handler;

    private ImageReader imageReader;
    private VirtualDisplay virtualDisplay;
    private volatile boolean busy;
    private volatile boolean stopped;
    private long lastProcessedAt;

    public OcrCapture(Context context, String sourceMlTag, Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
        this.recognizer = createRecognizer(sourceMlTag);
        this.thread = new HandlerThread("floating-ocr-capture");
        this.thread.start();
        this.handler = new Handler(thread.getLooper());
    }

    public void start(MediaProjection projection) {
        try {
            WindowManager wm = context.getSystemService(WindowManager.class);
            Rect bounds = wm.getMaximumWindowMetrics().getBounds();
            int originalWidth = Math.max(1, bounds.width());
            int originalHeight = Math.max(1, bounds.height());
            float scale = Math.min(1f, MAX_OCR_WIDTH / (float) originalWidth);
            int width = Math.max(1, Math.round(originalWidth * scale));
            int height = Math.max(1, Math.round(originalHeight * scale));
            int density = Math.max(1, Math.round(context.getResources().getDisplayMetrics().densityDpi * scale));

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
            imageReader.setOnImageAvailableListener(this::onImageAvailable, handler);
            virtualDisplay = projection.createVirtualDisplay(
                "FloatingTranslator-OCR",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                handler
            );
            if (virtualDisplay == null) {
                throw new IllegalStateException("VirtualDisplay 创建失败");
            }
        } catch (Exception e) {
            callback.onError(e);
        }
    }

    private void onImageAvailable(ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) return;
            long now = SystemClock.elapsedRealtime();
            if (stopped || busy || now - lastProcessedAt < OCR_INTERVAL_MS) return;
            lastProcessedAt = now;
            busy = true;

            Bitmap bitmap = imageToBitmap(image);
            if (bitmap == null) {
                busy = false;
                return;
            }

            callback.onFrame(bitmap.getWidth(), bitmap.getHeight());
            InputImage input = InputImage.fromBitmap(bitmap, 0);
            recognizer.process(input)
                .addOnSuccessListener(result -> {
                    String text = normalize(result.getText());
                    if (!text.isEmpty() && !stopped) callback.onText(text);
                })
                .addOnFailureListener(callback::onError)
                .addOnCompleteListener(task -> {
                    try { bitmap.recycle(); } catch (Exception ignored) {}
                    busy = false;
                });
        } catch (Exception e) {
            busy = false;
            callback.onError(e);
        } finally {
            if (image != null) image.close();
        }
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane[] planes = image.getPlanes();
        if (planes == null || planes.length == 0) return null;
        Image.Plane plane = planes[0];
        ByteBuffer buffer = plane.getBuffer();
        buffer.rewind();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int width = image.getWidth();
        int height = image.getHeight();
        int rowPadding = Math.max(0, rowStride - pixelStride * width);
        int bitmapWidth = width + rowPadding / Math.max(1, pixelStride);

        Bitmap padded = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buffer);
        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, width, height);
        if (cropped != padded) padded.recycle();
        return cropped;
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String text = value.replace('\u0000', ' ')
            .replaceAll("[\\t ]+", " ")
            .replaceAll("\\n{3,}", "\\n\\n")
            .trim();
        if (text.length() > 900) text = text.substring(0, 900);
        return text;
    }

    private static TextRecognizer createRecognizer(String sourceMlTag) {
        if ("zh".equals(sourceMlTag)) {
            return TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        }
        if ("ja".equals(sourceMlTag)) {
            return TextRecognition.getClient(new JapaneseTextRecognizerOptions.Builder().build());
        }
        if ("ko".equals(sourceMlTag)) {
            return TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());
        }
        if ("hi".equals(sourceMlTag) || "mr".equals(sourceMlTag)) {
            return TextRecognition.getClient(new DevanagariTextRecognizerOptions.Builder().build());
        }
        return TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    }

    public void stop() {
        stopped = true;
        if (virtualDisplay != null) {
            try { virtualDisplay.release(); } catch (Exception ignored) {}
            virtualDisplay = null;
        }
        if (imageReader != null) {
            try { imageReader.close(); } catch (Exception ignored) {}
            imageReader = null;
        }
        try { recognizer.close(); } catch (Exception ignored) {}
        thread.quitSafely();
    }
}
