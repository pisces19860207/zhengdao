// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百二十四批 · 触发账本「序列化边界」的变异看守。
//
// 账本一行是**只增不改**的快照（304）：读侧解析（`TriggerRowCodec.parse`）与字段集
// （`TriggerRow` 的 13 个字段）一旦漂移，坏处不是"报错"，而是**静默丢一半证据**——
// 限频状态读不全 ⇒ 该压的不压；读数读丢 ⇒ 演化输入凭空归零。
// 所以这里两条判据各自钉死一个"跳"：
//   ① 往返：encode → parse 之后**逐字段**一致（含 null 与空串的区分）；
//   ② 字段集：反射出的字段数、编码出的键数、期望键表三者必须相等——
//      加字段不编码 / 删字段不改键表 / 键名写错，都会在这里红。
//
// 三条变异（脚本注入，见交付报告 §4）：把 parse 的某个字段写死、把 TriggerRow 的字段
// 删一个、把 ofWire 的匹配翻转——本文件的用例必须各自变红。
package com.example.zhengdao.baopu.evolution.trigger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerRowCodecGuardTest {

    /** 触发行：评测读数全在（含跑不出读数时的 [TriggerRow.evalError]）。 */
    private val triggered = TriggerRow(
        atMs = 42L,
        type = TriggerType.USER_CORRECTION,
        triggered = true,
        ref = "skill_event:9",
        intent = "技能「查文献」被连续纠正两次",
        evalRan = 13,
        evalPassed = 12,
        evalTotal = 13,
        evalPassRate = 12.0 / 13.0,
        evalFailedCases = listOf("case-7", "case-9"),
        evalError = "第 3 条没跑出读数",
        route = "proposal:abc",
        suppressReason = "",
    )

    /** 被抑制行：没有评测读数，但必须写明原因（`init` 的硬约定）。 */
    private val suppressed = TriggerRow(
        atMs = 43L,
        type = TriggerType.RUN_FAILED,
        triggered = false,
        ref = "task:run-9",
        intent = "同日再 FAILED",
        suppressReason = "budget:same_day",
    )

    /**
     * 编码后的键表（**故意写死**）：字段集是判据的一部分，改它必须来这里同步改，
     * 不许"加了字段、编码顺手漏掉"还全绿。
     */
    private val allKeys = setOf(
        "at", "type", "triggered", "ref", "intent",
        "evalRan", "evalPassed", "evalTotal", "evalPassRate", "evalFailedCases", "evalError",
        "route", "suppressReason",
    )

    @Test
    fun `往返_触发行逐字段一致_含小数与失败场景列表`() {
        val parsed = TriggerRowCodec.parse(TriggerRowCodec.encode(triggered).toString())
        assertEquals("触发行必须原样读回来", triggered, parsed)
        assertEquals("小数不许被截成整数", 12.0 / 13.0, parsed!!.evalPassRate!!, 1e-12)
        assertEquals(listOf("case-7", "case-9"), parsed.evalFailedCases)
        assertEquals("空串与 null 不是一个东西（抑制原因空着＝没被抑制）", "", parsed.suppressReason)
    }

    @Test
    fun `往返_被抑制行逐字段一致_读数保持空值而不是0`() {
        val parsed = TriggerRowCodec.parse(TriggerRowCodec.encode(suppressed).toString())
        assertEquals("被抑制行必须原样读回来", suppressed, parsed)
        assertNull("没跑出读数就是 null——写成 0.0 会被当成「全挂」", parsed!!.evalPassRate)
        assertEquals("budget:same_day", parsed.suppressReason)
        assertEquals("读数缺省是 0 次不是 1 次", 0, parsed.evalRan)
    }

    @Test
    fun `字段集_反射字段数等于编码键数等于13_键名逐一对上`() {
        val declared = TriggerRow::class.java.declaredFields
            // `$stable` 是 Compose 编译期加的可稳定性标记（不是业务字段），其余 `$` 开头的一律不算。
            .filterNot { it.isSynthetic || it.name.startsWith("$") }
            .map { it.name }
            .toSet()
        assertEquals(
            "TriggerRow 的字段集变了（现在 $declared）：加/删/改字段请同步 encode、parse 与本用例的键表",
            13,
            declared.size,
        )
        val encodedAll = TriggerRowCodec.encode(triggered).keys().asSequence().toSet()
        val encodedSuppressed = TriggerRowCodec.encode(suppressed).keys().asSequence().toSet()
        assertEquals(
            "触发行的编码键少了（现在 $encodedAll）：这个字段读回来就会是默认值",
            allKeys - "suppressReason",
            encodedAll,
        )
        assertEquals(
            "被抑制行的编码键少了（现在 $encodedSuppressed）：抑制原因丢了就看不出「为什么没触发」",
            allKeys - setOf("evalPassRate", "evalError", "route"),
            encodedSuppressed,
        )
        assertEquals("字段数必须与键数对得上（多一个字段没编码＝静默丢证据）", declared.size, allKeys.size)
    }

    @Test
    fun `坏行整行丢弃_不许让整本账读不出来`() {
        assertNull("不是 JSON", TriggerRowCodec.parse("这不是 JSON"))
        assertNull("type 认不出（不许默认成某一类）", TriggerRowCodec.parse("""{"type":"nope"}"""))
        assertNull("缺 at（必填）", TriggerRowCodec.parse("""{"type":"user_correction","triggered":true}"""))
        assertNull(
            "违反「被抑制必须写原因」的行读不回来（init 的 require 会被 runCatching 收住）",
            TriggerRowCodec.parse("""{"at":1,"type":"user_correction","triggered":false}"""),
        )
        // 正常行必须还能读（坏行不许把好行一起带走）
        val ok = TriggerRowCodec.parse(TriggerRowCodec.encode(suppressed).toString())
        assertNotEquals("好行不许被坏行连坐", null, ok)
    }
}
