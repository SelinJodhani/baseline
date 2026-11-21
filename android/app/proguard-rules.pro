# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class app.ojas.**$$serializer { *; }
-keepclassmembers class app.ojas.** { *** Companion; }
-keepclasseswithmembers class app.ojas.** { kotlinx.serialization.KSerializer serializer(...); }
# Tink
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
