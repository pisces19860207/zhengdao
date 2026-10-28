// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 259 批 B 段 #10 的第①②处：技能账本没写成，**答复与返回值都得说话**。
//
// 改前表现（`全仓隐患审计-2026-10-28.md` A-P2-21）：
// · `BaopuRuntime.recordSkillUse` 的 `getOrElse { RunLog.log(...); null }`——回给工具答复的
//   那句 `null` 与「读过了、不用提醒」**一模一样**：日志只有开发者看得见，模型和用户拿到的是
//   "一切正常"。账本没写成 ⇒ 晋升/退役统计悄悄少一条。
// · `BaopuRuntime.recordSkillUseManual` 返回 `Unit`——设置页那句「已记一次」在写失败时照样弹。
//   本批只把布尔值交出去：界面在 `BaopuSettingsScreen`（清单外），接线记进报告 §7。
//
// 统一原则（派工单 §1 本批静默吞异常那条）：**「没有」和「坏了」必须可分**。
package com.example.zhengdao.baopu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SkillLedgerOutcomeTest {

    private fun src(rel: String): String {
        val f = listOf(rel, "../$rel").map { File(it) }.firstOrNull { it.isFile }
        assertTrue("找不到源码 $rel（门禁不许空转，cwd=${File("").absolutePath}）", f != null)
        return f!!.readText()
    }

    private val runtime: String get() = src("src/main/java/com/example/zhengdao/baopu/BaopuRuntime.kt")

    // ── ① 纯函数：账本没写成 ⇒ 有一句人话 ────────────────────────────────

    @Test
    fun `记录失败给得出话不是 null`() {
        // 改前：这条路径回 null（与「不用提醒」同形）。现在回一句带异常类别的说明。
        val note = BaopuRuntime.skillUseLedgerNote(
            java.util.concurrent.TimeoutException("等 DB 提交等了 3 秒"),
        )
        assertTrue("要说清是账本没写成", note.contains("没记进账本"))
        assertTrue("要带异常类别（能定位）", note.contains("TimeoutException"))
        assertTrue("要说本轮照跑（降级口径，不是拦）", note.contains("本轮照跑"))
        assertTrue("要说统计会少一条（后果写明白）", note.contains("晋升/退役的统计会少这一条"))
    }

    @Test
    fun `那句话不抄异常原文也不带路径`() {
        // 日志与工具答复都会进上下文：异常 message 可能带路径/账号，只取 javaClass.simpleName。
        val note = BaopuRuntime.skillUseLedgerNote(
            IllegalStateException("C:\\Users\\someone\\secret\\db.sqlite 打不开 token=abc"),
        )
        listOf("someone", "secret", "db.sqlite", "token", "abc").forEach {
            assertFalse("不该出现：$it（note=$note）", note.contains(it))
        }
    }

    @Test
    fun `闸把静默 null 换成了说明`() {
        val at = runtime.indexOf("抱朴：skill_use 记录失败")
        assertTrue("找不到原日志那句（说明日志被删了——不许）", at >= 0)
        val after = runtime.substring(at).lines().take(6).joinToString("\n")
        assertTrue("日志之后必须交出一句话", after.contains("skillUseLedgerNote(it)"))
        assertFalse(
            "改前写法不许回来：日志之后紧跟一行裸 null（读坏了被当没事）",
            Regex("(?m)^ {12}null$").containsMatchIn(after),
        )
    }

    @Test
    fun `降级是说明不是拦也不改成崩`() {
        val at = runtime.indexOf("抱朴：skill_use 记录失败")
        val block = runtime.substring(at).lines().take(5).joinToString("\n")
        assertFalse("不许 throw（§3：把静默改成崩溃）", block.contains("throw "))
        assertFalse("不许 rethrow 原异常", block.contains("throw it"))
        // 57 批红线：账本卡住业务就是本末倒置 ⇒ 本轮结果不许改（提醒话术仍走同一个返回值）
        assertTrue("原有那句提醒话术一字未改",
            runtime.contains("先用起来验证它还行不行，别照单全收）\" else null"))
    }

    // ── ② 手动「用一次」：把落库结果交出去 ───────────────────────────────

    @Test
    fun `手动记一次返回布尔不是 unit`() {
        assertTrue("签名要交结果（改前是 Unit）",
            runtime.contains("fun recordSkillUseManual(appCtx: Context, name: String, skillFile: java.io.File?): Boolean"))
        assertTrue("空白名直接算没写", runtime.contains("if (name.isBlank()) return false"))
        assertTrue("默认没写成", runtime.contains("var wrote = false"))
        assertTrue("两条都落了才算落", runtime.contains("wrote = true"))
        assertTrue("把结果交回调用方", runtime.contains("return wrote"))
    }

    @Test
    fun `被闸门拦下的那条不算落库成功`() {
        val gate = runtime.indexOf("手动使用效果信号被闸门拦下")
        val ok = runtime.indexOf("wrote = true")
        assertTrue("拦下分支还在", gate >= 0)
        assertTrue("落库成功标记在两条写盘之后", ok >= 0)
        assertTrue("闸门拦下 ⇒ 提前 return，wrote 保持 false（顺序即判据）", gate < ok)
        assertTrue("拦下走的仍是 return@runCatching（不改控制流）",
            runtime.contains("return@runCatching"))
    }

    @Test
    fun `只加日志不返回值这种糊法不算修好`() {
        // §3 负例：不许把 RunLog 当返回值用。
        val head = runtime.indexOf("fun recordSkillUseManual")
        val body = runtime.substring(head, head + 1600)
        assertTrue("返回值必须是 Boolean", Regex("fun recordSkillUseManual\\([^)]*\\): Boolean").containsMatchIn(body))
        assertFalse("不许退回 Unit", runtime.contains("fun recordSkillUseManual(appCtx: Context, name: String, skillFile: java.io.File?) {"))
    }
}
