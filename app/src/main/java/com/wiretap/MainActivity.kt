package com.wiretap

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {  // Using default MaterialTheme instead of custom theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    var showGoalDialog by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    var userGoal by remember { mutableStateOf("") }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Service enablement section
        Text(
            text = "Wiretap Accessibility Service",
            style = MaterialTheme.typography.headlineMedium
        )

        Button(
            onClick = {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                context.startActivity(intent)
            }
        ) {
            Text("Enable Accessibility Service")
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Recording controls
        if (!isRecording) {
            Button(
                onClick = { showGoalDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("Start Recording")
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Recording in progress...",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    "Goal: $userGoal",
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(
                    onClick = {
                        isRecording = false
                        // Send broadcast to stop recording
                        context.sendBroadcast(Intent("com.wiretap.STOP_RECORDING"))
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Stop Recording")
                }
            }
        }
    }

    // Goal Input Dialog
    if (showGoalDialog) {
        AlertDialog(
            onDismissRequest = { showGoalDialog = false },
            title = { Text("What's your goal?") },
            text = {
                TextField(
                    value = userGoal,
                    onValueChange = { userGoal = it },
                    placeholder = { Text("Enter your goal here...") }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (userGoal.isNotBlank()) {
                            isRecording = true
                            showGoalDialog = false
                            // Send broadcast to start recording
                            context.sendBroadcast(Intent("com.wiretap.START_RECORDING").apply {
                                putExtra("goal", userGoal)
                            })
                        }
                    }
                ) {
                    Text("Start")
                }
            },
            dismissButton = {
                Button(onClick = { showGoalDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}