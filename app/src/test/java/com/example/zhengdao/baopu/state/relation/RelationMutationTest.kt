// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.state.relation

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V1–V5 变异：逐个关掉一条规则，断言对应夹具**翻面**，且翻面前后红码精确可指。
 * 变异在单个 JVM 里做，生产开关默认恒正确（另有源码门禁钉死）；
 * 判完 reset，避免串味。全程只有一轮 Gradle。
 */
class RelationMutationTest {

    @After
    fun clear() {
        RelationRuleSwitch.reset()
    }

    private fun ablate(id: String, fixture: String, flipped: String, goneCode: String) {
        val baseline = RelationFixtures.run(fixture)
        assertTrue("变异前 $fixture 必须命中 $goneCode，实为 $baseline", baseline.codes.contains(goneCode))
        RelationRuleSwitch.ablate(id)
        val after = RelationFixtures.run(fixture)
        assertEquals("变异 $id 后 $fixture 必须翻面", flipped, after.verdict)
        assertTrue("变异 $id 后 $goneCode 必须消失，实为 ${after.codes}", !after.codes.contains(goneCode))
        println("MUTATION|$id|$fixture|${baseline.verdict}:$goneCode -> ${after.verdict}")
    }

    @Test
    fun `V1_合并双向_F2必须翻绿`() = ablate("V1", "F2", "GREEN", "R2-DIR-MERGED")

    @Test
    fun `V2_去掉ceiling_F4必须翻绿`() = ablate(
        "V2", "F4", "GREEN", "R4-CEILING-COLLEAGUE-TRUST",
    )

    @Test
    fun `V3_去掉事件要求_F5必须翻绿`() = ablate("V3", "F5", "GREEN", "R5-NO-EVENT")

    @Test
    fun `V4_rapport直接升relKind_F8必须翻红`() {
        val baseline = RelationFixtures.run("F8")
        assertEquals("变异前 F8 必须绿", "GREEN", baseline.verdict)
        RelationRuleSwitch.ablate("V4")
        val after = RelationFixtures.run("F8")
        assertEquals("变异 V4 后 F8 必须翻红", "RED", after.verdict)
        assertTrue("必须在 R7 上红，实为 ${after.codes}", after.codes.contains("R7-RAPPORT-LEAK"))
        println("MUTATION|V4|F8|GREEN -> RED:R7-RAPPORT-LEAK")
    }

    @Test
    fun `V5_去掉关系不推性反应_F11必须翻绿`() = ablate("V5", "F11", "GREEN", "R10-REL-SEX")

    @Test
    fun `变异后开关全部复位`() {
        listOf("V1", "V2", "V3", "V4", "V5").forEach { RelationRuleSwitch.ablate(it) }
        RelationRuleSwitch.reset()
        assertEquals("V1=false,V2=true,V3=true,V4=false,V5=true", RelationRuleSwitch.snapshot())
        println("MUTATION-RESET|5/5")
    }
}
