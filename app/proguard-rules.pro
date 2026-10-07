# 证道 R8 规则（独立编写）。
# okhttp / commons-compress / zstd-jni 的 AAR 自带 consumer 规则，无需重复配置。

# JNI 静态绑定：libsha256poc.so（rust/sha256poc）按
# Java_com_example_zhengdao_rust_Sha256Native_nativeSha256Hex 符号查找函数，
# 类名与方法名不可混淆（否则运行时 UnsatisfiedLinkError）。
-keep class com.example.zhengdao.rust.Sha256Native {
    native <methods>;
}

# WebView JS 桥：xterm.js 页面按方法名调用 Android 侧接口。
-keepclassmembers class com.example.zhengdao.terminal.TerminalBridge {
    @android.webkit.JavascriptInterface <methods>;
}
