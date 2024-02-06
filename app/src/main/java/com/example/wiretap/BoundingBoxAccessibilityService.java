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

        if (rootNode != null) {
            Log.d("SuccessRootNode", "we've got one!");
            List<AccessibilityNodeInfo> clickableNodes = rootNode.findAccessibilityNodeInfosByViewId("android:id/list");
            for (AccessibilityNodeInfo node : clickableNodes) {
                if (node.isClickable()) {
                    Rect bounds = new Rect();
                    node.getBoundsInScreen(bounds);
                    overlayView.addBoundingBox(bounds);
                }
            }
        } else {
            Log.e("ErrorRootNode", "no root node");
        }
    }

    @Override
    public void onInterrupt() {
        // Required method to handle interruptions
    }

    @Override
    protected void onServiceConnected() {
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.flags = AccessibilityServiceInfo.DEFAULT;
        info.eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED |
                AccessibilityEvent.TYPE_VIEW_FOCUSED | AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_VISUAL;
        setServiceInfo(info);
    }
}
