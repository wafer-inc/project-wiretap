package com.wiretap

import android.accessibilityservice.AccessibilityGestureEvent
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream


class WiretapAccessibilityService : AccessibilityService() {
    private val TAG = "WiretapAccessibility"

    private val treeCreator = AccessibilityTreeCreator()

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    // Add variables for text input debouncing
    private var textInputJob: Job? = null
    private val TEXT_INPUT_DELAY = 1000L

    private val HIERARCHY_CAPTURE_DELAY = 500L

    private var previousPackage: CharSequence? = null

    private var currentEpisodeDir: File? = null
    private var currentTreeIndex = 0

    private var hasReachedLauncher = false
    private var launcherVisitCount = 0
    private var isStartRequested = false

    private var lastActionTimestamp: Long = 0L

    private fun isLauncher(packageName: String?): Boolean {
        return packageName?.contains("launcher", ignoreCase = true) == true
    }

    private fun initializeNewEpisode() {
        // Use getExternalFilesDir which is app-specific but still accessible via ADB
        val datasetDir = File(getExternalFilesDir(null), "wiretap_dataset")
        if (!datasetDir.exists()) {
            datasetDir.mkdirs()
        }

        // Find the next episode number
        val episodeNumber = datasetDir.listFiles()?.size ?: 0

        // Create new episode directory
        currentEpisodeDir = File(datasetDir, "episode_$episodeNumber")
        currentEpisodeDir?.mkdirs()

        // Reset tree index
        currentTreeIndex = 0

        Log.d(TAG, "Created new episode directory: ${currentEpisodeDir?.absolutePath}")
    }

    private fun saveTreeToFile(treeJson: String) {
        currentEpisodeDir?.let { dir ->
            val treeFile = File(dir, "accessibility_tree_${currentTreeIndex}.txt")
            treeFile.writeText(treeJson)
            currentTreeIndex++
        }
    }

    private fun saveMetadata() {
        currentEpisodeDir?.let { dir ->
            val actionsJson = recordingActions.joinToString(",\n")  // Remove the extra indentation

            val metadata = """
{
  "goal": ${currentGoal?.let { "\"$it\"" } ?: "null"},
  "actions": [
    $actionsJson
  ]
}""".trimIndent()

            File(dir, "metadata.json").writeText(metadata)
        }
    }

