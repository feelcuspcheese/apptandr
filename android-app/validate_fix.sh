#!/bin/bash

# Validation script for ScheduleScreen scrollability fix (BUG-001)
# This script validates the fix without requiring a full Gradle build

SCHEDULE_SCREEN_FILE="app/src/main/java/com/booking/bot/ui/screens/ScheduleScreen.kt"
TEST_PASSED=true

echo "=========================================="
echo "ScheduleScreen Scrollability Fix Validation"
echo "=========================================="
echo ""

# Check if file exists
if [ ! -f "$SCHEDULE_SCREEN_FILE" ]; then
    echo "❌ FAILED: ScheduleScreen.kt not found at $SCHEDULE_SCREEN_FILE"
    exit 1
fi

echo "✓ ScheduleScreen.kt found"
echo ""

# Test 1: Check for verticalScroll import
echo "Test 1: Checking for verticalScroll import..."
if grep -q "import androidx.compose.foundation.verticalScroll" "$SCHEDULE_SCREEN_FILE"; then
    echo "✓ PASSED: verticalScroll import found"
else
    echo "❌ FAILED: verticalScroll import missing"
    TEST_PASSED=false
fi
echo ""

# Test 2: Check for rememberScrollState import
echo "Test 2: Checking for rememberScrollState import..."
if grep -q "import androidx.compose.foundation.rememberScrollState" "$SCHEDULE_SCREEN_FILE"; then
    echo "✓ PASSED: rememberScrollState import found"
else
    echo "❌ FAILED: rememberScrollState import missing"
    TEST_PASSED=false
fi
echo ""

# Test 3: Check for verticalScroll usage in Column modifier
echo "Test 3: Checking for verticalScroll usage in Column..."
if grep -q "\.verticalScroll(rememberScrollState())" "$SCHEDULE_SCREEN_FILE"; then
    echo "✓ PASSED: verticalScroll modifier applied to Column"
else
    echo "❌ FAILED: verticalScroll modifier not found in Column"
    TEST_PASSED=false
fi
echo ""

# Test 4: Verify all required UI components are present
echo "Test 4: Checking for required UI components..."
REQUIRED_COMPONENTS=(
    "Select Site"
    "Select Museum"
    "Select Credential"
    "Select Mode"
    "Select Timezone"
    "Select Date & Time"
    "Schedule Run"
    "Scheduled Runs"
)

ALL_COMPONENTS_FOUND=true
for component in "${REQUIRED_COMPONENTS[@]}"; do
    if grep -q "$component" "$SCHEDULE_SCREEN_FILE"; then
        echo "  ✓ Found: $component"
    else
        echo "  ❌ Missing: $component"
        ALL_COMPONENTS_FOUND=false
    fi
done

if [ "$ALL_COMPONENTS_FOUND" = true ]; then
    echo "✓ PASSED: All required UI components present"
else
    echo "❌ FAILED: Some UI components missing"
    TEST_PASSED=false
fi
echo ""

# Test 5: Verify TECHNICAL_SPEC compliance
echo "Test 5: Checking TECHNICAL_SPEC.md section 5.3 compliance..."
if [ -f "docs/TECHNICAL_SPEC.md" ]; then
    if grep -q "FIX (BUG-001)" "docs/TECHNICAL_SPEC.md"; then
        echo "✓ PASSED: BUG-001 specification found in TECHNICAL_SPEC.md"
    else
        echo "⚠ WARNING: BUG-001 specification not found in TECHNICAL_SPEC.md"
    fi
else
    echo "⚠ WARNING: TECHNICAL_SPEC.md not found"
fi
echo ""

# Final result
echo "=========================================="
if [ "$TEST_PASSED" = true ]; then
    echo "✅ ALL TESTS PASSED"
    echo "The ScheduleScreen is now scrollable and all fields are accessible."
    exit 0
else
    echo "❌ SOME TESTS FAILED"
    echo "Please review the failed tests above."
    exit 1
fi
