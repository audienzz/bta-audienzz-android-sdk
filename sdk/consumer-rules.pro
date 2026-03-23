# Keep @JavascriptInterface annotated methods so ProGuard does not strip them.
-keepclassmembers class org.audienzz.bta.sdk.BtaJsBridge {
    @android.webkit.JavascriptInterface <methods>;
}
