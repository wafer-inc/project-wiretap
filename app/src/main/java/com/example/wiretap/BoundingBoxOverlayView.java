package com.example.wiretap;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;
import java.util.LinkedHashMap;

public class BoundingBoxOverlayView extends View {
    public LinkedHashMap<Integer, DisplayableRect> boundingBoxes = new LinkedHashMap<>();

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

