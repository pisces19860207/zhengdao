// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百五十三批 · 法典门禁 **L16 留痕可回滚**（派工单 253；判据为枚举全部 Spec＋208 落行核对）。
//
// ## 判据（可执行）
// ① **有副作用的工具**（`ToolSpec.readOnly == false`；缺省即写——保守口径）其执行必须走
//    GuardPipeline 这**唯一**执行口，且该口对每个 `!readOnly` 工具**落 208**（`DecisionLedger`）
//    并声明 `reversible`；
// ② **声明为可回滚的行必须给出回滚点**（`rollbackRef` 非空）；kind 天生不可回滚的行不要求；
// ③ 枚举**真注册表**的全部 Spec：副作用工具一个不漏地被 ①② 覆盖（单点保证）。
//
// ## 门禁形态（派工单 253）
// 反射/枚举遍历真注册表（BaopuToolCatalog + 运行时两条）＋ 源码扫描（执行单点、落行块、
// 全部 `DecisionDraft(` 构造点）。不给生产代码加反射出口、不改可见性——扫描只读源码文本。
//
// ## 负例（本文件自证会红）
// 负例①：假 agent 文件在管线外直调 `spec.executor(...)` ⇒ 执行单点扫描器抓到；
// 负例②：假管线源码没有「!readOnly ⇒ 落 208」的块 ⇒ 落行块探针抓到；
// 负例③：可回滚却不给回滚点 ⇒ 探针红（并用**真** `DecisionLedger.build` 造出 main 里
//         那条真违例行，证明探针逮的就是它——缺陷 #253-16a，见文件尾的「缺陷在账」）。
package com.example.zhengdao.baopu.constitution

