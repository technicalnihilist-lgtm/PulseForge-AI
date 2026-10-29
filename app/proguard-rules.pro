# PulseForge AI - R8 configuration.
#
# The analyser works on files and reflection-free data classes; the only library needing special
# handling is apksig, which is used for signature verification and is optional at runtime
# (failures are caught and reported).

-keepattributes *Annotation*, InnerClasses, Signature
-keep,includedescriptorclasses class com.pulseforge.**$$serializer { *; }
-keepclassmembers class com.pulseforge.** { *** Companion; }
-keepclasseswithmembers class com.pulseforge.** { kotlinx.serialization.KSerializer serializer(...); }
-keep class kotlinx.serialization.** { *; }

-dontwarn com.android.apksig.**
-keep class com.android.apksig.** { *; }

-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
