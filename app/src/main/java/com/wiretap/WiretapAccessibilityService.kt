package com.wiretap

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.*

class WiretapAccessibilityService : AccessibilityService() {
    private val TAG = "WiretapAccessibility"
    private val treeCreator = AccessibilityTreeCreator()

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    // Add variables for text input debouncing
    private var textInputJob: Job? = null
    private val TEXT_INPUT_DELAY = 1000L

    private val HIERARCHY_CAPTURE_DELAY = 1000L

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
    private val recordingActions = mutableListOf<String>()

    private val recordingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.wiretap.START_RECORDING" -> {
                    isRecording = true
                    currentGoal = intent.getStringExtra("goal")
                    recordingActions.clear()
                    Log.d(TAG, "Started recording for goal: $currentGoal")
                }
                "com.wiretap.STOP_RECORDING" -> {
                    isRecording = false
                    // Save or process the recorded actions
                    val recording = """
                    {
                      "goal": "$currentGoal",
                      "actions": [
                        ${recordingActions.joinToString(",\n")}
                      ]
                    }
                    """.trimIndent()
                    Log.d(TAG, "Recording completed: $recording")
                    currentGoal = null
                    recordingActions.clear()
                }
            }
        }
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
        if (!isRecording) return

        val action = when (event.eventType) {
            // Click events
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val sourceNode = event.source
                sourceNode?.let { node ->
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    // Using center point of the bounds for x,y coordinates
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

            // Text input events
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val text = event.text?.joinToString("") ?: ""
                // Cancel any pending text input job
                textInputJob?.cancel()
                // Create new debounced job
                textInputJob = serviceScope.launch {
                    delay(TEXT_INPUT_DELAY)  // Wait for 1 second of no typing
                    """
                    {
                      "action_type": "input_text",
                      "text": "$text"
                    }
                    """.trimIndent()
                }
                null  // Return null for now, actual JSON will be processed after delay
            }

            // Navigation and app launch events
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                when {
                    // Check if it's an app launch
                    event.packageName != null && event.className != null -> {
                        val appName = event.packageName.toString().substringAfterLast(".")
                        """
                       {
                         "action_type": "open_app",
                         "app_name": "$appName"
                       }
                       """.trimIndent()
                    }
                    // Check if it's a back navigation
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
                // Launch a coroutine to handle the delayed capture
                serviceScope.launch {
                    // Add action to recording immediately
                    recordingActions.add(actionJson)

                    // Wait for screen to update
                    delay(HIERARCHY_CAPTURE_DELAY)

                    // Capture the view hierarchy after delay
                    val windows = windows?.toList() ?: emptyList()
                    val forestJson = treeCreator.buildForest(windows)

                    Log.d(TAG, """{
                        "action": $actionJson,
                        "tree": $forestJson
                    }""".trimIndent())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing accessibility tree", e)
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