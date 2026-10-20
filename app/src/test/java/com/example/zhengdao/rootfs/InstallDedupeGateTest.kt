// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.rootfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第六十五批 · 必做 #4（去重复校验）的**源码门禁 + 纯函数**双保险。
 *
 * 背景（改前真机读数，2026-10-20）：一次「修复环境」对同一个 192MB 包做了两遍整包哈希——
 * ① 修复路径为了判断"是否同源"先 `sha256Of(archive)`（184ms）；
 * ② 安装内 Rust 流水线开解前再整读一遍对账（含在解包 3163ms 里）。
 *
 * 改后：同源判定改吃 ② 的返回值（Rust 对"恰好被解压的字节"算出的 sha256），调用方
 * 一遍都不再自己算。门禁直接盯源码：修复路径不得出现 `sha256Of(archive)`，安装内核里
 * 「安装内哈希 +1」只能有一处。日志侧另有断言：汇总行 `整包哈希 安装内 1 次 · 安装前 0 次`。
 *
 * ⚠️ 红线边界：Rust 的预扫描（`extract.rs` 步骤 1）是**解包语义的一部分**，本期按红线
 * 不动它——它是安装内唯一那一遍哈希，不是"重复"；重复的是 Kotlin 侧另起的一遍。
 */
class InstallDedupeGateTest {

    private fun source(relative: String): String =
        File("src/main/java/com/example/zhengdao/$relative").readText()

    @Test
    fun `修复路径不再自己整包哈希一遍`() {
        val src = source("ui/SettingsEnvActivity.kt")
        assertFalse(
            "修复路径不得再调用 sha256Of(archive)：同源判定改由 Rust 回报的实际 sha 完成",
            src.contains("sha256Of(archive)"),
        )
        assertTrue("修复路径必须通过 resolveEnv 延迟解析 env", src.contains("resolveEnv ="))
        assertTrue("调用方只记录「安装前」哈希", src.contains("trace.preHashPasses"))
        assertFalse("安装内哈希由 InstallTrace 自己记，调用方不得代记", src.contains("trace.hashPasses"))
    }

    @Test
    fun `安装内核只在 Rust 回报处记一次哈希并保存实际 sha`() {
        val src = source("rootfs/RootfsInstaller.kt")
        val hits = Regex("it\\.hashPasses \\+= 1").findAll(src).count()
        assertEquals("安装内哈希计数只应出现在 Rust 回报那一处（Java 回退不哈希）", 1, hits)
        assertTrue("必须保存 Rust 回报的实际 sha256", src.contains("it.actualSha256 = report.third"))
        assertTrue("env 延迟解析必须走纯函数内核", src.contains("envForActualSha(resolveEnv, actualSha)"))
    }

    @Test
    fun `envForActualSha 只在拿到实际哈希时解析 且异常不炸安装`() {
        assertEquals(
            "有实际 sha 且解析成功 ⇒ 保留 env",
            "368b59b30b10712c",
            RootfsInstaller.envForActualSha({ "368b59b30b10712c" }, "abc123"),
        )
        assertNull("没有 resolveEnv ⇒ 不写 env", RootfsInstaller.envForActualSha(null, "abc123"))
        assertNull("实际 sha 缺失（Java 回退）⇒ 不写 env", RootfsInstaller.envForActualSha({ "x" }, ""))
        assertNull("解析抛异常 ⇒ 照装不写 env，不能把安装炸掉", RootfsInstaller.envForActualSha({ error("boom") }, "abc123"))
        assertNull("解析返回 null ⇒ 不写 env", RootfsInstaller.envForActualSha({ null }, "abc123"))
    }
}
