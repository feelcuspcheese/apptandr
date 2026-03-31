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
          go-version: '1.21'

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
          cd go-agent
          go mod download
          gomobile bind -target=android -o ../libs/booking.aar -androidapi 21 ./mobile
        env:
          GO111MODULE: on

      - name: Build Android APK
        run: ./gradlew assembleRelease

      - name: Sign APK
        uses: r0adkll/sign-android-release@v1
        id: sign_app
        with:
          releaseDirectory: app/build/outputs/apk/release
          signingKeyBase64: ${{ secrets.SIGNING_KEY }}
          alias: ${{ secrets.ALIAS }}
          keyStorePassword: ${{ secrets.KEY_STORE_PASSWORD }}
          keyPassword: ${{ secrets.KEY_PASSWORD }}

      - name: Create Release
        uses: softprops/action-gh-release@v1
        with:
          files: |
            app/build/outputs/apk/release/*-unsigned.apk
            libs/booking.aar
          generate_release_notes: true
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}









Secrets required in repository:

SIGNING_KEY – base64‑encoded keystore

ALIAS – key alias

KEY_STORE_PASSWORD – keystore password

KEY_PASSWORD – key password
