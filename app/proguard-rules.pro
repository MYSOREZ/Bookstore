# Агрессивная обфускация (убираем полное сохранение классов)
-repackageclasses ''
-allowaccessmodification
-printmapping mapping.txt

# Сохраняем только самое необходимое
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Сохраняем Room (если используется)
-keep class androidx.room.** { *; }

# Сохраняем OkHttp и Kotlin Serialization
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class kotlinx.serialization.** { *; }

# Убираем логи из релизной сборки (для защиты от отладки)
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
}
