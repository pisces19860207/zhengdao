// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百六十四批 · 纪元实体化——**行为层门禁**。
//
// 310 已经把四把尺钉在源级（`tools/check-criteria-gates.py` ＋ `ImmutableCriteriaGateTest`
// ＋ `NoScalarScoreGateTest`）：清单被改写、被配置化、产物出现标量分、锚定集被静默改、
// 纪元账没有三态。那些这里一条都不重做。本文件数的是**新长出来的三个写口与两个读口**：
// `open`／`transition`／`record` 会不会拦，`snapshotsOf`／`CriteriaChange.rows` 读不读得回。
//
// 接线归第三百八十批，所以本文件出现的锚点**全是 fixture**（`HARMLESS` 那一条不在清单上，
// 清单上的七条只用来证明「撞了就红」）；没有一处生产调用。
//
// 纪元的**算法**（一周一纪／纪内钉死／边界换表／缺陷回滚）在 310 的 `CriteriaEpoch.kt`，
// 本批只补它缺的历史实体与账。因此这里既复用 [EpochId]／[CriteriaSnapshot]／[ImmutableCriteria]，
// 也有一条负例钉住「纪元册不许写 310 的当前表」——两套东西不能变成两条权力链。
package com.example.zhengdao.baopu.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDate

private const val DAY_MS = 24L * 3_600_000L

/** 2026-10-26 是本单的周一；测试拿它当纪元基准，不读系统时钟（照 310 `CriteriaEvolutionTest` 口径）。 */
private val BASE_MONDAY: LocalDate = LocalDate.of(2026, 10, 26)

private fun weekStart(weeks: Long): Long = BASE_MONDAY.plusWeeks(weeks).toEpochDay() * DAY_MS

/** 故意很远的一周：负例「日历纪倒退」要的就是「起点往后挪、日历纪却往回走」这条路。 */
private const val FAR_WEEKS = 50L

/** fixture 锚点：不在不可进化清单上，改它合法。 */
private const val HARMLESS = "app/src/main/java/com/example/zhengdao/baopu/eval/EvalFloor#floorOf"

class EpochTest {

    @Before
    fun resetRegistry() {
        EpochRegistry.reset()
    }

    // ── 造数据的四个小工具 ────────────────────────────────────────────

    private fun entry(id: String, target: String = HARMLESS): CriterionEntry =
        CriterionEntry(id = id, description = "判据 $id（fixture，非御案数据）", target = target, admittedInEpoch = 0L)

    private fun snap(atMs: Long, vararg ids: String): CriteriaSnapshot =
        CriteriaSnapshot.of(epoch = EpochId.of(atMs), seq = 1L, criteria = ids.map { entry(it) })

    private fun epochOf(id: String, startedAt: Long, vararg ids: String): Epoch =
        Epoch.of(id = id, label = "$id 纪", startedAt = startedAt, criteria = ids.map { entry(it) }, reason = "把这类误判补上")

    private fun change(
        id: String = "C1",
        epochId: String? = "E1",
        target: String = HARMLESS,
    ): CriteriaChange = CriteriaChange(
        changeId = id,
        epochId = epochId,
        criterionId = "K1",
        target = target,
        atMs = weekStart(0),
    )

    private fun openFirstEpoch() {
        val opened = EpochRegistry.open(epochOf("E1", weekStart(0), "K1", "K2"), snap(weekStart(0), "K1", "K2"), weekStart(0))
        assertTrue("开纪本身被拒：${opened.reason}", opened.accepted)
    }

    // ── 判据①：切纪元有测试（旧→新、原因必填、空原因拒）────────────────

    @Test
    fun `切纪元记下旧到新原因与裁定编号`() {
        openFirstEpoch()
        val r = EpochRegistry.transition(epochOf("E2", weekStart(1), "K1", "K3"), snap(weekStart(1), "K1", "K3"), "御批 364-1", weekStart(1))
        assertTrue(r.reason, r.accepted)
        val hops = EpochRegistry.epochTransitions()
        assertEquals(1, hops.size)
        assertEquals("E1", hops[0].fromId)
        assertEquals("E2", hops[0].toId)
        assertEquals("把这类误判补上", hops[0].reason)
        assertEquals("御批 364-1", hops[0].rulingRef)
        assertEquals(weekStart(1), hops[0].atMs)
        assertEquals("当前纪要跟着走", "E2", EpochRegistry.current()?.id)
        assertEquals("历次纪元按开纪顺序", listOf("E1", "E2"), EpochRegistry.epochs().map { it.id })
        assertTrue(hops[0].wire().startsWith("E1→E2|"))
    }

