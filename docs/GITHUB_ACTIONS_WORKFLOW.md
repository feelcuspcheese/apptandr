Purpose: CI/CD pipeline specification.

# GitHub Actions Workflow

Create `.github/workflows/build-and-release.yml`:

```yaml
name: Build and Release Android App

on:
  push:
    tags:
      - 'v*'
  workflow_dispatch:
    inputs:
      tag_name:
        description: 'Tag name for the release (e.g., v1.0.0)'
        required: true
        type: string

jobs:
  build:
    runs-on: ubuntu-latest

    steps:
      - name: Checkout code
        uses: actions/checkout@v4

      - name: Set up Go
        uses: actions/setup-go@v5
        with:
          go-version: '1.25'

      - name: Set up Java
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '17'

      - name: Install gomobile
        run: |
          go install golang.org/x/mobile/cmd/gomobile@latest
          gomobile init

      - name: Build Go AAR
        run: |
          mkdir -p android-app/libs
          go mod download
          gomobile bind -target=android -androidapi 23 -o android-app/libs/booking.aar ./mobile
        env:
          GO111MODULE: on

      - name: Build Android APK
        run: |
          cd android-app
          ./gradlew assembleRelease

      - name: Create Release
        uses: softprops/action-gh-release@v1
        with:
          tag_name: ${{ github.event.inputs.tag_name || github.ref_name }}
          files: |
            android-app/app/build/outputs/apk/release/*.apk
            android-app/libs/booking.aar
          generate_release_notes: true
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

No secrets required - the workflow builds an unsigned APK. The gradlew script must be present in the `android-app/` directory and executable. The Go AAR is built first and placed in `android-app/libs/` where the Android build expects it (as per TECHNICAL_SPEC.md section 8, the dependency is `$rootDir/libs/booking.aar`).


## Supported Android Versions and Architectures

The app is configured to support:

- **Minimum SDK**: 26 (Android 8.0 Oreo) - as per TECHNICAL_SPEC.md section 1
- **Target SDK**: 34 (Android 14) - as per TECHNICAL_SPEC.md section 11
- **Supported CPU Architectures**: 
  - `armeabi-v7a` (32-bit ARM)
  - `arm64-v8a` (64-bit ARM)
  - `x86` (32-bit Intel)
  - `x86_64` (64-bit Intel)

This ensures the generated universal APK can run on a wide range of Android devices from Android 8.0 up to Android 14. The build targets all major architectures to ensure compatibility with both phones and emulators.

### Architecture Configuration

The `build.gradle.kts` file includes NDK ABI filters to bundle native libraries for all supported architectures per TECHNICAL_SPEC.md section 1:

```kotlin
ndk {
    abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
}
```

This produces a universal APK that supports both ARM-based phones and x86-based emulators.

### Native Library Compatibility Requirements

For the APK to install and run correctly with the Go agent's native libraries, the following requirements must be met:

#### 1. Native Library Extraction

The `AndroidManifest.xml` must explicitly set `android:extractNativeLibs="true"` to allow Go's cgo runtime to properly load native libraries (as updated in the manifest):

```xml
<application
    android:extractNativeLibs="true"
    ... >
```

Additionally, `build.gradle.kts` must enable legacy packaging (as updated per TECHNICAL_SPEC.md section 11):

```kotlin
packaging {
    jniLibs {
        useLegacyPackaging = true
    }
}
```

#### 2. APK Signature Schemes

For Android compatibility, the APK must be signed with signature schemes v2, v3, and v4. The default Android Gradle Plugin 8.2+ handles this automatically when signing configs are properly configured.


## Tag Requirement for Releases

The `softprops/action-gh-release@v1` action requires a tag to create a GitHub release. The workflow handles this in two ways:

1. **Push to tag**: When you push a tag matching `v*` (e.g., `v1.0.0`), the workflow automatically uses that tag (`github.ref_name`) for the release.

2. **Manual dispatch**: When manually triggering the workflow via "Run workflow" in GitHub Actions, you must provide a `tag_name` input (e.g., `v1.0.0`). This tag will be used for the release.

**Important**: If running the workflow manually without providing a tag name, the release will fail with the error "GitHub Releases requires a tag".


Required Files for Successful Build:
android-app/gradle.properties 
# Project-wide Gradle settings.
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
android.enableJetifier=true
kotlin.code.style=official
android.nonTransitiveRClass=true


android-app/gradlew - Must be executable 
android-app/gradle/wrapper/gradle-wrapper.jar - Must exist
android-app/app/build.gradle.kts - should have AndroidX dependencies
.github/workflows/build-and-release.yml 
