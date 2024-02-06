package com.example.wiretap;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

public class BoundingBoxOverlayView extends View {
    private List<Rect> boundingBoxes = new ArrayList<>();
    private Paint boxPaint;
    private Paint textPaint;
    private Paint textBackgroundPaint;
    private WindowManager windowManager;
    private WindowManager.LayoutParams params;

    public BoundingBoxOverlayView(Context context) {
        super(context);
        init();
    }

    private void init() {
        boxPaint = new Paint();
        boxPaint.setColor(0xFFFF0000); // Set the bounding box color to red
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(5); // Set the stroke width

        textPaint = new Paint();
        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTextSize(60);
        textPaint.setTextAlign(Paint.Align.LEFT);

        textBackgroundPaint = new Paint();
        textBackgroundPaint.setColor(0xFFFF0000); // Black color for the text background
        textBackgroundPaint.setStyle(Paint.Style.FILL);
    }

    private void setupLayoutParams() {
        // Define the layout parameters for the overlay
        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, // Make overlay non-interactive
                PixelFormat.TRANSLUCENT); // Ensure the overlay is translucent
        params.gravity = Gravity.TOP | Gravity.START;
    }

    public void addBoundingBox(Rect boundingBox) {
        Log.d("BoundingBoxTest", "addBoundingBox: " + boundingBox);
        boundingBoxes.add(boundingBox);
        invalidate(); // Invalidate to trigger a redraw
    }

    public void clearBoundingBoxes() {
        boundingBoxes.clear();
        invalidate(); // Invalidate to trigger a redraw
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        for (int i = 0; i < boundingBoxes.size(); i++) {
            Rect rect = boundingBoxes.get(i);
            canvas.drawRect(rect, boxPaint);
            String annotationText = "A" + (i + 1); // "A1", "A2", etc.

            // Measure the text to place the background appropriately
            float textWidth = textPaint.measureText(annotationText);
            float textHeight = textPaint.getTextSize();

            // Calculate the background rectangle's coordinates
            float backgroundLeft = rect.left;
            float backgroundTop = rect.top - textHeight;
            float backgroundRight = rect.left + textWidth;
            float backgroundBottom = rect.top;

            // Draw the background rectangle
            canvas.drawRect(backgroundLeft, backgroundTop, backgroundRight, backgroundBottom, textBackgroundPaint);

            // Draw the annotation text on top of the background
            canvas.drawText(annotationText, rect.left, rect.top, textPaint);
        }
    }

    public void removeOverlay() {
        windowManager.removeView(this);
    }
}

