from dataclasses import dataclass
import subprocess
import re
from typing import Optional
import time
from enum import Enum, auto


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


class GestureDetector:
    def __init__(self, service_package: str):
        self.touch_state = TouchState()
        self.click_max_duration = 0.3    # seconds
        self.click_max_distance = 100    # pixels
        self.swipe_min_distance = 200    # pixels
        self.service_package = service_package

        # Event type constants
        self.EV_SYN = 0x0000
        self.EV_KEY = 0x0001
        self.EV_ABS = 0x0003

        # Event code constants
        self.ABS_MT_TRACKING_ID = 0x0039
        self.ABS_MT_POSITION_X = 0x0035
        self.ABS_MT_POSITION_Y = 0x0036
        self.BTN_TOUCH = 0x014a

        # Event type mapping for service
        self.EVENT_TYPES = {
            GestureType.CLICK: "CLICK",
            GestureType.SWIPE_LEFT: "SWIPE_LEFT",
            GestureType.SWIPE_RIGHT: "SWIPE_RIGHT",
            GestureType.SWIPE_UP: "SWIPE_UP",
            GestureType.SWIPE_DOWN: "SWIPE_DOWN"
        }

    def send_gesture_event(self, event_type: str, x: int, y: int, x2: Optional[int] = None, y2: Optional[int] = None):
        # Build the basic command
        command = [
            'adb', 'shell', 'am', 'broadcast',
            '--user', '0',
            '-a', f'{self.service_package}.ACTION_GESTURE',
            '-p', self.service_package,  # Specify package
            '--es', 'type', event_type,
            '--ei', 'x', str(x),
            '--ei', 'y', str(y)
        ]

        # Add end coordinates for swipes
        if x2 is not None and y2 is not None:
            command.extend(['--ei', 'x2', str(x2)])
            command.extend(['--ei', 'y2', str(y2)])

        # Print the command for debugging
        print("Sending command:", ' '.join(command))

        try:
            result = subprocess.run(
                command, check=True, capture_output=True, text=True)
            print(f"Command output: {result.stdout}")
            if result.stderr:
                print(f"Command error: {result.stderr}")
        except subprocess.CalledProcessError as e:
            print(f"Failed to send event: {e}")
            print(f"Error output: {e.stderr}")

    def parse_line(self, line: str) -> Optional[EventData]:
        pattern = r'/dev/input/(\w+): ([0-9a-f]{4}) ([0-9a-f]{4}) ([0-9a-f]{8})'
        match = re.match(pattern, line.decode('utf-8', errors='ignore'))

        if not match:
            return None

        device, type_hex, code_hex, value_hex = match.groups()
        return EventData(
            device=device,
            type=int(type_hex, 16),
            code=int(code_hex, 16),
            value=int(value_hex, 16)
        )

    def process_event(self, event: EventData):
        if event.type == self.EV_KEY and event.code == self.BTN_TOUCH:
            self.touch_state.touching = bool(event.value)
            if event.value:
                self.touch_state.start_time = time.time()
            else:
                self.on_touch_end()

        elif event.type == self.EV_ABS:
            if event.code == self.ABS_MT_POSITION_X:
                self.touch_state.x = event.value
                if self.touch_state.start_x is None and self.touch_state.touching:
                    self.touch_state.start_x = event.value

            elif event.code == self.ABS_MT_POSITION_Y:
                self.touch_state.y = event.value
                if self.touch_state.start_y is None and self.touch_state.touching:
                    self.touch_state.start_y = event.value
                    self.on_touch_start()

            elif event.code == self.ABS_MT_TRACKING_ID:
                if event.value == -1:
                    self.on_touch_end()
                self.touch_state.tracking_id = event.value

    def detect_gesture(self) -> Optional[GestureType]:
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
                return GestureType.SWIPE_DOWN if dy > 0 else GestureType.SWIPE_UP

        return None

    def on_touch_start(self):
        print(f"\nTouch started at ({self.touch_state.start_x}, {
              self.touch_state.start_y})")

    def on_touch_end(self):
        if not all([self.touch_state.start_x, self.touch_state.start_y, self.touch_state.start_time]):
            return

        gesture = self.detect_gesture()
        if gesture:
            event_type = self.EVENT_TYPES[gesture]

            # Send event to accessibility service
            if gesture == GestureType.CLICK:
                self.send_gesture_event(event_type,
                                        self.touch_state.x,
                                        self.touch_state.y)
            else:
                self.send_gesture_event(event_type,
                                        self.touch_state.start_x,
                                        self.touch_state.start_y,
                                        self.touch_state.x,
                                        self.touch_state.y)

            # Print debug information
            print(f"\n{'='*50}")
            print(f"Gesture Detected: {gesture.name}")
            print(f"Start position: ({self.touch_state.start_x}, {
                  self.touch_state.start_y})")
            print(f"End position: ({self.touch_state.x}, {
                  self.touch_state.y})")
            print(f"Duration: {time.time() -
                  self.touch_state.start_time:.3f} seconds")
            print(f"{'='*50}\n")

        # Reset start positions
        self.touch_state.start_x = None
        self.touch_state.start_y = None
        self.touch_state.start_time = None

    def run(self):
        process = subprocess.Popen(
            ['adb', 'shell', 'getevent'],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE
        )

        print("\nGesture Detector Started")
        print(f"Sending events to: {self.service_package}")
        print("Press Ctrl+C to stop\n")

        try:
            while True:
                line = process.stdout.readline()
                if not line:
                    break

                event = self.parse_line(line)
                if event:
                    self.process_event(event)

        except KeyboardInterrupt:
            print("\nStopping gesture detector...")
        finally:
            process.terminate()
            process.wait()


if __name__ == "__main__":
    detector = GestureDetector("com.wiretap")
    detector.run()
