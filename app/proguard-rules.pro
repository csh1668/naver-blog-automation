# WebView JS bridge: keep annotated methods
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
