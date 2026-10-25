// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百零四批 · 技能索引两向对账（只报不改）的判据测试——全部 JVM 直测、表驱动。
//
// §1 三类不一致的正例与「不该报」的负例（派工单 §2：.bak 不算正文／近似名不救两边／
//    同名不同场景不算双行／空目录不报／软删不算悬空）；
// §2 双行判据的场景归一细节（去标点空白、前 20 字、归一为空不判）；
// §3 收尾便签文案与去重门（全一致不出现、同批每会话最多一次）；
// §4 162 报告附录（二）「存量脏数据清单」的**等价假数据**整跑（三类各命中一条＋空目录静默）；
// §5 源码级红线（负例五/六）：对账文件里不许出现任何写口／删档调用——只报不改是字面的。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SkillIndexReconcileTest {

    // ── 夹具：索引行走 SkillRules 渲染口（与写侧同一套口径，不手拼路径） ──

    private fun idx(name: String, scenario: String): String =
        SkillRules.renderIndexLine(SkillRules.IndexLine(MemoryScope.PROJECT, name, scenario))

    private fun dir(name: String, vararg files: String): SkillIndexReconcile.SkillDir =
        SkillIndexReconcile.SkillDir(name, files.toList())

    private fun reportOf(
        index: String?,
        dirs: List<SkillIndexReconcile.SkillDir>,
    ): SkillIndexReconcile.Report = SkillIndexReconcile.reconcile(index, dirs)

    private fun doubleNames(r: SkillIndexReconcile.Report): List<List<String>> =
        r.doubleRows.map { it.names }

    // ── §1 三类判据：正例＋不该报的（表驱动） ─────────────────────────────

    private data class Case(
        val label: String,
        val index: String?,
        val dirs: List<SkillIndexReconcile.SkillDir>,
        val wantDangling: List<String>,
        val wantOrphans: List<String>,
        val wantDouble: List<List<String>>,
    )

    private val cases = listOf(
        Case(
            label = "悬空正例：目录只剩 .bak ⇒ 照报（.bak 不算正文，真实例 punct3）",
            index = idx("punct3", "中文排版先调标点间距"),
            dirs = listOf(dir("punct3", "punct3.SKILL.md.bak-20261021-122604")),
            wantDangling = listOf("punct3"), wantOrphans = emptyList(), wantDouble = emptyList(),
        ),
        Case(
            label = "悬空正例·目录整个不在 ⇒ 照报；同场的旁人目录照常算失联",
            index = idx("甲", "场景一"),
            dirs = listOf(dir("乙", SkillRules.SKILL_FILE)),
            wantDangling = listOf("甲"), wantOrphans = listOf("乙"), wantDouble = emptyList(),
        ),
        Case(
            label = "失联正例：有正文没行（真实例 haiku-helper）",
            index = null,
            dirs = listOf(dir("haiku-helper", SkillRules.SKILL_FILE)),
            wantDangling = emptyList(), wantOrphans = listOf("haiku-helper"), wantDouble = emptyList(),
        ),
        Case(
            label = "双行正例：场景一字不差（真实例 note1/note2）",
            index = listOf(
                idx("note1", "回复末尾带简短总结"),
                idx("note2", "回复末尾带简短总结"),
            ).joinToString("\n"),
            dirs = listOf(dir("note1", SkillRules.SKILL_FILE), dir("note2", SkillRules.SKILL_FILE)),
            wantDangling = emptyList(), wantOrphans = emptyList(), wantDouble = listOf(listOf("note1", "note2")),
        ),
        Case(
            label = "全一致 ⇒ 干净（便签不许出现）",
            index = idx("甲", "场景一"),
            dirs = listOf(dir("甲", SkillRules.SKILL_FILE)),
            wantDangling = emptyList(), wantOrphans = emptyList(), wantDouble = emptyList(),
        ),
        Case(
            label = "去噪·软删不算悬空：目录只剩 SKILL.md.deleted-<ts> ⇒ 有意删的，不报",
            index = idx("gone1", "场景一"),
            dirs = listOf(dir("gone1", "SKILL.md.deleted-1792548545321")),
            wantDangling = emptyList(), wantOrphans = emptyList(), wantDouble = emptyList(),
        ),
        Case(
            label = "去噪·软删目录没索引行也不算失联：没有正文就没有失联",
            index = null,
            dirs = listOf(dir("gone1", "SKILL.md.deleted-1792548545321")),
            wantDangling = emptyList(), wantOrphans = emptyList(), wantDouble = emptyList(),
        ),
        Case(
            label = "去噪·近似名不吃两边：note1 不吃 note10 的正文（精确相等才对上）",
            index = idx("note1", "场景甲"),
            dirs = listOf(dir("note10", SkillRules.SKILL_FILE)),
            wantDangling = listOf("note1"), wantOrphans = listOf("note10"), wantDouble = emptyList(),
        ),
        Case(
            label = "去噪·同名不同场景不算双行：双行只看场景，不看名字",
            index = listOf(idx("甲", "场景一"), idx("甲", "场景二")).joinToString("\n"),
            dirs = listOf(dir("甲", SkillRules.SKILL_FILE)),
            wantDangling = emptyList(), wantOrphans = emptyList(), wantDouble = emptyList(),
        ),
        Case(
            label = "去噪·空目录（真实例 punct2）：没正文没行 ⇒ 既非失联也非悬空，静默",
            index = idx("甲", "场景一"),
            dirs = listOf(dir("punct2")),
            wantDangling = listOf("甲"), wantOrphans = emptyList(), wantDouble = emptyList(),
        ),
        Case(
            label = "去噪·软删压不过正文：目录同时有 SKILL.md 与 .deleted-<ts> ⇒ 正文在就不报",
            index = idx("restore1", "场景一"),
            dirs = listOf(dir("restore1", SkillRules.SKILL_FILE, "SKILL.md.deleted-1792548545321")),
            wantDangling = emptyList(), wantOrphans = emptyList(), wantDouble = emptyList(),
        ),
    )

    @Test
    fun `三类判据_正例与不该报的_表驱动`() {
        cases.forEach { c ->
            val r = reportOf(c.index, c.dirs)
            assertEquals("悬空不一致［${c.label}］", c.wantDangling, r.dangling)
            assertEquals("失联不一致［${c.label}］", c.wantOrphans, r.orphans)
            assertEquals("双行不一致［${c.label}］", c.wantDouble, doubleNames(r))
            assertEquals("干净与否［${c.label}］", c.wantDangling.isEmpty() && c.wantOrphans.isEmpty() && c.wantDouble.isEmpty(), r.isClean)
        }
    }

    @Test
    fun `悬空_同名两条悬空行只报一次_去重保序`() {
        val r = reportOf(
            listOf(idx("punct3", "场景一"), idx("punct3", "场景二")).joinToString("\n"),
            listOf(dir("punct3", "punct3.SKILL.md.bak-20261021-122604")),
        )
        assertEquals(listOf("punct3"), r.dangling)
    }

    // ── §2 双行判据：场景归一细节 ─────────────────────────────────────────

    @Test
    fun `归一_标点与空白不算差异_命中双行`() {
        val r = reportOf(
            listOf(
                idx("甲", "回复末尾，带简短总结！"),
                idx("乙", "回复末尾 带简短总结"),
            ).joinToString("\n"),
            emptyList(),
        )
        assertEquals(listOf(listOf("甲", "乙")), doubleNames(r))
    }

    @Test
    fun `归一_前20字相同即双行_第20字不同不算`() {
        val base20 = "拆章转编码导出书稿都要先看目录再动手做完" // 20 字整
        val r = reportOf(
            listOf(
                idx("甲", base20 + "甲"), // 与乙前 20 字相同
                idx("乙", base20 + "乙"),
                idx("丙", "拆章转编码导出书稿都要先看目录再动手做丙"), // 第 20 字不同
            ).joinToString("\n"),
            emptyList(),
        )
        assertEquals(listOf(listOf("甲", "乙")), doubleNames(r))
        assertEquals(base20, SkillIndexReconcile.normalizeScenario(base20 + "随便多长都截到二十"))
    }

    @Test
    fun `归一_全标点残行不判双行_空归一说不出同场景`() {
        val r = reportOf(
            listOf(idx("甲", "。"), idx("乙", "？？")).joinToString("\n"),
            emptyList(),
        )
        assertEquals(emptyList<List<String>>(), doubleNames(r))
    }

    @Test
    fun `负例_同名不同场景不许误报双行_按名字分组即红`() {
        // 派工单 §2 负例：双行判据只看场景归一；把判据换成「按名字分组」，这里必须红。
        val r = reportOf(
            listOf(idx("甲", "场景一"), idx("甲", "场景二")).joinToString("\n"),
            listOf(dir("甲", SkillRules.SKILL_FILE)),
        )
        assertEquals(emptyList<List<String>>(), doubleNames(r))
    }

    @Test
    fun `双行_同一组里同名两条_便签去重后只点一次名`() {
        val r = reportOf(
            listOf(idx("x", "同场景"), idx("x", "同场景")).joinToString("\n"),
            listOf(dir("x", SkillRules.SKILL_FILE)),
        )
        assertEquals(1, r.doubleRows.size)
        assertEquals("双行 1（x）", SkillIndexReconcile.noteLine(r)!!.substringAfter("不符：").substringBefore("——"))
    }

    // ── §3 收尾便签：文案与去重门 ─────────────────────────────────────────

    @Test
    fun `便签_全一致不出_零噪音`() {
        val r = reportOf(idx("甲", "场景一"), listOf(dir("甲", SkillRules.SKILL_FILE)))
        assertTrue(r.isClean)
        assertNull("全一致时便签必须不出现（负例：一致时也弹便签）", SkillIndexReconcile.noteLine(r))
    }

    @Test
    fun `便签_只报不为零的三类_一条一行`() {
        val onlyDangling = reportOf(
            idx("punct3", "场景"),
            listOf(dir("punct3", "punct3.SKILL.md.bak-20261021-122604")),
        )
        assertEquals(
            "技能账实不符：悬空 1（punct3）——去技能页看",
            SkillIndexReconcile.noteLine(onlyDangling),
        )
    }

    @Test
    fun `便签_每类点名最多3个_超出收等`() {
        val r = SkillIndexReconcile.Report(
            dangling = listOf("甲", "乙", "丙", "丁"),
            orphans = emptyList(),
            doubleRows = emptyList(),
        )
        assertEquals("技能账实不符：悬空 4（甲、乙、丙等）——去技能页看", SkillIndexReconcile.noteLine(r))
    }

    @Test
    fun `去重门_同会话同批只报一次_批变了再报_换会话重新开账`() {
        val dirtyA = reportOf(idx("punct3", "场景"), listOf(dir("punct3", "punct3.SKILL.md.bak-20261021-122604")))
        val dirtyB = reportOf(null, listOf(dir("haiku-helper", SkillRules.SKILL_FILE)))
        val gate = SkillIndexReconcile.NoteGate()

        // 负例「一致时也弹便签」的反面：一致 ⇒ null，且不消耗任何记账
        assertNull("一致时不报", gate.noteFor(1, reportOf(idx("甲", "场景"), listOf(dir("甲", SkillRules.SKILL_FILE)))))

        // 同会话：同批只报一次（负例「每轮重复报」）
        val first = gate.noteFor(1, dirtyA)
        assertEquals("技能账实不符：悬空 1（punct3）——去技能页看", first)
        assertNull("同会话同批第二次必须静默", gate.noteFor(1, dirtyA))
        assertNull("同批行序打乱仍是同一批（签名排序）", gate.noteFor(1, dirtyA))

        // 批变了 ⇒ 新的一批，再报一次
        val second = gate.noteFor(1, dirtyB)
        assertEquals("技能账实不符：失联 1（haiku-helper）——去技能页看", second)
        assertNull("新批第二次又静默", gate.noteFor(1, dirtyB))

        // 中间一度全一致不清账：同一批在本会话里再次出现仍然静默（最多报一次）
        assertNull(gate.noteFor(1, reportOf(idx("甲", "场景"), listOf(dir("甲", SkillRules.SKILL_FILE)))))
        assertNull("老批再次出现，本会话已报过", gate.noteFor(1, dirtyA))

        // 换会话 ⇒ 重新开账（新会话的人还没看过，本会话内依旧最多一次）
        assertEquals(first, gate.noteFor(2, dirtyA))
        assertNull(gate.noteFor(2, dirtyA))
    }

    @Test
    fun `去重门_签名只看账目内容_名字排序_行序无关`() {
        val a = SkillIndexReconcile.Report(
            dangling = listOf("乙", "甲"),
            orphans = listOf("丁"),
            doubleRows = listOf(SkillIndexReconcile.DoubleRow(listOf("n2", "n1"), "场景")),
        )
        val b = SkillIndexReconcile.Report(
            dangling = listOf("甲", "乙"),
            orphans = listOf("丁"),
            doubleRows = listOf(SkillIndexReconcile.DoubleRow(listOf("n1", "n2"), "场景")),
        )
        assertEquals("同一批不一致换了次序，签名必须相同", a.signature, b.signature)
        assertFalse(SkillIndexReconcile.Report().signature == a.signature)
    }

    // ── §4 162 附录（二）存量脏数据清单 · 等价假数据整跑 ────────────────────
    //
    // 附录（二）三条（脱敏/假造等价物；名字与形状照报告原文，正文场景为等价假造）：
    //   ① 索引第 1 行 punct3 悬空——目录只剩 punct3.SKILL.md.bak-20261021-122604；
    //   ② 技能/haiku-helper/SKILL.md 正文完整却没有索引行（_变更.jsonl L1–L6 全记 indexUpdated:true）；
    //   ③ note1/note2 两行场景一字不差（「回复末尾带简短总结」），note1 uses=0 死技能。
    // 同场噪音（附录点名不该报的）：punct2 空目录；write-skill/note3 账实相符。

    @Test
    fun `162存量等价数据_整跑一次_三类各命中一条_噪音静默`() {
        val indexText = """
            # 人设（项目层，等价假造）
            你是证道，回复保持简短。
            ${idx("punct3", "中文排版先调标点间距")}
            ${idx("note1", "回复末尾带简短总结")}
            ${idx("note2", "回复末尾带简短总结")}
            ${idx("write-skill", "需要沉淀新技能时按 write_skill 的步骤做")}
            ${idx("note3", "每天收尾补一行日记")}
        """.trimIndent()

        val dirs = listOf(
            dir("punct3", "punct3.SKILL.md.bak-20261021-122604"), // ① 只剩备份
            dir("punct2"),                                        // 同场噪音：空目录
            dir("note1", SkillRules.SKILL_FILE),
            dir("note2", SkillRules.SKILL_FILE),
            dir("note3", SkillRules.SKILL_FILE),
            dir("write-skill", SkillRules.SKILL_FILE),
            dir("haiku-helper", SkillRules.SKILL_FILE),           // ② 有正文没行
        )

        val r = SkillIndexReconcile.reconcile(indexText, dirs, MemoryScope.PROJECT)
        assertFalse("存量在 ⇒ 必须不是干净账", r.isClean)
        assertEquals("悬空：只有 punct3（punct2 空目录静默）", listOf("punct3"), r.dangling)
        assertEquals("失联：只有 haiku-helper", listOf("haiku-helper"), r.orphans)
        assertEquals("双行：只有 note1/note2 一组", listOf(listOf("note1", "note2")), doubleNames(r))
        assertEquals(
            "收尾便签一行（派工单 §1.2 原房型）",
            "技能账实不符：悬空 1（punct3）、失联 1（haiku-helper）、双行 1（note1/note2）——去技能页看",
            SkillIndexReconcile.noteLine(r),
        )
    }

    @Test
    fun `两层账本plus合成一份_便签一行带两层_跨层不并组`() {
        // 项目层＝162 存量等价物；全局层另有一份失联。合成后便签一行带两层。
        val project = SkillIndexReconcile.reconcile(
            listOf(
                idx("punct3", "中文排版先调标点间距"),
                idx("note1", "回复末尾带简短总结"),
                idx("note2", "回复末尾带简短总结"),
            ).joinToString("\n"),
            listOf(
                dir("punct3", "punct3.SKILL.md.bak-20261021-122604"),
                dir("note1", SkillRules.SKILL_FILE),
                dir("note2", SkillRules.SKILL_FILE),
                dir("haiku-helper", SkillRules.SKILL_FILE),
            ),
            MemoryScope.PROJECT,
        )
        val global = SkillIndexReconcile.reconcile(
            null,
            listOf(dir("整理书稿", SkillRules.SKILL_FILE)),
            MemoryScope.GLOBAL,
        )
        val merged = project + global
        assertEquals(listOf("punct3"), merged.dangling)
        assertEquals(listOf("haiku-helper", "整理书稿"), merged.orphans)
        assertEquals(listOf(listOf("note1", "note2")), doubleNames(merged))
        assertEquals(
            "技能账实不符：悬空 1（punct3）、失联 2（haiku-helper、整理书稿）、双行 1（note1/note2）——去技能页看",
            SkillIndexReconcile.noteLine(merged),
        )
        assertTrue("两层都干净 ⇒ 合成仍干净", (SkillIndexReconcile.Report() + SkillIndexReconcile.Report()).isClean)
        assertTrue("合成签名对双行组排序拼接", merged.signature.contains("double[note1+note2]"))
    }

    // ── §5 源码级红线（负例五「拿对账结果自动改索引」／负例六「删 .deleted-*」） ──
    //
    // 只报不改是字面的：本文件不许出现任何写口（人设/技能）与删档调用——
    // 房型照 SkillStoreTest S17 的源码级判据：字面不在，这条路就不存在。

    /** SkillIndexReconcile.kt 的源码文本（跑测试的工作目录可能是仓库根或 app/）。 */
    private fun reconcileSrc(): String = File("src/main/java/com/example/zhengdao/baopu/SkillIndexReconcile.kt")
        .let { if (it.exists()) it else File("app/src/main/java/com/example/zhengdao/baopu/SkillIndexReconcile.kt") }
        .readText()

    @Test
    fun `S204_只报不改_源码里没有写口与删档的调用`() {
        val src = reconcileSrc()
        val banned = listOf(
            "PersonaFiles",          // 不收人设层对象 ⇒ 拿不到 applyProposal 这条唯一写口
            "SkillStore.apply",      // 技能唯一写口不许从对账侧出现
            "applyProposal",         // 写人设的唯一口（字符串级兜底，防换写法）
            ".writeText(",           // 不许自己 fopen 写
            ".renameTo(",            // 软删是 rename——对账侧连改名都不许
            ".copyTo(",
            ".mkdirs(",
            "deleteRecursively",     // 负例六：删 .deleted-* 是硬删，字面不许存在
            ".delete(",
            "import java.io",        // 纯函数无 IO：连 File 类型都不该进这个文件
        )
        banned.forEach { token ->
            assertFalse("只报不改被破坏：源码出现「$token」", src.contains(token))
        }
        // 正锚点：解析与写侧同一套口径；软删口径与 SkillStore.archive 同名。
        assertTrue("索引行解析必须走 SkillRules（与写侧同一套）", src.contains("SkillRules.parseIndexLines("))
        assertTrue("软删口径要与 SkillStore.archive 对得上", src.contains("SKILL.md.deleted-"))
    }
}