import com.example.zhengdao.baopu.DecisionActor
import com.example.zhengdao.baopu.DecisionDraft
import com.example.zhengdao.baopu.DecisionKind
import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.PersonaFiles
import com.example.zhengdao.baopu.agent.AppendPersonaTool
import com.example.zhengdao.baopu.agent.BaopuToolCatalog
import com.example.zhengdao.baopu.agent.InMemoryToolPrefs
import com.example.zhengdao.baopu.agent.ToolOutcome
import com.example.zhengdao.baopu.agent.ToolRegistry
import com.example.zhengdao.baopu.agent.SkillTools
import com.example.zhengdao.baopu.agent.ToolSpec
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class L16ReversibleLedgerGateTest {

    private val tempRoots = mutableListOf<File>()

    @After
    fun cleanup() {
        tempRoots.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun newTempRoot(): File =
        java.nio.file.Files.createTempDirectory("l16-registry-").toFile().also { tempRoots += it }

    // ── 判据探针（只读）────────────────────────────────────────────────────

    internal object ExecutionLaw {
        /** 工具执行器的调用点：必须在单点白名单内。 */
        val APIS: Map<String, Regex> = mapOf("executor-call" to Regex("""\.executor\s*\("""))

        /** 执行单点白名单：唯一执行口是 GuardPipeline（第④段），批准路径共用同一段。 */
        val WHITELIST: Set<String> = setOf("agent/GuardPipeline.kt")

        /** 落行块探针：单点源码里必须有「副作用工具 ⇒ 落 208 并声明 reversible」的块。 */
        fun hasSideEffectLedgerBlock(strippedSrc: String): Boolean =
            strippedSrc.contains("if (!spec.readOnly)") &&
                strippedSrc.contains("DecisionLedger.record(") &&
                strippedSrc.contains("reversible")
    }

    internal object ReversibleCheck {
        /** 208 的一行：声明了可回滚就必须给回滚点（kind 天生不可回滚的行不要求）。 */
        fun violation(reversible: Boolean, rollbackRef: String, kindReversibleByDefault: Boolean = true): String? =
            when {
                reversible && rollbackRef.isBlank() && kindReversibleByDefault ->
                    "208 行声明可回滚（reversible=true）但没给回滚点（rollbackRef 空）——退不回去的账不许标可回滚"
                else -> null
            }
    }

    private val AGENT_ROOT = "src/main/java/com/example/zhengdao/baopu/agent"
    private val BAOPU_ROOT = "src/main/java/com/example/zhengdao/baopu"
    private val APP_ROOT = "src/main/java/com/example/zhengdao"

    private fun List<SourceHit>.relBaopu(): List<SourceHit> =
        map { it.copy(relPath = it.relPath.substringAfter("baopu/")) }

    // ── 门①：执行单点——副作用工具的执行只许发生在 GuardPipeline ─────────────

    @Test
    fun `门1_执行器调用点必须全在GuardPipeline_绕开守卫直调即红`() {
        val hits = GateIo.scanTree(AGENT_ROOT, ExecutionLaw.APIS).relBaopu()
        assertTrue(
            "扫描器只认出 ${hits.size} 处执行器调用——至少应有单点内的 2 处（串行+并行），扫描器瞎了",
            hits.size >= 2,
        )
        val offenders = hits.filter { it.relPath !in ExecutionLaw.WHITELIST }
        assertTrue(
            "发现 GuardPipeline 之外的执行器调用点（工具只有一条执行路径，这是 208 落行的前提）：\n" +
                offenders.joinToString("\n") { "  ${it.where()} ${it.text}" },
            offenders.isEmpty(),
        )
    }

    // ── 门②：单点必须给每个副作用工具落 208 并声明 reversible ────────────────

    @Test
    fun `门2_GuardPipeline对副作用工具落208并声明reversible_落行块不许拆`() {
        val probeSrc = GateIo.stripComments(
            GateIo.read("src/main/java/com/example/zhengdao/baopu/agent/GuardPipeline.kt"),
        )
        assertTrue(
            "GuardPipeline 里「!readOnly ⇒ DecisionLedger.record + reversible」的落行块不见了——副作用工具会变成无账动作",
            ExecutionLaw.hasSideEffectLedgerBlock(probeSrc),
        )
        assertFalse("探针对空源码必须不认（防恒绿）", ExecutionLaw.hasSideEffectLedgerBlock(""))
    }

    // ── 门③：枚举真注册表——副作用工具一个不漏、缺省按写处理 ──────────────────

    @Test
    fun `门3_真注册表枚举_副作用工具成规模且缺省readOnly为false_按写处理`() {
        val reg = newRegistry()
        val all = reg.all()
        assertTrue("真注册表应有 60+ 个工具，实到 ${all.size}——夹具坏了", all.size >= 60)
        val sideEffect = all.filter { !it.readOnly }
        assertTrue(
            "副作用工具应有相当规模（写/改/删/画/卡…），实到 ${sideEffect.size}——枚举瞎了",
            sideEffect.size >= 15,
        )
        assertTrue(
            "副作用工具名单不许有空名：${sideEffect.map { it.name }}",
            sideEffect.all { it.name.isNotBlank() },
        )
        // fail-closed：没声明 readOnly 的新工具必须按写处理（保守——宁可独占，不许混进读并行）。
        val undeclared = minimalToolSpec()
        assertFalse("ToolSpec.readOnly 缺省必须是 false（按写处理）", undeclared.readOnly)
        // 单点（门①）保证这些工具的执行全部经过落行块（门②）——副作用工具数与本断言共同构成覆盖。
    }

    private fun minimalToolSpec(): ToolSpec =
        ToolSpec(
            name = "l16-fake",
            description = "探针用最小工具",
            inputSchema = JSONObject().put("type", "object"),
        ) { ToolOutcome.Ok("x") }

    // ── 门④：全部 DecisionDraft( 构造点——可回滚的必须给回滚点 ────────────────

    @Test
    fun `门4_主源码全部DecisionDraft构造点_可回滚的行必须带rollbackRef或显式声明`() {
        val nonReversibleKinds = DecisionKind.entries.filter { !it.reversible }.map { it.name }.toSet()
        val knownDefects = mapOf(
            // 缺陷 #253-16a（记缺陷，不顺手改）：SITE_TRUST 的 kind 默认可回滚，但落行既没给
            // rollbackRef 也没显式 reversible——回滚通道实际存在（site_trust_add/remove 对偶，
            // 写在 evidence 字面里），账上却指不出回滚点。修复批次：补 rollbackRef ＋ 把本格移出名单。
            "baopu/BaopuBrowserWiring.kt" to "缺陷 #253-16a：recordSiteTrust 的 SITE_TRUST 落行缺回滚点（通道在 evidence 里）",
        )
        val offenders = mutableListOf<String>()
        var total = 0
        // 扫整个 app 源（baopu/**＋ui/**——决策中心界面也落 208，同为有副作用的留痕点）。
        GateIo.kotlinSources(APP_ROOT).forEach { (rel, src) ->
            draftBlocks(GateIo.stripComments(src)).forEach { block ->
                total++
                val kind = Regex("""DecisionKind\.(\w+)""").find(block)?.groupValues?.get(1)
                val declares = block.contains("reversible")
                val givesPoint = block.contains("rollbackRef")
                val kindSaysIrreversible = kind != null && kind in nonReversibleKinds
                val ok = declares || givesPoint || kindSaysIrreversible
                if (!ok) {
                    val where = "$rel"
                    val reason = knownDefects[where]
                    if (reason == null) {
                        offenders += "$where → kind=$kind 缺 reversible/rollbackRef（可回滚的账必须指得出回滚点）"
                    }
                }
            }
        }
        assertTrue(
            "扫描器共认出 $total 处 DecisionDraft 构造——少于 15 处说明扫描器瞎了（门禁假绿）",
            total >= 15,
        )
        assertTrue(
            "发现新的「可回滚却不给回滚点」落行点（缺陷清单之外不许新增）：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /**
     * 抽出源码里每个 `DecisionDraft(` 构造块的文本（括号配平；跳过 `class DecisionDraft(` 声明；
     * 注释须先经 [GateIo.stripComments]）。总数是给「反空转」用的。
     */
    private fun draftBlocks(src: String): List<String> {
        val blocks = mutableListOf<String>()
        var i = 0
        while (true) {
            val at = src.indexOf("DecisionDraft(", i)
            if (at < 0) break
            i = at + 1
            val before = src.substring(0, at).trimEnd().takeLast(12)
            if (before.endsWith("class")) continue // 声明不是构造
            var depth = 0
            var j = at + "DecisionDraft".length
            var started = false
            while (j < src.length) {
                val c = src[j]
                if (c == '(') {
                    depth++
                    started = true
                } else if (c == ')') {
                    depth--
                    if (started && depth == 0) break
                }
                j++
            }
            if (started && j < src.length) {
                blocks += src.substring(at, j + 1)
                i = j + 1
            }
        }
        return blocks
    }

    // ── 负例①：管线外直调执行器 ⇒ 执行单点扫描器红 ───────────────────────────

    @Test
    fun `负例1_假源码绕开管线直调executor_扫描器抓到`() {
        val bad = """
            package com.example.zhengdao.baopu.agent

            internal object RoguePath {
                fun run(spec: ToolSpec, args: JSONObject): ToolOutcome = spec.executor(args)
            }
        """.trimIndent()
        val hits = GateIo.scanLines("fake/RoguePath.kt", bad, ExecutionLaw.APIS).relBaopu()
        assertEquals(1, hits.size)
        assertTrue(
            "管线外的执行器调用必须红（它同时也就绕开了 208 落行）",
            hits[0].relPath !in ExecutionLaw.WHITELIST,
        )
        // 同一调用落在单点文件里就放行——红的是「单点外」，不是调用本身：
        val inPipeline = GateIo.scanLines("agent/GuardPipeline.kt", "val r = spec.executor(args)", ExecutionLaw.APIS)
        assertTrue(inPipeline.single().relPath in ExecutionLaw.WHITELIST)
    }

    // ── 负例②：没有落行块的假管线 ⇒ 探针红 ──────────────────────────────────

    @Test
    fun `负例2_假管线没有副作用落行块_探针抓到_真管线过`() {
        val fakePipeline = """
            internal class FakePipeline {
                private fun finish(spec: ToolSpec, text: String): String {
                    return DataFence.wrap(spec.name, text) // 只围栏，不落账
                }
            }
        """.trimIndent()
        assertFalse(
            "没有「!readOnly ⇒ 落 208」块的管线必须被探针抓住",
            ExecutionLaw.hasSideEffectLedgerBlock(GateIo.stripComments(fakePipeline)),
        )
        val real = GateIo.stripComments(
            GateIo.read("src/main/java/com/example/zhengdao/baopu/agent/GuardPipeline.kt"),
        )
        assertTrue("真管线必须过（防恒红）", ExecutionLaw.hasSideEffectLedgerBlock(real))
    }

    // ── 负例③：可回滚却不给回滚点 ⇒ 探针红；真码里那条已知违例行也逃不掉 ────────

    @Test
    fun `负例3_可回滚缺回滚点_探针红_真build造出的已知违例行也被逮`() {
        // 假实现（探针的靶）：
        assertNotNull(
            ReversibleCheck.violation(reversible = true, rollbackRef = ""),
        )
        assertNull("不可回滚的行不要求回滚点（防恒红）", ReversibleCheck.violation(reversible = false, rollbackRef = ""))
        assertNull("给了回滚点的行不红", ReversibleCheck.violation(reversible = true, rollbackRef = ".bak-1"))

        // 真实现（缺陷 #253-16a 在账）：main 的 build 对「kind 默认可回滚、没给 rollbackRef」
        // 的草稿照落不误——探针逮住的就是它。修复批次把 build 改严后，本用例随缺陷单一起改
        // （断言 build 拒绝），并把门④白名单里的那一格移出。
        val defective = DecisionLedger.build(
            DecisionDraft(
                kind = DecisionKind.SITE_TRUST, // kind.reversible == true
                actor = DecisionActor.USER,
                reason = "站点信任加白（探针复刻 main 的 recordSiteTrust 形状）",
                evidence = "site_trust_add:example.com",
                before = "",
                after = "",
                atMs = 1L,
            ),
        )
        assertNotNull("main 现状：这样的行落得进账（缺陷 #253-16a 在账）", defective)
        assertTrue(defective!!.reversible)
        assertTrue("rollbackRef 为空——正是探针判违规的形状", defective.rollbackRef.isBlank())
        assertNotNull(
            "探针必须逮得住真码造出的违例行",
            ReversibleCheck.violation(defective.reversible, defective.rollbackRef),
        )
    }

    // ── 真码行为：208 的只能降级＋宁可无账（L16 的两个既有护栏，钉住防回归）─────

    @Test
    fun `真码行为_reversible只能降级_缺证据整条不落`() {
        val base = DecisionDraft(
            kind = DecisionKind.DIARY_MAINTAIN, // kind.reversible == true
            actor = DecisionActor.AUTO,
            reason = "日记归档",
            evidence = "diary:archive",
            before = "3 条",
            after = "归档",
            atMs = 1L,
            rollbackRef = "archive/2026-10",
            reversible = true,
        )
        val kept = DecisionLedger.build(base)
        assertNotNull(kept)
        assertTrue(kept!!.reversible)
        assertEquals("archive/2026-10", kept.rollbackRef)

        // kind 说不可回滚的，谁传 true 都得被降级（只降级，不改写历史）：
        val downgraded = DecisionLedger.build(base.copy(kind = DecisionKind.READ_SCOPE))
        assertNotNull(downgraded)
        assertFalse("READ_SCOPE 天生不可回滚 ⇒ 传 true 也必须降级", downgraded!!.reversible)
        assertEquals("降级后回滚引用清空", "", downgraded.rollbackRef)

        // 缺证据 ⇒ 整条不落（宁可无账；这也是 L19 的账本层判据）：
        assertNull(DecisionLedger.build(base.copy(evidence = " ")))
        assertTrue(
            "缺证据的拒绝理由要能被人看见",
            DecisionLedger.lastError.orEmpty().contains("证据"),
        )
    }

    // ── 夹具：真注册表（形制照 ToolSchemaGateTest，58+ 条 ENTRIES ＋ 运行时 2 条）──

    private fun newRegistry(): ToolRegistry {
        val root = newTempRoot()
        val files = { PersonaFiles(File(root, "global"), root) }
        val reg = ToolRegistry(InMemoryToolPrefs())
        BaopuToolCatalog.loadInto(reg) { root }
        reg.register(SkillTools.spec(workspaceRoot = { root }, personaFiles = files))
        reg.register(AppendPersonaTool.spec(personaFiles = files))
        return reg
    }
}
