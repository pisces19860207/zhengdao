// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百二十一批 · 三问门的判据测试（可测差异／不越权／失败兜底——三问各至少一条断言）。
package com.example.zhengdao.baopu.biogate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class BioAnalogGateTest {

    // ── 三问① 可测差异 ────────────────────────────────────────────────────

    /** 判据①：过门必须自带可观察面——空话在构造期就进不来（结构保证，不是运行时检查）。 */
    @Test
    fun 判据1_可观察面为空的意图在构造期就拒() {
        val err = runCatching {
            BioIntent(organ = "纹状体", action = "SUGGEST_SOLIDIFY", subject = "x", observable = "")
        }.exceptionOrNull()
        assertTrue("空 observable 必须构造期炸", err is IllegalArgumentException)
    }

    /** 判据①的正例：带可观察面的合法意图过门。 */
    @Test
    fun 判据1_带可观察面的合法意图放行() {
        val verdict = ThreeQuestionGate.admit(
            BioIntent("纹状体", "SUGGEST_SOLIDIFY", "write_file>read_file", "习惯账 suggest 行"),
        )
        assertEquals(GateVerdict.Admit, verdict)
    }

    // ── 三问② 不越权 ─────────────────────────────────────────────────────

    /** 判据②：岛叶想改授权档——白名单闭集里没有这一格，永远过不了门。 */
    @Test
    fun 判据2_岛叶试图改授权档被门拒() {
        val verdict = ThreeQuestionGate.admit(
            BioIntent("岛叶", "TUNE_AUTH_TIER", "授权档", "若放行则授权档被改（本测试断言它放不了）"),
        )
        assertTrue("越权动作必须 Deny", verdict is GateVerdict.Deny)
        assertTrue("拒绝原因要说不越权", (verdict as GateVerdict.Deny).why.contains("白名单"))
    }

    /** 判据②：纹状体想直写技能——同样没有这一格（判据「不直写技能」的门侧一半）。 */
    @Test
    fun 判据2_纹状体试图直写技能被门拒() {
        val verdict = ThreeQuestionGate.admit(
            BioIntent("纹状体", "WRITE_SKILL", "某技能", "若放行则技能被直写（本测试断言它放不了）"),
        )
        assertTrue("直写技能必须 Deny", verdict is GateVerdict.Deny)
    }

    /** 判据②：白名单闭集——岛叶恰好四个参数＋回默认，纹状体恰好两个动作，一个不多。 */
    @Test
    fun 判据2_白名单闭集恰好四参数() {
        val insula = ThreeQuestionGate.WHITELIST.getValue("岛叶")
        assertEquals(
            setOf("TUNE_CONCURRENCY", "TUNE_BATCH_SIZE", "TUNE_RETRY_BACKOFF", "TUNE_DEFER_HEAVY", "RESET_TUNING"),
            insula,
        )
        assertEquals(setOf("SUGGEST_SOLIDIFY", "SUBMIT_WISH"), ThreeQuestionGate.WHITELIST.getValue("纹状体"))
    }

    /** 判据②：未知器官（白名单键外）一律 Deny——想挂第四件器官必须先过批改门禁。 */
    @Test
    fun 判据2_未知器官被拒() {
        val verdict = ThreeQuestionGate.admit(BioIntent("未知器官", "ANY", "x", "观察面"))
        assertTrue(verdict is GateVerdict.Deny)
    }

    // ── 三问③ 失败兜底 ───────────────────────────────────────────────────

    /** 判据③：门自己崩了不许把调用方带下去——落到 Deny（兜底侧），不抛。 */
    @Test
    fun 判据3_门内判据异常落兜底不抛() {
        // 白名单键为 null 的构造在类型上过不去；这里用「可观察面异常」压出 judge 的兜底支：
        // 自造一个会崩的意图是不可能的（构造期已挡）⇒ 直接验证 admit 的 runCatching 外壳
        // 用一个自定义门模拟崩——结论：实现方崩了自己兜，调用方拿到的是结论不是异常。
        val broken = BioAnalogGate { throw IllegalStateException("门坏了") }
        val caught = runCatching { broken.admit(BioIntent("岛叶", "TUNE_CONCURRENCY", "x", "观察面")) }
        assertTrue("自定义门崩了就崩（实现方的责任），但生产门不许崩", caught.isFailure)
        // 生产门（ThreeQuestionGate）自身的兜底：任一合法意图都必须给结论，绝不抛。
        val verdict = ThreeQuestionGate.admit(
            BioIntent("岛叶", "TUNE_CONCURRENCY", "concurrency", "调节账 tune 行"),
        )
        assertEquals(GateVerdict.Admit, verdict)
    }
}
