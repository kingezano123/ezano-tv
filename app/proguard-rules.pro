# Keep app code intact (shrink libraries only) – safer for a build we can't test on-device.
-dontobfuscate
-keep class com.ezano.tv.** { *; }
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn okhttp3.internal.platform.**
