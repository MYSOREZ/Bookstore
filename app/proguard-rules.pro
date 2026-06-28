-repackageclasses ''
-allowaccessmodification
-printmapping mapping.txt

-keepclasseswithmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-keep class androidx.room.** { *; }

-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class kotlinx.serialization.** { *; }

-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
}
