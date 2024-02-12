package com.example.wiretap;

import android.graphics.Rect;

public class DisplayableRect {
    public Rect rect;
    public boolean shouldDisplay;

    public DisplayableRect(Rect rect, boolean shouldDisplay) {
        this.rect = rect;
        this.shouldDisplay = shouldDisplay;
    }
}