    @Test
    fun `负例_空原因建不出纪元`() {
        val hash = Epoch.criteriaHashOf(listOf(entry("K1")))
        assertThrows("判据①：原因空白＝这个纪元根本建不出来", IllegalArgumentException::class.java) {
            Epoch(id = "E1", label = "E1 纪", startedAt = weekStart(0), criteriaHash = hash, reason = "   ")
        }
    }

    @Test
    fun `负例_空原因也建不出那一跳`() {
        assertThrows(IllegalArgumentException::class.java) {
            EpochTransition(fromId = "E1", toId = "E2", reason = "", rulingRef = "御批 364-1", atMs = weekStart(1))
        }
    }

    @Test
    fun `负例_缺依据裁定编号的切纪被拒`() {
        openFirstEpoch()
        val r = EpochRegistry.transition(epochOf("E2", weekStart(1), "K1"), snap(weekStart(1), "K1"), "", weekStart(1))
        assertFalse("没有裁定编号凭什么叫新纪", r.accepted)
        assertTrue(r.reason, r.reason.contains("裁定编号"))
        assertEquals("E1", EpochRegistry.current()?.id)
        assertEquals("拒了就不许留一行跳账", emptyList<EpochTransition>(), EpochRegistry.epochTransitions())
    }

    @Test
    fun `负例_没开纪就想切纪被拒`() {
        val r = EpochRegistry.transition(epochOf("E2", weekStart(1), "K1"), snap(weekStart(1), "K1"), "御批 364-1", weekStart(1))
        assertFalse(r.accepted)
        assertTrue(r.reason, r.reason.contains("还没开纪"))
    }

    @Test
    fun `负例_同一纪元不许开第二次`() {
        openFirstEpoch()
        val r = EpochRegistry.open(epochOf("E1", weekStart(0), "K9"), snap(weekStart(0), "K9"), weekStart(0))
        assertFalse(r.accepted)
        assertTrue("此后动判据只能切纪：" + r.reason, r.reason.contains("不许再开一纪"))
        assertEquals("E1", EpochRegistry.current()?.id)
    }

    @Test
    fun `负例_纪元id不许重复`() {
        openFirstEpoch()
        val r = EpochRegistry.transition(epochOf("E1", weekStart(1), "K1", "K3"), snap(weekStart(1), "K1", "K3"), "御批 364-2", weekStart(1))
        assertFalse(r.accepted)
        assertTrue(r.reason, r.reason.contains("已存在"))
    }

    @Test
    fun `负例_起点不往后挪的切纪被拒`() {
        openFirstEpoch()
        val r = EpochRegistry.transition(epochOf("E2", weekStart(0), "K1"), snap(weekStart(0), "K1"), "御批 364-3", weekStart(0))
        assertFalse(r.accepted)
        assertTrue(r.reason, r.reason.contains("纪要往后切") && r.reason.contains("回滚"))
    }

    @Test
    fun `负例_原地踏步的那一跳建不出来`() {
        assertThrows(IllegalArgumentException::class.java) {
            EpochTransition(fromId = "E1", toId = "E1", reason = "换个说法", rulingRef = "御批 364-4", atMs = weekStart(0))
        }
    }

    @Test
    fun `负例_日历纪倒退不是新纪元`() {
        // 开纪时把落账时刻报得很远（那一纪装的是 FAR_WEEKS 的日历纪），切纪时只把起点往前挪一周：
        // 起点递增管住了，日历纪却在往回走——这条路必须被单独拦住，否则「回滚」可以冒充「进化」。
        val opened = EpochRegistry.open(epochOf("E1", weekStart(0), "K1"), snap(weekStart(FAR_WEEKS), "K1"), weekStart(FAR_WEEKS))
        assertTrue(opened.reason, opened.accepted)
        val r = EpochRegistry.transition(epochOf("E2", weekStart(1), "K1", "K2"), snap(weekStart(1), "K1", "K2"), "御批 364-5", weekStart(1))
        assertFalse(r.accepted)
        assertTrue(r.reason, r.reason.contains("倒退"))
        assertEquals("拒了就不许改当前纪", "E1", EpochRegistry.current()?.id)
    }

    // ── 判据②：纪元内改不可进化条 ⇒ 拒 ────────────────────────────────

