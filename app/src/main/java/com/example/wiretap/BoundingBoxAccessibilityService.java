package com.example.wiretap;

import java.util.List;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.util.Log;


public class BoundingBoxAccessibilityService extends AccessibilityService {
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        BoundingBoxOverlayView overlayView = new BoundingBoxOverlayView(this);

        Log.d("AccessibilityTest", "we made it");

        if (rootNode != null) {
            // Find all clickable elements
            List<AccessibilityNodeInfo> clickableNodes = rootNode.findAccessibilityNodeInfosByViewId("android:id/list");
            for (AccessibilityNodeInfo node : clickableNodes) {
                if (node.isClickable()) {
                    Rect bounds = new Rect();
                    node.getBoundsInScreen(bounds);

                    // Here you would handle drawing an overlay around the bounds
                    // You will need to implement an overlay view that can draw these bounds
                    overlayView.addBoundingBox(bounds);
                }
            }
        }
    }

    @Override
    public void onInterrupt() {
        // Required method to handle interruptions
    }

    @Override
    protected void onServiceConnected() {
        // Configure your accessibility service here
        Log.d("AccessibilityTest", "onServiceConnected: it's done");
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.flags = AccessibilityServiceInfo.DEFAULT;
        info.eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED |
                AccessibilityEvent.TYPE_VIEW_FOCUSED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_VISUAL;
        setServiceInfo(info);
    }
}
