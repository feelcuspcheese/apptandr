# Following TECHNICAL_SPEC.md section 11.3
-keep class go.** { *; }
-keep class mobile.** { *; }
-keep class com.booking.bot.data.** { *; }
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}

# Android 16 compatibility: keep native library classes
-keep class * extends java.lang.Thread { *; }
-keepclassmembers class * extends java.lang.Thread {
    public void run();
}

# Keep foreground service
-keep class com.booking.bot.service.** { *; }
-keep class * extends android.app.Service { *; }

# Keep scheduler components
-keep class com.booking.bot.scheduler.** { *; }
-keep class * extends android.content.BroadcastReceiver { *; }

# Keep ViewModel and Lifecycle
-keep class * extends androidx.lifecycle.ViewModel { *; }
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# Keep Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Keep DataStore
-keep class androidx.datastore.preferences.** { *; }
-keep class androidx.datastore.core.** { *; }
