package com.example.wiretap;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import android.view.View;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class BoundingBoxOverlayView extends View {
    public LinkedHashMap<Integer, DisplayableRect> boundingBoxes = new LinkedHashMap<>();

    private Paint boxPaint;
    private Paint textPaint;
    private Paint textBackgroundPaint;
    private Context mContext;

    public BoundingBoxOverlayView(Context context) {
        super(context);
        mContext = context;
        init();
    }

    private void init() {
        boxPaint = new Paint();
        boxPaint.setColor(0xFFFF0000);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(5);

        textPaint = new Paint();
        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTextSize(60);
        textPaint.setTextAlign(Paint.Align.LEFT);

        textBackgroundPaint = new Paint();
        textBackgroundPaint.setColor(0xFFFF0000);
        textBackgroundPaint.setStyle(Paint.Style.FILL);
    }

    public void addBoundingBox(Integer viewId, DisplayableRect boundingBox) {
        boundingBoxes.put(viewId, boundingBox);
        invalidate();
    }

    public void clearDrawings() {
        for (DisplayableRect rect : boundingBoxes.values()) {
            rect.shouldDisplay = false;
        }
        invalidate();
    }

    public void submitAction() {
        boundingBoxes.clear();
        invalidate();
    }

    public void captureScreen(Intent captureIntent) {
        Log.d("CaptureScreen", "This was called");
        if (captureIntent != null) {
            MediaProjectionManager projectionManager = (MediaProjectionManager) mContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE);

            MediaProjection mediaProjection = projectionManager.getMediaProjection(Activity.RESULT_OK, captureIntent);

            DisplayMetrics metrics = getResources().getDisplayMetrics();
            int density = metrics.densityDpi;
            int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR;
            int width = metrics.widthPixels;
            int height = metrics.heightPixels;

            ImageReader imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            Surface surface = imageReader.getSurface();

            final VirtualDisplay virtualDisplay = mediaProjection.createVirtualDisplay(
                    "ScreenCapture",
                    width, height, density,
                    flags, surface, null, null
            );

            ExecutorService executorService = Executors.newSingleThreadExecutor();

            imageReader.setOnImageAvailableListener(reader -> {
                executorService.execute(() -> {
                    Image image = null;
                    try {
                        image = reader.acquireLatestImage();
                        if (image != null) {
                            Image.Plane[] planes = image.getPlanes();
                            ByteBuffer buffer = planes[0].getBuffer();
                            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                            bitmap.copyPixelsFromBuffer(buffer);

                            ByteArrayOutputStream stream = new ByteArrayOutputStream();
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream);
                            byte[] byteArray = stream.toByteArray();

                            String url = "http://b9aa-71-198-153-83.ngrok-free.app/generate";
                            MediaType JSON = MediaType.get("image/jpeg");

                            OkHttpClient client = new OkHttpClient();

                            RequestBody body = RequestBody.create(byteArray, JSON);
                            Request request = new Request.Builder()
                                    .url(url)
                                    .post(body)
                                    .build();

                            try (Response response = client.newCall(request).execute()) {
                                Log.d("HTTPResponse", "response: " + response.body().string());
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }

                            Log.d("Image", "captureScreen: " + byteArray);
                        }
                    } finally {
                        if (image != null) {
                            image.close();
                        }

                        if (virtualDisplay != null) {
                            virtualDisplay.release();
                        }

                        if (imageReader != null) {
                            imageReader.close();
                        }
                    }
                });
            }, new Handler(Looper.getMainLooper()));
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        DisplayableRect[] rectsArray = boundingBoxes.values().toArray(new DisplayableRect[0]);
        for (int i = 0; i < rectsArray.length; i++) {
            DisplayableRect displayObject = rectsArray[i];
            if (displayObject.shouldDisplay) {
                Rect rect = displayObject.rect;
                canvas.drawRect(rect, boxPaint);
                String annotationText = String.valueOf(i);

                float textWidth = textPaint.measureText(annotationText);
                float textHeight = textPaint.getTextSize();

                float backgroundLeft = rect.left;
                float backgroundTop = rect.top - textHeight;
                float backgroundRight = rect.left + textWidth;
                float backgroundBottom = rect.top;

                canvas.drawRect(backgroundLeft, backgroundTop, backgroundRight, backgroundBottom, textBackgroundPaint);

                canvas.drawText(annotationText, rect.left, rect.top, textPaint);
            }
        }
    }
}

