# Build Fixes Log

This document tracks all build fixes applied to the project for future validation and reference.

## Fix 1: Android Resource Linking Error - Missing Theme.BookingBot

**Date:** Applied during CI build fix

**Issue:**
```
ERROR: /home/runner/work/apptandr/apptandr/android-app/app/src/main/AndroidManifest.xml:14:5-74:19: AAPT: error: resource style/Theme.BookingBot (aka com.booking.bot:style/Theme.BookingBot) not found.
ERROR: /home/runner/work/apptandr/apptandr/android-app/app/src/main/AndroidManifest.xml:25:9-33:20: AAPT: error: resource style/Theme.BookingBot (aka com.booking.bot:style/Theme.BookingBot) not found.
```

**Root Cause:**
The `AndroidManifest.xml` referenced a theme `@style/Theme.BookingBot` that was not defined in any of the resource files. The `themes.xml` file only contained `Theme.AppointmentAgent`.

**Solution:**
1. Added the missing `Theme.BookingBot` style definition to `/workspace/android-app/app/src/main/res/values/themes.xml`:
   ```xml
   <style name="Theme.BookingBot" parent="android:Theme.Material.Light.NoActionBar">
       <item name="android:statusBarColor">@color/purple_700</item>
   </style>
   ```

2. Removed the deprecated `android:extractNativeLibs="true"` attribute from the `<application>` tag in `AndroidManifest.xml` as per AGP warnings. This attribute should not be specified in the AndroidManifest.xml file.

**Files Modified:**
- `android-app/app/src/main/res/values/themes.xml` - Added Theme.BookingBot style
- `android-app/app/src/main/AndroidManifest.xml` - Removed extractNativeLibs attribute

**Verification:**
The build should now proceed past the `processReleaseResources` task without the AAPT resource linking errors.

---

## Notes for Future Reference

- When adding new themes to the AndroidManifest.xml, ensure they are properly defined in the `res/values/themes.xml` file.
- The `android:extractNativeLibs` attribute should be configured in the build.gradle file, not in the AndroidManifest.xml, as per modern AGP guidelines.
