#!/usr/bin/env python3
"""
BlindPath YOLOv8n TFLite Model Downloader

This script downloads the YOLOv8n TFLite model for obstacle detection.

Usage:
    python download_model.py [--output OUTPUT_PATH]

Options:
    --output    Output directory path (default: app/src/main/assets)
    --force     Force download even if model exists
"""

import argparse
import os
import sys
import hashlib
import urllib.request
import urllib.error
from pathlib import Path


# Model configuration
MODEL_CONFIG = {
    "name": "yolov8n.tflite",
    "url": "https://github.com/ultralytics/assets/releases/download/v8.2.0/yolov8n.tflite",
    "backup_url": "https://huggingface.co/Ultralytics/YOLOv8/resolve/main/yolov8n.tflite",
    "expected_size": 6_297_376,  # ~6MB
    "sha256": None,  # Optional: add SHA256 for verification
}


def get_script_dir() -> Path:
    """Get the directory where this script is located."""
    return Path(__file__).parent.resolve()


def calculate_sha256(file_path: Path) -> str:
    """Calculate SHA256 hash of a file."""
    sha256_hash = hashlib.sha256()
    with open(file_path, "rb") as f:
        for chunk in iter(lambda: f.read(8192), b""):
            sha256_hash.update(chunk)
    return sha256_hash.hexdigest()


def download_file(url: str, output_path: Path, description: str = "Downloading") -> bool:
    """Download a file with progress indicator."""
    try:
        print(f"{description} from: {url}")
        
        # Create parent directories if needed
        output_path.parent.mkdir(parents=True, exist_ok=True)
        
        # Download with progress
        def report_progress(block_num, block_size, total_size):
            if total_size > 0:
                downloaded = block_num * block_size
                percent = min(100, downloaded * 100 // total_size)
                mb_downloaded = downloaded / (1024 * 1024)
                mb_total = total_size / (1024 * 1024)
                print(f"\r  Progress: {percent}% ({mb_downloaded:.1f}MB / {mb_total:.1f}MB)", end="", flush=True)
        
        urllib.request.urlretrieve(url, output_path, reporthook=report_progress)
        print()  # New line after progress
        
        return True
        
    except urllib.error.URLError as e:
        print(f"\n  Error downloading: {e.reason}")
        return False
    except Exception as e:
        print(f"\n  Unexpected error: {e}")
        return False


def verify_model(file_path: Path) -> bool:
    """Verify the downloaded model file."""
    if not file_path.exists():
        print("  Model file not found")
        return False
    
    actual_size = file_path.stat().st_size
    expected_size = MODEL_CONFIG["expected_size"]
    
    # Allow 10% tolerance in size
    size_ratio = actual_size / expected_size
    if size_ratio < 0.9 or size_ratio > 1.1:
        print(f"  Warning: Model size mismatch. Expected ~{expected_size / 1024 / 1024:.1f}MB, got {actual_size / 1024 / 1024:.1f}MB")
        return False
    
    # Verify SHA256 if configured
    if MODEL_CONFIG["sha256"]:
        actual_sha256 = calculate_sha256(file_path)
        if actual_sha256 != MODEL_CONFIG["sha256"]:
            print(f"  Warning: SHA256 mismatch")
            return False
    
    print(f"  Model verified: {file_path.name} ({actual_size / 1024 / 1024:.2f} MB)")
    return True


def main():
    parser = argparse.ArgumentParser(description="Download YOLOv8n TFLite model for BlindPath")
    parser.add_argument(
        "--output",
        type=str,
        default=None,
        help="Output directory path (default: app/src/main/assets)"
    )
    parser.add_argument(
        "--force",
        action="store_true",
        help="Force download even if model exists"
    )
    
    args = parser.parse_args()
    
    # Determine output path
    script_dir = get_script_dir()
    output_dir = Path(args.output) if args.output else script_dir / "app" / "src" / "main" / "assets"
    output_path = output_dir / MODEL_CONFIG["name"]
    
    print("=" * 60)
    print("BlindPath YOLOv8n Model Downloader")
    print("=" * 60)
    print(f"Output path: {output_path}")
    print()
    
    # Check if model already exists
    if output_path.exists() and not args.force:
        print(f"Model already exists at: {output_path}")
        if verify_model(output_path):
            print("Model is valid. Use --force to re-download.")
            return 0
        else:
            print("Existing model appears corrupted. Re-downloading...")
    
    # Try primary URL
    print("\n[1/2] Trying primary download source...")
    success = download_file(MODEL_CONFIG["url"], output_path, "Downloading model")
    
    # Try backup URL if primary failed
    if not success and MODEL_CONFIG["backup_url"]:
        print("\n[2/2] Trying backup download source...")
        success = download_file(MODEL_CONFIG["backup_url"], output_path, "Downloading model (backup)")
    
    if not success:
        print("\n" + "=" * 60)
        print("ERROR: Failed to download model from all sources")
        print("=" * 60)
        print("\nManual download instructions:")
        print(f"  1. Visit: {MODEL_CONFIG['url']}")
        print(f"  2. Download: {MODEL_CONFIG['name']}")
        print(f"  3. Place at: {output_path}")
        return 1
    
    # Verify the downloaded model
    print("\nVerifying model...")
    if not verify_model(output_path):
        print("Warning: Model verification failed, but file was downloaded")
        print("The model may still work, but consider re-downloading")
    
    print("\n" + "=" * 60)
    print("SUCCESS: Model downloaded successfully!")
    print("=" * 60)
    print(f"Location: {output_path}")
    print(f"Size: {output_path.stat().st_size / 1024 / 1024:.2f} MB")
    print("\nYou can now build the APK with the AI model included.")
    
    return 0


if __name__ == "__main__":
    sys.exit(main())
