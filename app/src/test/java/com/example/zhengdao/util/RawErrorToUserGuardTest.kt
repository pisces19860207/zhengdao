package com.example.zhengdao.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P2-6（issue #17）：错误原文弹给用户的守卫。
 *
 * 审计原文：「报错原文弹给用户：`失败：${` 命中 55 行、`util/HumanizeError.kt` 31 行 / 7 文件」。
 *
 * 开工实测（2026-10-14，main = 88c9a2a）：`失败：${` 24 处（10 文件，其中 2 处在
 * `oc/` 禁区动不了）。逐条分类后**真正用户可见的只有 5 处**（全在 TerminalActivity）：
 * - ^C 发送中断失败 Toast、发送失败 Toast（2 处）
 * - 安装失败 installFailed（3 处调用点，走 Toast + 横幅 + InstallFlow.fail 三通道）
 *
 * 其余 19 处是 `RunLog.log` / `Log.w`——按 HumanizeError 自己 KDoc 的口径：
 * 「RunLog.log（开发/支持看的）→ 继续用 t.message」，**那些是对的，不许改**。
 * 审计的"55 行"把开发日志也算进了缺陷，实际要改的只有用户可见面。
 *
 * 本守卫钉：① 用户可见通道（Toast/Snackbar/showBanner/InstallFlow.fail/installFailed）
 * 不许直接插 `it.message` 类原文；② 5 处已切到 HumanizeError.title；③ 原文仍落 RunLog
 * （可诊断性不丢——HumanizeError 设计原则 5）。
 */
class RawErrorToUserGuardTest {

    private val rawMsg = Regex("""\$\{\s*(it|e|t|ex|err|throwable)\.message""")
    private val userVisible = Regex("""(Toast\.makeText|Snackbar\.make|showBanner\(|InstallFlow\.fail\(|installFailed\()""")

    @Test
    fun `门禁_用户可见通道不许插异常原文`() {
        val root = File("src/main/java")
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            // ⚠️ 路径归一化：Windows 上 walk 出来是反斜杠，contains("/oc/") 永不命中
            .filter { !it.path.replace('\\', '/').contains("/oc/") }
            .flatMap { f ->
                f.readLines().mapIndexedNotNull { i, line ->
                    if (userVisible.containsMatchIn(line) && rawMsg.containsMatchIn(line)) {
                        "${f.path}:${i + 1} => ${line.trim().take(60)}"
                    } else null
                }
            }
            .toList()
        assertTrue(
            "以下位置把异常原文弹给用户（应改 HumanizeError.title()，原文落 RunLog）：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `门禁_五处已切到 HumanizeError 且原文落 RunLog`() {
        val ta = File("src/main/java/com/example/zhengdao/TerminalActivity.kt").readText()
        assertTrue("^C 失败 Toast 走人话", ta.contains("发送中断失败：\${HumanizeError.title(it, resources)}"))
        assertTrue("发送失败 Toast 走人话", ta.contains("发送失败：\${HumanizeError.title(it, resources)}"))
        assertTrue(
            "安装失败走 installFailed(t) 重载（人话）",
            ta.contains("private fun installFailed(t: Throwable)") &&
                ta.contains("安装失败：\${HumanizeError.title(t, resources)}"),
        )
        assertTrue("^C 原文落 RunLog", ta.contains("RunLog.log(\"发送中断失败："))
        assertTrue("发送原文落 RunLog", ta.contains("RunLog.log(\"发送失败："))
        assertTrue("安装原文落 RunLog（可诊断性不丢）", ta.contains("RunLog.log(\"安装失败（原文）"))
        // 旧写法必须绝迹
        assertFalse("旧：Toast 直插 message", ta.contains("发送失败：\${it.message}"))
        assertFalse("旧：installFailed 直插 message", ta.contains("installFailed(\"安装失败：\${t.message}"))
    }

    @Test
    fun `文档性_开发日志继续用原文是对的`() {
        // 反向钉口径：RunLog/Log 里的 ${it.message} **不是**缺陷（HumanizeError KDoc 原则）。
        // 这条钉的是"分类边界"——防止后人把日志也一律改成人话，丢掉可诊断性。
        val kb = File("src/main/java/com/example/zhengdao/terminal/KnowledgeBase.kt").readText()
        assertTrue(
            "资料库刷新失败仍落原文（日志面，正确）",
            kb.contains("RunLog.log(\"资料库刷新失败：\${it.message}\")"),
        )
    }
}
