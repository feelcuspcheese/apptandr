#!/bin/bash
# Build Go AAR for Android wrapper
# This script builds the Go mobile library for the booking agent

set -e

echo "Building Go AAR for Android..."

# Check if go-agent directory exists
if [ ! -d "go-agent" ]; then
    echo "Error: go-agent directory not found"
    echo "The Go agent code should be in a 'go-agent' subdirectory with a 'mobile' package"
    exit 1
fi

# Navigate to go-agent directory
cd go-agent

# Download dependencies
echo "Downloading Go modules..."
go mod download

# Initialize gomobile if not already done
echo "Initializing gomobile..."
gomobile init

# Build the AAR
echo "Building booking.aar..."
gomobile bind -target=android -o ../libs/booking.aar -androidapi 21 ./mobile

echo "Build complete! AAR file created at libs/booking.aar"
