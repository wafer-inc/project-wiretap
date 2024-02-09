package com.example.wiretap;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.Log;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

public class BoundingBoxOverlayView extends View {
    private List<Rect> boundingBoxes = new ArrayList<>();
    private Paint boxPaint;
    private Paint textPaint;
    private Paint textBackgroundPaint;

    public BoundingBoxOverlayView(Context context) {
        super(context);
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

    public void addBoundingBox(Rect boundingBox) {
        Log.d("BoundingBoxTest", "addBoundingBox: " + boundingBox);
        boundingBoxes.add(boundingBox);
        invalidate();
    }

    public void clearDrawings() {
        boundingBoxes.clear();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        for (int i = 0; i < boundingBoxes.size(); i++) {
            Rect rect = boundingBoxes.get(i);
            canvas.drawRect(rect, boxPaint);
            String annotationText = "A" + (i + 1);

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

