// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百六十四批 · 纪元实体化——**行为层门禁**（2026-11-02 按用户裁定①②③改写）。
//
// 310 已经把四把尺钉在源级（`tools/check-criteria-gates.py` ＋ `ImmutableCriteriaGateTest`
// ＋ `NoScalarScoreGateTest`）：清单被改写、被配置化、产物出现标量分、锚定集被静默改、
// 纪元账没有三态。那些这里一条都不重做。本文件数的是**新长出来的两条写口与几个读口**：
// `append`／`record` 会不会拦，`snapshotsOf`／`CriteriaChange.rows` 读不读得回。
//
// **委托证据**（裁定③）：本批删掉的是第二套判定（`open`／`transition`／`currentId`——它们自己判
// 周历、判边界、判回滚），留下的必须是「委托关系」的证据。所以原来那七例**一条也没删**，
// 改成同一形状：**造一个 310 会拒的现场 ⇒ `append` 也拒 ⇒ 账上一行都不落**。
// 「310 拦换表、账拦落账」是同一件事的两层，删掉任何一层就没人知道另一层还在不在。
//
// 接线归第三百八十批，所以本文件出现的锚点**全是 fixture**（`HARMLESS` 那一条不在清单上，
// 清单上的七条只用来证明「撞了就红」）；没有一处生产调用。让一张表「此刻生效」走的是
// 310 的 [EpochRuntime.swap]——本批不代替它，也不缓存它的读数。
package com.example.zhengdao.baopu.policy

import com.example.zhengdao.baopu.constitution.GateIo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

/** fixture 锚点：不在不可进化清单上，改它合法。 */
private const val HARMLESS = "app/src/main/java/com/example/zhengdao/baopu/eval/EvalFloor#floorOf"

private const val RULING = "御批 364-1"

class EpochTest {

    @Before
    fun resetBothRegistries() {
        // 两个册各清各的：310 管「此刻生效的是哪张表」，本批管「历次纪元的账」。
        EpochRuntime.reset()
        EpochRegistry.reset()
    }

    // ── 造数据的小工具 ────────────────────────────────────────────────

    private fun entry(id: String, target: String = HARMLESS): CriterionEntry =
        CriterionEntry(id = id, description = "判据 $id（fixture，非御案数据）", target = target, admittedInEpoch = 0L)

    private fun snap(atMs: Long, vararg ids: String): CriteriaSnapshot =
        CriteriaSnapshot.of(epoch = EpochId.of(atMs), seq = 1L, criteria = ids.map { entry(it) })

    /** 身份证与表同源（裁定②）：id 与 label 都从这张表的 [EpochId] 来，测试也不自己起名。 */
    private fun epochOf(snapshot: CriteriaSnapshot, startedAt: Long, reason: String = "把这类误判补上"): Epoch =
        Epoch.of(epoch = snapshot.epoch, startedAt = startedAt, criteria = snapshot.criteria, reason = reason)

    /** 让一张表「此刻生效」——这件事归 310；现场要造对，所以它一红就是测试的问题。 */
    private fun adopt(snapshot: CriteriaSnapshot, atMs: Long) {
        val swapped = EpochRuntime.swap(snapshot, atMs)
        assertTrue("现场没造对：310 的 swap 该成，实测「${swapped.reason}」", swapped.swapped)
    }

    /** 一纪的完整走法：310 换表 → 纪元册落账。返回落账读数。 */
    private fun install(week: Long, vararg ids: String): EpochResult {
        val snapshot = snap(weekStart(week), *ids)
        adopt(snapshot, weekStart(week))
        return EpochRegistry.append(epochOf(snapshot, weekStart(week)), snapshot, RULING, weekStart(week))
    }

    private fun firstEpoch() {
        val r = install(0L, "K1", "K2")
        assertTrue("第一纪落不进账：${r.reason}", r.accepted)
    }

    /** 切下一纪并把读数留下（不能写在断言消息里：那会让 install 跑两遍）。 */
    private fun nextEpoch(): EpochResult = install(1L, "K1", "K3")

    private val id0: String get() = Epoch.idOf(EpochId.of(weekStart(0)))
    private val id1: String get() = Epoch.idOf(EpochId.of(weekStart(1)))
    private val label0: String get() = EpochId.of(weekStart(0)).label()
    private val label1: String get() = EpochId.of(weekStart(1)).label()

