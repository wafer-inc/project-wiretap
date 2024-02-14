package com.example.wiretap;

import android.accessibilityservice.AccessibilityService;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.net.URISyntaxException;
import java.util.LinkedHashMap;

public class BoundingBoxAccessibilityService extends AccessibilityService {
    private WindowManager windowManager;
    private WindowManager.LayoutParams params;
    private BoundingBoxOverlayView overlayView;
    private String actionText;
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        int eventType = event.getEventType();
        if (event.getSource() != null) {
            Integer nodeIndex = indexOfKeyInLinkedHashMap(overlayView.boundingBoxes, event.getSource().hashCode());
            switch (eventType) {
                case AccessibilityEvent.TYPE_VIEW_CLICKED:
                case AccessibilityEvent.TYPE_VIEW_CONTEXT_CLICKED:
                    actionText = "Clicked: " + nodeIndex;
                    overlayView.submitAction(actionText);
                    break;
                case AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED:
                    CharSequence newText = event.getText().toString();
                    actionText = "Typed: " + newText + " into box " + nodeIndex;
                    break;
                default:
                    break;
            }
        }

        overlayView.clearDrawings();
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();

        if (rootNode != null) {
            findClickableNodes(rootNode);
        } else {
            Log.e("ErrorRootNode", "no root node");
        }
    }

    private int indexOfKeyInLinkedHashMap(LinkedHashMap<Integer, DisplayableRect> map, Integer keyToFind) {
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

            overlayView.addBoundingBox(node.hashCode(), new DisplayableRect(bounds, true));
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

        Intent intent = new Intent(this, TransparentActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        overlayView = new BoundingBoxOverlayView(this);

        setupLayoutParams();

        try {
            windowManager.addView(overlayView, params);
        } catch (Exception e) {
            Log.e("OverlayError", "Failed to add overlay view", e);
        }

        int NOTIFICATION_ID = 1;
        String CHANNEL_ID = "MediaProjectionServiceChannel";

        NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = "Screen Capture";
            String description = "Notifications for screen capture service";
            int importance = NotificationManager.IMPORTANCE_LOW;
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);
            notificationManager.createNotificationChannel(channel);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Screen Capture")
                .setContentText("Capturing the screen.")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);

        startForeground(NOTIFICATION_ID, builder.build());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (windowManager != null && overlayView != null) {
            windowManager.removeView(overlayView);
            overlayView = null;
        }
        stopForeground(true);
    }
}
