# Project Wiretap

A tool for recording Android device interactions for dataset collection. Wiretap captures touch events, keyboard input, screenshots, and app state as a user interacts with an Android device.

## Features

- Capture touch events (clicks and swipes)
- Detect keyboard input
- Take screenshots at each interaction point
- Organize recordings into episodes with sequential numbering
- Smart screenshot buffering to avoid capturing transition states
- Automatic recording when an app is launched

## Requirements

- Python 3.6+
- ADB (Android Debug Bridge)
- Android device with USB debugging enabled

## Usage

1. Connect an Android device via USB with debugging enabled
2. Run the client:
   ```
   python client.py
   ```
3. Select an app to record from the interactive menu
4. Interact with the app on the Android device
5. Use the CLI commands to stop recording or quit