    /** 归属到本纪的合法变更行（机读列＋人读列都同源）。 */
    private fun owned(
        id: String = "C1",
        week: Long = 0L,
        target: String = HARMLESS,
        label: String? = null,
    ): CriteriaChange {
        val epoch = EpochId.of(weekStart(week))
        return CriteriaChange(
            changeId = id,
            epochId = Epoch.idOf(epoch),
            epochLabel = label ?: epoch.label(),
            criterionId = "K1",
            target = target,
            atMs = weekStart(week),
        )
    }

    /** 本批源级牙齿读的那个文件（原文，注释也算——话术里写时钟一样是写时钟）。 */
    private fun epochFileText(): String {
        val root = File("src/main").takeIf { it.isDirectory } ?: File("app/src/main")
        return File(root, "java/com/example/zhengdao/baopu/policy/Epoch.kt").readText(Charsets.UTF_8)
    }

    /** 剥注释（照 GateIo 口径）后再剥字符串字面量：拒绝话术里提到 310 不算「还手算」。 */
    private fun epochCode(): String =
        Regex("\"(?:[^\"\\\\]|\\\\.)*\"").replace(GateIo.stripComments(epochFileText()), "")

    // ── 委托证据（裁定③：原七例改写，一条不删）─────────────────────────
    //
    // 形状统一：310 的现场 → 310 拒 → append 也拒 → 账上一行不落。

    @Test
    fun `委托_310没有生效表时append拒且账上一行不落`() {
        // 原「负例_没开纪就想切纪被拒」：「哪张表生效」还没被 310 判过，历史册无处可记。
        val snapshot = snap(weekStart(1), "K1")
        assertNull("现场：310 的当前表就是空的", EpochRuntime.currentSnapshot())
        val r = EpochRegistry.append(epochOf(snapshot, weekStart(1)), snapshot, RULING, weekStart(1))
        assertFalse(r.accepted)
        assertTrue("拒得要指回 310：" + r.reason, r.reason.contains("currentSnapshot") && r.reason.contains("swap"))
        assertEquals("不落一行纪", emptyList<Epoch>(), EpochRegistry.epochs())
        assertEquals("不落一行跳账", emptyList<EpochTransition>(), EpochRegistry.epochTransitions())
        assertEquals("该纪也不许多一张快照", emptyList<CriteriaSnapshot>(), EpochRegistry.snapshotsOf(id1))
    }

    @Test
    fun `委托_未到边界的新纪310先拒_append也不落账`() {
        // 原「负例_落账时刻早于纪元起始被拒」的同一件事：边界归 310 判，账不自己算周历。
        firstEpoch()
        val future = snap(weekStart(5), "K1", "K2")
        val swapped = EpochRuntime.swap(future, weekStart(0))
        assertFalse("现场：310 判它未到边界", swapped.swapped)
        assertTrue("现场没造对：" + swapped.reason, swapped.reason.contains("未到纪元边界"))
        val r = EpochRegistry.append(epochOf(future, weekStart(0)), future, "御批 364-2", weekStart(0))
        assertFalse("310 没让它生效，账上就不许有这一纪", r.accepted)
        assertTrue(r.reason, r.reason.contains("不是此刻生效那张表"))
        assertEquals("账上还是只有一纪", 1, EpochRegistry.epochs().size)
        assertEquals("多一跳都没有", emptyList<EpochTransition>(), EpochRegistry.epochTransitions())
    }

    @Test
    fun `委托_装的不是此刻生效那张表append就拒`() {
        // 原「负例_快照的日历纪与落账时刻不符被拒」：不符这件事现在由「与 310 的当前表比」得出。
        firstEpoch()
        val other = snap(weekStart(1), "K1", "K3")
        val r = EpochRegistry.append(epochOf(other, weekStart(1)), other, RULING, weekStart(1))
        assertFalse("表没换过就想记下一纪＝账与生效表各说一套", r.accepted)
        assertTrue(r.reason, r.reason.contains("不是此刻生效那张表"))
        assertEquals(1, EpochRegistry.epochs().size)
        assertEquals(emptyList<EpochTransition>(), EpochRegistry.epochTransitions())
        assertEquals("此刻生效的还是第一纪那张", snap(weekStart(0), "K1", "K2"), EpochRuntime.currentSnapshot())
    }

