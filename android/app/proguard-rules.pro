# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class app.baseline.**$$serializer { *; }
-keepclassmembers class app.baseline.** { *** Companion; }
-keepclasseswithmembers class app.baseline.** { kotlinx.serialization.KSerializer serializer(...); }
# Tink
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
