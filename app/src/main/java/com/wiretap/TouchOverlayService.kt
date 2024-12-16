package com.wiretap

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager

// Custom ViewGroup to handle and forward touch events
class TransparentTouchInterceptor(context: Context) : ViewGroup(context) {

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // Log the touch events
        Log.d("TouchInterceptor", "Touch Event: ${ev.action} at (${ev.x}, ${ev.y})")

        // Forward touch events manually by redispatching them
        return false // Ensure touch events propagate to underlying views
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Optionally log or process events
        return false // Do not consume; forward the event
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        // Forward touch events to the WindowManager's underlying window
        return false
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        // No children in this ViewGroup, so no layout logic needed
    }
}

// Service to manage the transparent overlay
class TouchOverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: TransparentTouchInterceptor

    override fun onCreate() {
        super.onCreate()

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // Create and configure the overlay view
        overlayView = TransparentTouchInterceptor(this)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )

        windowManager.addView(overlayView, params)
    }

    override fun onDestroy() {
        super.onDestroy()
        windowManager.removeView(overlayView)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