    @Test
    fun `委托_纪内热改第二张表进不了310也就进不了账`() {
        // 原「负例_起点不往后挪的切纪被拒」：纪内钉死这件事本批不再自己判，读 310 的读数。
        firstEpoch()
        val hot = snap(weekStart(0), "K1", "K9").copy(seq = 2L)
        val swapped = EpochRuntime.swap(hot, weekStart(0))
        assertFalse("现场：310 拦纪内热改", swapped.swapped)
        assertTrue("现场没造对：" + swapped.reason, swapped.reason.contains("纪内不许热改"))
        val r = EpochRegistry.append(epochOf(hot, weekStart(0)), hot, RULING, weekStart(0))
        assertFalse(r.accepted)
        assertTrue("拒得要指到没生效：" + r.reason, r.reason.contains("不是此刻生效那张表"))
        assertEquals("该纪还是只装过一张表", 1, EpochRegistry.snapshotsOf(id0).size)
        assertEquals("生效表没动", snap(weekStart(0), "K1", "K2"), EpochRuntime.currentSnapshot())
    }

    @Test
    fun `委托_倒退装表被310判成回滚_纪元册不许多一跳`() {
        // 原「负例_日历纪倒退不是新纪元」：倒退＝回滚，回滚不进进化通道，也就不进历史册。
        firstEpoch()
        val second = nextEpoch()
        assertTrue("第二纪没落上：" + second.reason, second.accepted)
        val back = snap(weekStart(0), "K1", "K2")
        assertFalse("倒退着装由 310 拒", EpochRuntime.swap(back, weekStart(1)).swapped)
        assertTrue("回滚本身是允许的（即时、不走审批）", EpochRuntime.rollbackToPrevious().rolledBack)
        val r = EpochRegistry.append(epochOf(back, weekStart(1)), back, "御批 364-9", weekStart(1))
        assertFalse("回滚冒充新纪元：账上不许落", r.accepted)
        assertTrue(r.reason, r.reason.contains("不是此刻生效那张表") || r.reason.contains("已经记过"))
        assertEquals("历次纪元不因为回滚多出一纪", 2, EpochRegistry.epochs().size)
        assertEquals("跳账还是那一跳", 1, EpochRegistry.epochTransitions().size)
        assertEquals("回滚的账由 310 自己记", listOf("$label1→$label0"), EpochRuntime.rollbackLedger())
    }

    @Test
    fun `委托_同一纪元不许记第二次`() {
        // 原「负例_同一纪元不许开第二次」：现在拦它的是「id 重复」这一条账目规矩，不是第二套状态机。
        firstEpoch()
        val snapshot = EpochRuntime.currentSnapshot()!!
        val r = EpochRegistry.append(epochOf(snapshot, weekStart(0), "换个说法"), snapshot, RULING, weekStart(0))
        assertFalse(r.accepted)
        assertTrue("拦它的必须是 id 重复：" + r.reason, r.reason.contains("已经记过"))
        assertEquals(1, EpochRegistry.epochs().size)
        assertEquals("同一纪不许塞两张快照", 1, EpochRegistry.snapshotsOf(id0).size)
        assertEquals("更不许多一跳", emptyList<EpochTransition>(), EpochRegistry.epochTransitions())
    }

    @Test
    fun `委托_id重复不许覆盖已落的身份证`() {
        // 原「负例_纪元id不许重复」：重复就是有人在修历史，账上那一张必须保持原样。
        firstEpoch()
        val snapshot = EpochRuntime.currentSnapshot()!!
        val forged = epochOf(snapshot, weekStart(0), "把 reason 改掉")
        assertFalse(EpochRegistry.append(forged, snapshot, "御批 364-3", weekStart(0)).accepted)
        assertEquals("原来那句还在", "把这类误判补上", EpochRegistry.epochOf(id0)?.reason)
        assertEquals(1, EpochRegistry.epochs().size)
    }

    @Test
    fun `委托_正例落过账的每一纪都等于当时生效那张表`() {
        firstEpoch()
        assertSame("落账读的就是 310 的当前表", EpochRuntime.currentSnapshot(), EpochRegistry.snapshotsOf(id0).single())
        val second = nextEpoch()
        assertTrue("第二纪：" + second.reason, second.accepted)
        assertSame("第二纪落的也是当时那张", EpochRuntime.currentSnapshot(), EpochRegistry.snapshotsOf(id1).single())
        assertEquals("两纪都在册", listOf(id0, id1), EpochRegistry.epochs().map { it.id })
    }

