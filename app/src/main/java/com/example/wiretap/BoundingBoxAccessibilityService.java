package com.example.wiretap;

import android.accessibilityservice.AccessibilityService;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.util.Log;

import java.util.LinkedHashMap;

public class BoundingBoxAccessibilityService extends AccessibilityService {
    private WindowManager windowManager;
    private WindowManager.LayoutParams params;
    private BoundingBoxOverlayView overlayView;
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        int eventType = event.getEventType();
        String eventText = "";
        switch (eventType) {
            case AccessibilityEvent.TYPE_VIEW_CLICKED:
                eventText = "click";
                if (event.getSource() != null) {
                    Integer nodeIndex = indexOfKeyInLinkedHashMap(overlayView.boundingBoxes, event.getSource().hashCode());
                    Log.d("test", "test: " + nodeIndex);
                }
                break;
            default:
                eventText = "other";
        }

        overlayView.clearDrawings();
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();

        if (rootNode != null) {
            findClickableNodes(rootNode);
        } else {
            Log.e("ErrorRootNode", "no root node");
        }
    }

    private int indexOfKeyInLinkedHashMap(LinkedHashMap<Integer, Rect> map, Integer keyToFind) {
        int index = 0;
        for (Integer key : map.keySet()) {
            if (key.equals(keyToFind)) {
                return index;
            }
            index++;
        }
        return -1;
    }

    private void findClickableNodes(AccessibilityNodeInfo node) {
        if (node == null) return;

        if (node.isClickable()) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);

            int statusBarHeight = getStatusBarHeight();
            bounds.top -= statusBarHeight;
            bounds.bottom -= statusBarHeight;

            overlayView.addBoundingBox(node.hashCode(), bounds);
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            findClickableNodes(node.getChild(i));
        }
    }

    private int getStatusBarHeight() {
        int result = 0;
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            result = getResources().getDimensionPixelSize(resourceId);
        }
        return result;
    }

    private void setupLayoutParams() {
        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
    }

    @Override
    public void onInterrupt() {
        overlayView.clearDrawings();
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        overlayView = new BoundingBoxOverlayView(this);

        setupLayoutParams();

        try {
            windowManager.addView(overlayView, params);
        } catch (Exception e) {
            Log.e("OverlayError", "Failed to add overlay view", e);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (windowManager != null && overlayView != null) {
            windowManager.removeView(overlayView);
            overlayView = null;
        }
    }
}
