# Keep the UniFFI-scaffolding classes; they are looked up reflectively at runtime.
-keep class app.fusion.wallet.core.** { *; }
-keepclassmembers class app.fusion.wallet.core.** { *; }

# JNA
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }

-dontwarn java.awt.**
-dontwarn com.sun.jna.**