    // ── 判据①：切纪元有测试（旧→新、原因必填、空原因拒）────────────────

    @Test
    fun `切纪元记下旧到新原因与裁定编号`() {
        firstEpoch()
        val second = nextEpoch()
        assertTrue(second.reason, second.accepted)
        val hops = EpochRegistry.epochTransitions()
        assertEquals(1, hops.size)
        assertEquals(id0, hops[0].fromId)
        assertEquals(id1, hops[0].toId)
        assertEquals("把这类误判补上", hops[0].reason)
        assertEquals(RULING, hops[0].rulingRef)
        assertEquals(weekStart(1), hops[0].atMs)
        val wire = hops[0].wire()
        assertTrue("机读列在前：" + wire, wire.startsWith("$id0→$id1|"))
        assertTrue("人读列紧跟（账是给人看的）：" + wire, wire.contains("$label0→$label1"))
        assertEquals("历次纪元按落账顺序", listOf(id0, id1), EpochRegistry.epochs().map { it.id })
    }

    @Test
    fun `负例_空原因建不出纪元`() {
        val hash = Epoch.criteriaHashOf(listOf(entry("K1")))
        assertThrows("判据①：原因空白＝这个纪元根本建不出来", IllegalArgumentException::class.java) {
            Epoch(id = id0, label = label0, startedAt = weekStart(0), criteriaHash = hash, reason = "   ")
        }
    }

    @Test
    fun `负例_空原因也建不出那一跳`() {
        assertThrows(IllegalArgumentException::class.java) {
            EpochTransition(fromId = id0, toId = id1, fromLabel = label0, toLabel = label1, reason = "", rulingRef = RULING, atMs = weekStart(1))
        }
    }

    @Test
    fun `负例_原地踏步的那一跳建不出来`() {
        assertThrows(IllegalArgumentException::class.java) {
            EpochTransition(fromId = id0, toId = id0, fromLabel = label0, toLabel = label0, reason = "换个说法", rulingRef = RULING, atMs = weekStart(0))
        }
    }

    @Test
    fun `负例_缺依据裁定编号的落账被拒`() {
        // 第一纪也要编号：这条不是「切纪才有」，落任何一纪都得有依据。
        val snapshot = snap(weekStart(0), "K1")
        adopt(snapshot, weekStart(0))
        val first = EpochRegistry.append(epochOf(snapshot, weekStart(0)), snapshot, "", weekStart(0))
        assertFalse("没有裁定编号凭什么记这一纪", first.accepted)
        assertTrue(first.reason, first.reason.contains("裁定编号"))
        assertEquals(emptyList<Epoch>(), EpochRegistry.epochs())
        val ok = EpochRegistry.append(epochOf(snapshot, weekStart(0)), snapshot, RULING, weekStart(0))
        assertTrue("补上编号就该落：" + ok.reason, ok.accepted)
        val next = snap(weekStart(1), "K1", "K3")
        adopt(next, weekStart(1))
        val hop = EpochRegistry.append(epochOf(next, weekStart(1)), next, "", weekStart(1))
        assertFalse(hop.accepted)
        assertTrue(hop.reason, hop.reason.contains("裁定编号"))
        assertEquals("拒了就不许留一行跳账", emptyList<EpochTransition>(), EpochRegistry.epochTransitions())
        assertEquals("也不许多一纪", 1, EpochRegistry.epochs().size)
    }

    // ── 判据②：纪元内改不可进化条 ⇒ 拒 ────────────────────────────────

    @Test
    fun `负例_纪内改清单上任一锚点都被拒`() {
        firstEpoch()
        val anchors = ImmutableCriteria.ALL.flatMap { it.anchors }
        assertEquals("三条硬闸共七处锚点（清单改了要同步这条）", 7, anchors.size)
        anchors.forEachIndexed { i, anchor ->
            val r = EpochRegistry.record(owned(id = "C$i", target = anchor))
            assertFalse("改硬闸 $anchor 必须拒", r.accepted)
            assertTrue("拒得要指到清单：" + r.reason, r.reason.contains("不可进化条"))
        }
        assertEquals("一条也没落进变更账", emptyList<CriteriaChange>(), EpochRegistry.changeRows())
    }

