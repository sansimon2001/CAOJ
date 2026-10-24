-keep class com.example.mindball.** { *; }
-keepattributes *Annotation*
# OkHttp 反射相关（如需）
-dontwarn okhttp3.**
-dontwarn okio.**
