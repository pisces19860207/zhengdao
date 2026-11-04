// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.agent.JudgmentExperienceLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第四百三十六批 A ①——A 内核**前缀段「环境与权限」**的门禁测试。
 *
 * 判据①原话：「前缀段能在首轮提示里看到（贴一段真实首轮提示）」。这里做两件事：
 *
 * 1. **文字门禁**：那一节必须在、必须在最前、只能有一处、三句与那句硬规矩逐句在
 *    （缺一句就红——本节存在的理由是第三句「被拒要换做法」，丢了它整节白加）；
 * 2. **真的把首轮 system 拼出来**（[真实首轮system能拼出_并落证据文件]）：照 `RunEngine`
 *    那条链路（`PersonaRules.assemble` → `ContextAssembler.assemble` → `prefixText()`
 *    → `+ PROMPT_CONTRACT + CriteriaPitPack.systemSuffix()`）跑一遍，把结果写进
 *    `app/build/reports/436a-first-turn-system.txt` 当证据——本仓没有"导出提示词"的
 *    运行时设施，判据要的"真实首轮提示"只能这样离线渲染出来，路径写在报告里。
 *
 * 为什么不直接断言 `RunEngine` 的运行时行为：那要 LLM 通道与设备。段序、字节稳定这些
 * 判据在纯 JVM 里全能钉住，就把它们钉在 JVM 里（与 `ContextAssemblerTest` 同一口径）。
 */
class HarnessPrefixSectionTest {

    private val ws = "/storage/emulated/0/Download/证道"

    /** 前缀段的标题（判据认这个字串，别改字面量）。 */
    private val heading = "## 环境与权限"

    private fun persona(): String = BaopuPreset.personaText(ws)

    /** 前缀段本体：从标题起，到 A 内核原有那节的标题之前。 */
    private fun harnessSection(text: String): String {
        val from = text.indexOf(heading)
        val end = text.indexOf("# 证道「抱朴」运行环境说明", from)
        assertTrue("前缀段找不到收尾（A 内核原有那节不见了）", end > from)
        return text.substring(from, end)
    }

    // ── 判据①：位置与唯一性 ───────────────────────────────────────────

    @Test
    fun `前缀段排在A内核最前`() {
        val text = persona()
        // personaText 以换行开头（A 内核一直如此）⇒ 标题下标只许是 0 或 1，不许更靠后。
        assertTrue("前缀段必须是 A 内核的第一节（它是每次首轮的第一屏）：实际下标 ${text.indexOf(heading)}",
            text.indexOf(heading) in 0..1)
        assertEquals(
            "「$heading」只能有一处（多处＝拼接被改坏了，substringAfter 那类定位会读错）",
            1,
            Regex(Regex.escape(heading)).findAll(text).count(),
        )
    }

    // ── 判据①：三句逐句在 ─────────────────────────────────────────────

    @Test
    fun `固定三句逐句在`() {
        val section = harnessSection(persona())
        listOf("**你在哪**", "**你能干什么**", "**被拒了怎么办**").forEach {
            assertTrue("前缀段缺这一句：$it", section.contains(it))
        }
        // 第三句是本节存在的理由：被拒文案从前只在回灌里出现，撞一次才看见一次。
        assertTrue("第三句必须给出口：要写「换做法」", section.contains("换做法"))
        assertTrue("第三句必须禁令：不许照原样重试", section.contains("不许照原样重试"))
        assertTrue("第二句要说清工具是按需解锁的", section.contains("按需解锁"))
    }

    @Test
    fun `硬规矩一句_判据与坑账不是建议`() {
        // 派工单 ⑤ 的原文要求：「判据/坑账是硬规矩不是建议」——这句必须在**前缀段里**，
        // 而不是只写在 ③ 的注入块里（注入块是尾部，模型漏读尾部时前面这句还在）。
        val section = harnessSection(persona())
        assertTrue("缺「判据与坑账是硬规矩，不是建议」", section.contains("判据与坑账是硬规矩，不是建议"))
        assertTrue("要把话说死：是约束不是参考", section.contains("约束，不是参考"))
    }

