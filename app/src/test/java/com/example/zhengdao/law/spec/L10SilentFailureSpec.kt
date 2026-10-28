// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// ⚠️ 本文件是**规范（spec）**，不是门禁：生产未接线（#271-L10），它**不会红**。
//    它钉的是「判据长什么样」；接线那天把判据搬进生产、用例名不变，它才升格为门禁。
//
// 第二百七十一批 · 法典规范（spec，非门禁） **L10 失败不得静默**（派工单 271「负例：catch 里只 log 不外传 ⇒ 拒」）。
//
// ## 判据（可执行）
// ① **行为面**：同一能力近 7 天失败 ≥3 次，而 208 里既没有退役行、正文也没软删 ⇒
//    勘验必须给 HIT（"该收没收拾"），不许当成没发生；
// ② **代码面**：`catch` 块里只有 log、既不 rethrow 也不把失败外传（返回失败态／落账／置
//    `lastError`）⇒ 判「静默」，必须被探针认出。
//
// ## 生产状态（诚实标注）——**本文件是规范，不是门禁；生产未接线（#271-L10），它不会红**
// ① 已在生产（`UnarchivedFailureCheck`，239 交付）；
// ② **只有判据探针，没有落成静态闸**（全仓 147 处 catch，逐处判定要先分「best-effort 清理」
//    与「真失败被吞」两类，属设计问题）⇒ 缺陷 #271-L10 在账。
//
// ## 负例（造违规即红——红的是**判据**，不是生产：生产里还没有它）
// ① 三次失败未归档 ⇒ 勘验必须 HIT（判据放宽就红）；
// ② `catch { Log.e(...) }` 这样的块 ⇒ 探针必须认出（探针瞎了就红）。
package com.example.zhengdao.law.spec


import com.example.zhengdao.baopu.inspect.InspectVerdict
import com.example.zhengdao.baopu.inspect.SkillFailure
import com.example.zhengdao.baopu.inspect.UnarchivedFailureCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class L10SilentFailureSpec {

    private val now = 1_000_000_000L

    internal object SilentCatch {

        /** 失败「外传」的四种写法（任一出现 ⇒ 不算静默）。 */
        private val ESCAPES: List<String> = listOf(
            "throw ", "lastError", "DecisionLedger.record", "return ToolOutcome.Fail",
            "return false", "onFailure", "Result.failure",
        )

        /** 只 log 不外传 ⇒ 静默。 */
        fun isSilent(catchBlock: String): Boolean {
            val body = catchBlock.substringAfter('{', "").trim().removeSuffix("}").trim()
            if (body.isBlank()) return true
            val logs = body.contains("Log.") || body.contains("Logcat") || body.contains("println(")
            val escapes = ESCAPES.any { body.contains(it) }
            return logs && !escapes
        }
    }

    // ── 正例：失败被收拾了 ⇒ 不报 ───────────────────────────────────────────

    @Test
    fun `L10_正例_失败已归档则勘验不报`() {
        val failures = (1..3).map { SkillFailure("日报整理", now - it * 60_000L) }
        val finding = UnarchivedFailureCheck.check(
            failures = failures,
            archivedNames = setOf("日报整理"),
            nowMs = now,
        )
        assertEquals("已退役的技能不该再被报「未归档」", InspectVerdict.CLEAR, finding.verdict)
        assertEquals("命中数必须是 0", 0, finding.count)
        // 干净代码：catch 里 rethrow ⇒ 不算静默
        assertFalse(
            "catch 里 rethrow 了就不算静默",
            SilentCatch.isSilent("""catch (e: IOException) { Log.e(TAG, "写失败", e); throw e }"""),
        )
    }

    // ── 负例①：三次失败没人收拾 ⇒ 必须报 ────────────────────────────────────

    @Test
    fun `L10_负例1_三次失败未归档必须报`() {
        val failures = (1..3).map { SkillFailure("日报整理", now - it * 60_000L) }
        val finding = UnarchivedFailureCheck.check(
            failures = failures,
            archivedNames = emptySet(),
            nowMs = now,
        )
        assertEquals("反复失败没人收拾必须出结论（HIT）", InspectVerdict.HIT, finding.verdict)
        assertEquals("命中条数", 1, finding.count)
        assertTrue("结论行要指名道姓", finding.oneLine.contains("日报整理"))
        assertTrue("证据要指到那条技能", finding.evidence.any { it.contains("日报整理") })
        // 两次不够（阈值是 3）——防「一次就报」的假门禁
        val twice = UnarchivedFailureCheck.check(
            failures = (1..2).map { SkillFailure("日报整理", now - it * 60_000L) },
            archivedNames = emptySet(),
            nowMs = now,
        )
        assertFalse("两次不该报", twice.verdict == InspectVerdict.HIT)
    }

    // ── 负例②：catch 里只 log 不外传 ⇒ 探针必须认出 ─────────────────────────

    @Test
    fun `L10_负例2_catch里只log不外传必须被判静默`() {
        assertTrue(
            "只 log 不外传就是静默失败",
            SilentCatch.isSilent("""catch (e: Exception) { Log.w(TAG, "忽略", e) }"""),
        )
        assertTrue("空 catch 更静默", SilentCatch.isSilent("""catch (e: Exception) { }"""))
        assertTrue(
            "log 加注释也不算外传",
            SilentCatch.isSilent("""catch (e: Exception) { Log.e(TAG, "失败", e) /* 就这样吧 */ }"""),
        )
        assertFalse(
            "把失败落账了就不算静默",
            SilentCatch.isSilent("""catch (e: Exception) { Log.e(TAG, "x", e); lastError = e.message }"""),
        )
        assertFalse(
            "返回失败态就不算静默",
            SilentCatch.isSilent("""catch (e: Exception) { Log.e(TAG, "x", e); return false }"""),
        )
    }
}
