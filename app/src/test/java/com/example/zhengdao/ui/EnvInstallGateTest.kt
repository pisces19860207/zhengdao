// 第五十二批 · P0-5（2026-10-19）：rootfs 会话守卫的门禁。
//
// 第一百一十批 · ENV-D1（2026-10-21）：用户可见正文搬进 strings.xml（中/英），由
// EnvNoticeDisplayGateTest 钉「两句完整、多行 Snackbar、不走两行 Toast」。本文件继续钉
// 判据/行为/接线，① 检查的是**日志句**（仍保留两句关键词，排障对账用）。
//
// 三层：
//   ① 纯判据：活会话 ⇒ 日志句（含派工单要求的两句话）；无活会话 ⇒ null；
//   ② 行为：被拦时安装动作**一次都不执行**、临时 rootfs 一个字节不变；
//   ③ 源码门禁：四条设置页路径的「守卫 → 替换调用」交错序必须与预期完全一致
//      （漏一条、错序、或换成 hasSession 判据，都红）。
//
// ③ 是这一单真正的保护：①② 只证明判据对，证明不了四条路径都接上了。
package com.example.zhengdao.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EnvInstallGateTest {

    // ── ① 纯判据 ─────────────────────────────────────────────────────────

    @Test
    fun `门禁_活会话时给出日志句_且两句关键词都在`() {
        val msg = liveSessionBlocksEnvInstall(alive = true)
        assertTrue("活会话必须拦下（返回日志句），实际 null", msg != null)
        assertTrue("日志里必须让用户先结束会话：$msg", msg!!.contains("结束当前终端会话"))
        assertTrue("日志里必须明说没动文件：$msg", msg.contains("没有改动任何文件"))
        // 110 批：这只是日志句，不是 UI 正文 —— UI 正文两句分行在 strings.xml。
        assertTrue("日志句不应再兼任 UI 正文（正文见 env_block_live_session）", !msg.contains("回到终端页输入 exit"))
    }

    @Test
    fun `门禁_没有活会话时放行`() {
        assertNull("没有活会话就不该拦", liveSessionBlocksEnvInstall(alive = false))
    }

    // ── ② 行为（假 installer）────────────────────────────────────────────

    /** 假安装器：记调用次数，并对一个临时 rootfs 目录"动刀"——被拦时应当一刀没动。 */
    private class FakeInstaller(private val rootfs: File) {
        var calls = 0
            private set

        fun run() {
            calls++
            File(rootfs, "bin").mkdirs()
            File(rootfs, "bin/sh").writeText("new-env")
        }
    }

    @Test
    fun `行为_活会话时安装动作一次都不执行_临时rootfs不变`() {
        val rootfs = kotlin.io.path.createTempDirectory("zd-env-gate-").toFile()
        try {
            // 现在盘上那份"旧环境"
            File(rootfs, "bin").mkdirs()
            File(rootfs, "bin/sh").writeText("old-env")
            val before = File(rootfs, "bin/sh").readBytes()

            val installer = FakeInstaller(rootfs)
            val logs = mutableListOf<String>()
            // ⚠️ 与四条设置页路径**同形**的早退：判定为真 ⇒ 立刻收手，安装动作根本没被构造进执行流。
            //    真实接线由 ③ 的源码门禁保证（这里验的是判据 + 早退不越界）。
            if (!envInstallBlocked(alive = true, scene = "修复环境", log = { logs += it })) {
                installer.run()
            }

            assertEquals("被拦时安装动作不许执行", 0, installer.calls)
            assertTrue("旧环境字节必须原样", File(rootfs, "bin/sh").readBytes().contentEquals(before))
            assertEquals("旧环境内容不许变", "old-env", File(rootfs, "bin/sh").readText())
            assertTrue("拦下必须留日志（不许静默）：$logs", logs.any { it.contains("修复环境") })
            assertTrue("日志里要点明零改动：$logs", logs.any { it.contains("没有改动任何文件") })
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `行为_没有活会话时安装动作照常执行`() {
        val rootfs = kotlin.io.path.createTempDirectory("zd-env-gate-ok-").toFile()
        try {
            File(rootfs, "bin").mkdirs()
            File(rootfs, "bin/sh").writeText("old-env")
            val installer = FakeInstaller(rootfs)
            val logs = mutableListOf<String>()

            if (!envInstallBlocked(alive = false, scene = "修复环境", log = { logs += it })) {
                installer.run()
            }

            assertEquals("无活会话必须放行", 1, installer.calls)
            assertEquals("放行后环境应被替换", "new-env", File(rootfs, "bin/sh").readText())
            assertTrue("放行时不该写拦截日志：$logs", logs.isEmpty())
        } finally {
            rootfs.deleteRecursively()
        }
    }

    // ── ③ 源码门禁：四条路径的守卫位置 ───────────────────────────────────

    /**
     * 守卫调用 + 替换调用的**交错序**。
     *
     * 只比对"守卫在调用之前"是不够的：`SettingsEnvActivity` 里增量与全量共用一道守卫、
     * 修复另有一道——把共用的那道删掉后，修复那道仍在全量调用之前，"有守卫在前"照样成立。
     * 所以这里钉的是完整序列：漏一道、多一道、顺序变了，都红。
     */
    private fun sequenceOf(path: String): List<String> {
        val src = File(path).readText()
        data class Hit(val at: Int, val token: String)
        val hits = mutableListOf<Hit>()

        Regex("""envInstallBlocked\(\s*com\.example\.zhengdao\.terminal\.SessionManager\.isAlive\(\),\s*"([^"]+)"""")
            .findAll(src)
            .forEach { m -> hits += Hit(m.range.first, "G:${m.groupValues[1]}") }
        Regex("""RootfsInstaller\.install\(""")
            .findAll(src)
            .forEach { hits += Hit(it.range.first, "I") }
        Regex("""RootfsDelta\.apply\(""")
            .findAll(src)
            .forEach { hits += Hit(it.range.first, "D") }

        return hits.sortedBy { it.at }.map { it.token }
    }

    @Test
    fun `源码门禁_环境页_修复与更新与全量三处都被守卫且顺序不变`() {
        assertEquals(
            "SettingsEnvActivity 的守卫/替换交错序变了（漏接线、顺序变化、或换了判据）：\n" +
                sequenceOf(ENV_PAGE).joinToString(" → "),
            listOf("G:修复环境", "I", "G:环境更新", "D", "I"),
            sequenceOf(ENV_PAGE),
        )
    }

    @Test
    fun `源码门禁_存储页_回退被守卫`() {
        assertEquals(
            "SettingsStorageActivity 的回退路径缺守卫或顺序变了：\n" +
                sequenceOf(STORAGE_PAGE).joinToString(" → "),
            listOf("G:回退环境", "I"),
            sequenceOf(STORAGE_PAGE),
        )
    }

    @Test
    fun `源码门禁_四条路径都不许拿 hasSession 当门禁`() {
        for (path in listOf(ENV_PAGE, STORAGE_PAGE)) {
            val src = File(path).readText()
            assertFalse(
                "$path 用 hasSession 当门禁了 —— 会话对象在但进程没起/已死时它仍是 true，会误拒正常安装；" +
                    "门禁只认 isAlive()",
                Regex("""envInstallBlocked\([^)]*hasSession""").containsMatchIn(src),
            )
            assertTrue("$path 里一道 isAlive 守卫都没有", src.contains("SessionManager.isAlive()"))
        }
    }

    @Test
    fun `源码门禁_守卫在下载与解包之前_不在替换之后补刀`() {
        // 位置口径：守卫必须出现在「任何 IO 之前」，而 Env 页的下载/解包发生在
        // InstallFlow.start 之后 —— 所以守卫必须早于同文件里第一个 InstallFlow.start。
        val envSrc = File(ENV_PAGE).readText()
        val firstGuard = envSrc.indexOf("envInstallBlocked(com.example.zhengdao.terminal.SessionManager.isAlive()")
        val firstIo = envSrc.indexOf("InstallFlow.start(")
        assertTrue("环境页找不到守卫", firstGuard >= 0)
        assertTrue(
            "守卫（$firstGuard）必须早于第一次安装 IO（$firstIo）—— 不能等下载完了再拦",
            firstGuard in 0 until firstIo,
        )
    }

    private companion object {
        const val ENV_PAGE = "src/main/java/com/example/zhengdao/ui/SettingsEnvActivity.kt"
        const val STORAGE_PAGE = "src/main/java/com/example/zhengdao/ui/SettingsStorageActivity.kt"
    }
}