    @Test
    fun `负例_纪内改清单上任一锚点都被拒`() {
        openFirstEpoch()
        val anchors = ImmutableCriteria.ALL.flatMap { it.anchors }
        assertEquals("三条硬闸共七处锚点（清单改了要同步这条）", 7, anchors.size)
        anchors.forEachIndexed { i, anchor ->
            val r = EpochRegistry.record(change(id = "C$i", target = anchor))
            assertFalse("改硬闸 $anchor 必须拒", r.accepted)
            assertTrue("拒得要指到清单：" + r.reason, r.reason.contains("不可进化条"))
        }
        assertEquals("一条也没落进变更账", emptyList<CriteriaChange>(), EpochRegistry.changeRows())
    }

    @Test
    fun `负例_换后缀也躲不过清单`() {
        openFirstEpoch()
        // 清单里的锚点不写 .kt；候选写成带后缀的是同一处——后缀不许当逃逸口（310 的归一化同口径）。
        val r = EpochRegistry.record(change(target = "app/src/main/java/com/example/zhengdao/baopu/law/EmergencyGate.kt#requiresApproval"))
        assertFalse(r.accepted)
        assertTrue(r.reason, r.reason.contains("不可进化条"))
    }

    @Test
    fun `负例_空锚点是清单的逃逸口同样拒`() {
        openFirstEpoch()
        val r = EpochRegistry.record(change(target = "  "))
        assertFalse(r.accepted)
        assertTrue(r.reason, r.reason.contains("空锚点"))
    }

    @Test
    fun `负例_没写归属的新行被拒`() {
        openFirstEpoch()
        val r = EpochRegistry.record(change(epochId = null))
        assertFalse("「未归属」只许从读口回来，不是写口开的洞", r.accepted)
        assertTrue(r.reason, r.reason.contains("没写 epochId"))
    }

    @Test
    fun `负例_归属到别一纪的行被拒`() {
        openFirstEpoch()
        val r = EpochRegistry.record(change(epochId = "E9"))
        assertFalse(r.accepted)
        assertTrue(r.reason, r.reason.contains("当前纪"))
    }

    @Test
    fun `负例_时刻早于本纪起点的变更挂不进本纪`() {
        openFirstEpoch()
        val r = EpochRegistry.record(change(id = "C9").copy(atMs = weekStart(0) - DAY_MS))
        assertFalse("老行挂进新纪＝假账", r.accepted)
        assertTrue(r.reason, r.reason.contains("早于当前纪"))
        assertEquals(emptyList<CriteriaChange>(), EpochRegistry.changeRows())
    }

    @Test
    fun `负例_同一笔记两遍就是改账`() {
        openFirstEpoch()
        assertTrue(EpochRegistry.record(change()).accepted)
        val r = EpochRegistry.record(change())
        assertFalse(r.accepted)
        assertTrue(r.reason, r.reason.contains("id 重复"))
        assertEquals(1, EpochRegistry.changeRows().size)
    }

    @Test
    fun `负例_没开纪就没有归属可追`() {
        val r = EpochRegistry.record(change())
        assertFalse(r.accepted)
        assertTrue(r.reason, r.reason.contains("还没开纪"))
    }

    @Test
    fun `纪内改普通判据落账且归属当前纪`() {
        openFirstEpoch()
        assertTrue(EpochRegistry.record(change()).accepted)
        val rows = EpochRegistry.changeRows()
        assertEquals(1, rows.size)
        assertTrue(rows[0].attributed())
        assertEquals("E1", rows[0].epochId)
        assertEquals(listOf(rows[0]), EpochRegistry.changesOf("E1"))
        assertEquals("别的纪一条也不该认领", emptyList<CriteriaChange>(), EpochRegistry.changesOf("E2"))
    }

    // ── 判据③：给定 epochId 可列出该纪的快照 ──────────────────────────

    @Test
    fun `按epochId列出该纪装过的快照`() {
        openFirstEpoch()
        assertTrue(
            EpochRegistry.transition(epochOf("E2", weekStart(1), "K1", "K3"), snap(weekStart(1), "K1", "K3"), "御批 364-1", weekStart(1)).accepted,
        )
        assertEquals(listOf("K1", "K2"), EpochRegistry.snapshotsOf("E1").single().criteria.map { it.id })
        assertEquals(listOf("K1", "K3"), EpochRegistry.snapshotsOf("E2").single().criteria.map { it.id })
        assertEquals("E1 的快照装的是 310 的日历纪", EpochId.of(weekStart(0)), EpochRegistry.snapshotsOf("E1").single().epoch)
        assertNotNull(EpochRegistry.epochOf("E2"))
    }

