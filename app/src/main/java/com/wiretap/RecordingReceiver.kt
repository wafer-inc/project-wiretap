package com.wiretap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class RecordingReceiver : BroadcastReceiver() {
    private val TAG = "WiretapAccessibility"

    override fun onReceive(context: Context?, intent: Intent?) {
        Log.d(TAG, "RecordingReceiver: Action: " + intent?.action)
        
        // Forward the intent to the service
        val serviceIntent = Intent(context, WiretapAccessibilityService::class.java).apply {
            action = intent?.action
            putExtras(intent?.extras ?: return)
        }
        context?.sendBroadcast(serviceIntent)
    }
}