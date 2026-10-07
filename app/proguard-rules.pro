# 证道 R8 规则（独立编写）。
# okhttp / commons-compress / zstd-jni 的 AAR 自带 consumer 规则，无需重复配置。

# JNI 静态绑定：libzhengdao_core.so（rust/core）按
# Java_com_example_zhengdao_rust_CoreNative_native{Sha256Hex,Extract} 符号查找函数，
# 类名与方法名不可混淆（否则运行时 UnsatisfiedLinkError）。
# v2.0 R1：原 Sha256Native/ExtractNative 已收编为单一 CoreNative。
# ⚠️ 2026-10-08 E-022 教训：这条规则以前只 keep 了 native 方法，
# Rust 侧**反向回调**的 CoreNative.onProgress(String,String) 被 R8 改了名，
# release 包一装环境就 SIGABRT（abort message: NoSuchMethodError:
# "Lcom/example/zhengdao/rust/CoreNative;.onProgress(Ljava/lang/String;Ljava/lang/String;)V"）。
# 凡是「被 native 按名字查到」的成员都必须显式 keep——R8 看不见 JNI 的调用点。
-keep class com.example.zhengdao.rust.CoreNative {
    native <methods>;
    public static void onProgress(java.lang.String, java.lang.String);
}

# WebView JS 桥：xterm.js 页面按方法名调用 Android 侧接口。
-keepclassmembers class com.example.zhengdao.terminal.TerminalBridge {
    @android.webkit.JavascriptInterface <methods>;
}
