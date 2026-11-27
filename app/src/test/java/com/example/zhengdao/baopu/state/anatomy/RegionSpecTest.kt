package com.example.zhengdao.baopu.state.anatomy

import com.example.zhengdao.baopu.state.subzone.SubzoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionSpecTest {
    @Test
    fun `零消费者参数被拒且给出absent原因`() {
        val rejected = ParameterDeclaration("skinSweat", emptySet())
        val result = declare(rejected)
        println(
            "FIXTURE=zero-consumer parameter=skinSweat consumers=[] " +
                "threshold=at-least-one fixture-side=reject distance=0",
        )
        println("OUTPUT=$result")

        val absent = absentOf(result)
        assertEquals("skinSweat", absent.subject)
        assertEquals(AbsenceReason.NO_CONSUMER, absent.reason)

        val admitted = declare(
            ParameterDeclaration("skinSweat", setOf(ParameterConsumer.GEOMETRY_KERNEL_QUERY)),
        )
        val accepted = valueOf(admitted)
        println(
            "FIXTURE=bound-consumer parameter=skinSweat consumers=[GEOMETRY_KERNEL_QUERY] value=fixture-value " +
                "threshold=at-least-one fixture-side=accept distance=1",
        )
        val parameters = valueOf(accepted.resolveParameters(mapOf("skinSweat" to "fixture-value")))
        val geometryReader: (AdmittedParameters) -> String = { valueOf(it.read("skinSweat")) }
        val read = geometryReader(parameters)
        println("OUTPUT=consumer-bound $admitted")
        println("OUTPUT=consumer-read parameter=skinSweat value=$read consumer=GEOMETRY_KERNEL_QUERY")

        assertEquals("skinSweat", valueOf(accepted.parameter("skinSweat")).name)
        assertEquals("fixture-value", read)
    }

    @Test
    fun `未知参数不得绕过准入进入消费路径`() {
        val spec = presentSpec()
        val supplied = mapOf(
            TorsoRegionExample.PROVENANCE_REFERENCE to "fixture-reference",
            "skinSweat" to "unread",
        )
        val result = spec.resolveParameters(supplied)
        println(
            "FIXTURE=unknown-parameter supplied=$supplied threshold=declared-keys-only " +
                "fixture-side=reject distance=1",
        )
        println("OUTPUT=$result")

        val absent = absentOf(result)
        assertEquals("skinSweat", absent.subject)
        assertEquals(AbsenceReason.UNDECLARED_PARAMETER, absent.reason)
    }

    @Test
    fun `有消费者参数进入spec且两个版本可读`() {
        val admitted = ParameterDeclaration(
            "torso.provenance.reference",
            setOf(ParameterConsumer.GENERATION_WITH_PROVENANCE),
        )
        val result = declare(admitted)
        println(
            "FIXTURE=admitted parameter=${admitted.name} consumers=${admitted.consumers} " +
                "threshold=at-least-one fixture-side=accept distance=1",
        )
        println("OUTPUT=$result")

        val spec = valueOf(result)
        assertEquals(ANATOMY_SCHEMA_VERSION, spec.schemaVersion)
        assertEquals(ANATOMY_REGION_CATALOG_VERSION, spec.regionCatalogVersion)
        assertEquals(admitted, valueOf(spec.parameter(admitted.name)))
    }

    @Test
    fun `缺参数值返回absent而非默认值`() {
        val spec = presentSpec()
        val admitted = spec.resolveParameters(emptyMap())
        println(
            "FIXTURE=missing-value parameter=torso.provenance.reference values={} " +
                "threshold=declared-value-present fixture-side=reject distance=missing",
        )
        println("OUTPUT=admitted $admitted")

        assertEquals(AbsenceReason.PARAMETER_VALUE_MISSING, absentOf(admitted).reason)
    }

    @Test
    fun `部分参数值不得绕过准入进入消费路径`() {
        val parameters = listOf(
            ParameterDeclaration(
                TorsoRegionExample.PROVENANCE_REFERENCE,
                setOf(ParameterConsumer.GENERATION_WITH_PROVENANCE),
            ),
            ParameterDeclaration("skinSweat", setOf(ParameterConsumer.GEOMETRY_KERNEL_QUERY)),
        )
        val spec = valueOf(
            RegionSpec.declare(
                ANATOMY_SCHEMA_VERSION,
                ANATOMY_REGION_CATALOG_VERSION,
                RegionId("torso"),
                RegionType("torso"),
                emptyList(),
                setOf(Capability.SURFACE),
                parameters,
                listOf(PERINEAL_EXTENSION_SLOT),
            ),
        )
        val result = spec.resolveParameters(mapOf(TorsoRegionExample.PROVENANCE_REFERENCE to "fixture-reference"))
        println(
            "FIXTURE=partial-values supplied=[torso.provenance.reference] missing=[skinSweat] " +
                "threshold=all-declared-values fixture-side=reject distance=1",
        )
        println("OUTPUT=$result")

        val absent = absentOf(result)
        assertEquals("skinSweat", absent.subject)
        assertEquals(AbsenceReason.PARAMETER_VALUE_MISSING, absent.reason)
    }

    @Test
    fun `七种能力恰好七项`() {
        val expected = setOf("SHAPE", "FRAME", "TOPOLOGY", "SURFACE", "INTERIOR", "MOTION", "SANITIZE")
        val actual = Capability.entries.map { it.name }.toSet()
        println("FIXTURE=capabilities threshold=exact-seven fixture-side=accept distance=0")
        println("OUTPUT=$actual")

        assertEquals(expected, actual)
    }

    @Test
    fun `参数消费者恰好五类`() {
        val expected = setOf(
            "GEOMETRY_KERNEL_QUERY",
            "SNAPSHOT_834",
            "EXTRACTION_VALIDATION_853",
            "GENERATION_WITH_PROVENANCE",
            "CONSTRAINT_ENGINE",
        )
        val actual = ParameterConsumer.entries.map { it.name }.toSet()
        println("OUTPUT=$actual")

        assertEquals(expected, actual)
    }

    @Test
    fun `第二个区域可追加且会阴扩展位仍未启用`() {
        val first = presentSpec()
        val second = RegionSpec.declare(
            ANATOMY_SCHEMA_VERSION,
            ANATOMY_REGION_CATALOG_VERSION,
            RegionId("chest"),
            RegionType("chest"),
            emptyList(),
            setOf(Capability.SHAPE),
            emptyList(),
            listOf(PERINEAL_EXTENSION_SLOT),
        )
        val catalog = RegionCatalog.create(
            ANATOMY_SCHEMA_VERSION,
            ANATOMY_REGION_CATALOG_VERSION,
            listOf(first),
            listOf(PERINEAL_EXTENSION_SLOT),
        )
        val initial = valueOf(catalog)
        val appended = initial.append(valueOf(second), "anatomy.catalog.v1")
        val reserved = RegionSpec.declare(
            ANATOMY_SCHEMA_VERSION,
            ANATOMY_REGION_CATALOG_VERSION,
            RegionId("reserved-example"),
            RegionType("perineal"),
            emptyList(),
            emptySet(),
            emptyList(),
            listOf(PERINEAL_EXTENSION_SLOT),
        )
        val reservedWithoutSlots = RegionSpec.declare(
            ANATOMY_SCHEMA_VERSION,
            ANATOMY_REGION_CATALOG_VERSION,
            RegionId("reserved-no-slot"),
            RegionType("perineal"),
            emptyList(),
            emptySet(),
            emptyList(),
            emptyList(),
        )
        val withoutSlots = valueOf(
            RegionCatalog.create(
                ANATOMY_SCHEMA_VERSION,
                ANATOMY_REGION_CATALOG_VERSION,
                listOf(first),
                emptyList(),
            ),
        )
        println("OUTPUT=appended $appended")
        println("OUTPUT=reserved-declaration $reserved")
        println("OUTPUT=reserved-without-slots $reservedWithoutSlots")
        println("OUTPUT=empty-slot-catalog $withoutSlots")

        val extended = valueOf(appended)
        assertEquals(listOf("torso", "chest"), extended.regions.map { it.regionId.value })
        assertEquals("anatomy.catalog.v1", extended.regionCatalogVersion)
        assertEquals(AbsenceReason.RESERVED_REGION_TYPE, absentOf(reserved).reason)
        assertEquals(AbsenceReason.RESERVED_REGION_TYPE, absentOf(reservedWithoutSlots).reason)
        assertTrue(PERINEAL_EXTENSION_SLOT.enabledType == null)
        assertTrue(withoutSlots.extensionSlots.single().enabledType == null)
    }

    @Test
    fun `schema版本与已知子区宿主不可绕过`() {
        val conflict = RegionSpec.declare(
            ANATOMY_SCHEMA_VERSION,
            ANATOMY_REGION_CATALOG_VERSION,
            RegionId("torso"),
            RegionType("torso"),
            listOf(Subzone(SubzoneId.APEX, RegionId("torso"))),
            emptySet(),
            emptyList(),
            listOf(PERINEAL_EXTENSION_SLOT),
        )
        val unsupportedSpec = RegionSpec.declare(
            "anatomy.schema.future",
            ANATOMY_REGION_CATALOG_VERSION,
            RegionId("future-example"),
            RegionType("future-example"),
            emptyList(),
            emptySet(),
            emptyList(),
            listOf(PERINEAL_EXTENSION_SLOT),
        )
        val unsupportedCatalog = RegionCatalog.create(
            "anatomy.schema.future",
            ANATOMY_REGION_CATALOG_VERSION,
            emptyList(),
            listOf(PERINEAL_EXTENSION_SLOT),
        )
        println("FIXTURE=known-subzone-host subzone=apex declared=torso threshold=mapped-host fixture-side=reject distance=1")
        println("FIXTURE=schema-version supplied=anatomy.schema.future threshold=anatomy.schema.v1 fixture-side=reject distance=1")
        println("OUTPUT=host-conflict $conflict")
        println("OUTPUT=unsupported-spec $unsupportedSpec")
        println("OUTPUT=unsupported-catalog $unsupportedCatalog")

        assertEquals(AbsenceReason.SUBZONE_REGION_MISMATCH, absentOf(conflict).reason)
        assertEquals(AbsenceReason.SCHEMA_VERSION_MISMATCH, absentOf(unsupportedSpec).reason)
        assertEquals(AbsenceReason.SCHEMA_VERSION_MISMATCH, absentOf(unsupportedCatalog).reason)
    }

    @Test
    fun `声明快照调用方集合并保持准入不变量`() {
        val consumers = mutableSetOf(ParameterConsumer.GENERATION_WITH_PROVENANCE)
        val parameter = ParameterDeclaration("torso.provenance.reference", consumers)
        val parameters = mutableListOf(parameter)
        val subzones = mutableListOf<Subzone>()
        val capabilities = mutableSetOf(Capability.SURFACE)
        val spec = valueOf(
            RegionSpec.declare(
                ANATOMY_SCHEMA_VERSION,
                ANATOMY_REGION_CATALOG_VERSION,
                RegionId("torso"),
                RegionType("torso"),
                subzones,
                capabilities,
                parameters,
                listOf(PERINEAL_EXTENSION_SLOT),
            ),
        )
        consumers.clear()
        parameters += ParameterDeclaration("skinSweat", emptySet())
        subzones += Subzone(SubzoneId.APEX, RegionId("other"))
        capabilities += Capability.MOTION
        println("OUTPUT=snapshot $spec")

        assertTrue(spec.subzones.isEmpty())
        assertTrue(spec.parameters.single().consumers.isNotEmpty())
        assertEquals(setOf(Capability.SURFACE), spec.capabilities)
        assertTrue(RegionSpec::class.java.methods.none { it.name == "copy" || it.name == "parameterValue" })
    }

    @Test
    fun `目录版本不漂移且公开追加必须升版`() {
        val second = valueOf(
            RegionSpec.declare(
                ANATOMY_SCHEMA_VERSION,
                ANATOMY_REGION_CATALOG_VERSION,
                RegionId("chest"),
                RegionType("chest"),
                emptyList(),
                setOf(Capability.SHAPE),
                emptyList(),
                listOf(PERINEAL_EXTENSION_SLOT),
            ),
        )
        val regions = mutableListOf(presentSpec(), second)
        val slots = mutableListOf<RegionTypeExtensionSlot>()
        val initial = valueOf(
            RegionCatalog.create(
                ANATOMY_SCHEMA_VERSION,
                ANATOMY_REGION_CATALOG_VERSION,
                regions,
                slots,
            ),
        )
        regions.clear()
        slots += RegionTypeExtensionSlot("future", RegionType("future"))
        println("OUTPUT=snapshot-catalog $initial")
        println("OUTPUT=caller-slots-mutated $slots")
        assertEquals(2, initial.regions.size)
        assertTrue(initial.extensionSlots.single().enabledType == null)
        assertTrue(initial.extensionSlots.none { it.key == "future" })

        val third = RegionSpec.declare(
            ANATOMY_SCHEMA_VERSION,
            ANATOMY_REGION_CATALOG_VERSION,
            RegionId("abdomen"),
            RegionType("abdomen"),
            emptyList(),
            setOf(Capability.SHAPE),
            emptyList(),
            listOf(PERINEAL_EXTENSION_SLOT),
        )
        val unchanged = initial.append(valueOf(third), ANATOMY_REGION_CATALOG_VERSION)
        val stale = RegionSpec.declare(
            ANATOMY_SCHEMA_VERSION,
            "anatomy.catalog.old",
            RegionId("stale-example"),
            RegionType("stale-example"),
            emptyList(),
            emptySet(),
            emptyList(),
            listOf(PERINEAL_EXTENSION_SLOT),
        )
        val mismatch = initial.append(valueOf(stale), "anatomy.catalog.v1")
        val malformed = initial.append(valueOf(third), "anatomy.catalog.v01")
        val blank = initial.append(valueOf(third), " ")
        val malformedCatalog = RegionCatalog.create(
            ANATOMY_SCHEMA_VERSION,
            "anatomy.catalog",
            emptyList(),
            listOf(PERINEAL_EXTENSION_SLOT),
        )
        val blankCatalog = RegionCatalog.create(
            ANATOMY_SCHEMA_VERSION,
            " ",
            emptyList(),
            listOf(PERINEAL_EXTENSION_SLOT),
        )
        val upgraded = valueOf(initial.append(valueOf(third), "anatomy.catalog.v1"))
        val nextSpec = valueOf(
            RegionSpec.declare(
                ANATOMY_SCHEMA_VERSION,
                "anatomy.catalog.v1",
                RegionId("back"),
                RegionType("back"),
                emptyList(),
                setOf(Capability.SHAPE),
                emptyList(),
                listOf(PERINEAL_EXTENSION_SLOT),
            ),
        )
        val regressed = upgraded.append(nextSpec, "anatomy.catalog.v0")
        println("OUTPUT=unchanged-catalog-version $unchanged")
        println("OUTPUT=mismatched-catalog-version $mismatch")
        println("OUTPUT=malformed-catalog-version $malformed")
        println("OUTPUT=blank-catalog-version $blank")
        println("OUTPUT=malformed-catalog-create $malformedCatalog")
        println("OUTPUT=blank-catalog-create $blankCatalog")
        println("OUTPUT=regressed-catalog-version $regressed")

        assertEquals(AbsenceReason.CATALOG_VERSION_UNCHANGED, absentOf(unchanged).reason)
        assertEquals(AbsenceReason.CATALOG_VERSION_MISMATCH, absentOf(mismatch).reason)
        assertEquals(AbsenceReason.CATALOG_VERSION_INVALID, absentOf(malformed).reason)
        assertEquals(AbsenceReason.CATALOG_VERSION_INVALID, absentOf(blank).reason)
        assertEquals(AbsenceReason.CATALOG_VERSION_INVALID, absentOf(malformedCatalog).reason)
        assertEquals(AbsenceReason.CATALOG_VERSION_INVALID, absentOf(blankCatalog).reason)
        assertEquals(AbsenceReason.CATALOG_VERSION_REGRESSED, absentOf(regressed).reason)
    }

    private fun declare(parameter: ParameterDeclaration): Resolution<RegionSpec> {
        return RegionSpec.declare(
            ANATOMY_SCHEMA_VERSION,
            ANATOMY_REGION_CATALOG_VERSION,
            RegionId("torso"),
            RegionType("torso"),
            emptyList(),
            setOf(Capability.SURFACE),
            listOf(parameter),
            listOf(PERINEAL_EXTENSION_SLOT),
        )
    }

    private fun presentSpec(): RegionSpec {
        val result = declare(
            ParameterDeclaration(
                "torso.provenance.reference",
                setOf(ParameterConsumer.GENERATION_WITH_PROVENANCE),
            ),
        )
        return valueOf(result)
    }

    private fun <T> valueOf(result: Resolution<T>): T {
        return when (result) {
            is Resolution.Present -> result.value
            is Resolution.Absent -> throw AssertionError("期望 Present，实际 $result")
        }
    }

    private fun absentOf(result: Resolution<*>): Resolution.Absent {
        return when (result) {
            is Resolution.Absent -> result
            is Resolution.Present -> throw AssertionError("期望 Absent，实际 $result")
        }
    }
}
