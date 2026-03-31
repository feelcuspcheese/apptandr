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
          mkdir -p android-app/app/libs
          go mod download
          gomobile bind -target=android -o android-app/app/libs/booking.aar -androidapi 21 ./mobile
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
            android-app/app/libs/booking.aar
          generate_release_notes: true
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

No secrets required - the workflow builds an unsigned APK. The gradlew script must be present in the `android-app/` directory and executable. The Go AAR is built first and placed in `android-app/app/libs/` where the Android build expects it.


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