    @Test
    fun `工作区路径进第一句`() {
        // 「你在哪」那句必须带**真的**落点路径（调用方注入），不许写成固定字串——
        // 用户 2026-10-30 的裁定是"工作区是默认落点不是权限边界"，路径不写出来这句就是空话。
        val section = harnessSection(persona())
        assertTrue("第一句要带工作区路径", section.contains(ws))
        assertTrue("要说明给绝对路径就能到全盘", section.contains("不是牢笼"))
        val other = harnessSection(BaopuPreset.personaText("/sdcard/别处"))
        assertTrue("换一个工作区，句子里的路径要跟着换（不是写死的）", other.contains("/sdcard/别处"))
    }

    // ── 本节不许吃掉旧纪律（212/145/59 批钉过的口径）──────────────────

    @Test
    fun `新增一节不许吃掉旧纪律`() {
        val text = persona()
        assertEquals(
            "「## 你会做什么」只能有一处（ProgressiveTools 用 substringAfter 定位）",
            1,
            Regex(Regex.escape("## 你会做什么")).findAll(text).count(),
        )
        assertTrue(
            "输出纪律一节仍要排在「## 纪律」之后（前缀段插在最前不许把这条顺序搅乱）",
            text.indexOf("## 纪律") < text.indexOf("## 输出纪律（答案先行）"),
        )
        assertTrue("A 内核原有那节仍在（前缀段是「加」，不是「换」）", text.contains("# 证道「抱朴」运行环境说明"))
        val tokens = TokenEstimator.estimate(text)
        assertTrue(
            "A 内核加了一节之后仍不超人设 token 上限，否则被 clip 截断＝纪律看不全（tokens=$tokens）",
            tokens <= PersonaRules.PERSONA_TOKEN_LIMIT,
        )
    }

    // ── 判据④：只加内容，不拦任何东西 ─────────────────────────────────

    @Test
    fun `零拦截_前缀段只说不拦`() {
        val code = File(sourceDir(), "BaopuPreset.kt").readText()
            .replace(Regex("(?s)/\\*.*?\\*/"), "")
            .replace(Regex("(?m)//.*$"), "")
        // A 内核本来就没有写路径（BaopuPresetGuardTest 钉死）；本批再加一条：
        // 前缀段不许顺手引入"拦截"语义的东西——守卫、闸门、拒绝结果一个都不许出现。
        listOf("GuardPipeline", "ConfirmFlow", "RiskGate", "Result.Rejected", "canWrite(").forEach {
            assertFalse("A 内核源码引用了守卫/闸门（本批判据④：零拦截）：$it", code.contains(it))
        }
    }

    // ── 判据①的正主：真把首轮 system 拼出来 ───────────────────────────

    @Test
    fun `真实首轮system能拼出_并落证据文件`() {
        // 证据要可复现：先把坑账与注入缓存清成"本机一行还没落"的干净态（resetForTest 只清内存，
        // 不动盘上那本账），否则这段会随同 JVM 里别的用例留下的行漂移。
        JudgmentExperienceLedger.resetForTest()
        CriteriaPitPack.resetForTest()
        // 同 RunEngine.kt:1909 那一跳：A 内核 ＋ B/C 层（首轮没有包 ⇒ null）
        val layers = PersonaRules.assemble(BaopuPreset.personaText(ws), null, null)
        val facts = HardFacts(workspacePath = ws, model = "gemini-2.5-flash")
        val prompt = ContextAssembler.assemble(
            persona = layers,
            facts = facts,
            stickyMemories = emptyList(),
            newMemories = emptyList(),
            history = emptyList(),
            current = "帮我把 Download 里的 PDF 归到资料库",
        )
        val prefix = prompt.prefixText()
        // 同 RunEngine.kt 里 systemFinal 那条表达式（编排关 ⇒ systemText 就是 prefix 本身）
        val systemFinal = prefix + "\n\n" + MediatorWiring.PROMPT_CONTRACT + CriteriaPitPack.systemSuffix()

        assertTrue("首轮 system 里看不到前缀段", systemFinal.contains(heading))
        assertTrue("首轮 system 里看不到运行时包版本行", systemFinal.contains("## 规矩与坑账（运行时包 v"))
        assertTrue("运行时包要在 258 那句契约之后（尾部追加，前面字节不动）",
            systemFinal.indexOf(MediatorWiring.PROMPT_CONTRACT) < systemFinal.indexOf("## 规矩与坑账"))
        assertTrue(
            "前缀段必须排在整段的最前面（实际下标 ${systemFinal.indexOf(heading)}）",
            systemFinal.indexOf(heading) in 0..1,
        )
        // 尾巴追加 ⇒ 前缀逐字节不动（258/34 批同一条缓存判据）
        assertTrue("追加之后原前缀仍是开头", systemFinal.startsWith(prefix))

        val out = evidenceFile()
        out.parentFile?.mkdirs()
        out.writeText(
            buildString {
                append("第四百三十六批 A · 判据①「真实首轮提示」证据（JVM 单测离线渲染）\n")
                append("渲染链路：PersonaRules.assemble → ContextAssembler.assemble → prefixText → systemFinal\n")
                append("测试值（非真机）：model=gemini-2.5-flash、编排关（systemText==prefix）、\n")
                append("            首轮无 B/C 层人设、无记忆召回、坑账清成 0 行（真机上这里是 抱朴记忆/判决经验账.jsonl 的行数）\n")
                append("生产真值：A 内核文案（含本次新加的前缀段）、workspacePath、判据三条（ImmutableCriteria.ALL）\n\n")
                append("──── ROLE_SYSTEM（首轮）开始 ────\n")
                append(systemFinal)
                append("──── ROLE_SYSTEM 结束 ────\n")
            },
            Charsets.UTF_8,
        )
        assertTrue("证据文件没落盘：${out.path}", out.isFile && out.length() > 0)
        assertTrue(
            "证据里前缀段与运行时包都要在",
            out.readText(Charsets.UTF_8).contains(heading) &&
                out.readText(Charsets.UTF_8).contains("运行时包 v${CriteriaPitPack.VERSION}"),
        )
    }

