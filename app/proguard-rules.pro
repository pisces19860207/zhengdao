# 证道 R8 规则（独立编写）。
# okhttp / commons-compress / zstd-jni 的 AAR 自带 consumer 规则，无需重复配置。

# JNI 静态绑定：pty.c 按 Java_com_example_zhengdao_terminal_Pty_nativeXxx 符号
# 查找函数，类名与方法名不可混淆（否则运行时 UnsatisfiedLinkError）。
-keep class com.example.zhengdao.terminal.Pty {
    native <methods>;
}

# WebView JS 桥：xterm.js 页面按方法名调用 Android 侧接口。
-keepclassmembers class com.example.zhengdao.terminal.TerminalBridge {
    @android.webkit.JavascriptInterface <methods>;
}