    @Test
    fun `负例_换后缀也躲不过清单`() {
        firstEpoch()
        // 清单里的锚点不写 .kt；候选写成带后缀的是同一处——后缀不许当逃逸口（310 的归一化同口径）。
        val r = EpochRegistry.record(owned(target = "app/src/main/java/com/example/zhengdao/baopu/law/EmergencyGate.kt#requiresApproval"))
        assertFalse(r.accepted)
        assertTrue(r.reason, r.reason.contains("不可进化条"))
    }

    @Test
    fun `负例_空锚点是清单的逃逸口同样拒`() {
        firstEpoch()
        val r = EpochRegistry.record(owned(target = "  "))
        assertFalse(r.accepted)
        assertTrue(r.reason, r.reason.contains("空锚点"))
    }

    @Test
    fun `装硬闸的表在310就建不出来_append根本没有那张表可落`() {
        // 派工单要求 append 也核「硬闸锚点拒」；这里钉住它的委托面：那张表在类型层就到不了 append。
        val hardAnchor = ImmutableCriteria.ALL.first().anchors.first()
        assertThrows(IllegalArgumentException::class.java) {
            CriteriaSnapshot.of(epoch = EpochId.of(weekStart(0)), seq = 1L, criteria = listOf(entry("K1", hardAnchor)))
        }
        // 连 copy（同样走构造期）也躲不过——append 里那道锚点扫描因此是不可达的第二层，留着防「哪天改了构造期」。
        firstEpoch()
        assertThrows(IllegalArgumentException::class.java) {
            EpochRuntime.currentSnapshot()!!.copy(criteria = listOf(entry("K1", hardAnchor)))
        }
    }

    // ── 判据③：给定 epochId 可列出该纪的快照（回放）───────────────────

    @Test
    fun `按epochId列出该纪装过的快照`() {
        firstEpoch()
        val second = nextEpoch()
        assertTrue(second.reason, second.accepted)
        assertEquals(listOf("K1", "K2"), EpochRegistry.snapshotsOf(id0).single().criteria.map { it.id })
        assertEquals(listOf("K1", "K3"), EpochRegistry.snapshotsOf(id1).single().criteria.map { it.id })
        assertEquals("第一纪的快照装的是 310 的日历纪", EpochId.of(weekStart(0)), EpochRegistry.snapshotsOf(id0).single().epoch)
        assertNotNull(EpochRegistry.epochOf(id1))
        assertEquals("人读的那一列也在身份证上", label1, EpochRegistry.epochOf(id1)?.label)
    }

    @Test
    fun `查不到的纪元列空表不替它编结论`() {
        firstEpoch()
        assertEquals(emptyList<CriteriaSnapshot>(), EpochRegistry.snapshotsOf("99"))
        assertNull("「没有这一纪」由 epochOf 答", EpochRegistry.epochOf("99"))
    }

    @Test
    fun `纪元册不写310的当前表`() {
        val snapshot = snap(weekStart(0), "K1", "K2")
        adopt(snapshot, weekStart(0))
        val before = EpochRuntime.currentSnapshot()
        assertTrue(EpochRegistry.append(epochOf(snapshot, weekStart(0)), snapshot, RULING, weekStart(0)).accepted)
        assertTrue(EpochRegistry.record(owned()).accepted)
        assertSame("两套各管各的：这里落了纪与账，310 的生效表一个字没动（只读不写）", before, EpochRuntime.currentSnapshot())
    }

    @Test
    fun `生效表换了上一纪的账就不许再落`() {
        // 「读一次」的另一个面：这里没缓存 current，所以边界一动，append 立刻跟着拒。
        firstEpoch()
        val old = EpochRegistry.snapshotsOf(id0).single()
        val second = nextEpoch()
        assertTrue("第二纪没落上：" + second.reason, second.accepted)
        val r = EpochRegistry.append(epochOf(old, weekStart(0)), old, RULING, weekStart(1))
        assertFalse("拿旧表再落一次不许过（也没有第二份 current 可供它过）", r.accepted)
        assertTrue(r.reason, r.reason.contains("不是此刻生效那张表"))
    }

