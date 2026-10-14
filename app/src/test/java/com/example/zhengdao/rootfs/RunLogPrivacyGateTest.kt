// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.rootfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * N-4 A 案隐私门禁（第⑰刀）——日志落点 + 脱敏 + 双语对称。
 *
 * 方案依据：`docs/N-4-日志落点改造方案-A案-2026-10-14.md` §2.5。
 *
 * 七条断言分三类：
 * 1. **落点**（真门禁，不是 grep）：[RunLog.dirFor] 的实际返回值必须在 filesDir 下、
 *    不在公共存储——防「哪天有人把公共区优先改回来」；
 * 2. **脱敏行为**：[RunLog.redact] 把两类私有绝对路径换占位、口令类换星号、
 *    保留公共区路径/时间戳/错误标记词、**不碰异常堆栈**（⑯刀边界）；
 * 3. **双语对称 + logcat 守卫**（源码门禁）：两个 strings.xml 键集合完全相同；
 *    logcat 镜像那行必须在 `BuildConfig.DEBUG` 守卫内。
 *
 * ⚠️ 已知局限（照实说）：③的 logcat 一条是**源码门禁**而非行为断言——
 * 项目只跑 `testDebugUnitTest`（debug 变体，`BuildConfig.DEBUG == true`），
 * 且 `android.util.Log` 在单测里是 `isReturnDefaultValues` 的空操作，
 * release 变体的「不写 logcat」无法在本套测试里跑出来（方案 §2.5 的
 * 「行为断言：release 变体下不写入 logcat」做不到，改以源码门禁兜底）。
 */
class RunLogPrivacyGateTest {

    private fun source(rel: String): String {
        val f = File(rel).let { if (it.exists()) it else File("app/$rel") }
        assertTrue("找不到 $rel：${f.path}", f.isFile)
        return f.readText()
    }

    // ── ① 落点：私有 filesDir，不在公共存储 ─────────────────────────────

    @Test
    fun `日志目录在私有 filesDir 下_不在公共存储`() {
        val fakeFilesDir = File("/data/user/0/com.example.zhengdao/files")
        val d = RunLog.dirFor(fakeFilesDir)
        assertTrue(
            "日志目录必须在 filesDir 下（N-4 A 案），实际：${d.path}",
            d.path.startsWith(fakeFilesDir.path)
        )
        assertTrue("必须是 filesDir/logs", d.path == File(fakeFilesDir, "logs").path)
        assertFalse("不得落公共存储", d.path.contains("/storage/emulated"))
        assertFalse("不得落公共区 Download/证道", d.path.contains("Download/证道"))
        assertFalse("不得再退 cacheDir 兜底", d.path.contains("cache"))
    }

    @Test
    fun `dir 实现体不再探测公共区`() {
        val src = source("src/main/java/com/example/zhengdao/rootfs/RunLog.kt")
        // dir() 的函数体里不得再出现公共区探测（Store.logsDir 只允许出现在
        // copyLegacyFromPublic 里——那是"读旧公共区老日志复制一份"的一次性迁移）。
        val dirBody = Regex("private fun dir\\(context: Context\\): File \\{[\\s\\S]*?\\n    \\}")
            .find(src)?.value ?: ""
        assertTrue("没找到 dir() 函数体，门禁失效了", dirBody.isNotEmpty())
        assertFalse("dir() 里不得再探测公共区", dirBody.contains("Store.logsDir"))
        assertFalse("dir() 里不得再有 cacheDir 兜底", dirBody.contains("cacheDir"))
    }

    // ── ② 脱敏行为 ──────────────────────────────────────────────────────

