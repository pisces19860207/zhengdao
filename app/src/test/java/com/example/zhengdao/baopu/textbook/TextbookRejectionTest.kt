// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 798 判据④：四条拒收各有正反例。经验类缺样本量只进待验证池；
// "引不到字段/判据 id"必须明确提示这是读书笔记。
package com.example.zhengdao.baopu.textbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextbookRejectionTest {

    @Test
    fun `经验类缺样本量只进待验证池_带样本量可入主库`() {
        val missing = TextbookValidator.validate(
            Fixtures.base(sourceType = "经验总结", scope = "老观测员的口述，很好用"),
        )
        assertEquals("缺样本量不该进主库，实为 ${missing.pool}", Pool.PENDING, missing.pool)
        assertTrue(RejectCode.EXPERIENCE_MISSING_SAMPLE.name in Fixtures.codes(missing))
        assertTrue("原因要说清只能进待验证池：${Fixtures.messages(missing)}",
            "待验证池" in Fixtures.messages(missing))

        val n0 = TextbookValidator.validate(Fixtures.base(sourceType = "经验总结", scope = "样本 n=0"))
        assertEquals(Pool.PENDING, n0.pool)

        val smallSample = TextbookValidator.validate(Fixtures.base(sourceType = "经验总结", scope = "样本 n=12"))
        assertEquals("样本<20 只进待验证池", Pool.PENDING, smallSample.pool)

        val withSample = TextbookValidator.validate(Fixtures.base(sourceType = "经验总结", scope = "样本 n=24"))
        assertEquals(Pool.MAIN, withSample.pool)
    }

    @Test
    fun `非经验类不检查样本量`() {
        val v = TextbookValidator.validate(Fixtures.base(sourceType = "标准规范", scope = "无样本量说明"))
        assertEquals(Pool.MAIN, v.pool)
    }

    @Test
    fun `引不到字段或判据id拒收并提示这是读书笔记`() {
        val notes = listOf(
            "盐湖区的屋顶常压盐碱土，这个设定很有意思。",
            "判分的时候要仔细一点，多想想人物会不会知道。",
            "很好用的经验，值得记下来。",
        )
        notes.forEach { text ->
            val v = TextbookValidator.validate(Fixtures.base(content = text))
            assertEquals("读书笔记应拒收：$text", Pool.REJECTED, v.pool)
            assertTrue("要给 NO_FIELD_REFERENCE：${Fixtures.codes(v)}",
                RejectCode.NO_FIELD_REFERENCE.name in Fixtures.codes(v))
            assertTrue("要提示这是读书笔记：${Fixtures.messages(v)}",
                "这是读书笔记" in Fixtures.messages(v))
        }
    }

    @Test
    fun `引用闭集外的字段或判据id也拒收_正例可入`() {
        val unknownField = TextbookValidator.validate(Fixtures.base(content = "字段 [字段:foo] 很关键。"))
        assertEquals(Pool.REJECTED, unknownField.pool)
        assertTrue(RejectCode.UNKNOWN_REFERENCE.name in Fixtures.codes(unknownField))

        val unknownCriterion = TextbookValidator.validate(Fixtures.base(content = "判据 [判据:NAR-XX-99] 很关键。"))
        assertEquals(Pool.REJECTED, unknownCriterion.pool)
        assertTrue(RejectCode.UNKNOWN_REFERENCE.name in Fixtures.codes(unknownCriterion))

        val okField = TextbookValidator.validate(Fixtures.base(content = "字段 [字段:evidence] 必须有出处。"))
        assertEquals(Pool.MAIN, okField.pool)
        val okCriterion = TextbookValidator.validate(Fixtures.base(content = "判据 [判据:NAR-OC-06] 记一条。"))
        assertEquals(Pool.MAIN, okCriterion.pool)
    }

    @Test
    fun `三类禁内容各有原因码且中性教材不误伤`() {
        val cases = listOf(
            "你必须先洗手再操作 [字段:question]。" to RejectCode.FORBID_BEHAVIOR,
            "该方法涉及未成年人的真实经历 [字段:answer]。" to RejectCode.FORBID_MINOR,
            "步骤见 https://example.com/start [字段:verdict]。" to RejectCode.FORBID_ACTIONABLE,
        )
        cases.forEach { (text, code) ->
            val v = TextbookValidator.validate(Fixtures.base(content = text))
            assertEquals("$text 应拒收，实为 ${v.pool}", Pool.REJECTED, v.pool)
            assertTrue("应给 $code：${Fixtures.codes(v)}", code.name in Fixtures.codes(v))
        }

        val neutral = TextbookValidator.validate(
            Fixtures.base(content = "判据 [字段:verdict] 要求：无明文反驳时判未知，不得升格为确定。"),
        )
        assertEquals(Pool.MAIN, neutral.pool)
    }
}
