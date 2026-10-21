// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百一十批 · ENV-D1（2026-10-21）：Snackbar 截断修复的展示门禁。
//
// 真机缺陷：P0-5 拦截文案第二句「本次没有改动任何文件…」看不到。根因不是文案太长，
// 而是这段文案走的是系统 Toast —— Android 12+ 的文本 Toast 只有两行（Android 16 实测），
// 再短的改写也只是把截断点挪个位置。本门禁钉四件事：
//   ① 用户正文在中/英 strings.xml，两句之间 \n 分行，第二句完整（“只留第一句”必红）；
//   ② 三处拦截都走现有 SnackbarHost 的多行 Snackbar（Indefinite + 知道了），不再走 Toast；
//   ③ 环境页两个确认框先关再弹（对话框是独立窗口，不关会把 Snackbar 盖住）；
//   ④ 同类长文案（更新失败/检查结果/无本地包/环境未安装）同样不走两行 Toast。
//
// 为什么不写 Compose UI 测试：本仓 Compose UI 测试为 0，不为这一单加依赖/定新规矩
// （与 UserFacingTextGateTest / SettingsPureGateTest 同一判断）。门禁证明的是“展示通道
// 不再有行数上限”，不是“某台设备上一定画得对”——1.3× 字体的真机视觉见交付报告的未验项。
package com.example.zhengdao.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvNoticeDisplayGateTest {

    private fun appFile(rel: String): File =
        File(rel).let { if (it.exists()) it else File("app/$rel") }

    private fun src(rel: String): String =
        appFile(rel).also { assertTrue("找不到 $rel：${it.path}", it.isFile) }.readText()

    /** 剥掉 Kotlin 行/块注释，保留字符串字面量：注释里写「Toast」不算展示通道。 */
    private fun codeOnly(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            val c = source[i]
            when {
                c == '"' && source.startsWith("\"\"\"", i) -> {
                    val end = source.indexOf("\"\"\"", i + 3).let { if (it < 0) source.length else it + 3 }
                    out.append(source, i, end)
                    i = end
                }
                c == '"' || c == '\'' -> {
                    val quote = c
                    out.append(c); i++
                    while (i < source.length) {
                        val d = source[i]
                        out.append(d); i++
                        if (d == '\\' && i < source.length) { out.append(source[i]); i++; continue }
                        if (d == quote) break
                    }
                }
                c == '/' && i + 1 < source.length && source[i + 1] == '/' -> {
                    while (i < source.length && source[i] != '\n') i++
                }
                c == '/' && i + 1 < source.length && source[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < source.length && !(source[i] == '*' && source[i + 1] == '/')) i++
                    i = (i + 2).coerceAtMost(source.length)
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    private fun xmlString(xml: String, key: String): String? =
        Regex(
            """<string name="${Regex.escape(key)}"[^>]*>(.*?)</string>""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(xml)?.groupValues?.get(1)

    private val zh: String get() = src("src/main/res/values/strings.xml")
    private val en: String get() = src("src/main/res/values-en/strings.xml")
    private val envPage: String get() = codeOnly(src(ENV_PAGE))
    private val storagePage: String get() = codeOnly(src(STORAGE_PAGE))
    private val common: String get() = codeOnly(src(COMMON))

    /** 一处守卫的展示块：`if (envInstallBlocked` 到它自己的早退。 */
    private fun guardBlocks(source: String): List<String> =
        Regex("""if \(envInstallBlocked\([\s\S]*?return@(?:TextButton|launch)""")
            .findAll(source)
            .map { it.value }
            .toList()

    private fun guardViolations(source: String): List<String> =
        guardBlocks(source).filterNot { block ->
            block.contains("showReadable(") &&
                block.contains("message = blockMessage") &&
                block.contains("actionLabel = gotIt") &&
                !block.contains("Toast.makeText") &&
                !block.contains("toastOnMain")
        }

    private fun toastCallContains(source: String, key: String): Boolean =
        Regex("""Toast\.makeText\([\s\S]{0,400}?R\.string\.""" + Regex.escape(key))
            .containsMatchIn(source) ||
            Regex("""toastOnMain\([\s\S]{0,200}?R\.string\.""" + Regex.escape(key))
                .containsMatchIn(source)

    private fun window(source: String, from: String, len: Int = 700): String {
        val at = source.indexOf(from)
        assertTrue("源码里找不到锚点：$from", at >= 0)
        return source.substring(at, (at + len).coerceAtMost(source.length))
    }

    /** 锚点前后各截一段：`showReadable(` 在 `message = …` 之前。 */
    private fun around(source: String, anchor: String, before: Int = 500, after: Int = 700): String {
        val at = source.indexOf(anchor)
        assertTrue("源码里找不到锚点：$anchor", at >= 0)
        return source.substring((at - before).coerceAtLeast(0), (at + after).coerceAtMost(source.length))
    }

    // ── ① 文案本身 ───────────────────────────────────────────────────────

    @Test
    fun `拦截文案_中英双语_第二句独立成行且完整`() {
        // 资源键必须由门禁映射；别处改成一个「更短的串」也逃不过 ② 的 LIVE_SESSION_BLOCK_MESSAGE_RES 断言。
        val gate = codeOnly(src(GATE))
        assertTrue(
            "门禁文案资源必须映射到 env_block_live_session：\n$gate",
            gate.contains("R.string.env_block_live_session"),
        )
        assertFalse(
            "用户可见正文不许再拼在 Kotlin 里（必须进 strings.xml）",
            gate.contains("回到终端页输入 exit"),
        )

        val zhMsg = xmlString(zh, KEY)
        val enMsg = xmlString(en, KEY)
        assertNotNull("中文缺 $KEY", zhMsg)
        assertNotNull("英文缺 $KEY（values-en）", enMsg)

        val zhLines = zhMsg!!.replace("\\n", "\n").lines().filter { it.isNotBlank() }
        assertEquals("中文拦截文案必须两句两行（第二句独立成行）：$zhMsg", 2, zhLines.size)
        assertTrue("第一句要告诉用户先结束会话：${zhLines[0]}", zhLines[0].contains("结束当前终端会话"))
        assertTrue("第二句必须明说零改动：${zhLines[1]}", zhLines[1].contains("没有改动任何文件"))
        assertFalse("不许用省略号把第二句「缩短」掉：$zhMsg", zhMsg.contains("…"))

        val enLines = enMsg!!.replace("\\n", "\n").lines().filter { it.isNotBlank() }
        assertEquals("英文拦截文案必须两句两行：$enMsg", 2, enLines.size)
        assertTrue(
            "英文第一句要告诉用户先结束会话：${enLines[0]}",
            enLines[0].lowercase().contains("end the current session"),
        )
        assertTrue(
            "英文第二句必须明说零改动：${enLines[1]}",
            enLines[1].lowercase().contains("no files were changed"),
        )
    }

    // ── ② 展示通道 ───────────────────────────────────────────────────────

    @Test
    fun `三处拦截_都走多行Snackbar且先关确认框`() {
        val envBlocks = guardBlocks(envPage)
        val storageBlocks = guardBlocks(storagePage)
        assertEquals("环境页守卫点数量变了（修复/更新两处）", 2, envBlocks.size)
        assertEquals("存储页守卫点数量变了（回退一处）", 1, storageBlocks.size)

        for (block in envBlocks + storageBlocks) {
            assertTrue("拦截必须走多行 Snackbar：\n$block", block.contains("showReadable("))
            assertTrue("必须展示完整正文（组合期取好的资源值）：\n$block", block.contains("message = blockMessage"))
            assertTrue("必须由用户点「知道了」关闭：\n$block", block.contains("actionLabel = gotIt"))
            assertFalse("拦截文案不许再走系统 Toast（Android 12+ 只有两行）：\n$block", block.contains("Toast.makeText"))
            assertFalse("拦截文案不许再走 toastOnMain：\n$block", block.contains("toastOnMain"))
        }

        // 正文与「知道了」都必须在组合期由 stringResource 取（协程里只捕获结果）：
        // 既保证多语言/字号配置变化后重取，也避开 Lint 的 LocalContextGetResourceValueCall。
        for ((page, label) in listOf(envPage to "环境页", storagePage to "存储页")) {
            assertTrue(
                "$label 的拦截正文必须在组合期绑定 stringResource(LIVE_SESSION_BLOCK_MESSAGE_RES)：\n$page",
                page.contains("val blockMessage = stringResource(LIVE_SESSION_BLOCK_MESSAGE_RES)"),
            )
            assertTrue(
                "$label 的「知道了」必须在组合期绑定 stringResource(common_got_it)",
                page.contains("val gotIt = stringResource(R.string.common_got_it)"),
            )
        }

        val repair = envBlocks.first { it.contains("\"修复环境\"") }
        val update = envBlocks.first { it.contains("\"环境更新\"") }
        assertTrue(
            "修复路径必须先关确认框，否则 Snackbar 被对话框窗口盖住：\n$repair",
            repair.indexOf("repairConfirm = false") in 0 until repair.indexOf("showReadable"),
        )
        assertTrue(
            "更新路径必须先关确认框，否则 Snackbar 被对话框窗口盖住：\n$update",
            update.indexOf("updateMsg = null") in 0 until update.indexOf("showReadable"),
        )
    }

    @Test
    fun `Snackbar正文不限行_且不限时自动消失`() {
        assertTrue("缺统一的多行 Snackbar helper", common.contains("showReadable("))
        val helper = window(common, "internal suspend fun SnackbarHostState.showReadable", 600)
        assertTrue("可读 Snackbar 必须等用户看完再关：$helper", helper.contains("SnackbarDuration.Indefinite"))
        assertFalse("正文不许设行数/省略（否则又是另一种截断）：$helper", helper.contains("maxLines"))
        assertFalse("正文不许设单行：$helper", helper.contains("singleLine"))
        assertFalse("正文不许手动省略：$helper", helper.contains("ellipsize"))
    }

    // ── ③ 同类长文案 ─────────────────────────────────────────────────────

    @Test
    fun `同类长文案_失败与检查结果不再走两行Toast`() {
        // 更新失败：可长可短，与「修复失败」同口径（Snackbar + 查看日志）
        assertFalse("env_update_failed 还在走 Toast：\n${around(envPage, "env_update_failed")}", toastCallContains(envPage, "env_update_failed"))
        assertTrue(
            "更新失败正文必须在组合期由 stringResource 取模板（协程里只捕获结果）",
            envPage.contains("val updateFailedTemplate = stringResource(R.string.env_update_failed)"),
        )
        val fail = around(envPage, "message = String.format(", before = 250, after = 450)
        assertTrue("更新失败必须走 showReadable：$fail", fail.contains("showReadable("))
        assertTrue("更新失败必须用资源模板拼原因：$fail", fail.contains("updateFailedTemplate"))
        assertTrue("更新失败必须能查看日志：$fail", fail.contains("viewLog"))

        // 检查结果（已是最新 / 旧清单 / 只读到 Releases）：最长中文 60 字、英文 180+，两行 Toast 装不下
        val check = window(envPage, "if (pendingUrl == null)")
        assertTrue("检查结果必须走 showReadable：$check", check.contains("showReadable("))
        assertTrue("检查结果展示的是完整 result：$check", check.contains("message = result"))

        // 无本地安装包（英文 61 字符）
        assertFalse("env_repair_no_local 还在走 Toast", toastCallContains(envPage, "env_repair_no_local"))
        assertTrue(
            "无本地安装包正文必须在组合期绑定 stringResource",
            envPage.contains("val repairNoLocalMsg = stringResource(R.string.env_repair_no_local)"),
        )
        assertTrue(
            "无本地安装包必须走 showReadable",
            around(envPage, "message = repairNoLocalMsg", before = 300, after = 300).contains("showReadable("),
        )

        // 环境未安装时点清理（英文 82 字符）
        assertFalse("storage_cache_not_installed 还在走 Toast", toastCallContains(storagePage, "storage_cache_not_installed"))
        assertTrue(
            "环境未安装正文必须在组合期绑定 stringResource",
            storagePage.contains("val cacheNotInstalledMsg = stringResource(R.string.storage_cache_not_installed)"),
        )
        assertTrue(
            "环境未安装提示必须走 showReadable",
            around(storagePage, "message = cacheNotInstalledMsg", before = 300, after = 300).contains("showReadable("),
        )
    }

    // ── ④ 门禁自己的负例 ─────────────────────────────────────────────────

    @Test
    fun `负例_只缩短第一句并继续用Toast必须被判红`() {
        val bad = """
            if (envInstallBlocked(com.example.zhengdao.terminal.SessionManager.isAlive(), "修复环境", com.example.zhengdao.rootfs.RunLog::log)) {
                toastOnMain(ctx.getString(R.string.env_block_short), long = true)
                return@TextButton
            }
        """.trimIndent()
        assertEquals("这个写法就是 ENV-D1 的病根，必须判红", 1, guardViolations(bad).size)

        val good = """
            if (envInstallBlocked(com.example.zhengdao.terminal.SessionManager.isAlive(), "修复环境", com.example.zhengdao.rootfs.RunLog::log)) {
                snackbarHostState.showReadable(
                    message = blockMessage,
                    actionLabel = gotIt,
                )
                return@TextButton
            }
        """.trimIndent()
        assertEquals("正确写法不许误报", 0, guardViolations(good).size)
    }

    private companion object {
        const val KEY = "env_block_live_session"
        const val GATE = "src/main/java/com/example/zhengdao/ui/EnvInstallGate.kt"
        const val COMMON = "src/main/java/com/example/zhengdao/ui/SettingsCommon.kt"
        const val ENV_PAGE = "src/main/java/com/example/zhengdao/ui/SettingsEnvActivity.kt"
        const val STORAGE_PAGE = "src/main/java/com/example/zhengdao/ui/SettingsStorageActivity.kt"
    }
}
