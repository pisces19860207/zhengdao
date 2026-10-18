// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第十八批 §4（判据 J1-J7）与 §5（负例 1-3 的靶子）。
// J1-J3 / J7 是**源码级门禁**（仿 BareThreadGuardTest 的纯文本扫描，工作目录 = app 模块根）；
// J4-J6 是纯函数直测（visibleRows 在 ui/HomeScreen.kt，输入输出全是纯数据）。
package com.example.zhengdao.ui

import com.example.zhengdao.ui.AppState.AgentInfo
import com.example.zhengdao.ui.EnvHealth.Check
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BaopuSettingsGuardTest {

    // ── 源码读取（工作目录 = app/，同 BareThreadGuardTest） ─────────────────

    private fun src(rel: String): String =
        File("src/main/java/com/example/zhengdao/$rel").readText(Charsets.UTF_8)

    private fun res(rel: String): String =
        File("src/main/res/$rel").readText(Charsets.UTF_8)

    /** 抽出 `onSettings = { … }` 整个 lambda（花括号配平），J2 的断言对象。 */
    private fun onSettingsBlock(srcText: String): String {
        val key = "onSettings = {"
        val start = srcText.indexOf(key)
        assertTrue("BaopuScreen 里找不到 onSettings 区块", start >= 0)
        var depth = 0
        var opened = false
        var i = start + key.length - 1 // 停在 '{' 上
        while (i < srcText.length) {
            when (srcText[i]) {
                '{' -> { depth++; opened = true }
                '}' -> { depth--; if (opened && depth == 0) return srcText.substring(start, i + 1) }
            }
            i++
        }
        error("onSettings 区块没有闭合")
    }

    // ── J1 抱朴三块搬走 ───────────────────────────────────────────────────

    @Test
    fun `J1_三块从终端与资料页搬走_新页里都在`() {
        val old = src("ui/SettingsDataActivity.kt")
        for (card in listOf("baopu_tools_card", "baopu_params_card", "baopu_effort_card")) {
            assertFalse(
                "SettingsDataActivity 不该再引用 $card（已搬进抱朴设置页）",
                old.contains("R.string.$card"),
            )
        }
        val newPage = src("ui/baopu/BaopuSettingsScreen.kt")
        for (card in listOf("baopu_tools_card", "baopu_params_card", "baopu_effort_card")) {
            assertTrue("抱朴设置页里必须有 $card", newPage.contains("R.string.$card"))
        }
        // 三个标签映射函数也要随块搬走（新页自给自足，不许反向依赖旧页）
        for (fn in listOf("baopuToolLabel", "effortLabel", "baopuToolDesc")) {
            assertTrue("抱朴设置页里必须有 $fn", newPage.contains("private fun $fn"))
            assertFalse(
                "SettingsDataActivity 不该再有 $fn（随块搬走）",
                old.contains("private fun $fn"),
            )
        }
    }

    // ── J2 抽屉直达 ───────────────────────────────────────────────────────

    @Test
    fun `J2_抽屉抱朴设置直达新页_不再指向SettingsDataActivity`() {
        val block = onSettingsBlock(src("ui/baopu/BaopuScreen.kt"))
        assertTrue(
            "「抱朴设置」要打开新设置页（showSettings = true）：$block",
            block.contains("showSettings = true"),
        )
        assertFalse(
            "「抱朴设置」不许再指向 SettingsDataActivity（openSettings）：$block",
            block.contains("openSettings("),
        )
    }

    // ── J3 设置 Hub 与终端与资料页无抱朴项 ────────────────────────────────

    @Test
    fun `J3_Hub与终端与资料页不再引用任何抱朴字符串`() {
        for (page in listOf("ui/SettingsScreen.kt", "ui/SettingsDataActivity.kt")) {
            assertFalse(
                "$page 不该再引用 R.string.baopu_*（抱朴项已归位到抱朴设置页）",
                src(page).contains("R.string.baopu"),
            )
        }
    }

    // ── J4/J5/J6 检测瘦身纯函数 ───────────────────────────────────────────

    private fun check(id: String, label: String, ok: Boolean) =
        Check(id = id, label = label, ok = ok, detail = if (ok) "正常" else "缺文件")

    private fun agent(id: String, name: String, installed: Boolean) = AgentInfo(
        id = id, name = name, desc = "x", launchCmd = id, installCmd = "y", installed = installed,
    )

    /** 出厂四 agent + opencode（首页原本过滤的那个），复刻真实数据形态。 */
    private fun allAgents(hermesInstalled: Boolean) = listOf(
        agent("opencode", "OpenCode", true),
        agent("claude-code", "Claude Code", false),
        agent("hermes", "Hermes Agent", hermesInstalled),
        agent("antigravity", "AGY CLI（Antigravity）", false),
        agent("codex", "Codex CLI", false),
    )

    @Test
    fun `J4_检测面只认Hermes_其它agent的名字不出现`() {
        val rows = visibleRows(
            checks = listOf(check("dns", "DNS 配置", false)), // 一个与 agent 无关的失败项
            agents = allAgents(hermesInstalled = true),
        )
        assertEquals("缺 1 项应合并成一行", 1, rows.size)
        val joined = rows.joinToString("\n") { it.headline + it.details.joinToString() }
        assertTrue("Hermes 相关信息在（若缺）或其它检测项在", joined.isNotEmpty())
        for (other in listOf("Claude Code", "AGY", "Codex", "OpenCode")) {
            assertFalse("其它 agent 名不许出现在检测面：$joined", joined.contains(other))
        }
    }

    @Test
    fun `J5_全绿返回空列表_一行都不给`() {
        val rows = visibleRows(
            checks = listOf(
                check("proot", "proot 就绪", true),
                check("hermes-deps", "Hermes 依赖环境", true),
                check("network", "网络连通性", true),
            ),
            agents = allAgents(hermesInstalled = true),
        )
        assertEquals("全部正常 ⇒ 空列表（调用方整块不渲染）", emptyList<DetectionRow>(), rows)
    }

    @Test
    fun `J6_缺2项合并成一行_error为真_文案带数量`() {
        val rows = visibleRows(
            checks = listOf(
                check("hermes-deps", "Hermes 依赖环境", false),
                check("uv", "uv 配置", false),
                check("dns", "DNS 配置", true),
                check("storage", "存储权限", true),
            ),
            agents = allAgents(hermesInstalled = true),
        )
        assertEquals("缺失要合并成恰好一行", 1, rows.size)
        val row = rows.single()
        assertTrue("合并行是错误行（红叉）", row.error)
        assertTrue("文案要带数量：${row.headline}", row.headline.contains("2"))
        assertEquals("明细逐项列出缺失的东西", 2, row.details.size)
    }

    @Test
    fun `附_Hermes本体缺失也算一项_出现在明细里`() {
        val rows = visibleRows(
            checks = emptyList(),
            agents = allAgents(hermesInstalled = false),
        )
        assertEquals(1, rows.size)
        assertEquals(1, rows[0].details.size)
        assertTrue(rows[0].details[0].contains("Hermes"))
    }

    // ── J7 思考深度提示 ───────────────────────────────────────────────────

    @Test
    fun `J7_思考深度提示中英各一条且被新页渲染`() {
        val zh = res("values/strings.xml")
        val en = res("values-en/strings.xml")
        for (r in listOf(zh, en)) {
            assertTrue("思考深度提示字符串必须存在（中英各一条）", r.contains("baopu_effort_deepseek_hint"))
        }
        val page = src("ui/baopu/BaopuSettingsScreen.kt")
        assertTrue("提示要在抱朴设置页里被渲染", page.contains("R.string.baopu_effort_deepseek_hint"))
        // 定位文案与 read/bash 说明同样要中英成对（C 段与 §2.1-4 的判据延伸）
        for (key in listOf(
            "baopu_positioning_label", "baopu_positioning_value",
            "baopu_tools_alias_read", "baopu_tools_alias_bash",
        )) {
            assertTrue("缺中文字符串：$key", zh.contains(key))
            assertTrue("缺英文字符串：$key", en.contains(key))
        }
    }
}
