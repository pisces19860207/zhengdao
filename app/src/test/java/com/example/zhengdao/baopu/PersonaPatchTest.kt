// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单 2026-10-20 第九十六批 §1/§2/§5（98 总单子项 B）。
// 这一份钉的是**人设补丁本体**（纯 JVM）：typed patch 的白名单/长度、只许追加、
// 作用域（C 不跨区、B 必须全局同意）、拒绝不写、导入整批拒、重置先备份、
// 审计行格式，以及 §1 的 **A 边界源码门禁**（A 内核没有写路径）。
package com.example.zhengdao.baopu

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PersonaPatchTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun files(): PersonaFiles = PersonaFiles(tmp.newFolder(), tmp.newFolder())

    private fun wsOf(f: PersonaFiles): String = PersonaPatchApply.workspaceIdOf(f)!!

    private fun cPatch(ws: String?, text: String = "- 一条规矩"): PersonaPatch = PersonaPatch(
        scope = PersonaPatchScope.C_PROJECT,
        workspaceId = ws,
        sections = listOf(PersonaSection(PersonaSectionKind.RULES, text)),
        reason = "用户说过",
    )

    private fun bPatch(): PersonaPatch = PersonaPatch(
        scope = PersonaPatchScope.B_GLOBAL,
        workspaceId = null,
        sections = listOf(PersonaSection(PersonaSectionKind.STYLE, "- 全局风格")),
        reason = "跨工作区的习惯",
    )

    // ── 1. 校验：结构/长度/作用域必填 ─────────────────────────────────────

    @Test
    fun `V1_空条款与空正文被判非法`() {
        val f = files()
        assertNotNull(PersonaPatchRules.validate(cPatch(wsOf(f)).copy(sections = emptyList())))
        assertNotNull(
            PersonaPatchRules.validate(cPatch(wsOf(f)).copy(sections = listOf(PersonaSection(PersonaSectionKind.RULES, "  ")))),
        )
        assertNotNull(PersonaPatchRules.validate(cPatch(wsOf(f)).copy(reason = " ")))
        assertNull("合法的要过", PersonaPatchRules.validate(cPatch(wsOf(f))))
    }

    @Test
    fun `V2_同类重复与超长被判非法`() {
        val f = files()
        val twice = cPatch(wsOf(f)).copy(
            sections = listOf(
                PersonaSection(PersonaSectionKind.RULES, "- a"),
                PersonaSection(PersonaSectionKind.RULES, "- b"),
            ),
        )
        assertNotNull("同一类别只能有一条", PersonaPatchRules.validate(twice))
        val long = cPatch(wsOf(f), "x".repeat(PersonaPatchRules.MAX_SECTION_CHARS + 1))
        assertNotNull(PersonaPatchRules.validate(long))
        val many = cPatch(wsOf(f)).copy(
            sections = PersonaSectionKind.values().map { PersonaSection(it, "- 一条") } +
                PersonaSection(PersonaSectionKind.RULES, "- 又来一条"),
        )
        assertNotNull("条款数超限", PersonaPatchRules.validate(many))
    }

    @Test
    fun `V3_作用域必填项_C缺工作区_B不许带工作区`() {
        val f = files()
        assertNotNull("C 必须带 workspaceId", PersonaPatchRules.validate(cPatch(null)))
        assertNotNull("B 不该带 workspaceId", PersonaPatchRules.validate(bPatch().copy(workspaceId = wsOf(f))))
        assertNull(PersonaPatchRules.validate(bPatch()))
    }

    @Test
    fun `V4_渲染是确定性的_只往末尾追加`() {
        val sections = listOf(
            PersonaSection(PersonaSectionKind.STYLE, "  - 先给结论 "),
            PersonaSection(PersonaSectionKind.RULES, "- 不许删既有条款"),
        )
        val a = PersonaPatchRules.render("", sections)
        val b = PersonaPatchRules.render("", sections)
        assertEquals("同输入两次渲染必须逐字节相同", a, b)
        assertTrue(a.startsWith("## 风格"))
        val next = PersonaPatchRules.render("- 原条款", sections)
        assertTrue("原有正文必须原样留在开头", next.startsWith("- 原条款"))
        assertTrue("必须满足只许追加", PersonaRules.appendOnly("- 原条款", next))
    }

    // ── 2. 作用域：C 不跨区、B 必须全局同意 ───────────────────────────────

    @Test
    fun `S1_本工作区同意写C_写前备份_只追加`() {
        val f = files()
        f.applyProposal(PersonaRules.propose(PersonaLayer.C_PROJECT, "", "- 原条款", "初始")!!)
        val ws = wsOf(f)
        val r = PersonaPatchApply.apply(cPatch(ws, "- 新条款"), PersonaConsent.WORKSPACE, ws, f)
        assertTrue("应成功：$r", r is PersonaApplyResult.Applied)
        val text = f.projectFile().readText(Charsets.UTF_8)
        assertTrue(text.startsWith("- 原条款"))
        assertTrue(text.contains("- 新条款"))
        val backups = PersonaBackup.backupsOf(f.projectFile())
        assertTrue("写人设要自动备份", backups.isNotEmpty())
        assertFalse("B 层不许被顺带写", f.globalFile().exists())
    }

    @Test
    fun `S2_C补丁选全局被拒_不许顺手升全局`() {
        val f = files()
        val ws = wsOf(f)
        val r = PersonaPatchApply.apply(cPatch(ws), PersonaConsent.GLOBAL, ws, f)
        assertTrue(r is PersonaApplyResult.Rejected)
        assertTrue("要说明白：${(r as PersonaApplyResult.Rejected).reason}", r.reason.contains("只有 B 层"))
        assertFalse(f.projectFile().exists())
        assertFalse(f.globalFile().exists())
    }

    @Test
    fun `S3_B补丁选本工作区被拒_B必须全局同意`() {
        val f = files()
        val ws = wsOf(f)
        val r = PersonaPatchApply.apply(bPatch(), PersonaConsent.WORKSPACE, ws, f)
        assertTrue(r is PersonaApplyResult.Rejected)
        assertTrue((r as PersonaApplyResult.Rejected).reason.contains("必须明确选「全局」"))
        assertFalse("拒绝时一个字节都不许写", f.globalFile().exists())
    }

    @Test
    fun `S4_B补丁选全局写B层_C层不动`() {
        val f = files()
        val ws = wsOf(f)
        val r = PersonaPatchApply.apply(bPatch(), PersonaConsent.GLOBAL, ws, f)
        assertTrue("应成功：$r", r is PersonaApplyResult.Applied)
        assertEquals(PersonaLayer.B_GLOBAL, (r as PersonaApplyResult.Applied).layer)
        assertTrue(f.globalFile().readText(Charsets.UTF_8).contains("- 全局风格"))
        assertFalse("C 层不许被顺带写", f.projectFile().exists())
    }

    @Test
    fun `S5_C补丁跨工作区被拒`() {
        val f = files()
        val other = files()
        val r = PersonaPatchApply.apply(cPatch(wsOf(other)), PersonaConsent.WORKSPACE, wsOf(f), f)
        assertTrue(r is PersonaApplyResult.Rejected)
        assertTrue((r as PersonaApplyResult.Rejected).reason.contains("别的工作区"))
        assertFalse(f.projectFile().exists())
    }

    @Test
    fun `S6_拒绝不写_且审计有一行`() {
        val f = files()
        val lines = mutableListOf<String>()
        val old = PersonaAudit.sink
        PersonaAudit.sink = { lines += it }
        try {
            val r = PersonaPatchApply.apply(cPatch(wsOf(f)), PersonaConsent.DECLINED, wsOf(f), f)
            assertTrue(r is PersonaApplyResult.Rejected)
            assertFalse(f.projectFile().exists())
            assertTrue("拒绝也要留痕：$lines", lines.any { it.startsWith("[persona]") && it.contains("用户没有同意") })
        } finally {
            PersonaAudit.sink = old
        }
    }

    // ── 3. 解析：typed patch 才收，角色卡整卡/自由文本一律拒 ────────────────

    private fun patchJson(scope: String = "c", ws: String? = null): String {
        val o = JSONObject()
            .put("scope", scope)
            .put("reason", "理由")
            .put(
                "sections",
                JSONArray().put(
                    JSONObject().put("kind", "rules").put("text", "- 一条规矩"),
                ),
            )
        if (ws != null) o.put("workspaceId", ws)
        return JSONArray().put(o).toString()
    }

    @Test
    fun `P1_合法补丁解析成功`() {
        // C 层补丁必须带 workspaceId（校验会拦），这里按真形状喂
        val r = PersonaPatchRules.parsePatches(patchJson(ws = "ws-1"))
        assertTrue("应解析成功：$r", r is PersonaPatchParse.Ok)
        val p = (r as PersonaPatchParse.Ok).patches.single()
        assertEquals(PersonaPatchScope.C_PROJECT, p.scope)
        assertEquals(PersonaSectionKind.RULES, p.sections.single().kind)
        assertEquals("ws-1", p.workspaceId)
    }

    @Test
    fun `P2_角色卡整卡被整批拒`() {
        // SillyTavern 角色卡的典型字段——一个都不在白名单里
        val card = JSONArray().put(
            JSONObject()
                .put("name", "小抱")
                .put("description", "一个角色")
                .put("personality", "温和")
                .put("first_mes", "你好"),
        ).toString()
        val r = PersonaPatchRules.parsePatches(card)
        assertTrue("角色卡必须被拒：$r", r is PersonaPatchParse.Rejected)
        val why = (r as PersonaPatchParse.Rejected).reason
        assertTrue("要说清是未知字段：$why", why.contains("未知字段"))
        assertTrue("要写明白不收角色卡：$why", why.contains("角色卡"))
    }

    @Test
    fun `P3_自由文本与未知类别被拒`() {
        assertTrue(PersonaPatchRules.parsePatches("就是一段自由文本，不是数组") is PersonaPatchParse.Rejected)
        val badKind = JSONArray().put(
            JSONObject().put("scope", "c").put("reason", "r").put("workspaceId", "w")
                .put("sections", JSONArray().put(JSONObject().put("kind", "prompt").put("text", "x"))),
        ).toString()
        assertTrue(PersonaPatchRules.parsePatches(badKind) is PersonaPatchParse.Rejected)
        // scope 只许 b/c（写 a 等于想给 A 内核打补丁）
        assertTrue(PersonaPatchRules.parsePatches(patchJson(scope = "a")) is PersonaPatchParse.Rejected)
    }

    // ── 4. 候选桥：只收 PERSONA_APPEND ────────────────────────────────────

    @Test
    fun `C1_候选桥只接人设追加_技能与工具规则进不来`() {
        val base = ProposalCandidate(
            id = "abc123",
            kind = ProposalKind.PERSONA_APPEND,
            target = "人设",
            payload = "- 用户常说先给结论",
            createdAt = 1L,
        )
        val patch = PersonaPatchRules.fromCandidate(base, PersonaPatchScope.C_PROJECT, "ws-1")
        assertNotNull("人设追加候选要能过桥", patch)
        assertEquals("abc123", patch!!.candidateId)
        assertEquals(PersonaSectionKind.RULES, patch.sections.single().kind)
        assertNull(
            "技能正文候选不许从这条路进人设",
            PersonaPatchRules.fromCandidate(base.copy(kind = ProposalKind.SKILL_BODY), PersonaPatchScope.C_PROJECT, "ws-1"),
        )
        assertNull(
            "工具规则候选不许从这条路进人设",
            PersonaPatchRules.fromCandidate(base.copy(kind = ProposalKind.TOOL_RULE), PersonaPatchScope.C_PROJECT, "ws-1"),
        )
    }

    // ── 5. 导入/导出/重置 ─────────────────────────────────────────────────

    @Test
    fun `I1_导入typed patch成功_跨区与角色卡整批拒且不写`() {
        val f = files()
        val ws = wsOf(f)
        val ok = PersonaPatchApply.importTyped(f, PersonaLayer.C_PROJECT, patchJson(ws = ws), ws)
        assertTrue("应导入成功：$ok", ok is PersonaImportResult.Imported)
        assertTrue(f.projectFile().readText(Charsets.UTF_8).contains("- 一条规矩"))

        val other = files()
        val cross = PersonaPatchApply.importTyped(other, PersonaLayer.C_PROJECT, patchJson(ws = ws), wsOf(other))
        assertTrue("跨工作区要拒", cross is PersonaImportResult.Rejected)
        assertFalse("拒了就不许写", other.projectFile().exists())

        val card = JSONArray().put(JSONObject().put("name", "小抱").put("description", "角色")).toString()
        val fresh = files()
        val r = PersonaPatchApply.importTyped(fresh, PersonaLayer.C_PROJECT, card, wsOf(fresh))
        assertTrue("角色卡必须拒", r is PersonaImportResult.Rejected)
        assertFalse("角色卡一个字节都不许入库", fresh.projectFile().exists())
    }

    @Test
    fun `I2_导入到B层要求补丁作用域也是B`() {
        val f = files()
        val r = PersonaPatchApply.importTyped(f, PersonaLayer.B_GLOBAL, patchJson(scope = "c", ws = wsOf(f)), wsOf(f))
        assertTrue(r is PersonaImportResult.Rejected)
        assertFalse(f.globalFile().exists())
    }

    @Test
    fun `E1_导出有值_A层没有`() {
        val f = files()
        f.applyProposal(PersonaRules.propose(PersonaLayer.C_PROJECT, "", "- 原条款", "初始")!!)
        assertEquals("- 原条款", PersonaPatchApply.export(f, PersonaLayer.C_PROJECT))
        assertNull("A 内核没有文件、不许导出", PersonaPatchApply.export(f, PersonaLayer.A_KERNEL))
    }

    @Test
    fun `R1_重置先备份再清空_已空则拒`() {
        val f = files()
        f.applyProposal(PersonaRules.propose(PersonaLayer.C_PROJECT, "", "- 原条款", "初始")!!)
        val r = PersonaPatchApply.reset(f, PersonaLayer.C_PROJECT)
        assertTrue("应成功：$r", r is PersonaApplyResult.Applied)
        assertEquals("", f.projectFile().readText(Charsets.UTF_8))
        assertTrue("重置前必须备份（可回滚）", PersonaBackup.backupsOf(f.projectFile()).isNotEmpty())
        // 再重置一次：已经空了 ⇒ 拒（不造无意义的备份）
        assertTrue(PersonaPatchApply.reset(f, PersonaLayer.C_PROJECT) is PersonaApplyResult.Rejected)
        // A 层不可重置
        assertTrue(PersonaPatchApply.reset(f, PersonaLayer.A_KERNEL) is PersonaApplyResult.Rejected)
    }

    // ── 6. 审计 ───────────────────────────────────────────────────────────

    @Test
    fun `A1_应用与重置各有一行_group式锚`() {
        val f = files()
        val lines = mutableListOf<String>()
        val old = PersonaAudit.sink
        PersonaAudit.sink = { lines += it }
        try {
            PersonaPatchApply.apply(cPatch(wsOf(f), "- 新条款"), PersonaConsent.WORKSPACE, wsOf(f), f)
            PersonaPatchApply.apply(bPatch(), PersonaConsent.GLOBAL, wsOf(f), f)
            PersonaPatchApply.reset(f, PersonaLayer.C_PROJECT)
            assertTrue(lines.all { it.startsWith("[persona]") })
            assertTrue("工作区写入要带来源与条款类别：$lines", lines[0].contains("C_PROJECT（工作区）来源=卡片 条款=rules"))
            assertTrue("全局写入要标 B：$lines", lines[1].contains("B_GLOBAL（全局）"))
            assertTrue("重置要留痕：$lines", lines[2].contains("重置 C_PROJECT"))
        } finally {
            PersonaAudit.sink = old
        }
    }

    // ── 7. A 边界（§1 源码门禁） ───────────────────────────────────────────

    @Test
    fun `X1_补丁作用域只有B与C_A内核写不进来`() {
        assertEquals(
            "PersonaPatchScope 只许 b/c 两档（A 连枚举值都没有）",
            listOf("b", "c"),
            PersonaPatchScope.values().map { it.key },
        )
        // 刀 0b 的只读判据原样成立（本批一个字没动）
        assertFalse(PersonaRules.canWrite(PersonaLayer.A_KERNEL))
        assertNull(PersonaRules.propose(PersonaLayer.A_KERNEL, "", "改内核", "试试"))
        assertTrue("B/C 仍可写", PersonaRules.canWrite(PersonaLayer.B_GLOBAL) && PersonaRules.canWrite(PersonaLayer.C_PROJECT))
    }

    @Test
    fun `X2_A文件里没有任何写人设的调用`() {
        // A 内核五件：提示词、内核边界、运行规则、守卫管线、工具注册表。
        // 它们只许**读** A 的字符串，不许出现「写人设/落盘」这一类调用。
        val aFiles = listOf(
            "baopu/BaopuPreset.kt",
            "baopu/kernel/BaopuKernel.kt",
            "baopu/kernel/KernelAudit.kt",
            "baopu/agent/RunRules.kt",
            "baopu/agent/GuardPipeline.kt",
        )
        val offenders = aFiles.filter { rel ->
            val src = File("src/main/java/com/example/zhengdao/$rel").readText(Charsets.UTF_8)
            Regex("applyProposal\\(|PersonaPatchApply\\.|writeText\\(").containsMatchIn(src)
        }
        assertEquals("A 内核文件里不许有写人设的调用：$offenders", emptyList<String>(), offenders)
    }

    @Test
    fun `X3_人设文件层拒绝A_并且没有A的写路径`() {
        val f = files()
        // fileOf(A) 直接抛（A 没有文件）
        var threw = false
        try {
            f.fileOf(PersonaLayer.A_KERNEL)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("A 层没有文件，fileOf 必须抛", threw)
        // 补丁代码里不许出现「A_KERNEL + 写动作」同一处（本文件读守卫可以有）
        listOf("baopu/PersonaPatch.kt", "baopu/PersonaPatchApply.kt").forEach { rel ->
            val src = File("src/main/java/com/example/zhengdao/$rel").readText(Charsets.UTF_8)
            val bad = src.lines().filter { it.contains("A_KERNEL") && Regex("writeText|applyProposal|fileOf\\(").containsMatchIn(it) }
            assertTrue("$rel 里出现 A 写路径：$bad", bad.isEmpty())
        }
    }
}