    @Test
    fun `redact 两类私有绝对路径都换占位`() {
        val a = RunLog.redact(
            "/data/user/0/com.example.zhengdao/files/home/.hermes/config.json 读取失败"
        )
        assertFalse("不得残留包名", a.contains("com.example.zhengdao"))
        assertTrue("必须出现占位", a.contains("<私有目录>"))
        assertTrue("错误标记词不能被吞（errors.log 判据靠它）", a.contains("读取失败"))

        val b = RunLog.redact(
            "EACCES: /data/data/com.example.zhengdao/cache/x.bin (Permission denied)"
        )
        assertFalse("第二种形态也不得残留包名", b.contains("com.example.zhengdao"))
        assertTrue(b.contains("<私有目录>"))
    }

    @Test
    fun `redact filesDir 精确路径也换占位`() {
        val filesDir = "/data/user/0/com.example.zhengdao/files"
        val out = RunLog.redact("写入 $filesDir/logs/zhengdao-log.txt 完成", filesDir)
        assertFalse(out.contains(filesDir))
        assertEquals("写入 <私有目录>/logs/zhengdao-log.txt 完成", out)
    }

    @Test
    fun `redact 保留公共区路径与时间戳`() {
        val line = "[10-14 22:00:01] 下载完成 /storage/emulated/0/Download/证道/rootfs.tar.zst"
        assertEquals("公共区路径是用户可见数据、排障要用，必须逐字保留", line, RunLog.redact(line))
    }

    @Test
    fun `redact 口令类换星号`() {
        assertFalse(RunLog.redact("Authorization: Basic YWJjOmRlZg==").contains("YWJjOmRlZg"))
        assertTrue(RunLog.redact("Authorization: Basic YWJjOmRlZg==").contains("****"))
        assertFalse(RunLog.redact("token=secret123 请求失败").contains("secret123"))
        assertFalse(RunLog.redact("passwd=hunter2").contains("hunter2"))
        assertFalse(RunLog.redact("server password: abcdef").contains("abcdef"))
    }

    @Test
    fun `redact 不碰异常堆栈_⑯刀边界`() {
        // ⑯刀在动 Exception/java.* 过滤，本刀不碰：无路径无口令的行必须逐字不变。
        val stack = "java.lang.IllegalStateException: boom\n\tat com.example.zhengdao.X.y(X.kt:1)"
        assertEquals(stack, RunLog.redact(stack))
    }

    // ── ③ 双语对称 + logcat 守卫（源码门禁） ────────────────────────────

    @Test
    fun `两个 strings 键集合完全相同`() {
        val zh = source("src/main/res/values/strings.xml")
        val en = source("src/main/res/values-en/strings.xml")
        val keyRegex = Regex("<string name=\"([^\"]+)\"")
        val zhKeys = keyRegex.findAll(zh).map { it.groupValues[1] }.toSet()
        val enKeys = keyRegex.findAll(en).map { it.groupValues[1] }.toSet()
        assertEquals("zh/en 键集合必须完全一致（本刀增删后仍对称）", zhKeys, enKeys)
        // 本刀新增的 4 键双侧都在
        listOf(
            "help_log_export_btn", "help_log_export_hint",
            "help_log_export_ok", "help_log_export_fail",
        ).forEach { k -> assertTrue("$k 必须双侧都有", k in zhKeys && k in enKeys) }
        // R-30 删掉的两条不得复活
        listOf("common_read_failed", "help_log_location").forEach { k ->
            assertFalse("R-30 已删的孤儿键不得复活：$k", k in zhKeys || k in enKeys)
        }
    }

    @Test
    fun `logcat 镜像在 BuildConfig_DEBUG 守卫内`() {
        val src = source("src/main/java/com/example/zhengdao/rootfs/RunLog.kt")
        val idx = src.indexOf("Log.i(\"zhengdao\"")
        assertTrue("没找到 logcat 镜像那行，门禁失效了", idx > 0)
        // 往前 200 字符内必须有 DEBUG 守卫
        val window = src.substring(maxOf(0, idx - 200), idx)
        assertTrue(
            "logcat 镜像必须在 if (BuildConfig.DEBUG) 守卫内（N-4 A 案：release 不镜像）",
            window.contains("BuildConfig.DEBUG")
        )
    }
}
