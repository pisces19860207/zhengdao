package com.example.zhengdao.baopu.state.anatomy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnatomyRegionTest {
    @Test
    fun `统一模型字段齐全且缺失项保留absent`() {
        val absent = AbsenceReason.NOT_OBSERVED
        val model = AnatomicalRegionModel(
            schemaVersion = ANATOMY_SCHEMA_VERSION,
            regionId = RegionId("torso"),
            regionType = RegionType("torso"),
            parentRegionId = Resolution.Absent("torso.parent", AbsenceReason.NO_PARENT_REGION),
            side = Side.MIDLINE,
            anatomicalFrame = Resolution.Absent("torso.frame", absent),
            morphology = Morphology(
                shapeRepresentation = Resolution.Absent("torso.shape", absent),
                profile = Resolution.Absent("torso.profile", absent),
                envelope = Resolution.Absent("torso.envelope", absent),
                surface = Resolution.Absent("torso.surface", absent),
            ),
            topology = TopologyRelations(emptyList(), emptyList(), emptyList()),
            state = Resolution.Absent("torso.state", absent),
            constraints = Resolution.Absent("torso.constraints", absent),
            provenance = Provenance(
                sourceType = "fixture",
                sourceId = "anatomy-region-test",
                confidence = 0.5,
                completeness = Completeness(emptyList(), listOf(Unobserved("torso.example", Side.MIDLINE, "未观测"))),
            ),
            validation = completenessValidation(
                Completeness(emptyList(), listOf(Unobserved("torso.example", Side.MIDLINE, "未观测"))),
            ),
        )
        println("FIXTURE=complete-field-model threshold=all-fields-present side=accept distance=0")
        println("OUTPUT=$model")

        assertEquals("anatomy.schema.v1", model.schemaVersion)
        assertEquals(RegionId("torso"), model.regionId)
        assertEquals(AbsenceReason.NO_PARENT_REGION, (model.parentRegionId as Resolution.Absent).reason)
        assertEquals(absent, (model.anatomicalFrame as Resolution.Absent).reason)
        assertEquals(absent, (model.morphology.profile as Resolution.Absent).reason)
        assertEquals(absent, (model.state as Resolution.Absent).reason)
        assertEquals(absent, (model.constraints as Resolution.Absent).reason)
        assertEquals(ValidationLevel.WARN, model.validation.findings.single().level)

        val inconsistent = runCatching { model.copy(validation = ValidationReport(emptyList())) }
        val unsupported = runCatching { model.copy(schemaVersion = "anatomy.schema.future") }
        val reserved = runCatching { model.copy(regionType = RegionType("perineal")) }
        println("OUTPUT=inconsistent-validation $inconsistent")
        println("OUTPUT=unsupported-schema $unsupported")
        println("OUTPUT=reserved-region-type $reserved")
        assertTrue(inconsistent.exceptionOrNull() is IllegalArgumentException)
        assertTrue(unsupported.exceptionOrNull() is IllegalArgumentException)
        assertTrue(reserved.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `躯干最小样例只有已声明参数且没有形态默认值`() {
        val result = TorsoRegionExample.declare()
        val spec = when (result) {
            is Resolution.Present -> result.value
            is Resolution.Absent -> throw AssertionError("期望 Present，实际 $result")
        }
        val parameter = when (val resolved = spec.parameter(TorsoRegionExample.PROVENANCE_REFERENCE)) {
            is Resolution.Present -> resolved.value
            is Resolution.Absent -> throw AssertionError("期望 Present，实际 $resolved")
        }
        println("OUTPUT=torso-spec $spec")
        println("OUTPUT=torso-parameter $parameter")

        assertEquals(RegionId("torso"), spec.regionId)
        assertEquals(emptyList<Subzone>(), spec.subzones)
        assertEquals(setOf(ParameterConsumer.GENERATION_WITH_PROVENANCE), parameter.consumers)
        assertEquals(AbsenceReason.UNDECLARED_PARAMETER, absentOf(spec.parameter("torso.profile")).reason)
        val parameterValue = valueOf(TorsoRegionExample.parameterValue("fixture-reference"))
        println("OUTPUT=torso-parameter-value $parameterValue")
        assertEquals("fixture-reference", parameterValue)
    }

    private fun <T> valueOf(result: Resolution<T>): T = when (result) {
        is Resolution.Present -> result.value
        is Resolution.Absent -> throw AssertionError("期望 Present，实际 $result")
    }

    private fun absentOf(result: Resolution<*>): Resolution.Absent = when (result) {
        is Resolution.Absent -> result
        is Resolution.Present -> throw AssertionError("期望 Absent，实际 $result")
    }
}
