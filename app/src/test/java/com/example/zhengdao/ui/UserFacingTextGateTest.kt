// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审计第 2 期 **R-29**（第⑯刀 #1）的验收门禁：**用户可见文案的实参里不得出现内部异常痕迹**。
 *
 * 背景：`TerminalActivity.kt` 三处与 `SettingsEnvActivity.kt` 三处把 `t.message` / `it.message`
 * （Java 异常原文，含 `/data/…` 路径、errno 等）直接拼进 Toast / InstallFlow 文案。
 * 正确范式＝同文件既有写法（TerminalActivity.installFailed）：**原文只落 RunLog，
 * UI 拿 `HumanizeError.title(...)` 的人话**。
 *
 * 判据（R-24 口径：先剥注释，再按调用点窗口判）：
 * 对每个点名的文案键（term_paste_failed 等 6 个），在**剥注释后的源码**里定位 `R.string.<键>`
 * 的每一处出现，取其后一小段窗口（覆盖该次调用的实参），断言窗口内不出现
 * `.message` / `javaClass` / `Exception` / `java.lang.` / `/data/` / `/storage/` 这类内部痕迹；
 * 并断言实参走的是 HumanizeError 人话（env_patch_fallback 的实参是 `why` 局部变量，
 * 单独断言「文件里所有 `val why =` 都来自 HumanizeError.title、没有一处来自 t.message」）。
 *
 * 为什么不上 Compose UI 测试：与 `InstallerCleanBusyGateTest` 同一判断（Compose UI 测试为 0，
 * 不为本刀加依赖定规则）。门禁证明的是「没人把异常原文塞回用户文案」，不是「弹窗一定弹对」。
 */
class UserFacingTextGateTest {

    private fun code(rel: String): String {
        val f = File(rel).let { if (it.exists()) it else File("app/$rel") }
        assertTrue("找不到 $rel：${f.path}", f.isFile)
        return stripComments(f.readText())
    }

    /** 只剥 `//` 与块注释（保留字符串字面量）：注释里写一句 `.message` 不算数。 */
    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c == '"' && src.startsWith("\"\"\"", i) -> {
                    val end = src.indexOf("\"\"\"", i + 3).let { if (it < 0) src.length else it + 3 }
                    out.append(src, i, end)
                    i = end
                }
                c == '"' || c == '\'' -> {
                    val quote = c
                    out.append(c)
                    i++
                    while (i < src.length) {
                        val d = src[i]
                        out.append(d)
                        i++
                        if (d == '\\' && i < src.length) { out.append(src[i]); i++; continue }
                        if (d == quote) break
                    }
                }
                c == '/' && i + 1 < src.length && src[i + 1] == '/' -> {
                    while (i < src.length && src[i] != '\n') i++
                }
                c == '/' && i + 1 < src.length && src[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < src.length && !(src[i] == '*' && src[i + 1] == '/')) i++
                    i = (i + 2).coerceAtMost(src.length)
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /** 内部痕迹判据：异常原文 / 类名 / 内部路径都不许出现在用户可见文案的实参里。 */
    private val INTERNAL_TRACE = Regex(
        """\.message\b|javaClass|\bException\b|java\.lang\.|/data/|/storage/"""
    )

    private fun assertCallArgsAreHumanized(rel: String, stringKey: String) {
        val s = code(rel)
        val anchor = "R.string.$stringKey"
        val hits = Regex(Regex.escape(anchor)).findAll(s).toList()
        assertTrue("$rel 里找不到锚点 `$anchor`（锚点变了就更新本测试）", hits.isNotEmpty())
        hits.forEach { m ->
            // 窗口覆盖该次调用的实参（调用语句一般不超过两三行）
            val window = s.substring(m.range.first, (m.range.first + 240).coerceAtMost(s.length))
            assertTrue(
                "$rel：`$anchor` 的用户可见实参里出现内部痕迹（R-29 回潮）；窗口：\n$window",
                !INTERNAL_TRACE.containsMatchIn(window),
            )
        }
    }

    @Test
    fun `R-29 终端页三处错误文案的实参必须是人话`() {
        val rel = "src/main/java/com/example/zhengdao/TerminalActivity.kt"
        listOf("term_paste_failed", "term_clear_failed", "term_browser_failed").forEach {
            assertCallArgsAreHumanized(rel, it)
        }
        // 正向：每处的**至少一个**出现点实参窗口里应能看到 HumanizeError（人话接线真的在）。
        // 注：term_browser_failed 在同文件还有一处空参调用（无失败语境，走既有键即可），
        // 所以不能要求"每一个"出现点都带 HumanizeError，只能要求"至少一个"。
        val s = code(rel)
        listOf("term_paste_failed", "term_clear_failed", "term_browser_failed").forEach { key ->
            val anyHumanized = Regex(Regex.escape("R.string.$key")).findAll(s).any { m ->
                val window = s.substring(m.range.first, (m.range.first + 240).coerceAtMost(s.length))
                window.contains("HumanizeError")
            }
            assertTrue(
                "TerminalActivity：`$key` 的实参应至少有一处走 HumanizeError.title(...)（原文只进 RunLog）",
                anyHumanized,
            )
        }
    }

    @Test
    fun `R-29 环境页错误文案的实参必须是人话`() {
        val rel = "src/main/java/com/example/zhengdao/ui/SettingsEnvActivity.kt"
        listOf("env_check_failed", "env_patch_fallback", "env_update_failed").forEach {
            assertCallArgsAreHumanized(rel, it)
        }
        // env_patch_fallback 的实参是局部变量 why —— 断言文件里所有 `val why =` 都来自
        // HumanizeError.title，没有一处来自异常原文（原文只进 RunLog）。
        val s = code(rel)
        val whyTotal = Regex("""val why =""").findAll(s).count()
        val whyHuman = Regex("""val why = HumanizeError\.title\(""").findAll(s).count()
        assertTrue(
            "SettingsEnvActivity：应有两处 `val why =`（增量回退与整体失败），实际 $whyTotal 处",
            whyTotal == 2,
        )
        assertTrue(
            "SettingsEnvActivity：两处 `val why =` 都必须来自 HumanizeError.title(...)，" +
                "实际 $whyHuman 处 —— 出现 `val why = t.message` 即 R-29 回潮",
            whyHuman == 2,
        )
        assertFalseLike(s)
    }

    /** 反向兜底：整个文件（剥注释后）不得出现 `val why = t.message` 这类直拼。 */
    private fun assertFalseLike(s: String) {
        assertTrue(
            "SettingsEnvActivity：发现 `val why = t.message` 直拼（R-29 回潮）",
            !s.contains("val why = t.message"),
        )
    }
}
