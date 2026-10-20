// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第六十八批 §1 的源码门禁：卸载弹窗要让人看见「要跑什么、删什么」，
 * 且「彻底清除」必须两段式确认 —— 第一下只进入确认态，不许直接跳终端。
 *
 * 判据（R-24 口径：先剥注释再结构断言）：
 * ① 弹窗里必须出现「将执行的命令」标签与真实命令串（等宽字体渲染）；
 * ② wipe 路径清单必须进弹窗文案（普通态与确认态各一处）；
 * ③ 彻底删的触发按钮只许把 `purgeArmed` 置 true，终端调用只许出现在
 *    `if (purgeArmed)` 的确认分支里；取消分支里一个终端调用都没有；
 * ④ 命令语义不变：`cmd` 与 `rm -rf` 路径集合仍是改前那一份，只多一条人话回声；
 * ⑤ wipe 为空（null/空表）时不提供「彻底清除」。
 *
 * 负例：把彻底删改回一键直跳 ⇒ ③ 红；把 rm -rf 的路径集合换掉 ⇒ ④ 红。
 */
class UninstallDialogVisibilityGateTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    private fun src(rel: String): String {
        val f = repoFile(rel)
        assertTrue("找不到 $rel：${f.path}", f.isFile)
        return f.readText()
    }

    /** 只剥 `//` 与块注释（保留字符串字面量、保留换行以稳住定位逻辑）。 */
    private fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' && text.startsWith("\"\"\"", i) -> {
                    val end = text.indexOf("\"\"\"", i + 3).let { if (it < 0) text.length else it + 3 }
                    out.append(text, i, end)
                    i = end
                }
                c == '"' || c == '\'' -> {
                    val quote = c
                    out.append(c)
                    i++
                    while (i < text.length) {
                        val d = text[i]
                        out.append(d)
                        i++
                        if (d == '\\' && i < text.length) { out.append(text[i]); i++; continue }
                        if (d == quote) break
                    }
                }
                c == '/' && i + 1 < text.length && text[i + 1] == '/' -> {
                    while (i < text.length && text[i] != '\n') i++
                }
                c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++
                    i = (i + 2).coerceAtMost(text.length)
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    private fun home(): String = stripComments(src("src/main/java/com/example/zhengdao/ui/HomeScreen.kt"))

    private fun dialog(): String {
        val s = home()
        val start = s.indexOf("uninstallTarget?.let { target ->")
        val end = s.indexOf("reinstallTarget?.let { target ->", start)
        assertTrue("找不到卸载弹窗（uninstallTarget?.let）", start >= 0)
        assertTrue("找不到卸载弹窗的结束边界（后面应有重新安装弹窗）", end > start)
        return s.substring(start, end)
    }

    private fun confirmBlock(): String {
        val d = dialog()
        val start = d.indexOf("confirmButton = {")
        val end = d.indexOf("dismissButton = {", start)
        assertTrue("卸载弹窗里找不到 confirmButton", start >= 0)
        assertTrue("卸载弹窗里找不到 dismissButton", end > start)
        return d.substring(start, end)
    }

    @Test
    fun `弹窗显式列出将要执行的命令`() {
        val d = dialog()
        assertTrue("弹窗缺「将执行的命令」标签", d.contains("R.string.home_uninstall_command_label"))
        assertTrue(
            "弹窗必须把真实命令串渲染出来（普通态只列卸载命令，确认态带 rm -rf）",
            d.contains("uninstallCommandWithWipe(cmd, if (purgeArmed) wipe else null)"),
        )
        assertTrue("命令串用等宽字体，路径里的空格/斜杠才看得清", d.contains("fontFamily = FontFamily.Monospace"))
    }

    @Test
    fun `wipe路径清单进弹窗文案`() {
        val d = dialog()
        assertTrue(
            "普通态必须列出将彻底删除的路径（wipe 清单）",
            d.contains("R.string.home_uninstall_purge, it.joinToString(\"、\")"),
        )
        assertTrue(
            "确认态必须再列一次同样的路径清单",
            d.contains("R.string.home_uninstall_purge_confirm"),
        )
        assertTrue(
            "确认态的路径清单也必须来自同一个 wipe 列表",
            d.contains("wipe.orEmpty().joinToString(\"、\")"),
        )
    }

    @Test
    fun `彻底清除第一下只进入确认态_不跳终端`() {
        val d = dialog()
        assertEquals(
            "彻底清除的触发按钮只能有一处，且只把 purgeArmed 置 true",
            1,
            Regex(Regex.escape("onClick = { purgeArmed = true }")).findAll(d).count(),
        )
        assertTrue(
            "触发按钮必须是「只置确认态」的一行（多了终端调用就说明又变回一键直跳）",
            d.contains("TextButton(onClick = { purgeArmed = true }) {"),
        )
        val dismissTail = d.substring(d.indexOf("dismissButton = {"))
        assertFalse(
            "取消/返回分支里不许出现终端调用（第一次点击只能进入确认态）",
            dismissTail.contains("onOpenTerminal"),
        )
        assertFalse(
            "取消/返回分支里不许拼 rm -rf",
            dismissTail.contains("rm -rf"),
        )
    }

    @Test
    fun `彻底清除的执行只在purgeArmed确认分支里`() {
        val cb = confirmBlock()
        val armed = cb.indexOf("if (purgeArmed) {")
        val elseBranch = cb.indexOf("} else {", armed)
        assertTrue("confirmButton 必须以 if (purgeArmed) 区分两段", armed >= 0)
        assertTrue("confirmButton 必须有 else（普通卸载）分支", elseBranch > armed)

        val purgeCall = cb.indexOf("onOpenTerminal(uninstallTerminalCommand(cmd, purgeEcho, wipe), null)")
        assertTrue(
            "确认态的终端调用必须带 purgeEcho 与 wipe 列表",
            purgeCall >= 0,
        )
        assertTrue(
            "彻底删的终端调用必须坐在 if (purgeArmed) 分支里（第一次点击不许执行）",
            purgeCall in (armed + 1) until elseBranch,
        )

        val normalCall = cb.indexOf("onOpenTerminal(uninstallTerminalCommand(cmd, uninstallEcho, null), null)")
        assertTrue(
            "普通卸载的终端调用必须坐在 else 分支里，且不拼 wipe",
            normalCall > elseBranch,
        )
        assertEquals(
            "整个弹窗只该有两条进终端的路（普通卸载 / 确认后的彻底清除）",
            2,
            Regex("onOpenTerminal\\(").findAll(dialog()).count(),
        )
    }

    @Test
    fun `wipe为空时不提供彻底清除`() {
        val d = dialog()
        assertTrue(
            "wipe 必须先过滤空表：路径为空就不该出现「彻底清除」",
            d.contains("wipeTargets[target.id]?.takeIf { it.isNotEmpty() }"),
        )
        assertTrue(
            "「彻底清除」按钮必须同时判 wipe 与 !purgeArmed",
            d.contains("if (wipe != null && !purgeArmed)"),
        )
    }

    @Test
    fun `卸载命令语义不变_只多回声`() {
        val s = home()
        assertEquals(
            "`&& rm -rf ` 只该在命令拼装函数里出现一次；弹窗里不许另行拼一条",
            1,
            Regex(Regex.escape("&& rm -rf ")).findAll(s).count(),
        )
        assertTrue(
            "路径集合仍用 wipe 原表、空格分隔（与改前逐字一致）",
            s.contains("cmd + \" && rm -rf \" + wipe.joinToString(\" \")"),
        )
        assertFalse("弹窗里不许再手拼 rm -rf（可见层与执行层必须同源）", dialog().contains("rm -rf"))
    }

    @Test
    fun `回声在命令前且原命令逐字保留`() {
        val cmd = "npm uninstall -g @scope/pkg"
        val out = uninstallTerminalCommand(cmd, "证道：正在卸载 Claude Code…", null)
        assertEquals("echo '证道：正在卸载 Claude Code…' && $cmd", out)
        assertTrue("原卸载命令必须逐字保留在回声之后", out.endsWith(" && $cmd"))
        assertFalse("普通卸载不许夹带 rm -rf", out.contains("rm -rf"))
    }

    @Test
    fun `彻底删的wipe路径集合与命令不变`() {
        val cmd = "npm uninstall -g hermes"
        val wipe = listOf("~/.hermes")
        val out = uninstallTerminalCommand(cmd, "证道：正在彻底清除 Hermes…", wipe)
        assertEquals("echo '证道：正在彻底清除 Hermes…' && $cmd && rm -rf ~/.hermes", out)
        assertEquals(cmd + " && rm -rf ~/.hermes", uninstallCommandWithWipe(cmd, wipe))
        assertEquals("空表按普通卸载处理，不拼 rm -rf", cmd, uninstallCommandWithWipe(cmd, emptyList()))
    }

    @Test
    fun `名称里的单引号不会撕开回声`() {
        val out = uninstallTerminalCommand("npm uninstall -g x", "证道：正在卸载 O'Brien…", null)
        assertEquals("echo '证道：正在卸载 O'\\''Brien…' && npm uninstall -g x", out)
    }

    @Test
    fun `新增文案键中英都在且非空`() {
        val keys = listOf(
            "home_uninstall_command_label",
            "home_uninstall_purge_title",
            "home_uninstall_purge_confirm",
            "home_uninstall_purge_confirm_action",
            "home_uninstall_purge_back",
            "home_uninstall_echo",
            "home_purge_echo",
        )
        val zh = repoFile("src/main/res/values/strings.xml").readText()
        val en = repoFile("src/main/res/values-en/strings.xml").readText()
        for (k in keys) {
            val marker = "name=\"$k\">"
            assertTrue("中文缺 `$k`", zh.contains(marker))
            assertTrue("英文缺 `$k`", en.contains(marker))
            assertFalse("中文 `$k` 是空串", zh.contains(marker + "</string>"))
            assertFalse("英文 `$k` 是空串", en.contains(marker + "</string>"))
        }
    }
}
