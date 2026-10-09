// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的公开资料：Android 官方 R8/ProGuard 手册（keep 规则语义）、
//   R8 的"看不见 JNI 调用点"这一既知限制（见 docs/ERRATA.md E-022）。
package com.example.zhengdao.rust

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R8 keep 规则的**防回归门禁**（2026-10-09 加，见
 * `docs/证道-Rust化余地审计-2026-10-09.md` 观察 2）。
 *
 * ## 为什么需要这个测试
 *
 * `docs/ERRATA.md` E-022 的真机事故：release 包里 R8 把
 * `CoreNative.onProgress(String,String)` 改了名，而 Rust 侧
 * `rust/core/src/jni_bridge.rs` 的 `report_progress` 是**按名字反射查找**这个静态方法的
 * —— R8 看不见 JNI 的调用点，于是查找失败。更狠的是失败会留下 pending exception，
 * 下一次 JNI 调用命中 ART 的 `AssertNoPendingException` ⇒ **整个进程 SIGABRT 闪退**
 * （用户现象："一装环境就退回主页，点了三次都装不上"）。
 *
 * 修法是两条：① `jni_bridge.rs` 里失败一律吞掉并 `exception_clear()`（兜底）；
 * ② `app/proguard-rules.pro` 显式 keep 这个成员（根治）。
 *
 * 但**规则本身会被人手滑删掉** —— 它看起来只是几行 proguard 配置，删掉照样能编译、
 * 照样能跑 debug（debug 不混淆），**只有 release 包真机装环境时才炸**。这条链上没有
 * 任何自动化会拦住它。所以这个测试存在的唯一目的就是：
 * **把"规则存在且与 Rust 侧引用的类名一致"这件事变成一个会红的断言。**
 *
 * ## 它检查什么（三条，任一不成立即失败）
 * 1. `app/proguard-rules.pro` 存在（路径按 Gradle 单测 cwd = 模块目录 `app/` 约定）；
 * 2. 里面有一条针对 `com.example.zhengdao.rust.CoreNative` 的 `-keep class` 规则；
 * 3. 该规则体内 keep 了 `onProgress`，**且**该类名与 `jni_bridge.rs` 里
 *    `find_class` / 符号名前缀实际引用的类名**逐个字符一致**。
 *
 * 第 3 条是重点：光有 keep 规则不够——如果将来把类改名/挪包，
 * 规则与 Rust 侧会**同时改错但互相不自洽**，这个测试会把不一致抓出来。
 */
class R8KeepRuleGuardTest {

    /** Gradle 单测的工作目录是模块目录 `app/`（同 AgentManifestVerifyTest 的 `../rootfs` 约定）。 */
    private fun appFile(rel: String): File = File(rel).let { if (it.exists()) it else File("app/$rel") }

    /** 仓库根（相对 `app/` 上一级）。 */
    private fun repoFile(rel: String): File = File("../$rel").let {
        if (it.exists()) it else File(rel)
    }

    private val proguard: String by lazy {
        val f = appFile("proguard-rules.pro")
        assertTrue("找不到 app/proguard-rules.pro（cwd=${File("").absolutePath}）", f.isFile)
        f.readText()
    }

    private val jniBridge: String by lazy {
        val f = repoFile("rust/core/src/jni_bridge.rs")
        assertTrue("找不到 rust/core/src/jni_bridge.rs（cwd=${File("").absolutePath}）", f.isFile)
        f.readText()
    }

    /** Rust 侧反射/符号查找所用的类名（JNI 全限定名）。 */
    private val jniClassName = "com.example.zhengdao.rust.CoreNative"

    @Test
    fun `proguard 里存在 CoreNative 的 keep class 规则`() {
        // -keep class com.example.zhengdao.rust.CoreNative { ... }
        val hasKeep = Regex(
            """-keep\s+class\s+${Regex.escape(jniClassName)}\b"""
        ).containsMatchIn(proguard)
        assertTrue(
            "proguard-rules.pro 缺少「-keep class $jniClassName」规则——" +
                "删掉它会让 release 包在 native 回调时 SIGABRT（ERRATA E-022）",
            hasKeep,
        )
    }

    @Test
    fun `keep 规则里保住了 onProgress 回调`() {
        val body = keepBody(jniClassName)
        assertTrue(
            "CoreNative 的 keep 规则体里没有 onProgress —— " +
                "Rust 的 report_progress 会找不到它、留下 pending exception 导致闪退（E-022）",
            body.contains("onProgress"),
        )
        // 顺便确认签名没被写成别的形态（String,String -> void）
        assertTrue(
            "onProgress 的 keep 签名应与 Java 侧一致：public static void onProgress(String,String)",
            body.contains("onProgress") &&
                Regex("""onProgress\s*\(\s*java\.lang\.String\s*,\s*java\.lang\.String\s*\)""")
                    .containsMatchIn(body),
        )
    }

    /**
     * 核心一致性断言：proguard 里 keep 的类名，与 jni_bridge.rs 实际引用的类名必须一致。
     *
     * Rust 侧有两处独立证据：
     * - `find_class("com/example/zhengdao/rust/CoreNative")`（斜杠形式）；
     * - JNI 导出符号前缀 `Java_com_example_zhengdao_rust_CoreNative_*`（下划线形式）。
     * 两者都要与 proguard 的点分形式对得上。
     */
    @Test
    fun `proguard 类名与 jni_bridge 引用的类名一致`() {
        val dotted = jniClassName
        val slashed = dotted.replace('.', '/')
        val underscored = "Java_" + dotted.replace('.', '_')

        assertTrue(
            "jni_bridge.rs 里找不到 find_class(\"$slashed\") —— Rust 回调查的类名变了，" +
                "proguard 的 keep 规则也跟着失效",
            jniBridge.contains("find_class(\"$slashed\")"),
        )
        assertTrue(
            "jni_bridge.rs 里找不到 JNI 导出符号前缀 `$underscored` —— " +
                "说明 Kotlin 类名/包名与 Rust 符号不再一致（运行时 UnsatisfiedLinkError）",
            jniBridge.contains(underscored),
        )
        // 再反向确认 proguard 用的正是这个点分类名（不是某个拼错的变体）
        assertTrue(
            "proguard-rules.pro 里没有出现 $dotted —— 与 Rust 侧引用的类名不一致",
            proguard.contains(dotted),
        )
    }

    /** 抽出某个 `-keep class X { ... }` 的规则体（支持多行、含内部方法与字段）。 */
    private fun keepBody(className: String): String {
        val m = Regex(
            """-keep\s+class\s+${Regex.escape(className)}\s*\{([^}]*)\}""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(proguard)
        assertTrue("找不到 $className 的 keep 规则体", m != null)
        return m!!.groupValues[1]
    }
}
