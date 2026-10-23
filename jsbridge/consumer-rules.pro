# Keep the methods exposed to JavaScript through addJavascriptInterface.
-keepclassmembers class me.xechoz.jsbridge.JsBridge$Host {
    @android.webkit.JavascriptInterface <methods>;
}
