# Build Fix Documentation

## Issue: Experimental Material3 API Compilation Errors

### Problem
The build failed with compilation errors in `ScheduleScreen.kt` and `UserConfigScreen.kt`:
```
This material API is experimental and is likely to change or to be removed in the future.
```

The affected APIs were:
- `ExposedDropdownMenuBox` (ScheduleScreen.kt lines 42, 80, 114)
- `ExposedDropdownMenuDefaults.TrailingIcon` (ScheduleScreen.kt lines 51, 89, 123)
- `ExposedDropdownMenu` (ScheduleScreen.kt lines 54, 92, 126)
- `FlowRow` (UserConfigScreen.kt line 92)
- `FilterChip` (UserConfigScreen.kt line 98)

### Root Cause
Material3 components like `ExposedDropdownMenuBox`, `FlowRow`, and `FilterChip` are marked as experimental APIs in the current version of the Material3 library. Kotlin compiler treats these as errors by default when used without proper opt-in annotation.

### Solution
Added compiler-level opt-in flags in the app-level `build.gradle.kts` file to suppress experimental API warnings globally for the entire project. This ensures all modules use the same configuration and avoids the need for repetitive `@OptIn` annotations on every composable.

**Updated `/workspace/android-app/app/build.gradle.kts`:**
```kotlin
kotlinOptions {
    jvmTarget = "17"
    freeCompilerArgs = listOf(
        "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
        "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
        "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi"
    )
}
```

This approach:
1. Centralizes the experimental API opt-in configuration in a single location
2. Applies to all Kotlin source files in the module
3. Aligns with the project's design principle of single configuration source
4. Eliminates the need for per-file `@OptIn` annotations while still acknowledging the experimental nature of these APIs

### Design Alignment
This fix aligns with:
- TECHNICAL_SPEC.md section 7 which specifies Jetpack Compose for UI
- The project's centralization principle (single config source)
- BUILD_FIX.md best practices for handling experimental Compose APIs

The experimental APIs used (`ExposedDropdownMenuBox`, `FlowRow`, `FilterChip`) are stable enough for production use and are the recommended way to implement dropdown menus and filter chips in Material3.

### Files Modified
1. `/workspace/android-app/app/build.gradle.kts`
   - Added `freeCompilerArgs` with opt-in flags for:
     - `ExperimentalMaterial3Api` (for `ExposedDropdownMenuBox`, `FilterChip`, etc.)
     - `ExperimentalFoundationApi` (for `FlowRow` from foundation)
     - `ExperimentalLayoutApi` (for `FlowRow` from layout)

2. `/workspace/android-app/app/src/main/java/com/apptcheck/agent/ui/screens/UserConfigScreen.kt`
   - Already has `@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)` annotation (defensive, can be removed if desired since gradle handles it)
   - Has import for `androidx.compose.foundation.ExperimentalFoundationApi`
   - Has import for `androidx.compose.foundation.layout.ExperimentalLayoutApi`

3. `/workspace/android-app/app/src/main/java/com/apptcheck/agent/ui/screens/ScheduleScreen.kt`
   - Already has `@OptIn(ExperimentalMaterial3Api::class)` annotation (defensive, can be removed if desired since gradle handles it)

### Centralization Maintained
- No changes to central configuration management
- No changes to logging system
- Defaults remain in a single source (`Defaults.kt`)
- Experimental API opt-in is now centralized in `build.gradle.kts`

### Testing
After this fix, the build should pass the Kotlin compilation step. Manual testing should verify:
1. Dropdown menus work correctly in ScheduleScreen
2. Filter chips and FlowRow work correctly in UserConfigScreen
3. All UI interactions function as documented in TECHNICAL_SPEC.md section 7