    @Test
    fun `查不到的纪元列空表不替它编结论`() {
        openFirstEpoch()
        assertEquals(emptyList<CriteriaSnapshot>(), EpochRegistry.snapshotsOf("E9"))
        assertNull("「没有这一纪」由 epochOf 答", EpochRegistry.epochOf("E9"))
    }

    @Test
    fun `快照沿用310的类型且纪内不装不可进化条`() {
        openFirstEpoch()
        // 310 的构造期自检：想把硬闸装进新表＝这张表根本建不出来（这里只确认本批没绕过它）。
        assertThrows(IllegalArgumentException::class.java) {
            CriteriaSnapshot.of(
                epoch = EpochId.of(weekStart(0)),
                seq = 1L,
                criteria = listOf(entry("K1", ImmutableCriteria.ALL.first().anchors.first())),
            )
        }
    }

    @Test
    fun `纪元册不写310的当前表`() {
        val before = EpochRuntime.currentSnapshot()
        openFirstEpoch()
        assertTrue(EpochRegistry.record(change()).accepted)
        assertEquals("两套各管各的：这里落了纪与账，310 的生效表一个字没动", before, EpochRuntime.currentSnapshot())
    }

    // ── 牙齿：指纹绑对象＋形状自洽 ────────────────────────────────────

    @Test
    fun `负例_声明的指纹与实际装入的快照不符被拒`() {
        val r = EpochRegistry.open(epochOf("E1", weekStart(0), "K1"), snap(weekStart(0), "K1", "K2"), weekStart(0))
        assertFalse(r.accepted)
        assertTrue("纪元说的与账上装的不是同一件事：" + r.reason, r.reason.contains("指纹"))
    }

    @Test
    fun `负例_手填一串字符冒充指纹建不出纪元`() {
        assertThrows(IllegalArgumentException::class.java) {
            Epoch(id = "E1", label = "E1 纪", startedAt = weekStart(0), criteriaHash = "abc123", reason = "随便")
        }
    }

    @Test
    fun `指纹是64位小写十六进制`() {
        val hash = Epoch.criteriaHashOf(listOf(entry("K1"), entry("K2")))
        assertEquals(64, hash.length)
        assertTrue(hash, hash.matches(Regex("^[0-9a-f]{64}$")))
    }

    @Test
    fun `同一批判据换个书写顺序不算换版`() {
        assertEquals(
            Epoch.criteriaHashOf(listOf(entry("K1"), entry("K2"))),
            Epoch.criteriaHashOf(listOf(entry("K2"), entry("K1"))),
        )
    }

    @Test
    fun `分隔符不许当指纹的逃逸口`() {
        val a = Epoch.criteriaHashOf(listOf(CriterionEntry("a|b", "c", HARMLESS, 0L)))
        val b = Epoch.criteriaHashOf(listOf(CriterionEntry("a", "b|c", HARMLESS, 0L)))
        assertFalse("两个字段边界不同的集合必须算出两个指纹", a == b)
    }

    @Test
    fun `判据条数变了就是另一版`() {
        assertFalse(
            Epoch.criteriaHashOf(listOf(entry("K1"))) == Epoch.criteriaHashOf(listOf(entry("K1"), entry("K2"))),
        )
    }

    @Test
    fun `负例_空判据表不算一个纪元`() {
        val r = EpochRegistry.open(epochOf("E1", weekStart(0)), snap(weekStart(0)), weekStart(0))
        assertFalse("把判据拆光不叫进化", r.accepted)
        assertTrue(r.reason, r.reason.contains("空判据表"))
    }

    @Test
    fun `负例_落账时刻早于纪元起始被拒`() {
        val r = EpochRegistry.open(epochOf("E1", weekStart(1), "K1"), snap(weekStart(0), "K1"), weekStart(0))
        assertFalse(r.accepted)
        assertTrue(r.reason, r.reason.contains("早于"))
    }

    @Test
    fun `负例_快照的日历纪与落账时刻不符被拒`() {
        val r = EpochRegistry.open(epochOf("E1", weekStart(0), "K1"), snap(weekStart(3), "K1"), weekStart(0))
        assertFalse(r.accepted)
        assertTrue(r.reason, r.reason.contains("日历纪与落账时刻不符"))
    }

    // ── 判据④：变更账每行带 epochId，老数据标「未归属」──────────────────

    @Test
    fun `变更账的五列原样读回`() {
        openFirstEpoch()
        assertTrue(EpochRegistry.record(change()).accepted)
        val text = EpochRegistry.changeRows().joinToString("\n") { it.wire() } + "\n"
        val back = CriteriaChange.rows(text)
        assertEquals(listOf(EpochRegistry.changeRows().first()), back)
        assertTrue(back.single().attributed())
    }

