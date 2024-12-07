package com.wiretap

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Environment
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
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
            // Request all types of accessibility events
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK

            // Set feedback type to generic
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC

            // Request all available window content
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS

            notificationTimeout = 100
        }
        serviceInfo = info
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

    override fun onCreate() {
        super.onCreate()
        // Register broadcast receiver
        registerReceiver(
            recordingReceiver,
            IntentFilter().apply {
                addAction("com.wiretap.START_RECORDING")
                addAction("com.wiretap.STOP_RECORDING")
            }
        )
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
                    // Click events
                    AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                        val sourceNode = event.source
                        sourceNode?.let { node ->
                            val rect = Rect()
                            node.getBoundsInScreen(rect)
                            val x = (rect.left + rect.right) / 2
                            val y = (rect.top + rect.bottom) / 2
                            """
                            {
                              "action_type": "click",
                              "x": $x,
                              "y": $y
                            }
                            """.trimIndent()
                        }
                    }

                    // Navigation and app launch events
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                        when {
                            // App launch
                            event.packageName != null &&
                                    event.className != null &&
                                    !event.packageName.toString().contains("launcher") &&
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

                    // Scroll events
                    AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                        when {
                            event.scrollDeltaY < 0 -> """
                                {
                                  "action_type": "scroll",
                                  "direction": "up"
                                }
                                """.trimIndent()
                            event.scrollDeltaY > 0 -> """
                                {
                                  "action_type": "scroll",
                                  "direction": "down"
                                }
                                """.trimIndent()
                            event.scrollDeltaX < 0 -> """
                                {
                                  "action_type": "scroll",
                                  "direction": "left"
                                }
                                """.trimIndent()
                            event.scrollDeltaX > 0 -> """
                                {
                                  "action_type": "scroll",
                                  "direction": "right"
                                }
                                """.trimIndent()
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
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing event", e)
                    }
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "WiretapAccessibilityService interrupted")
    }

    // Clean up coroutine scope when service is destroyed
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}