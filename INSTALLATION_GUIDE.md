# Fixing INSTALL_PARSE_FAILED_NO_CERTIFICATES Error

## Problem
The error `INSTALL_PARSE_FAILED_NO_CERTIFICATES: Failed to collect certificates from ../base.apk` occurs when trying to install an **unsigned** APK. Android requires all APKs to be signed before installation.

## Root Cause
Your current GitHub Actions workflow builds a release APK without signing it, resulting in an unsigned APK that cannot be installed on devices.

## Solution

### Option 1: For CI/CD (GitHub Actions) - Recommended for Production

The GitHub Actions workflow has been updated to automatically sign the APK during build.

#### Step 1: Add Secrets to Your GitHub Repository
Go to your repository settings → Secrets and variables → Actions, and add these secrets:

| Secret Name | Description | Example Value |
|-------------|-------------|---------------|
| `RELEASE_KEY_ALIAS` | Keystore key alias | `booking-key` |
| `RELEASE_STORE_PASSWORD` | Keystore password | `your_secure_password` |
| `RELEASE_KEY_PASSWORD` | Key password | `your_key_password` |

#### Step 2: Push Changes
When you push to main or create a tag, GitHub Actions will:
1. Generate a keystore with your credentials
2. Sign the APK properly
3. Create a release with the signed APK

### Option 2: For Local Development

#### Step 1: Generate a Keystore
```bash
cd android-app
keytool -genkey -v -keystore release-keystore.jks \
  -alias booking-key \
  -keyalg RSA -keysize 2048 -validity 10000
```

Follow the prompts to set passwords.

#### Step 2: Update keystore.properties
Edit `android-app/keystore.properties` with your actual values:
```properties
RELEASE_STORE_FILE=release-keystore.jks
RELEASE_STORE_PASSWORD=your_actual_store_password
RELEASE_KEY_ALIAS=booking-key
RELEASE_KEY_PASSWORD=your_actual_key_password
```

#### Step 3: Build Signed APK
```bash
cd android-app
./gradlew assembleRelease
```

#### Step 4: Install the APK
The signed APK will be at:
```
android-app/app/build/outputs/apk/release/app-release.apk
```

Install using:
```bash
adb install android-app/app/build/outputs/apk/release/app-release.apk
```

## Important Notes

1. **Do NOT use unsigned APKs**: Android does not allow installation of unsigned APKs. Never try to make the APK unsigned as a solution.

2. **Debug vs Release**: 
   - Debug builds are automatically signed with a debug key (can be installed via `./gradlew assembleDebug`)
   - Release builds MUST be signed with your own keystore

3. **Keystore Security**: 
   - Keep your keystore file secure
   - Never commit the keystore file to Git
   - Store passwords in environment variables or secrets manager

4. **For Testing Only**: If you just need to test quickly, use debug builds:
   ```bash
   ./gradlew assembleDebug
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

## Verification

To verify if an APK is signed:
```bash
jarsigner -verify -verbose -certs your-app.apk
```

Look for "jar verified" message. If it says "jar is unsigned", the APK cannot be installed.