    // ── 牙齿：指纹绑对象＋id 同源 ─────────────────────────────────────

    @Test
    fun `负例_声明的指纹与实际装入的快照不符被拒`() {
        val snapshot = snap(weekStart(0), "K1", "K2")
        adopt(snapshot, weekStart(0))
        val thin = Epoch.of(epoch = snapshot.epoch, startedAt = weekStart(0), criteria = listOf(entry("K1")), reason = "把这类误判补上")
        val r = EpochRegistry.append(thin, snapshot, RULING, weekStart(0))
        assertFalse(r.accepted)
        assertTrue("纪元说的与账上装的不是同一件事：" + r.reason, r.reason.contains("指纹"))
        assertEquals("不符就不落一行", emptyList<Epoch>(), EpochRegistry.epochs())
    }

    @Test
    fun `负例_手填一串字符冒充指纹建不出纪元`() {
        assertThrows(IllegalArgumentException::class.java) {
            Epoch(id = id0, label = label0, startedAt = weekStart(0), criteriaHash = "abc123", reason = "随便")
        }
    }

    @Test
    fun `负例_身份证与表不同源被拒`() {
        // 裁定②：机读列＝310 的日历纪序号，人读列＝它那句 label；两列都不许自己起。
        val snapshot = snap(weekStart(0), "K1")
        adopt(snapshot, weekStart(0))
        val hash = Epoch.criteriaHashOf(snapshot.criteria)
        // 「2964」正是 BASE_MONDAY 那一周的真实序号（首稿拿它当错值，于是递进去一个对的）：
        // 错值必须从这张表自己的 ordinal 推，免得哪天日历纪变了这条负例悄悄变成正例。
        val wrongId = Epoch(
            id = (snapshot.epoch.ordinal + 1).toString(),
            label = label0,
            startedAt = weekStart(0),
            criteriaHash = hash,
            reason = "把这类误判补上",
        )
        val r1 = EpochRegistry.append(wrongId, snapshot, RULING, weekStart(0))
        assertFalse("序号与表不同源不许落账：" + r1.reason, r1.accepted)
        assertTrue("拦它的必须是机读列不同源：" + r1.reason, r1.reason.contains("不同源"))
        val wrongLabel = Epoch(id = Epoch.idOf(snapshot.epoch), label = "随便起的纪名", startedAt = weekStart(0), criteriaHash = hash, reason = "把这类误判补上")
        val r = EpochRegistry.append(wrongLabel, snapshot, RULING, weekStart(0))
        assertFalse(r.accepted)
        assertTrue("拦它的必须是人名不同源：" + r.reason, r.reason.contains("人读名"))
        assertEquals(emptyList<Epoch>(), EpochRegistry.epochs())
    }

