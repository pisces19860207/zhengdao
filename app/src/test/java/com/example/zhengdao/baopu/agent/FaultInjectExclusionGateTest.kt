// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百八十四批 · **注入剔除的源码级门禁**（判据③的前半：源码断言）。
//
// 判据原话：「release 编译期剔除（源码断言 ＋ 字符串不在产物里）」。
// 这一条管前半：`app/src/main/java` 里**不许**出现注入的规则、文件名、实现类名——
// 因为 main 会被编进正式包；注入实现全部住在 `app/src/debug/java`（只在开发包编译），
// main 只留一根针（`ToolFaultSeam`，一个接口 ＋ 一个可空引用，零字面量）。
//
// 后半（产物里没有这些字符串）由 `tools/check-fault-inject-absent.py` 对 release APK
// 的 dex 做扫描——两半都要，因为"源码干净"只证明"没写在 main 里"，
// 而"产物干净"才是用户拿到的那个包。
//
// 每个判别器都先喂一段**故意违规**的样本自证咬得住，再对真实源码断言干净
// （同 491 `BackupSourceGateTest` 的口径：防恒真绿灯）。
package com.example.zhengdao.baopu.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FaultInjectExclusionGateTest {

    private fun projectFile(rel: String): File {
        val cwd = File(".").canonicalFile
        return listOf(File(cwd, rel), File(cwd.parentFile, rel), File(cwd, "app/$rel"))
            .firstOrNull { it.isFile } ?: error("找不到 $rel（cwd=${cwd.absolutePath}）")
    }

    /** 目录版（`src/main/java/...` 是目录，`projectFile` 只认文件）。 */
    private fun projectDir(rel: String): File {
        val cwd = File(".").canonicalFile
        return listOf(File(cwd, rel), File(cwd.parentFile, rel), File(cwd, "app/$rel"))
            .firstOrNull { it.isDirectory } ?: error("找不到目录 $rel（cwd=${cwd.absolutePath}）")
    }

    /** main 源集的全部 Kotlin 文件（递归）。 */
    private fun mainSources(): List<File> =
        projectDir("src/main/java/com/example/zhengdao")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    /**
     * 注入的**特征串**：配置文件名、实现类名、规则类型名、探针/安装器名。
     *
     * 为什么用这些而不是 "Fault"：main 里有 `ToolFaultSeam`（那根针，故意留的），
     * 它一个字节的规则/路径都不含；下面这几串只要出现在 main 就说明有人把实现搬回去了。
     */
    private val markers = listOf(
        "fault-inject",
        "FaultInjection",
        "FaultInjector",
        "FaultRule",
        "FaultInjectProbe",
        "FaultInjectInstaller",
    )

    /** 判别器：源码里出现任一特征串 ⇒ 返回命中的那一行（null＝干净）。 */
    private fun leak(src: String): String? =
        stripComments(src).lines().firstOrNull { line -> markers.any { line.contains(it) } }

    /**
     * 只删整行注释与块注释——**注释不进产物**（Kotlin 元数据里没有 KDoc），
     * 所以 main 的注释里提到 debug 类的全名不算泄漏；代码里出现才算。
     */
    private fun stripComments(src: String): String = src
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .lines()
        .filterNot { it.trimStart().startsWith("//") }
        .joinToString("\n")

    @Test
    fun 判别器咬得住搬回main的注入实现() {
        assertNotNull("配置文件名搬回 main 必须被抓", leak("val f = File(ctx.filesDir, \"baopu/fault-inject.txt\")"))
        assertNotNull("实现类名搬回 main 必须被抓", leak("ToolFaultSeam.hook = FaultInjection.watcher(file)"))
        assertNotNull("规则类型名搬回 main 必须被抓", leak("private val rules = listOf(FaultRule(\"read_file\", 0, 3, \"protocol\"))"))
        assertEquals("干净样本不该误报", null, leak("ToolFaultSeam.intercept(spec.name, call.arguments)"))
    }

    @Test
    fun main里没有注入的规则与实现() {
        val hits = mainSources().mapNotNull { f ->
            leak(f.readText())?.let { "${f.name}: $it" }
        }
        assertTrue("main 里出现了注入实现（应只在 src/debug）：$hits", hits.isEmpty())
    }

    @Test
    fun main里没有给缝赋值的第二处() {
        // 缝是**唯一**一根针：main 只声明它、只读它；赋值只许在 debug 的安装器里。
        val assign = mainSources().flatMap { f ->
            stripComments(f.readText()).lines()
                .filter { it.contains("ToolFaultSeam.hook") && it.contains("=") }
                .map { "${f.name}: ${it.trim()}" }
        }
        assertTrue("main 里有人给缝赋值（赋值点只许在 debug 安装器）：$assign", assign.isEmpty())
    }

    @Test
    fun 缝真的接在执行路径上() {
        // 防"源码干净但根本没接"：GuardPipeline 的第 ④ 段必须问过缝。
        val pipeline = projectFile("src/main/java/com/example/zhengdao/baopu/agent/GuardPipeline.kt").readText()
        assertTrue(
            "GuardPipeline 的第 ④ 段没有问过 ToolFaultSeam —— 注入不会走真失败路径",
            pipeline.contains("ToolFaultSeam.intercept("),
        )
    }

    @Test
    fun 注入实现确实住在debug源集里() {
        // 反方向断言：debug 源集里必须有实现（否则上面"main 干净"是空断言，注入根本不存在）。
        val impl = projectFile("src/debug/java/com/example/zhengdao/debug/FaultInjection.kt").readText()
        assertTrue("debug 源集里找不到规则解析器", impl.contains("object FaultRuleParser"))
        assertTrue("debug 源集里找不到注入器", impl.contains("class FaultInjector"))
        assertTrue("配置文件落点必须是 baopu/fault-inject.txt", impl.contains("\"fault-inject.txt\""))
        val provider = projectFile("src/debug/java/com/example/zhengdao/debug/FaultInjectInstaller.kt").readText()
        assertTrue("debug 源集里找不到进程入口", provider.contains("class FaultInjectInstaller"))
        val manifest = projectFile("src/debug/AndroidManifest.xml").readText()
        assertTrue("debug 清单里没注册安装器", manifest.contains("com.example.zhengdao.debug.FaultInjectInstaller"))
    }
}
