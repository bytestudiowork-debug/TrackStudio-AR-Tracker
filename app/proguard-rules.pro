# Keep JNI entry points and Android components reachable by the framework.
-keepclasseswithmembers class * {
    native <methods>;
}
-keep class com.trackstudio.artracker.** { *; }
