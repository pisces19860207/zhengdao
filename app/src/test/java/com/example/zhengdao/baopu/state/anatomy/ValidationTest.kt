package com.example.zhengdao.baopu.state.anatomy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidationTest {
    @Test
    fun `单侧结构缺失是PASS`() {
        val completeness = Completeness(
            structuralAbsences = listOf(StructuralAbsence("torso.left.example", Side.LEFT, "结构不存在")),
            unobserved = emptyList(),
        )
        val result = completenessValidation(completeness)
        println(
            "FIXTURE=structural-absence anatomical-side=LEFT threshold=structural-absence " +
                "level=PASS fixture-side=accept distance=0",
        )
        println("OUTPUT=$result")

        assertEquals(ValidationLevel.PASS, result.findings.single().level)
        assertEquals("completeness.structural_absence", result.findings.single().rule)
    }

    @Test
    fun `单侧未观测是WARN且不与结构缺失混用`() {
        val completeness = Completeness(
            structuralAbsences = emptyList(),
            unobserved = listOf(Unobserved("torso.right.example", Side.RIGHT, "存在但没有数据")),
        )
        val result = completenessValidation(completeness)
        println(
            "FIXTURE=unobserved anatomical-side=RIGHT threshold=unobserved " +
                "level=WARN fixture-side=accept distance=0",
        )
        println("OUTPUT=$result")

        assertEquals(ValidationLevel.WARN, result.findings.single().level)
        assertEquals("completeness.unobserved", result.findings.single().rule)
    }

    @Test
    fun `不同侧结构缺失保留PASS且未观测为WARN`() {
        val completeness = Completeness(
            structuralAbsences = listOf(StructuralAbsence("torso.example", Side.LEFT, "左侧结构不存在")),
            unobserved = listOf(Unobserved("torso.example", Side.RIGHT, "右侧存在但没有数据")),
        )
        val result = completenessValidation(completeness)
        val findings = result.findings.associateBy { it.rule }
        println(
            "FIXTURE=mixed-sides structural-absence=LEFT unobserved=RIGHT " +
                "threshold=independent-completeness-levels fixture-side=accept distance=0",
        )
        println("OUTPUT=$result")

        assertEquals(2, findings.size)
        assertEquals(ValidationLevel.PASS, findings.getValue("completeness.structural_absence").level)
        assertEquals(ValidationLevel.WARN, findings.getValue("completeness.unobserved").level)
    }

    @Test
    fun `同一对象同一侧不许同时结构缺失与未观测`() {
        val thrown = runCatching {
            Completeness(
                structuralAbsences = listOf(StructuralAbsence("torso.left.example", Side.LEFT, "结构不存在")),
                unobserved = listOf(Unobserved("torso.left.example", Side.LEFT, "存在但没有数据")),
            )
        }.exceptionOrNull()
        println(
            "FIXTURE=mixed-completeness subject=torso.left.example anatomical-side=LEFT " +
                "threshold=exclusive-states fixture-side=reject distance=0",
        )
        println("OUTPUT=mixed-completeness $thrown")

        assertTrue(thrown is IllegalArgumentException)
    }

    @Test
    fun `代理几何重叠但有语义连接必须是WARN`() {
        val result = validateProxyOverlap(
            ProxyGeometryOverlap(observed = Resolution.Present(true)),
            SemanticConnection(connected = Resolution.Present(true)),
        )
        println(
            "FIXTURE=proxy-overlap observed=true semantic-connection=true threshold=connection-present " +
                "level=WARN fixture-side=accept distance=0",
        )
        println("OUTPUT=$result")

        assertEquals(ValidationLevel.WARN, result.level)
        assertEquals("proxy.overlap_with_semantic_connection", result.rule)
    }

    @Test
    fun `代理几何重叠且无语义连接才是INVALID`() {
        val result = validateProxyOverlap(
            ProxyGeometryOverlap(observed = Resolution.Present(true)),
            SemanticConnection(connected = Resolution.Present(false)),
        )
        println(
            "FIXTURE=proxy-overlap observed=true semantic-connection=false threshold=connection-present " +
                "level=INVALID fixture-side=reject distance=1",
        )
        println("OUTPUT=overlap-without-connection $result")

        assertEquals(ValidationLevel.INVALID, result.level)
    }

    @Test
    fun `已观测无代理几何重叠是PASS`() {
        val result = validateProxyOverlap(
            ProxyGeometryOverlap(observed = Resolution.Present(false)),
            SemanticConnection(connected = Resolution.Present(false)),
        )
        println(
            "FIXTURE=proxy-overlap observed=false semantic-connection=false threshold=overlap-present " +
                "level=PASS fixture-side=accept distance=1",
        )
        println("OUTPUT=observed-no-overlap $result")

        assertEquals(ValidationLevel.PASS, result.level)
        assertEquals("proxy.no_overlap", result.rule)
    }

    @Test
    fun `语义连接未观测是WARN而非INVALID`() {
        val result = validateProxyOverlap(
            ProxyGeometryOverlap(observed = Resolution.Present(true)),
            SemanticConnection(
                connected = Resolution.Absent("torso.connection", AbsenceReason.NOT_OBSERVED),
            ),
        )
        println(
            "FIXTURE=proxy-overlap observed=true semantic-connection=absent(NOT_OBSERVED) " +
                "threshold=observed-boolean level=WARN fixture-side=accept distance=missing",
        )
        println("OUTPUT=unobserved-semantic-connection $result")

        assertEquals(ValidationLevel.WARN, result.level)
        assertEquals("proxy.semantic_connection_unobserved", result.rule)
    }

    @Test
    fun `代理几何重叠未观测是WARN而非INVALID`() {
        val result = validateProxyOverlap(
            ProxyGeometryOverlap(
                observed = Resolution.Absent("torso.overlap", AbsenceReason.NOT_OBSERVED),
            ),
            SemanticConnection(connected = Resolution.Present(false)),
        )
        println(
            "FIXTURE=proxy-overlap observed=absent(NOT_OBSERVED) semantic-connection=false " +
                "threshold=observed-boolean level=WARN fixture-side=accept distance=missing",
        )
        println("OUTPUT=unobserved-overlap $result")

        assertEquals(ValidationLevel.WARN, result.level)
        assertEquals("proxy.overlap_unobserved", result.rule)
    }
}
