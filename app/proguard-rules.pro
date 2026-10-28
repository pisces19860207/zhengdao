# 证道 R8 规则（独立编写）。
# okhttp 的 AAR 自带 consumer 规则，无需重复配置。
# （commons-compress / zstd-jni 已分别于第二百六十六批 / 2026-10-27 删依赖，规则随之无对象。）

# ── 死代码清单（第二百六十六批，2026-10-28）──────────────────────────────────
# `-printusage` 是**编译器替我们做了一遍可达性分析**：release 包（R8 开后）实际被裁掉的
# 类/成员逐条列出来。比「detekt ＋ grep」准得多 —— grep 只能看引用，看不到 R8 真正的
# keep → 内联 → 裁撤结果。
#
# ⚠️ 资源侧**没有**对应选项：`-printresources` 是 ProGuard 的写法，**R8 不认**
#    （2026-10-28 实测：`R8: Unknown option "-printresources"`，整包构建直接失败）。
#    资源清单由 AGP 自己产出：开 `isShrinkResources = true` 后，AGP 在
#    `app/build/outputs/mapping/<变体>/resources.txt` 写被裁掉的 drawable/string/layout，
#    同一目录下还有 `configuration.txt`。所以规则文件里只留 `-printusage`。
#
# ⚠️ 只在开 R8 的变体（release / benchmark）生效，debug 不跑 R8 也就没有这份清单。
# ⚠️ 产物路径相对 module 目录（app/），与 build.gradle.kts 里 mapping.txt 的位置一致。
# ⚠️ 这两个文件**只写不读**：R8 不会因为路径不存在而失败；CI 把它们当 artifact 上传
#    （.github/workflows/ci.yml 的 R8 冒烟段、build.yml 的正式 release 段）。
-printusage build/outputs/mapping/release/usage.txt

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

