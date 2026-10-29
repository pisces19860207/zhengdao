// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// ⚠️ 本文件是**规范（spec）**，不是门禁：生产未接线（#271-L10），它**不会红**。
//    它钉的是「判据长什么样」；接线那天把判据搬进生产、用例名不变，它才升格为门禁。
//
// 第二百七十一批 · 法典规范（spec，非门禁） **L10 失败不得静默（代码面）**
// ——派工单 271 原文「负例：catch 里只 log 不外传 ⇒ 拒」。
//
// ## 判据（可执行）
// `catch` 块里只有 log、既不 rethrow 也不把失败外传（返回失败态／落账／置 `lastError`／
// `Result.failure`）⇒ 判「静默」，必须被认出。
//
// ## 生产状态（诚实标注）——**本文件是规范，不是门禁；生产未接线（#271-L10），它不会红**
// 生产里**没有**这条静态闸（全仓 147 处 catch，逐处判定要先分「best-effort 清理」与
// 「真失败被吞」两类，属设计问题）⇒ 缺陷 #271-L10 在账。
// 本条的行为面（反复失败未归档必须报）**已经接线**，在门禁账上：
// `law/L10UnarchivedFailureGateTest.kt`。
//
// ## 负例（造违规即红——红的是**判据**，不是生产：生产里还没有它）
// `catch { Log.e(...) }`／空 catch／log 加注释 ⇒ 必须判静默（判据写松了就红）。
package com.example.zhengdao.law.spec

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class L10SilentCatchSpec {

    internal object SilentCatch {

        /** 失败「外传」的写法（任一出现 ⇒ 不算静默）。 */
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

    // ── 正例：把失败外传了 ⇒ 不算静默 ───────────────────────────────────────

    @Test
    fun `L10_正例2_把失败外传就不算静默`() {
        assertFalse(
            "catch 里 rethrow 了就不算静默",
            SilentCatch.isSilent("""catch (e: IOException) { Log.e(TAG, "写失败", e); throw e }"""),
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

    // ── 负例：只 log 不外传 ⇒ 必须判静默 ────────────────────────────────────

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
    }
}