    @Test
    fun `负例_纪元id不是日历纪序号就建不出来`() {
        val hash = Epoch.criteriaHashOf(listOf(entry("K1")))
        listOf("E1", " 1", "-1", "1x").forEach {
            assertThrows("寻址必须挂 310 的 ordinal，实测「$it」", IllegalArgumentException::class.java) {
                Epoch(id = it, label = "whatever", startedAt = weekStart(0), criteriaHash = hash, reason = "把这类误判补上")
            }
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
        assertThrows("把判据拆光不叫进化", IllegalArgumentException::class.java) {
            Epoch.of(epoch = EpochId.of(weekStart(0)), startedAt = weekStart(0), criteria = emptyList(), reason = "把判据拆光")
        }
    }

    // ── 判据④：变更账每行归属一个纪元（老数据标「未归属」）──────────────

    @Test
    fun `负例_没写归属的新行被拒`() {
        firstEpoch()
        val r = EpochRegistry.record(CriteriaChange("C1", null, CriteriaChange.UNATTRIBUTED, "K1", HARMLESS, weekStart(0)))
        assertFalse("「未归属」只许从读口回来，不是写口开的洞", r.accepted)
        assertTrue(r.reason, r.reason.contains("没写纪元归属"))
    }

    @Test
    fun `负例_归属到别一纪的行被拒`() {
        firstEpoch()
        val r = EpochRegistry.record(CriteriaChange("C1", "9", label1, "K1", HARMLESS, weekStart(0)))
        assertFalse(r.accepted)
        assertTrue("拦它的必须是「此刻生效那一纪」：" + r.reason, r.reason.contains("此刻生效"))
        assertEquals(emptyList<CriteriaChange>(), EpochRegistry.changeRows())
    }

    @Test
    fun `负例_310没有生效表时变更无处归属`() {
        val r = EpochRegistry.record(owned())
        assertFalse(r.accepted)
        assertTrue("归属的「此刻」也读 310，不自己存：" + r.reason, r.reason.contains("还没有生效表"))
        assertEquals(emptyList<CriteriaChange>(), EpochRegistry.changeRows())
    }

    @Test
    fun `负例_纪还没落账就不许认领变更`() {
        adopt(snap(weekStart(0), "K1"), weekStart(0))
        val r = EpochRegistry.record(owned())
        assertFalse("先落纪，再记它的变更", r.accepted)
        assertTrue(r.reason, r.reason.contains("还没在册上"))
    }

    @Test
    fun `负例_机读与人读各说一套的变更行被拒`() {
        firstEpoch()
        val r = EpochRegistry.record(owned(label = "瞎写的纪名"))
        assertFalse(r.accepted)
        assertTrue(r.reason, r.reason.contains("各说一套"))
        assertEquals(emptyList<CriteriaChange>(), EpochRegistry.changeRows())
    }

    @Test
    fun `负例_时刻早于本纪起点的变更挂不进本纪`() {
        firstEpoch()
        val r = EpochRegistry.record(CriteriaChange("C9", id0, label0, "K1", HARMLESS, weekStart(0) - DAY_MS))
        assertFalse("老行挂进新纪＝假账", r.accepted)
        assertTrue(r.reason, r.reason.contains("早于"))
        assertEquals(emptyList<CriteriaChange>(), EpochRegistry.changeRows())
    }

    @Test
    fun `负例_同一笔记两遍就是改账`() {
        firstEpoch()
        assertTrue(EpochRegistry.record(owned()).accepted)
        val r = EpochRegistry.record(owned())
        assertFalse(r.accepted)
        assertTrue(r.reason, r.reason.contains("id 重复"))
        assertEquals(1, EpochRegistry.changeRows().size)
    }

    @Test
    fun `纪内改普通判据落账且归属当前纪`() {
        firstEpoch()
        assertTrue(EpochRegistry.record(owned()).accepted)
        val rows = EpochRegistry.changeRows()
        assertEquals(1, rows.size)
        assertTrue(rows[0].attributed())
        assertEquals(id0, rows[0].epochId)
        assertEquals(label0, rows[0].epochLabel)
        assertEquals(listOf(rows[0]), EpochRegistry.changesOf(id0))
        assertEquals("别的纪一条也不该认领", emptyList<CriteriaChange>(), EpochRegistry.changesOf(id1))
    }

    @Test
    fun `变更账的六列原样读回`() {
        firstEpoch()
        assertTrue(EpochRegistry.record(owned()).accepted)
        val rows = EpochRegistry.changeRows()
        val text = rows.joinToString("\n") { it.wire() } + "\n"
        assertTrue("机读列与人读列都在文本上：" + text, text.contains("|$id0|") && text.contains(label0))
        assertEquals(rows, CriteriaChange.rows(text))
        assertTrue(CriteriaChange.rows(text).single().attributed())
    }

    @Test
    fun `老格式四列读回null归属并标未归属`() {
        val row = CriteriaChange.decode("C1|K1|$HARMLESS|${weekStart(0)}")
        assertNull("本批之前的行压根没有纪元列", row?.epochId)
        assertEquals(CriteriaChange.UNATTRIBUTED, row?.epochLabel)
        assertFalse(row!!.attributed())
    }

    @Test
    fun `第二列空也是未归属`() {
        // 没归属 ⇒ 第三列是谁都不作数，统一落成「未归属」，不许留一句假人名。
        assertEquals(CriteriaChange.UNATTRIBUTED, CriteriaChange.decode("C1||瞎写的名字|K1|$HARMLESS|${weekStart(0)}")?.epochLabel)
        assertEquals(CriteriaChange.UNATTRIBUTED, CriteriaChange.decode("C1|||K1|$HARMLESS|${weekStart(0)}")?.epochLabel)
        assertNull(CriteriaChange.decode("C1|||K1|$HARMLESS|${weekStart(0)}")?.epochId)
    }

    @Test
    fun `负例_有机读列却没人读列的行读不回`() {
        assertNull("机读与人读两列必须都在：账不许只剩数字", CriteriaChange.decode("C1|$id0||K1|$HARMLESS|${weekStart(0)}"))
    }

    @Test
    fun `负例_读口的未归属不许反过来当写口的洞`() {
        firstEpoch()
        val legacy = CriteriaChange.decode("C1|K1|$HARMLESS|${weekStart(0)}")
        assertTrue("读得回才算真的兼容老数据", legacy != null)
        assertFalse(EpochRegistry.record(legacy!!).accepted)
    }

    @Test
    fun `负例_列数不对的行读不回`() {
        assertNull(CriteriaChange.decode("C1|K1|$HARMLESS|${weekStart(0)}|多一列"))
        assertNull(CriteriaChange.decode("C1|$id0|$label0|K1|$HARMLESS"))
        assertNull(CriteriaChange.decode("C1|$id0|$label0|K1|$HARMLESS|不是时刻"))
        assertNull(CriteriaChange.decode("|$id0|$label0|K1|$HARMLESS|${weekStart(0)}"))
    }

    @Test
    fun `坏行跳过但条数不静默吞`() {
        val text = "C1|$id0|$label0|K1|$HARMLESS|${weekStart(0)}\n坏行\n还有一行|||\n"
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
        firstEpoch()
        assertTrue(EpochRegistry.record(owned(id = "C1")).accepted)
        val second = install(1L, "K1", "K2", "K3")
        assertTrue("第二纪没落账：" + second.reason, second.accepted)
        val row = CriteriaChange("C2", id1, label1, "K1", HARMLESS, weekStart(1))
        val recorded = EpochRegistry.record(row)
        assertTrue("第二纪的变更记不进：" + recorded.reason, recorded.accepted)
        val rows = EpochRegistry.changeRows()
        assertEquals(listOf(id0, id1), rows.map { it.epochId })
        assertTrue(rows.all { it.attributed() })
        assertEquals(1, EpochRegistry.changesOf(id0).size)
        assertEquals(1, EpochRegistry.changesOf(id1).size)
    }

    // ── 源级牙齿：零时钟、不存第二份 current、类型全 internal ────────────

    @Test
    fun `纪元册文件零时钟`() {
        val text = epochFileText()
        listOf("System.currentTimeMillis", "System.nanoTime", "SystemClock", "Instant.now", "LocalDateTime.now", "LocalDate.now", "Date(", "Clock.")
            .forEach { token ->
                assertFalse("时刻一律由调用方给（同 310）：Epoch.kt 不许出现 $token", text.contains(token))
            }
    }

    @Test
    fun `纪元册不存第二份current也不算周历`() {
        val code = epochCode()
        assertFalse("裁定①：不许有 currentId 这种第二份「此刻」", code.contains("currentId"))
        assertFalse("不许把生效表存成字段或局部量", Regex("""(?:val|var)\s+current\b""").containsMatchIn(code))
        assertFalse("裁定①：周历一次都不许自己算（EpochId.of 归 310）", code.contains("EpochId.of"))
        assertFalse("不许有换表写口（边界归 EpochRuntime.swap）", code.contains(".swap("))
        assertFalse("不许有回滚口（回滚归 310）", code.contains("rollbackToPrevious("))
        assertFalse("不许碰三态（那是 310 的纪元账）", code.contains("EpochState"))
        val reads = Regex("""EpochRuntime\s*\.\s*([A-Za-z]+)""").findAll(code).map { it.groupValues[1] }.toSet()
        assertEquals("对 310 只许读生效表这一件事，不写不算：$reads", setOf("currentSnapshot"), reads)
        assertFalse("三个写口里删掉的两个不许回来", code.contains("fun open(") || code.contains("fun transition("))
    }

    @Test
    fun `委托那句还写在KDoc里`() {
        val text = epochFileText()
        assertTrue("裁定①那句路桩不许被下一个人抹掉", text.contains("周历、边界、回滚一律不还手算"))
        assertTrue("「不许再存一份 current」也要在路上", text.contains("不许在这里存第二份 current"))
    }

    @Test
    fun `本批新建的类型全是internal没有公共面`() {
        val text = epochFileText()
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