    @Test
    fun `老格式四列读回null归属并标未归属`() {
        val row = CriteriaChange.decode("C1|K1|$HARMLESS|${weekStart(0)}")
        assertNull("本批之前的行压根没有纪元列", row?.epochId)
        assertEquals(CriteriaChange.UNATTRIBUTED, row?.epochLabel())
        assertFalse(row!!.attributed())
    }

    @Test
    fun `新格式第二列空也是未归属`() {
        val row = CriteriaChange.decode("C1||K1|$HARMLESS|${weekStart(0)}")
        assertNull(row?.epochId)
        assertEquals("未归属", row?.epochLabel())
    }

    @Test
    fun `负例_读口的未归属不许反过来当写口的洞`() {
        openFirstEpoch()
        val legacy = CriteriaChange.decode("C1|K1|$HARMLESS|${weekStart(0)}")
        assertTrue("读得回才算真的兼容老数据", legacy != null)
        assertFalse(EpochRegistry.record(legacy!!).accepted)
    }

    @Test
    fun `负例_列数不对的行读不回`() {
        assertNull(CriteriaChange.decode("C1|K1|$HARMLESS"))
        assertNull(CriteriaChange.decode("C1|E1|K1|$HARMLESS|${weekStart(0)}|多一列"))
        assertNull(CriteriaChange.decode("C1|E1|K1|$HARMLESS|不是时刻"))
        assertNull(CriteriaChange.decode("|E1|K1|$HARMLESS|${weekStart(0)}"))
    }

    @Test
    fun `坏行跳过但条数不静默吞`() {
        val text = "C1|E1|K1|$HARMLESS|${weekStart(0)}\n坏行\n还有一行|||\n"
        assertEquals(1, CriteriaChange.rows(text).size)
        assertEquals("账上有行、这里读不出＝格式变了，不许当成「没写过」", 2, CriteriaChange.unreadable(text))
    }

    @Test
    fun `空账读回零行且零坏行`() {
        assertEquals(emptyList<CriteriaChange>(), CriteriaChange.rows(""))
        assertEquals(0, CriteriaChange.unreadable(""))
    }

    @Test
    fun `账上每一行都追得到纪元`() {
        openFirstEpoch()
        assertTrue(EpochRegistry.record(change(id = "C1")).accepted)
        assertTrue(
            EpochRegistry.transition(epochOf("E2", weekStart(1), "K1", "K2"), snap(weekStart(1), "K1", "K2"), "御批 364-1", weekStart(1)).accepted,
        )
        assertTrue(EpochRegistry.record(change(id = "C2", epochId = "E2").copy(atMs = weekStart(1))).accepted)
        val rows = EpochRegistry.changeRows()
        assertEquals(listOf("E1", "E2"), rows.map { it.epochId })
        assertTrue(rows.all { it.attributed() })
        assertEquals(1, EpochRegistry.changesOf("E1").size)
        assertEquals(1, EpochRegistry.changesOf("E2").size)
    }

    // ── 源级牙齿：本包零时钟、新建的类型全 internal ────────────────────

    @Test
    fun `纪元册文件零时钟`() {
        val root = File("src/main").takeIf { it.isDirectory } ?: File("app/src/main")
        val text = File(root, "java/com/example/zhengdao/baopu/policy/Epoch.kt").readText(Charsets.UTF_8)
        listOf("System.currentTimeMillis", "System.nanoTime", "SystemClock", "Instant.now", "LocalDateTime.now", "LocalDate.now", "Date(", "Clock.")
            .forEach { token ->
                assertFalse("时刻一律由调用方给（同 310）：Epoch.kt 不许出现 $token", text.contains(token))
            }
    }

    @Test
    fun `本批新建的类型全是internal没有公共面`() {
        val root = File("src/main").takeIf { it.isDirectory } ?: File("app/src/main")
        val text = File(root, "java/com/example/zhengdao/baopu/policy/Epoch.kt").readText(Charsets.UTF_8)
        listOf(
            "internal data class Epoch(",
            "internal data class EpochTransition(",
            "internal data class CriteriaChange(",
            "internal data class EpochResult(",
            "internal object EpochRegistry",
        ).forEach {
            assertTrue("缺了 $it", text.contains(it))
        }
        assertFalse("接线归 380：本文件不许引用 agent 的东西", text.contains("com.example.zhengdao.baopu.agent"))
    }
}
