import subprocess
import re
import json
import time
import threading
import sys
import os
import math
from dataclasses import dataclass
from typing import Optional, List, Dict
from enum import Enum, auto
from PIL import Image, ImageDraw
import io


@dataclass
class EventData:
    device: str
    type: int
    code: int
    value: int


class GestureType(Enum):
    CLICK = auto()
    SWIPE_LEFT = auto()
    SWIPE_RIGHT = auto()
    SWIPE_UP = auto()
    SWIPE_DOWN = auto()


@dataclass
class TouchState:
    x: int = 0
    y: int = 0
    tracking_id: int = -1
    touching: bool = False
    start_x: Optional[int] = None
    start_y: Optional[int] = None
    start_time: Optional[float] = None

    def is_active(self) -> bool:
        return self.tracking_id != -1 and self.touching


class SessionRecorder:
    def __init__(self):
        self.actions = []
        self.current_app = None
        self.monitor_process = None
        self.recording = False
        self.touch_state = TouchState()

        # Session metadata
        self.goal = None
        self.episode_id = None
        self.screenshot_widths = []
        self.screenshot_heights = []

        # Screenshot management
        self.screenshot_thread = None
        self.screenshot_lock = threading.Lock()
        self.current_screenshot = None
        self.screenshot_interval = 0.3  # 300ms
        self.session_dir = None
        self.action_count = 0

        # Gesture thresholds
        self.click_max_duration = 0.3  # seconds
        self.click_max_distance = 100  # pixels
        self.swipe_min_distance = 200  # pixels

        # Event type constants
        self.EV_SYN = 0x0000
        self.EV_KEY = 0x0001
        self.EV_ABS = 0x0003

        # Event code constants
        self.ABS_MT_TRACKING_ID = 0x0039
        self.ABS_MT_POSITION_X = 0x0035
        self.ABS_MT_POSITION_Y = 0x0036
        self.BTN_TOUCH = 0x014a

    def create_session_directory(self):
        """Create a directory for this recording session"""
        # Ensure episodes directory exists
        os.makedirs("episodes", exist_ok=True)

        # Find next episode ID by checking existing episodes
        existing_episodes = []
        if os.path.exists("episodes"):
            for dir_name in os.listdir("episodes"):
                if dir_name.startswith("episode_"):
                    try:
                        episode_num = int(dir_name.split("_")[1])
                        existing_episodes.append(episode_num)
                    except (IndexError, ValueError):
                        continue

        # Set episode ID to next available number
        self.episode_id = max(existing_episodes, default=0) + 1

        # Create episode directory
        self.session_dir = os.path.join(
            "episodes", f"episode_{self.episode_id}")
        os.makedirs(self.session_dir, exist_ok=True)

        print(f"✓ Created episode directory: {self.session_dir}")
        print(f"✓ Episode ID: {self.episode_id}")

    def capture_screenshot(self):
        """Capture a screenshot using adb"""
        try:
            # Use adb to capture screenshot data directly
            result = subprocess.run(
                ['adb', 'exec-out', 'screencap', '-p'],
                capture_output=True,
                check=True
            )

            # Convert bytes to PIL Image
            screenshot = Image.open(io.BytesIO(result.stdout))
            return screenshot

        except Exception as e:
            print(f"✗ Screenshot capture failed: {e}")
            return None

    def screenshot_monitor(self):
        """Continuously capture screenshots in background"""
        print("📸 Screenshot monitor started")

        while self.recording:
            try:
                screenshot = self.capture_screenshot()
                if screenshot:
                    with self.screenshot_lock:
                        self.current_screenshot = screenshot

                time.sleep(self.screenshot_interval)

            except Exception as e:
                print(f"Screenshot monitor error: {e}")
                time.sleep(self.screenshot_interval)

    def save_action_screenshot(self, action=None):
        """Save the current screenshot for the latest action"""
        with self.screenshot_lock:
            if self.current_screenshot:
                screenshot_path = os.path.join(
                    self.session_dir,
                    f"screenshot_{self.action_count}.png"
                )
                self.current_screenshot.save(screenshot_path)

                # Create annotated version if action is provided
                if action and action["action_type"] in ["click", "swipe"]:
                    annotated = self.create_annotated_screenshot(
                        self.current_screenshot,
                        action
                    )
                    annotated_path = os.path.join(
                        self.session_dir,
                        f"screenshot_annotated_{self.action_count}.png"
                    )
                    annotated.save(annotated_path)

                # Track screenshot dimensions
                width, height = self.current_screenshot.size
                self.screenshot_widths.append(width)
                self.screenshot_heights.append(height)

                self.action_count += 1
                return screenshot_path
        return None

    def scale_coordinate(self, x: int, y: int) -> tuple:
        """Scale touch coordinates to screen resolution"""
        # Touch device max values from getevent -il
        MAX_RAW_X = 1343
        MAX_RAW_Y = 2991

        # Screen resolution - update these based on 'adb shell wm size'
        # Common Pixel values: 1080x2400, 1284x2778, etc.
        SCREEN_WIDTH = 1008   # Update if different
        SCREEN_HEIGHT = 2244  # Update if different

        scaled_x = int(x * SCREEN_WIDTH / MAX_RAW_X)
        scaled_y = int(y * SCREEN_HEIGHT / MAX_RAW_Y)
        return scaled_x, scaled_y

    def create_annotated_screenshot(self, screenshot, action):
        """Create an annotated version of the screenshot showing the action"""
        # Create a copy of the screenshot
        annotated = screenshot.copy()
        draw = ImageDraw.Draw(annotated)

        # Define colors
        primary_color = (255, 0, 0)  # Red
        secondary_color = (255, 255, 255)  # White
        line_color = (0, 255, 0)  # Green for swipes

        if action["action_type"] == "click":
            x, y = action["x"], action["y"]

            # Draw crosshair
            crosshair_size = 30
            line_width = 3

            # Horizontal line
            draw.line(
                [(x - crosshair_size, y), (x + crosshair_size, y)],
                fill=primary_color,
                width=line_width
            )

            # Vertical line
            draw.line(
                [(x, y - crosshair_size), (x, y + crosshair_size)],
                fill=primary_color,
                width=line_width
            )

            # Center circle
            circle_radius = 10
            draw.ellipse(
                [(x - circle_radius, y - circle_radius),
                 (x + circle_radius, y + circle_radius)],
                outline=primary_color,
                width=line_width
            )

            # Inner dot
            dot_radius = 3
            draw.ellipse(
                [(x - dot_radius, y - dot_radius),
                 (x + dot_radius, y + dot_radius)],
                fill=primary_color
            )

        elif action["action_type"] == "swipe":
            start_x = action["start_x"]
            start_y = action["start_y"]
            end_x = action["end_x"]
            end_y = action["end_y"]

            # Draw swipe line
            draw.line(
                [(start_x, start_y), (end_x, end_y)],
                fill=line_color,
                width=5
            )

            # Draw start point (circle)
            radius = 15
            draw.ellipse(
                [(start_x - radius, start_y - radius),
                 (start_x + radius, start_y + radius)],
                fill=primary_color,
                outline=secondary_color,
                width=3
            )

            # Draw end point (arrow-like triangle)
            # Calculate arrow direction
            angle = math.atan2(end_y - start_y, end_x - start_x)
            arrow_length = 30
            arrow_angle = 0.5  # radians

            # Arrow points
            arrow_tip = (end_x, end_y)
            arrow_left = (
                end_x - arrow_length * math.cos(angle - arrow_angle),
                end_y - arrow_length * math.sin(angle - arrow_angle)
            )
            arrow_right = (
                end_x - arrow_length * math.cos(angle + arrow_angle),
                end_y - arrow_length * math.sin(angle + arrow_angle)
            )

            # Draw arrow
            draw.polygon(
                [arrow_tip, arrow_left, arrow_right],
                fill=line_color,
                outline=secondary_color
            )

        return annotated

    def get_installed_apps(self) -> List[Dict[str, str]]:
        """Get list of installed third-party apps"""
        try:
            result = subprocess.run(
                ['adb', 'shell', 'pm', 'list', 'packages', '-3'],
                capture_output=True, text=True, check=True
            )

            apps = []
            for line in result.stdout.strip().split('\n'):
                if line.startswith('package:'):
                    package = line.replace('package:', '')
                    # Get app label
                    label_result = subprocess.run(
                        ['adb', 'shell', 'pm', 'dump', package,
                            '|', 'grep', '-A1', 'labelRes'],
                        capture_output=True, text=True, shell=True
                    )
                    apps.append({
                        'package': package,
                        # Simple name extraction
                        'name': package.split('.')[-1].title()
                    })

            return sorted(apps, key=lambda x: x['name'])
        except Exception as e:
            print(f"Error getting apps: {e}")
            return []

    def launch_app(self, package: str, app_name: str):
        """Launch app using monkey command"""
        try:
            subprocess.run(
                ['adb', 'shell', 'monkey', '-p', package, '-c',
                    'android.intent.category.LAUNCHER', '1'],
                capture_output=True, check=True
            )
            self.current_app = package  # Store package name instead of app name

            # Save screenshot before recording the action
            screenshot_path = self.save_action_screenshot()

            action = {
                "action_type": "open_app",
                "app_name": app_name
            }
            self.actions.append(action)

            print(f"✓ Launched {app_name}")
            time.sleep(2)  # Wait for app to load
        except Exception as e:
            print(f"✗ Failed to launch app: {e}")

    def parse_line(self, line: str) -> Optional[EventData]:
        """Parse getevent line"""
        # Handle both formats:
        # With device: /dev/input/event1: 0003 0035 000001a5
        # Without device: 0003 0035 000001a5

        # Try with device path first
        pattern_with_device = r'/dev/input/(\w+): ([0-9a-f]{4}) ([0-9a-f]{4}) ([0-9a-f]{8})'
        match = re.match(pattern_with_device, line)

        if match:
            device, type_hex, code_hex, value_hex = match.groups()
        else:
            # Try without device path
            pattern_without_device = r'([0-9a-f]{4}) ([0-9a-f]{4}) ([0-9a-f]{8})'
            match = re.match(pattern_without_device, line.strip())
            if match:
                type_hex, code_hex, value_hex = match.groups()
                device = "event1"  # Default to our touch device
            else:
                return None

        return EventData(
            device=device,
            type=int(type_hex, 16),
            code=int(code_hex, 16),
            value=int(value_hex, 16)
        )

    def create_annotated_screenshot(self, screenshot, action):
        """Create an annotated version of the screenshot showing the action"""
        # Create a copy of the screenshot
        annotated = screenshot.copy()
        draw = ImageDraw.Draw(annotated)

        # Define colors
        primary_color = (255, 0, 0)  # Red
        secondary_color = (255, 255, 255)  # White
        line_color = (0, 255, 0)  # Green for swipes

        if action["action_type"] == "click":
            x, y = action["x"], action["y"]

            # Draw crosshair
            crosshair_size = 30
            line_width = 3

            # Horizontal line
            draw.line(
                [(x - crosshair_size, y), (x + crosshair_size, y)],
                fill=primary_color,
                width=line_width
            )

            # Vertical line
            draw.line(
                [(x, y - crosshair_size), (x, y + crosshair_size)],
                fill=primary_color,
                width=line_width
            )

            # Center circle
            circle_radius = 10
            draw.ellipse(
                [(x - circle_radius, y - circle_radius),
                 (x + circle_radius, y + circle_radius)],
                outline=primary_color,
                width=line_width
            )

            # Inner dot
            dot_radius = 3
            draw.ellipse(
                [(x - dot_radius, y - dot_radius),
                 (x + dot_radius, y + dot_radius)],
                fill=primary_color
            )

        elif action["action_type"] == "swipe":
            start_x = action["start_x"]
            start_y = action["start_y"]
            end_x = action["end_x"]
            end_y = action["end_y"]

            # Draw swipe line
            draw.line(
                [(start_x, start_y), (end_x, end_y)],
                fill=line_color,
                width=5
            )

            # Draw start point (circle)
            radius = 15
            draw.ellipse(
                [(start_x - radius, start_y - radius),
                 (start_x + radius, start_y + radius)],
                fill=primary_color,
                outline=secondary_color,
                width=3
            )

            # Draw end point (arrow-like triangle)
            # Calculate arrow direction
            import math
            angle = math.atan2(end_y - start_y, end_x - start_x)
            arrow_length = 30
            arrow_angle = 0.5  # radians

            # Arrow points
            arrow_tip = (end_x, end_y)
            arrow_left = (
                end_x - arrow_length * math.cos(angle - arrow_angle),
                end_y - arrow_length * math.sin(angle - arrow_angle)
            )
            arrow_right = (
                end_x - arrow_length * math.cos(angle + arrow_angle),
                end_y - arrow_length * math.sin(angle + arrow_angle)
            )

            # Draw arrow
            draw.polygon(
                [arrow_tip, arrow_left, arrow_right],
                fill=line_color,
                outline=secondary_color
            )

        return annotated
        """Scale touch coordinates to screen resolution"""
        # Scale to 1080x2640 (adjust based on your device)
        scaled_x = int(x * 1008 / 4095)
        scaled_y = int(y * 2240 / 4095)
        return scaled_x, scaled_y

    def process_event(self, event: EventData):
        """Process touch events"""
        if event.type == self.EV_KEY and event.code == self.BTN_TOUCH:
            self.touch_state.touching = bool(event.value)
            if event.value:
                self.touch_state.start_time = time.time()
            else:
                self.on_touch_end()

        elif event.type == self.EV_ABS:
            if event.code == self.ABS_MT_POSITION_X:
                scaled_x, _ = self.scale_coordinate(
                    event.value, self.touch_state.y)
                self.touch_state.x = scaled_x
                if self.touch_state.start_x is None and self.touch_state.touching:
                    self.touch_state.start_x = scaled_x

            elif event.code == self.ABS_MT_POSITION_Y:
                _, scaled_y = self.scale_coordinate(
                    self.touch_state.x, event.value)
                self.touch_state.y = scaled_y
                if self.touch_state.start_y is None and self.touch_state.touching:
                    self.touch_state.start_y = scaled_y
                    self.on_touch_start()

            elif event.code == self.ABS_MT_TRACKING_ID:
                if event.value == -1:
                    self.on_touch_end()
                self.touch_state.tracking_id = event.value

    def detect_gesture(self) -> Optional[GestureType]:
        """Detect gesture type from touch state"""
        if (self.touch_state.start_x is None or
            self.touch_state.start_y is None or
                self.touch_state.start_time is None):
            return None

        duration = time.time() - self.touch_state.start_time
        dx = self.touch_state.x - self.touch_state.start_x
        dy = self.touch_state.y - self.touch_state.start_y
        distance = (dx ** 2 + dy ** 2) ** 0.5

        if duration <= self.click_max_duration and distance <= self.click_max_distance:
            return GestureType.CLICK

        if distance >= self.swipe_min_distance:
            if abs(dx) > abs(dy):
                return GestureType.SWIPE_RIGHT if dx > 0 else GestureType.SWIPE_LEFT
            else:
                return GestureType.SWIPE_UP if dy > 0 else GestureType.SWIPE_DOWN

        return None

    def on_touch_start(self):
        """Called when touch starts"""
        pass  # Removed print to avoid interfering with input

    def on_touch_end(self):
        """Process end of touch and record action"""
        if not all([self.touch_state.start_x, self.touch_state.start_y, self.touch_state.start_time]):
            return

        gesture = self.detect_gesture()
        if gesture:
            # First, create the action
            if gesture == GestureType.CLICK:
                action = {
                    "action_type": "click",
                    "x": self.touch_state.x,
                    "y": self.touch_state.y
                }
                print(
                    f"\r✓ Click at ({self.touch_state.x}, {self.touch_state.y})")
                sys.stdout.flush()
            else:
                # It's a swipe
                direction = gesture.name.replace("SWIPE_", "").lower()
                action = {
                    "action_type": "swipe",
                    "start_x": self.touch_state.start_x,
                    "start_y": self.touch_state.start_y,
                    "end_x": self.touch_state.x,
                    "end_y": self.touch_state.y,
                    "direction": direction
                }
                print(
                    f"\r✓ Swipe {direction} from ({self.touch_state.start_x}, {self.touch_state.start_y}) to ({self.touch_state.x}, {self.touch_state.y})")
                sys.stdout.flush()

            # Save screenshot with annotation
            screenshot_path = self.save_action_screenshot(action)

            # Add action to list
            self.actions.append(action)

        # Reset start positions
        self.touch_state.start_x = None
        self.touch_state.start_y = None
        self.touch_state.start_time = None

    def monitor_gestures(self):
        """Monitor touch events in background"""
        # Use the specific touch device we found
        self.monitor_process = subprocess.Popen(
            ['adb', 'shell', 'getevent', '/dev/input/event1'],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE
        )

        try:
            while self.recording:
                line = self.monitor_process.stdout.readline()
                if not line:
                    break

                event = self.parse_line(line.decode('utf-8', errors='ignore'))
                if event:
                    self.process_event(event)

        except Exception as e:
            print(f"Monitor error: {e}")
        finally:
            if self.monitor_process:
                self.monitor_process.terminate()

    def inject_text(self, text: str):
        """Inject text and record action"""
        # Save screenshot before the action
        screenshot_path = self.save_action_screenshot()

        # Escape special characters
        escaped = text.replace(' ', '%s').replace(
            "'", "\\'").replace('"', '\\"')

        try:
            # Run the command and capture output
            result = subprocess.run(
                ['adb', 'shell', 'input', 'text', escaped],
                capture_output=True,
                text=True,
                check=False
            )

            if result.returncode == 0:
                action = {
                    "action_type": "input_text",
                    "text": text
                }
                self.actions.append(action)
                print(f"✓ Typed: '{text}'")
                time.sleep(0.1)
            else:
                print(f"✗ Failed to type '{text}': {result.stderr}")
                if result.stdout:
                    print(f"   Output: {result.stdout}")

        except Exception as e:
            print(f"✗ Error typing '{text}': {e}")

    def save_metadata(self):
        """Save actions to metadata.json"""
        metadata = {
            "episode_id": self.episode_id,
            "goal": self.goal,
            "screenshot_widths": self.screenshot_widths,
            "screenshot_heights": self.screenshot_heights,
            "app": self.current_app,  # This is now the package name
            "actions": self.actions
        }

        metadata_path = os.path.join(self.session_dir, 'metadata.json')
        with open(metadata_path, 'w') as f:
            json.dump(metadata, f, indent=2)

        print(
            f"\n✓ Saved {len(self.actions)} actions to episode {self.episode_id}")
        print(
            f"✓ Saved {self.action_count} screenshots (including final state)")
        print(f"✓ Created annotated screenshots for clicks and swipes")

    def run(self):
        """Main recording session"""
        print("\n=== Android Session Recorder ===")

        # Get goal description
        self.goal = input(
            "\nDescribe the goal of this recording session: ").strip()
        while not self.goal:
            print("Goal description cannot be empty!")
            self.goal = input(
                "Describe the goal of this recording session: ").strip()

        # Create session directory (this will auto-assign episode ID)
        self.create_session_directory()

        print("\nGetting installed apps...")

        # Get and display apps
        apps = self.get_installed_apps()
        if not apps:
            print("No apps found!")
            return

        print("\nSelect an app to record:")
        for i, app in enumerate(apps, 1):
            print(f"{i:2d}. {app['name']} ({app['package']})")

        # Get selection
        while True:
            try:
                choice = input("\nEnter app number (or 'q' to quit): ").strip()
                if choice.lower() == 'q':
                    return

                idx = int(choice) - 1
                if 0 <= idx < len(apps):
                    selected_app = apps[idx]
                    break
                else:
                    print("Invalid selection!")
            except ValueError:
                print("Please enter a number!")

        # Start recording
        self.recording = True

        # Start screenshot monitoring
        self.screenshot_thread = threading.Thread(
            target=self.screenshot_monitor)
        self.screenshot_thread.daemon = True
        self.screenshot_thread.start()

        # Wait a moment to capture initial screenshots
        time.sleep(1)

        # Launch app
        print(f"\nLaunching {selected_app['name']}...")
        self.launch_app(selected_app['package'], selected_app['name'])

        # Start gesture monitoring
        monitor_thread = threading.Thread(target=self.monitor_gestures)
        monitor_thread.daemon = True
        monitor_thread.start()

        print("\n📹 Recording started!")
        print("📸 Taking screenshots every 300ms")
        print("- Perform gestures on your device")
        print("- Type text here to inject it")
        print("- Press Ctrl+C to stop and save")
        print("-" * 40)

        # Interactive loop
        try:
            while self.recording:
                try:
                    sys.stdout.flush()
                    text = input()
                    if text and text.strip():
                        self.inject_text(text.strip())
                except EOFError:
                    continue
                except Exception as e:
                    continue

        except KeyboardInterrupt:
            # Stop recording
            self.recording = False
            print("\n\n⏹️  Stopping recording...")

            # Capture final screenshot
            print("📸 Capturing final screenshot...")
            final_screenshot = self.capture_screenshot()
            if final_screenshot:
                # Save the final screenshot
                final_path = os.path.join(
                    self.session_dir,
                    f"screenshot_{self.action_count}.png"
                )
                final_screenshot.save(final_path)

                # Track dimensions
                width, height = final_screenshot.size
                self.screenshot_widths.append(width)
                self.screenshot_heights.append(height)

                print(
                    f"✓ Saved final screenshot: screenshot_{self.action_count}.png")
                self.action_count += 1

            if self.monitor_process:
                self.monitor_process.terminate()
                self.monitor_process.wait()

            # Wait for threads to finish
            if self.screenshot_thread:
                self.screenshot_thread.join(timeout=2)

            # Save metadata
            self.save_metadata()
            print(
                f"\n✓ Episode {self.episode_id} saved to: {self.session_dir}")
            print("Recording complete!")

        except Exception as e:
            print(f"\nError: {e}")
            self.recording = False
            if self.monitor_process:
                self.monitor_process.terminate()


if __name__ == "__main__":
    # Check for required dependencies
    try:
        from PIL import Image
    except ImportError:
        print("Error: PIL (Pillow) is required. Install with: pip install Pillow")
        sys.exit(1)

    recorder = SessionRecorder()
    recorder.run()
