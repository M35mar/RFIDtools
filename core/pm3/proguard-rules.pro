# R8 keep rules for RFIDtools.
#
# The app talks to two runtimes that resolve classes by name at runtime: the
# embedded Proxmark3 client (JNI) and the Chaquopy Python interpreter. Anything
# they reach through reflection or jclass lookups must survive shrinking.

# JNI entry points of the embedded client.
-keep class com.farmacia.pm3android.pm3.Pm3Native { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Called from Python: jclass("com.farmacia.pm3android.pm3.Pm3PythonBridge").
-keep class com.farmacia.pm3android.pm3.Pm3PythonBridge { *; }
-keep class com.farmacia.pm3android.pm3.Pm3PythonRunner { *; }
-keep class com.farmacia.pm3android.pm3.Pm3StreamSink { *; }

# Chaquopy runtime and the generated Python proxies.
-keep class com.chaquo.python.** { *; }
-dontwarn com.chaquo.python.**

# Keep line numbers so a crash report stays readable without shipping the
# full debug information.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
