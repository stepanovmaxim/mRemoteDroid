# FreeRDP's native code (JNI) looks up and calls Java classes, methods and fields
# by name (e.g. LibFreeRDP.OnGraphicsUpdate, OnConnectionSuccess, ...). R8 must not
# rename or remove anything it reaches that way, so keep the whole FreeRDP package.
-keep class com.freerdp.freerdpcore.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