    private fun captureScreenshot(episodeDir: File, index: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                applicationContext.mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val bitmap = Bitmap.wrapHardwareBuffer(
                            screenshot.hardwareBuffer,
                            screenshot.colorSpace
                        )

                        try {
                            val screenshotFile = File(episodeDir, "screenshot_$index.png")
                            FileOutputStream(screenshotFile).use { out ->
                                bitmap?.compress(Bitmap.CompressFormat.PNG, 100, out)
                            }
                            Log.d(TAG, "Screenshot saved: ${screenshotFile.absolutePath}")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error saving screenshot", e)
                        } finally {
                            bitmap?.recycle()
                            screenshot.hardwareBuffer.close()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e(TAG, "Screenshot failed with error code: $errorCode")
                    }
                }
            )
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE  // Add this flag
            notificationTimeout = 100
        }
        serviceInfo = info


        registerReceiver(
            recordingReceiver,
            IntentFilter().apply {
                addAction("com.wiretap.START_RECORDING")
                addAction("com.wiretap.STOP_RECORDING")
            }
        )

        // Register gesture receiver
        registerReceiver(
            gestureReceiver,
            IntentFilter().apply {
                addAction("com.wiretap.ACTION_GESTURE")
            }
        )

        Log.d(TAG, "Registered all receivers")

        Log.i(TAG, "WiretapAccessibilityService connected")
    }

    private var isRecording = false
    private var currentGoal: String? = null
    private val
            recordingActions = mutableListOf<String>()

    private val recordingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.wiretap.START_RECORDING" -> {
                    isStartRequested = true
                    currentGoal = intent.getStringExtra("goal")
                    recordingActions.clear()
                    // We'll initialize the episode when we actually start recording
                    Log.d(TAG, "Waiting for home screen before starting recording for goal: $currentGoal")
                }
                "com.wiretap.STOP_RECORDING" -> {
                    stopRecording()
                }
            }
        }
    }

    private fun stopRecording() {
        isRecording = false
        isStartRequested = false
        hasReachedLauncher = false
        launcherVisitCount = 0
        saveMetadata()
        Log.d(TAG, "Recording completed and saved to ${currentEpisodeDir?.absolutePath}")
        currentEpisodeDir = null
        currentGoal = null
        recordingActions.clear()
        currentTreeIndex = 0
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString()

            if (isStartRequested && !isRecording && isLauncher(packageName)) {
                // First launcher visit - start recording
                isRecording = true
                hasReachedLauncher = true
                launcherVisitCount = 1
                initializeNewEpisode()

                // Capture initial launcher state
                serviceScope.launch {
                    val windows = windows?.toList() ?: emptyList()
                    val forestJson = treeCreator.buildForest(windows)
                    saveTreeToFile(forestJson)

                    currentEpisodeDir?.let { dir ->
                        captureScreenshot(dir, currentTreeIndex - 1)
                    }
                }

                Log.d(TAG, "Reached launcher, captured initial state and started recording")
                return
            } else if (isRecording && isLauncher(packageName)) {
                // Second launcher visit - stop recording
                if (launcherVisitCount == 1) {
                    Log.d(TAG, "Returned to launcher, stopping recording")
                    stopRecording()
                    return
                }
            }
        }

        if (!isRecording) return

        val currentTime = System.currentTimeMillis()
        if (lastActionTimestamp == 0L || currentTime - lastActionTimestamp > 600) {
            when (event.eventType) {
                // Text input events - handle separately with debounce
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    val text = event.text?.joinToString("") ?: ""
                    Log.d(TAG, "Typed: $text")
                    textInputJob?.cancel()
                    textInputJob = serviceScope.launch {
                        delay(TEXT_INPUT_DELAY)  // Wait for 1 second of no typing

                        val textInputJson = """
                        {
                          "action_type": "input_text",
                          "text": "$text"
                        }
                        """.trimIndent()

                        try {
                            recordingActions.add(textInputJson)
                            delay(HIERARCHY_CAPTURE_DELAY)

                            val windows = windows?.toList() ?: emptyList()
                            val forestJson = treeCreator.buildForest(windows)
                            saveTreeToFile(forestJson)

                            currentEpisodeDir?.let { dir ->
                                captureScreenshot(dir, currentTreeIndex - 1)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing text input event", e)
                        }
                    }
                    return  // Return early for text events
                }

                // All other events - handle immediately
                else -> {
                    val action = when (event.eventType) {
                        // Navigation and app launch events
                        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                            when {
                                // App launch
                                event.packageName != null &&
                                        event.className != null &&
                                        !isLauncher(event.packageName.toString()) &&
                                        event.className.toString().endsWith("Activity") &&
                                        previousPackage != event.packageName -> {
                                    previousPackage = event.packageName
                                    val appName = event.packageName.toString()
                                    """
                                    {
                                      "action_type": "open_app",
                                      "app_name": "$appName"
                                    }
                                    """.trimIndent()
                                }
                                // Back navigation
                                event.contentDescription?.contains("back") == true ||
                                        event.className?.contains("back") == true -> {
                                    """
                                    {
                                      "action_type": "navigate_back"
                                    }
                                    """.trimIndent()
                                }

                                else -> null
                            }
                        }

                        else -> null
                    }


                    action?.let { actionJson ->
                        try {
                            serviceScope.launch {
                                recordingActions.add(actionJson)
                                delay(HIERARCHY_CAPTURE_DELAY)

                                val windows = windows?.toList() ?: emptyList()
                                val forestJson = treeCreator.buildForest(windows)
                                saveTreeToFile(forestJson)

                                currentEpisodeDir?.let { dir ->
                                    captureScreenshot(dir, currentTreeIndex - 1)
                                }
                            }
                            lastActionTimestamp = System.currentTimeMillis()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing event", e)
                        }
                    }
                }
            }
        }
    }

    private fun handleStartRecording(intent: Intent) {
        isStartRequested = true
        currentGoal = intent.getStringExtra("goal")
        recordingActions.clear()
        Log.d(TAG, "Waiting for home screen before starting recording for goal: $currentGoal")
    }

    private fun handleStopRecording() {
        stopRecording()
    }

    private val gestureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Service received gesture broadcast directly")
            if (!isRecording) {
                Log.d(TAG, "Not recording, ignoring gesture")
                return
            }

            val type = intent?.getStringExtra("type")
            Log.d(TAG, "Processing gesture type: $type")
            val x = intent?.getIntExtra("x", 0) ?: 0
            val y = intent?.getIntExtra("y", 0) ?: 0
            val x2 = intent?.getIntExtra("x2", -1)
            val y2 = intent?.getIntExtra("y2", -1)

            // Create gesture action JSON
            val gestureJson = when (type) {
                "CLICK" -> """
                {
                  "action_type": "click",
                  "coordinates": {
                    "x": $x,
                    "y": $y
                  }
                }
                """.trimIndent()

                "SWIPE_LEFT", "SWIPE_RIGHT", "SWIPE_UP", "SWIPE_DOWN" -> """
                {
                  "action_type": "swipe",
                  "direction": "$type",
                  "coordinates": {
                    "start_x": $x,
                    "start_y": $y,
                    "end_x": $x2,
                    "end_y": $y2
                  }
                }
                """.trimIndent()

                else -> null
            }

            gestureJson?.let { actionJson ->
                Log.d(TAG, "Recording gesture: $actionJson")
                serviceScope.launch {
                    recordingActions.add(actionJson)
                    delay(HIERARCHY_CAPTURE_DELAY)

                    val windows = windows?.toList() ?: emptyList()
                    val forestJson = treeCreator.buildForest(windows)
                    saveTreeToFile(forestJson)

                    currentEpisodeDir?.let { dir ->
                        captureScreenshot(dir, currentTreeIndex - 1)
                    }
                }
                lastActionTimestamp = System.currentTimeMillis()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(gestureReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }
        serviceScope.cancel()
    }

    override fun onInterrupt() {
        Log.w(TAG, "WiretapAccessibilityService interrupted")
    }

}