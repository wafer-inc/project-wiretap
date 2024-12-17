import json
import os
from PIL import Image, ImageDraw, ImageFont
import sys


def create_overlay(image_path, action, output_path):
    """Create an overlay visualization for a given action on an image."""
    # Open and create a copy of the image
    img = Image.open(image_path)
    draw = ImageDraw.Draw(img)

    if action['action_type'] == 'click':
        # Draw a red circle at click coordinates
        x, y = action['coordinates']['x'], action['coordinates']['y']
        radius = 30
        draw.ellipse(
            [(x - radius, y - radius), (x + radius, y + radius)],
            outline='red',
            width=3
        )
        # Draw crosshair
        draw.line([(x - radius, y), (x + radius, y)], fill='red', width=3)
        draw.line([(x, y - radius), (x, y + radius)], fill='red', width=3)

    elif action['action_type'] == 'input_text':
        # Draw a text box at the top of the screen
        text = f"Input Text: {action['text']}"
        # Create semi-transparent background for text
        text_bbox = draw.textbbox((0, 0), text, font=None)
        text_width = text_bbox[2] - text_bbox[0]
        text_height = text_bbox[3] - text_bbox[1]

        padding = 10
        rect_coords = [
            (10, 10),
            (20 + text_width, 20 + text_height)
        ]
        draw.rectangle(rect_coords, fill=(0, 0, 0, 128))
        draw.text((15, 15), text, fill='white')

    elif action['action_type'] == 'open_app':
        # Draw app name at the top of the screen
        text = f"Opening: {action['app_name']}"
        # Create semi-transparent background for text
        text_bbox = draw.textbbox((0, 0), text, font=None)
        text_width = text_bbox[2] - text_bbox[0]
        text_height = text_bbox[3] - text_bbox[1]

        padding = 10
        rect_coords = [
            (10, 10),
            (20 + text_width, 20 + text_height)
        ]
        draw.rectangle(rect_coords, fill=(0, 0, 0, 128))
        draw.text((15, 15), text, fill='white')

    # Save the modified image
    img.save(output_path)


def process_directory(directory_path):
    """Process all screenshots in a directory based on metadata.json."""
    # Read metadata.json
    metadata_path = os.path.join(directory_path, 'metadata.json')

    # Skip if no metadata.json exists
    if not os.path.exists(metadata_path):
        print(f"Skipping {directory_path} - No metadata.json found")
        return False

    try:
        with open(metadata_path, 'r') as f:
            metadata = json.load(f)
    except json.JSONDecodeError:
        print(f"Error reading metadata.json in {directory_path}")
        return False

    # Create output directory
    output_dir = os.path.join(directory_path, 'overlays')
    os.makedirs(output_dir, exist_ok=True)

    # Process each action with corresponding screenshot
    success = True
    for i, action in enumerate(metadata['actions']):
        screenshot_path = os.path.join(directory_path, f'screenshot_{i}.png')
        output_path = os.path.join(output_dir, f'overlay_{i}.png')

        if os.path.exists(screenshot_path):
            try:
                create_overlay(screenshot_path, action, output_path)
                print(f"Created overlay for action {i} in {
                      directory_path}: {action['action_type']}")
            except Exception as e:
                print(f"Error processing screenshot {
                      i} in {directory_path}: {str(e)}")
                success = False
        else:
            print(f"Warning: Screenshot {i} not found in {directory_path}!")
            success = False

    return success


def process_dataset(dataset_path):
    """Process all episode directories in the dataset."""
    successful_episodes = 0
    failed_episodes = 0

    # Walk through all subdirectories
    for entry in os.listdir(dataset_path):
        episode_dir = os.path.join(dataset_path, entry)

        # Skip if not a directory or doesn't start with "episode_"
        if not os.path.isdir(episode_dir) or not entry.startswith("episode_"):
            continue

        print(f"\nProcessing {entry}...")
        if process_directory(episode_dir):
            successful_episodes += 1
        else:
            failed_episodes += 1

    # Print summary
    print(f"\nProcessing complete!")
    print(f"Successfully processed: {successful_episodes} episodes")
    print(f"Failed to process: {failed_episodes} episodes")


def main():
    if len(sys.argv) != 2:
        print("Usage: python script.py <dataset_path>")
        sys.exit(1)

    dataset_path = sys.argv[1]
    if not os.path.exists(dataset_path):
        print(f"Error: Directory {dataset_path} does not exist!")
        sys.exit(1)

    process_dataset(dataset_path)


if __name__ == "__main__":
    main()
