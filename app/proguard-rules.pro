# ---- kotlinx.serialization ----
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.vplayer.app.**$$serializer { *; }
-keepclassmembers class com.vplayer.app.** { *** Companion; }
-keepclasseswithmembers class com.vplayer.app.** { kotlinx.serialization.KSerializer serializer(...); }

# ---- 数据模型 / Room ----
-keep class com.vplayer.app.data.model.** { *; }
-keep class com.vplayer.app.data.db.** { *; }

# ---- OkHttp ----
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---- Media3 ----
-dontwarn androidx.media3.**

# ---- WebView JS 接口（嗅探） ----
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ---- IJK 内核（native 代码按名字回调 Java，不能混淆 / 删除） ----
-keep class tv.danmaku.ijk.media.player.** { *; }
-dontwarn tv.danmaku.ijk.media.player.**