    @Test
    fun `同版本两轮_首轮system不增长`() {
        // 判据②「同版本连续两轮 ⇒ 第二轮不再重复增长」在**整段 system** 上的口径：
        // 前缀段 ＋ 契约句 ＋ 运行时包三截拼出来的串，第二轮必须逐字节相同。
        CriteriaPitPack.resetForTest()
        val first = renderFirstTurnSystem()
        val second = renderFirstTurnSystem()
        assertEquals("第二轮 system 长了字节＝每轮重复烧 token", first, second)
    }

    /** 与上面那个用例同一条链路，抽出来复用（第二轮用的就是它）。 */
    private fun renderFirstTurnSystem(): String {
        val prompt = ContextAssembler.assemble(
            persona = PersonaRules.assemble(BaopuPreset.personaText(ws), null, null),
            facts = HardFacts(workspacePath = ws, model = "gemini-2.5-flash"),
            stickyMemories = emptyList(),
            newMemories = emptyList(),
            history = emptyList(),
            current = "帮我把 Download 里的 PDF 归到资料库",
        )
        val prefix = prompt.prefixText()
        return prefix + "\n\n" + MediatorWiring.PROMPT_CONTRACT + CriteriaPitPack.systemSuffix()
    }

    // ── 负例自证（旧形态 ⇒ 本门禁必然红）──────────────────────────────

    /** 旧形态：A 内核没有前缀段（第四百三十六批之前的样子）。 */
    private val oldNoHarness = "# 证道「抱朴」运行环境说明（每次对话开始前必读）\n\n## 你在哪\n…"

    /** 旧形态：三句里丢了最要紧的第三句。 */
    private val oldMissingThird = "## 环境与权限（固定三句，先看这个）\n1. **你在哪**\n2. **你能干什么**\n\n"

    @Test
    fun `负例_没有前缀段必须红`() {
        assertEquals(-1, oldNoHarness.indexOf(heading))
    }

    @Test
    fun `负例_丢第三句必须红`() {
        assertFalse("负例自证：三句断言真的会抓缺句", oldMissingThird.contains("**被拒了怎么办**"))
    }

    // ── 路径工具（照本仓 repoFile 口径：模块目录或仓根都能跑）──────────

    private fun sourceDir(): File {
        val roots = listOf(
            File("src/main/java/com/example/zhengdao/baopu"),
            File("app/src/main/java/com/example/zhengdao/baopu"),
        )
        return roots.firstOrNull { it.isDirectory }
            ?: error("找不到 baopu 包目录（cwd=${File("").absolutePath}）")
    }

    private fun evidenceFile(): File {
        val rel = "build/reports/436a-first-turn-system.txt"
        return if (File("src/main/java").isDirectory) File(rel) else File("app/$rel")
    }
}
