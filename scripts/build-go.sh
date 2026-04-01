#!/bin/bash
# Build Go AAR for Android wrapper
# This script builds the Go mobile library for the booking agent
# Following TECHNICAL_SPEC.md section 12

set -e

echo "Building Go AAR for Android..."

# Check if mobile directory exists (Go agent code is in workspace root 'mobile' package)
if [ ! -d "mobile" ]; then
    echo "Error: mobile directory not found"
    echo "The Go agent code should be in a 'mobile' subdirectory at the workspace root"
    exit 1
fi

# Download dependencies from workspace root
echo "Downloading Go modules..."
go mod download

# Initialize gomobile if not already done
echo "Initializing gomobile..."
gomobile init

# Create libs directory in android-app
mkdir -p android-app/libs

# Build the AAR - output to android-app/libs as per TECHNICAL_SPEC.md section 8
echo "Building booking.aar..."
gomobile bind -target=android -o android-app/libs/booking.aar -androidapi 23 ./mobile

echo "Build complete! AAR file created at android-app/libs/booking.aar"
