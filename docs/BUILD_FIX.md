1	# Build Fix Documentation
     2	
     3	## Issue: Experimental Material3 API Compilation Errors
     4	
     5	### Problem
     6	The build failed with compilation errors in `ScheduleScreen.kt` and `UserConfigScreen.kt`:
     7	```
     8	This material API is experimental and is likely to change or to be removed in the future.
     9	```
    10	
    11	The affected APIs were:
    12	- `ExposedDropdownMenuBox` (ScheduleScreen.kt lines 40, 78, 112)
    13	- `ExposedDropdownMenuDefaults.TrailingIcon` (ScheduleScreen.kt lines 49, 87, 121)
    14	- `ExposedDropdownMenu` (ScheduleScreen.kt lines 52, 90, 124)
    15	- `FlowRow` (UserConfigScreen.kt line 88)
    16	- `FilterChip` (UserConfigScreen.kt line 94)
    17	
    18	### Root Cause
    19	Material3 components like `ExposedDropdownMenuBox`, `FlowRow`, and `FilterChip` are marked as experimental APIs in the current version of the Material3 library. Kotlin compiler treats these as errors by default when used without proper opt-in annotation.
    20	
    21	### Solution
    22	Added `@OptIn(ExperimentalMaterial3Api::class)` and `@OptIn(ExperimentalFoundationApi::class)` annotations to the composables that use these experimental APIs:
    23	
    24	**ScheduleScreen.kt:**
    25	```kotlin
    26	@OptIn(ExperimentalMaterial3Api::class)
    27	@Composable
    28	fun ScheduleScreen() {
    29	    // ... uses ExposedDropdownMenuBox, ExposedDropdownMenu
    30	}
    31	```
    32	
    33	**UserConfigScreen.kt:**
    34	```kotlin
    35	@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
    36	@Composable
    37	fun UserConfigScreen() {
    38	    // ... uses FlowRow (requires ExperimentalFoundationApi), FilterChip (requires ExperimentalMaterial3Api)
    39	}
    40	```
    41	
    42	Note: `FlowRow` is from `androidx.compose.foundation.layout` and requires `@OptIn(ExperimentalFoundationApi::class)`, while `FilterChip` requires `@OptIn(ExperimentalMaterial3Api::class)`.
    43	
    44	### Design Alignment
    45	This fix aligns with TECHNICAL_SPEC.md section 7 which specifies Jetpack Compose for UI. The experimental APIs used are stable enough for production use and are the recommended way to implement dropdown menus and filter chips in Material3.
    46	
    47	### Files Modified
    48	1. `/workspace/android-app/app/src/main/java/com/apptcheck/agent/ui/screens/ScheduleScreen.kt`
    49	   - Added `@OptIn(ExperimentalMaterial3Api::class)` annotation
    50	
    51	2. `/workspace/android-app/app/src/main/java/com/apptcheck/agent/ui/screens/UserConfigScreen.kt`
    52	   - Added `@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)` annotation
    53	   - Added import for `androidx.compose.foundation.ExperimentalFoundationApi`
    54	
    55	3. `/workspace/android-app/app/src/main/java/com/apptcheck/agent/model/Defaults.kt`
    56	   - Created supporting model file referenced by UserConfigScreen (contains constants like CHECK_WINDOW, CHECK_INTERVAL, etc.)
    57	
    58	### Centralization Maintained
    59	- No changes to central configuration management
    60	- No changes to logging system
    61	- Defaults remain in a single source (`Defaults.kt`)
    62	
    63	### Testing
    64	After this fix, the build should pass the Kotlin compilation step. Manual testing should verify:
    65	1. Dropdown menus work correctly in ScheduleScreen
    66	2. Filter chips work correctly in UserConfigScreen
    67	3. All UI interactions function as documented in TECHNICAL_SPEC.md section 7
