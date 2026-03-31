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
          files: |
            android-app/app/build/outputs/apk/release/*.apk
            android-app/app/libs/booking.aar
          generate_release_notes: true
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

No secrets required - the workflow builds an unsigned APK. The gradlew script must be present in the `android-app/` directory and executable. The Go AAR is built first and placed in `android-app/app/libs/` where the Android build expects it.
