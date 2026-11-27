// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.state.relation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Relation921Test {

    @Test
    fun `四维表_四加二字段与七类上限`() {
        assertEquals("relKind 七类", RelationKind.COUNT, RelationKind.entries.size)
        assertEquals("四维", RelationCatalog.DIM_COUNT, 4)
        assertEquals("两心理维", RelationCatalog.PSYCH_COUNT, 2)
        assertEquals("脸认三档", FaceRecognition.COUNT, FaceRecognition.entries.size)
        assertEquals("事件四类", EventKind.COUNT, EventKind.entries.size)
        assertEquals("refusal 三态", RefusalMode.COUNT, RefusalMode.entries.size)
        println("FIELD-READING trust/dependency/intimacy=0..3 conflict=0..100 fearOfAbandon/needToBeNeeded=0..3")
        RelationKind.entries.forEach { println("CEILING|" + RelationCatalog.ceilingLine(it)) }
        assertEquals("同事 trust 上限中=2", 2, RelationCatalog.CEILINGS.getValue(RelationKind.COLLEAGUE).trust)
        assertEquals("陌生人全 0", RelationCatalog.STRANGER_START, RelationDims(0, 0, 0, 0))
    }

    @Test
    fun `F1到F12_逐条真跑读数`() {
        assertEquals(12, RelationFixtures.IDS.size)
        RelationFixtures.IDS.forEach { id ->
            val expected = RelationFixtures.EXPECTED.getValue(id)
            val actual = RelationFixtures.run(id)
            assertEquals(id + " 判词", expected.first, actual.verdict)
            if (expected.second == null) {
                assertTrue(id + " GREEN 不该有红码：" + actual.codes, actual.codes.isEmpty())
            } else {
                assertTrue(id + " 必须命中 " + expected.second + "，实为 " + actual.codes,
                    actual.codes.contains(expected.second))
            }
            println("FIXTURE|" + RelationFixtures.line(id))
        }
        assertEquals("开关保持默认", "V1=false,V2=true,V3=true,V4=false,V5=true",
            RelationRuleSwitch.snapshot())
    }

    @Test
    fun `范围构造即守_坏状态造不出来`() {
        assertIllegal { RelationDims(4, 0, 0, 0) }
        assertIllegal { RelationDims(0, 0, 101, 0) }
        assertIllegal { PsychDims(4, 0) }
        assertIllegal { RelationEvent(EventKind.HELP, "") }
        assertIllegal {
            RelationSheet.of(
                cellOf("A", "B", RelationKind.FRIEND),
                cellOf("A", "C", RelationKind.FRIEND),
            )
        }
        println("CONSTRUCT-READING 5/5 illegal rejected")
    }

    @Test
    fun `锚定起始只来自设定_670格与930 values`() {
        val stranger = DirectedCell.started("A", "B", RelationAnchor.stranger())
        assertEquals(RelationCatalog.STRANGER_START, stranger.dims)
        val anchored = DirectedCell.started(
            "A", "B",
            RelationAnchor.fromSetting(RelationKind.FRIEND, "设定：同班三年",
                track = com.example.zhengdao.baopu.state.track.RelationState.T5),
        )
        assertTrue("有锚定才允许非陌生起始", anchored.dims.trust > 0)
        assertEquals("同事超上限仍会被 R4 咬住", "R4-CEILING-COLLEAGUE-TRUST",
            RelationChecks.ceiling(
                cellOf("A", "B", RelationKind.COLLEAGUE).copy(
                    dims = RelationDims(3, 1, 0, 1),
                ),
            )!!.code)
    }

    private fun cellOf(from: String, to: String, kind: RelationKind): DirectedCell = DirectedCell(
        from = from,
        to = to,
        kind = kind,
        dims = RelationDims(1, 0, 0, 1),
        psych = PsychDims(),
        anchor = RelationAnchor.fromSetting(kind, "设定：测试用"),
    )

    private fun assertIllegal(block: () -> Unit) {
        var thrown = false
        try {
            block()
        } catch (e: IllegalArgumentException) {
            thrown = true
        }
        assertTrue("坏构造必须被拒", thrown)
    }
}
