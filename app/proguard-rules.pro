# App code needs no extra rules: Room, Compose, Navigation and Biometric ship their
# own consumer rules; FreeRDP's JNI rules come from :freeRDPCore (consumer-rules.pro).

# Keep line numbers so crash reports stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# JSch picks its ciphers, key exchanges and signatures by class name (JSch.getConfig).
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.newsclub.net.unix.**
-dontwarn com.sun.jna.**
-dontwarn javax.naming.**
-dontwarn org.ietf.jgss.**
