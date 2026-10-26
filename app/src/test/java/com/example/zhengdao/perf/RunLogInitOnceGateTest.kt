// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百一十八批 §2 **T3** 的钉桩：一次冷启动只跑一次 `RunLog.init`。
//
// ## 这一刀修的是什么
// `RunLog.init()` 里会跑 `migrateLegacy()` —— 对**外部公共目录**做 `listFiles` 再逐个
// `isFile`/`length`（FUSE 上的 stat）。冷启动主线程上它跑了**两遍**：
// `ZhengdaoApp.onCreate` 一遍、`MainActivity.onCreate` 又一遍。删的是第二遍。
//
// ## 为什么必须同时钉住"另一条没被删坏"
// `RunLog.init` 进 Application 是 2026-10-06 的教训（ERRATA：init 原先只在 TerminalActivity，
// 换 Compose 原生 UI 后用户根本不进终端 ⇒ `appContext == null` ⇒ 日志**静默丢掉**，
// SSE 断连原因完全无法定位）。**这行必须在。** 所以本文件既钉"MainActivity 不再第二次 init"，
// 也钉"Application 里那条还是 onCreate 的第一条实质语句"——只钉一边，早晚被人删成另一边。
package com.example.zhengdao.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RunLogInitOnceGateTest {

    private fun src(rel: String): String {
        val f = listOf(
            File("src/main/java/com/example/zhengdao/$rel"),
            File("app/src/main/java/com/example/zhengdao/$rel"),
            File("../../app/src/main/java/com/example/zhengdao/$rel"),
        ).firstOrNull { it.isFile } ?: error("gate cannot find $rel - pin path needs update")
        return f.readText()
    }

    @Test
    fun `T3_一次冷启动只跑一次RunLog_init`() {
        val app = src("ZhengdaoApp.kt")
        val main = src("MainActivity.kt")
        // 历史判据（日志必须落盘）由 Application 入口独立承担，不许跟着这行一起删掉
        // 第二百四十批：init 改成注入式（`RunLog.init(this, legacyLogsDir=…, adoptFile=…)`），
        // 调用变成跨行。判据从「同一行里有 `RunLog.init(this)`」改成**更强**的
        // 「super.onCreate() 之后的第一条实质语句就是 RunLog.init(」——
        // 要防的从来不是括号里写了什么，而是"Application 里这行被挪走/删掉"。
        val afterSuper = app.substringAfter("override fun onCreate() {")
            .substringAfter("super.onCreate()")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
            .firstOrNull()
            .orEmpty()
        assertTrue(
            "ZhengdaoApp.onCreate must still RunLog.init first（实际第一条语句：$afterSuper）",
            afterSuper.startsWith("RunLog.init("),
        )
        // MainActivity 不再第二次 init（只判代码行；注释里提到它是给后人看的，允许出现）
        assertFalse(
            "RunLog.init call reappeared in MainActivity",
            main.lineSequence().any { it.trimStart().startsWith("RunLog.init(") },
        )
        // 全仓 init 调用点只剩 2 处：Application + TerminalActivity（终端那份归红线，本批不动）
        val root = listOf(File("src/main/java"), File("app/src/main/java"), File("../../app/src/main/java"))
            .firstOrNull { it.isDirectory } ?: error("gate cannot find main source dir - pin root needs update")
        val sites = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { f -> f.readLines().map { f to it } }
            .filter { (_, line) -> line.trimStart().startsWith("RunLog.init(") }
            .toList()
        assertEquals("RunLog.init call sites expect 2: ${sites.map { it.first.name }}", 2, sites.size)
        assertTrue("ZhengdaoApp must remain an init site", sites.any { it.first.name == "ZhengdaoApp.kt" })
        assertTrue("TerminalActivity init must stay", sites.any { it.first.name == "TerminalActivity.kt" })
    }
}
