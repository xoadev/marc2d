# R8 shrinks and renames the release build, but native code finds Java classes, fields and methods by their names.
# Without these, the release crashes where the debug build works: Vosk, through JNA, fails with
# "UnsatisfiedLinkError: Can't obtain peer field ID for class com.sun.jna.Pointer".

# JNA, the rules from its own documentation.
-dontwarn java.awt.*
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }

# Vosk calls into its library through JNA, by method name.
-keep class org.vosk.** { *; }

# Linphone's native core calls back into its Java classes.
-keep class org.linphone.** { *; }
