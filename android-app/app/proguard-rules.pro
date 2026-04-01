# Following TECHNICAL_SPEC.md section 11.3
-keep class go.** { *; }
-keep class mobile.** { *; }
-keep class com.booking.bot.data.** { *; }
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
