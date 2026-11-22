// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 798 判据①（七字段齐，缺一红——本 schema 十字段全钉）与判据②（四类 sourceType/domain 可入，
// 非法值红）。每条负例只动一个字段，保证红得有原因、不是"整体写坏了"。
package com.example.zhengdao.baopu.textbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextbookSchemaTest {

    @Test
    fun `十字段齐全的正例可入主库`() {
        val v = TextbookValidator.validate(Fixtures.base())
        assertEquals("正例应入主库，实为 ${v.pool}／${Fixtures.codes(v)}", Pool.MAIN, v.pool)
        assertTrue("正例不该带拒收原因：${Fixtures.messages(v)}", v.rejections.isEmpty())
    }

    @Test
    fun `十个字段逐个缺失全部拒收_每字段一个负例`() {
        TEXTBOOK_FIELDS.forEach { field ->
            val raw = Fixtures.base()
            raw.remove(field)
            val v = TextbookValidator.validate(raw)
            assertEquals("缺「$field」应拒收，实为 ${v.pool}", Pool.REJECTED, v.pool)
            assertTrue("缺「$field」应给 MISSING_FIELD：${Fixtures.codes(v)}",
                RejectCode.MISSING_FIELD.name in Fixtures.codes(v))
        }
    }

    @Test
    fun `source 与 asOf 缺失各有专属原因码`() {
        val noSource = Fixtures.base().apply { remove("source") }
        val noAsOf = Fixtures.base().apply { remove("asOf") }
        assertTrue("缺 source 要给 MISSING_SOURCE：${Fixtures.codes(TextbookValidator.validate(noSource))}",
            RejectCode.MISSING_SOURCE.name in Fixtures.codes(TextbookValidator.validate(noSource)))
        assertTrue("缺 asOf 要给 MISSING_AS_OF：${Fixtures.codes(TextbookValidator.validate(noAsOf))}",
            RejectCode.MISSING_AS_OF.name in Fixtures.codes(TextbookValidator.validate(noAsOf)))
    }

    @Test
    fun `asOf 不是 ISO 日期拒收`() {
        val v = TextbookValidator.validate(Fixtures.base(asOf = "去年冬天"))
        assertEquals(Pool.REJECTED, v.pool)
        assertTrue(RejectCode.INVALID_AS_OF.name in Fixtures.codes(v))
    }

    @Test
    fun `四类 sourceType 全部可入_经验类带样本量`() {
        SourceType.entries.forEach { type ->
            val scope = if (type == SourceType.经验总结) "样本 n=24" else "叙事连续性判定"
            val v = TextbookValidator.validate(Fixtures.base(sourceType = type.name, scope = scope))
            assertEquals("$type 应可入，实为 ${v.pool}／${Fixtures.codes(v)}", Pool.MAIN, v.pool)
        }
    }

    @Test
    fun `四类 domain 全部可入`() {
        Domain.entries.forEach { domain ->
            val v = TextbookValidator.validate(Fixtures.base(domain = domain.name))
            assertEquals("$domain 应可入，实为 ${v.pool}／${Fixtures.codes(v)}", Pool.MAIN, v.pool)
        }
    }

    @Test
    fun `非法 sourceType 与 domain 分别拒收`() {
        val badType = TextbookValidator.validate(Fixtures.base(sourceType = "道听途说"))
        assertEquals(Pool.REJECTED, badType.pool)
        assertTrue(RejectCode.ILLEGAL_ENUM.name in Fixtures.codes(badType))

        val badDomain = TextbookValidator.validate(Fixtures.base(domain = "玄域"))
        assertEquals(Pool.REJECTED, badDomain.pool)
        assertTrue(RejectCode.ILLEGAL_ENUM.name in Fixtures.codes(badDomain))
    }
}
