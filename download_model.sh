#!/bin/bash
#
# BlindPath YOLOv8n TFLite Model Downloader (Shell Version)
#
# This script downloads the YOLOv8n TFLite model for obstacle detection.
#
# Usage:
#   ./download_model.sh [--output OUTPUT_PATH] [--force]
#
# Options:
#   --output    Output directory path (default: app/src/main/assets)
#   --force     Force download even if model exists
#

set -e

# Configuration
MODEL_NAME="yolov8n.tflite"
MODEL_URL="https://github.com/ultralytics/assets/releases/download/v8.2.0/yolov8n.tflite"
BACKUP_URL="https://huggingface.co/Ultralytics/YOLOv8/resolve/main/yolov8n.tflite"
EXPECTED_SIZE_MB=6

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Get script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEFAULT_OUTPUT_DIR="$SCRIPT_DIR/app/src/main/assets"

# Parse arguments
OUTPUT_DIR=""
FORCE=false

while [[ $# -gt 0 ]]; do
    case $1 in
        --output)
            OUTPUT_DIR="$2"
            shift 2
            ;;
        --force)
            FORCE=true
            shift
            ;;
        --help)
            echo "BlindPath YOLOv8n Model Downloader"
            echo ""
            echo "Usage: $0 [OPTIONS]"
            echo ""
            echo "Options:"
            echo "  --output DIR   Output directory (default: app/src/main/assets)"
            echo "  --force        Force download even if model exists"
            echo "  --help         Show this help message"
            exit 0
            ;;
        *)
            echo -e "${RED}Unknown option: $1${NC}"
            exit 1
            ;;
    esac
done

# Set output path
OUTPUT_DIR="${OUTPUT_DIR:-$DEFAULT_OUTPUT_DIR}"
OUTPUT_PATH="$OUTPUT_DIR/$MODEL_NAME"

echo "============================================================"
echo "BlindPath YOLOv8n Model Downloader"
echo "============================================================"
echo -e "Output path: ${BLUE}$OUTPUT_PATH${NC}"
echo ""

# Create output directory if needed
mkdir -p "$OUTPUT_DIR"

# Check if model already exists
if [[ -f "$OUTPUT_PATH" && "$FORCE" == false ]]; then
    echo -e "${YELLOW}Model already exists at: $OUTPUT_PATH${NC}"
    
    # Verify file size (rough check)
    ACTUAL_SIZE_MB=$(du -m "$OUTPUT_PATH" | cut -f1)
    if [[ $ACTUAL_SIZE_MB -ge $((EXPECTED_SIZE_MB - 1)) ]]; then
        echo -e "${GREEN}Model appears valid (~${ACTUAL_SIZE_MB}MB). Use --force to re-download.${NC}"
        exit 0
    else
        echo -e "${YELLOW}Existing model appears corrupted (~${ACTUAL_SIZE_MB}MB). Re-downloading...${NC}"
    fi
fi

# Function to download file with progress
download_with_progress() {
    local url="$1"
    local output="$2"
    
    if command -v wget &> /dev/null; then
        echo -e "${BLUE}Downloading with wget...${NC}"
        wget --progress=bar:force -O "$output" "$url" 2>&1 || return 1
    elif command -v curl &> /dev/null; then
        echo -e "${BLUE}Downloading with curl...${NC}"
        curl -L --progress-bar -o "$output" "$url" || return 1
    else
        echo -e "${RED}Error: Neither wget nor curl is available${NC}"
        return 1
    fi
    return 0
}

# Try primary URL
echo -e "\n[1/2] Trying primary download source..."
echo -e "URL: $MODEL_URL"

if download_with_progress "$MODEL_URL" "$OUTPUT_PATH"; then
    echo -e "${GREEN}Download successful!${NC}"
else
    echo -e "${YELLOW}Primary download failed, trying backup source...${NC}"
    echo -e "URL: $BACKUP_URL"
    
    if download_with_progress "$BACKUP_URL" "$OUTPUT_PATH"; then
        echo -e "${GREEN}Download successful from backup!${NC}"
    else
        echo -e "${RED}============================================================"
        echo "ERROR: Failed to download model from all sources"
        echo "============================================================${NC}"
        echo ""
        echo "Manual download instructions:"
        echo "  1. Visit: $MODEL_URL"
        echo "  2. Download: $MODEL_NAME"
        echo "  3. Place at: $OUTPUT_PATH"
        exit 1
    fi
fi

# Verify the downloaded model
echo ""
echo "Verifying model..."
ACTUAL_SIZE_MB=$(du -m "$OUTPUT_PATH" | cut -f1)

if [[ $ACTUAL_SIZE_MB -ge $((EXPECTED_SIZE_MB - 1)) ]]; then
    echo -e "${GREEN}Model verified: $MODEL_NAME (~${ACTUAL_SIZE_MB}MB)${NC}"
else
    echo -e "${YELLOW}Warning: Model size smaller than expected (~${ACTUAL_SIZE_MB}MB)${NC}"
    echo "The model may still work, but consider re-downloading"
fi

echo ""
echo -e "${GREEN}============================================================"
echo "SUCCESS: Model downloaded successfully!"
echo "============================================================${NC}"
echo -e "Location: ${BLUE}$OUTPUT_PATH${NC}"
echo -e "Size: ~${ACTUAL_SIZE_MB} MB"
echo ""
echo "You can now build the APK with the AI model included."

exit 0
