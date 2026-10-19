// 第五十批 · 提案自检与回滚的门禁（JVM：假 eval + 临时目录，不联网、不烧额度）。
//
// 派工单 §4 六条用例逐一对应：
//   1. 10/10 ⇒ 保留并报通过          → `全过_文件保留并报通过`
//   2. 9/10  ⇒ 回到落盘前、报已回滚   → `九过_回滚到落盘前sha` / `九过_首写的文件等于删掉`
//   3. 红线命中 ⇒ 先回滚再报红线      → `红线命中_先回滚再报红线`
//   4. 无通道 ⇒ 保留、报未跑、不回滚  → `无通道_保留且报未跑_不回滚`
//   5. 回滚失败 ⇒ 明说「回滚失败」    → `回滚原语失败_报回滚失败并给路径`
//   6. 单飞 ⇒ 第二次不并行、不互滚    → `单飞_第二次直接Busy`
package com.example.zhengdao.baopu.eval

import com.example.zhengdao.baopu.PersonaBackup
import com.example.zhengdao.baopu.SkillStore
import com.example.zhengdao.baopu.SkillWriteResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ProposalSelfCheckTest {

    // ── 小工具 ───────────────────────────────────────────────────────────

    private fun case(id: String, ok: Boolean) = CaseResult(
        id = id,
        note = "",
        ok = ok,
        steps = 1,
        failures = if (ok) emptyList() else listOf("$id 挂了"),
        finalText = "",
        toolCalls = emptyList(),
        tokensIn = 1_000,
        tokensOut = 100,
        error = null,
        dir = null,
    )

    private fun runOf(pass: Int, total: Int) =
        EvalRun(results = (1..total).map { case("c$it", it <= pass) }, elapsedMs = 1_234)

    private fun tempDir(tag: String): File = kotlin.io.path.createTempDirectory("zd-sc-$tag-").toFile()

    private fun wrote(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text, Charsets.UTF_8)
    }

    // ── ① 全过 ⇒ 保留 ──────────────────────────────────────────────────

    @Test
    fun `全过_文件保留并报通过`() {
        val dir = tempDir("pass")
        try {
            val persona = File(dir, "人设.md")
            wrote(persona, "旧人设")
            val target = TargetSnapshot.of(persona, TargetKind.PERSONA)

            val check = ProposalSelfCheck(runEval = { EvalAttempt.Ran(runOf(10, 10)) }, log = {})
            val out = check.execute("采纳", listOf(target))

            assertTrue("全过必须是 Passed，实际 $out", out is SelfCheckOutcome.Passed)
            out as SelfCheckOutcome.Passed
            assertEquals(10, out.passed)
            assertEquals(10, out.total)
            assertEquals("旧人设", persona.readText())
            val text = SelfCheckText.of(out)!!
            assertTrue("通过文案要报 10/10：$text", text.contains("10/10"))
            assertTrue("通过文案要报真实耗时：$text", text.contains("1s"))
            assertTrue("通过文案要报估算花费：$text", text.contains("¥"))
        } finally {
            dir.deleteRecursively()
        }
    }

    // ── ② 9/10 ⇒ 回滚到落盘前 ─────────────────────────────────────────

    @Test
    fun `九过_回滚到落盘前sha_索引回到旧版`() {
        val dir = tempDir("roll")
        try {
            val persona = File(dir, "人设.md")
            wrote(persona, "旧人设（没有新增索引行）")
            val beforeSha = Sha256.of(persona)
            val target = TargetSnapshot.of(persona, TargetKind.PERSONA)
            // 生产的备份由 PersonaFiles.applyProposal 落盘前自动造；这里绕过它，手工补一份
            PersonaBackup.backupBefore(persona, "20260101-000000")

            // 提案真的落了盘：索引行加进去了
            wrote(persona, "旧人设\n- 新技能索引行（提案带进来的）")

            val check = ProposalSelfCheck(runEval = { EvalAttempt.Ran(runOf(9, 10)) }, log = {})
            val out = check.execute("采纳", listOf(target))

            assertTrue("9/10 必须是已回滚，实际 $out", out is SelfCheckOutcome.FailedRolledBack)
            assertEquals(
                "回滚后必须与落盘前逐字节一致",
                beforeSha,
                Sha256.of(persona),
            )
            assertFalse("索引行必须没了（回滚生效）", persona.readText().contains("新技能索引行"))
            val text = SelfCheckText.of(out)!!
            assertTrue("回滚文案要说 sha256 一致：$text", text.contains("sha256"))
            assertTrue("回滚文案要说 9/10：$text", text.contains("9/10"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `九过_首写的文件等于删掉_不是拿更老的备份盖上去`() {
        val dir = tempDir("first")
        try {
            val persona = File(dir, "人设.md")
            // 落盘前**不存在**（首次写入，没有备份可恢复）
            val target = TargetSnapshot.of(persona, TargetKind.PERSONA)
            assertFalse("快照应记下'落盘前不存在'", target.existed)

            wrote(persona, "提案第一次写出来的人设")

            val check = ProposalSelfCheck(runEval = { EvalAttempt.Ran(runOf(8, 10)) }, log = {})
            val out = check.execute("采纳", listOf(target))

            assertTrue(out is SelfCheckOutcome.FailedRolledBack)
            assertFalse("首写文件回滚＝回到'没有'，文件必须消失", persona.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `九过_技能正文走SkillStore备份回滚`() {
        val dir = tempDir("skill")
        try {
            val skillDir = File(dir, "技能/写技能")
            skillDir.mkdirs()
            val body = File(skillDir, "SKILL.md")
            wrote(body, "旧正文")
            // SkillStore.apply 落盘前会造的那份备份（同名 ⇒ 先复制，绝不覆盖）
            wrote(File(skillDir, "写技能.SKILL.md.bak-20260101-000000"), "旧正文")
            val target = TargetSnapshot.of(body, TargetKind.SKILL)

            wrote(body, "提案写进来的新正文")

            val check = ProposalSelfCheck(runEval = { EvalAttempt.Ran(runOf(7, 10)) }, log = {})
            val out = check.execute("技能提案", listOf(target))

            assertTrue("技能回滚失败：$out", out is SelfCheckOutcome.FailedRolledBack)
            assertEquals("技能正文必须回到落盘前那份", "旧正文", body.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    // ── ③ 红线命中 ⇒ 先回滚再报红线 ─────────────────────────────────────

    @Test
    fun `红线命中_先回滚再报红线`() {
        val dir = tempDir("red")
        try {
            // 构造一次越界写：文件落在禁区（技能目录外的某个前缀），且能被回滚
            val badDir = File(dir, "禁区").apply { mkdirs() }
            val target = File(badDir, "SKILL.md")
            wrote(target, "旧正文（本来就该在这）")
            wrote(File(badDir, "禁区.SKILL.md.bak-20260101-000000"), "旧正文（本来就该在这）")
            val snap = TargetSnapshot.of(target, TargetKind.SKILL)
            wrote(target, "越界写进来的新正文")

            val check = ProposalSelfCheck(runEval = { EvalAttempt.Ran(runOf(10, 10)) }, log = {})
            val out = check.execute(
                scene = "越界",
                targets = listOf(snap),
                redline = listOf("红线：禁区.txt 命中禁改前缀「terminal/」"),
            )

            assertTrue("红线命中必须回滚，实际 $out", out is SelfCheckOutcome.FailedRolledBack)
            out as SelfCheckOutcome.FailedRolledBack
            assertTrue("原因要说红线：${out.reason}", out.reason.contains("红线命中"))
            assertTrue("原因要点名那条：${out.reason}", out.reason.contains("terminal/"))
            assertEquals("越界写必须被撤回", "旧正文（本来就该在这）", target.readText())
            val text = SelfCheckText.of(out)!!
            assertTrue("红线文案也要说已回滚：$text", text.contains("已回滚"))
        } finally {
            dir.deleteRecursively()
        }
    }

    // ── ④ 无通道 ⇒ 保留、报未跑、不回滚 ─────────────────────────────────

    @Test
    fun `无通道_Skip分支_文件原样保留且不说通过`() {
        val dir = tempDir("skip2")
        try {
            val persona = File(dir, "人设.md")
            wrote(persona, "提案写的新人设")
            val target = TargetSnapshot.of(persona, TargetKind.PERSONA)

            val check = ProposalSelfCheck(
                runEval = { EvalAttempt.Skip("无可用通道（当前通道没填 Key）") },
                log = {},
            )
            val out = check.execute("采纳", listOf(target))

            assertTrue("无通道必须 NotRun，实际 $out", out is SelfCheckOutcome.NotRun)
            assertEquals("文件一个字都不许动", "提案写的新人设", persona.readText())
            val text = SelfCheckText.of(out)!!
            assertTrue("文案要说「自检未跑」：$text", text.contains("自检未跑"))
            assertFalse("不许谎报通过：$text", text.contains("通过"))
            assertFalse("更不许说已回滚：$text", text.contains("已回滚"))
        } finally {
            dir.deleteRecursively()
        }
    }

    // ── ⑤ 回滚原语自身失败 ⇒ 明说回滚失败 ───────────────────────────────

    @Test
    fun `回滚原语失败_报回滚失败并给路径`() {
        val dir = tempDir("badroll")
        try {
            val persona = File(dir, "人设.md")
            wrote(persona, "提案写的新人设")
            val target = TargetSnapshot.of(persona, TargetKind.PERSONA)

            val check = ProposalSelfCheck(
                runEval = { EvalAttempt.Ran(runOf(9, 10)) },
                // 故意让回滚失败（只读文件/备份缺失都由这一层表达）
                restoreOne = { _, _ -> false },
                log = {},
            )
            val out = check.execute("采纳", listOf(target))

            assertTrue("回滚失败必须单独一类，实际 $out", out is SelfCheckOutcome.RollbackFailed)
            out as SelfCheckOutcome.RollbackFailed
            assertTrue(
                "必须给出回滚失败的文件路径：${out.paths}",
                out.paths.any { it.endsWith("人设.md") },
            )
            val text = SelfCheckText.of(out)!!
            assertTrue("文案要说「回滚也没成功」：$text", text.contains("回滚也没成功"))
            assertFalse("不许谎称已回滚：$text", text.contains("已回滚"))
            // 文件没被动过（回滚失败 ⇒ 保持原样，不二次破坏）
            assertEquals("提案写的新人设", persona.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    // ── ⑥ 单飞 ─────────────────────────────────────────────────────────

    @Test
    fun `单飞_第二次直接Busy_不并行跑也不互相回滚`() {
        val dir = tempDir("single")
        try {
            val persona = File(dir, "人设.md")
            wrote(persona, "旧人设")
            val target = TargetSnapshot.of(persona, TargetKind.PERSONA)
            PersonaBackup.backupBefore(persona, "20260101-000000")
            wrote(persona, "提案写的新人设")

            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val evalCount = java.util.concurrent.atomic.AtomicInteger(0)
            val check = ProposalSelfCheck(
                runEval = {
                    evalCount.incrementAndGet()
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    EvalAttempt.Ran(runOf(9, 10))
                },
                log = {},
            )

            val t = Thread { check.execute("第一次采纳", listOf(target)) }
            t.isDaemon = true
            t.start()
            assertTrue("第一个自检没进场", entered.await(5, TimeUnit.SECONDS))

            val second = check.execute("第二次采纳", listOf(target))
            assertTrue("并发第二次必须 Busy，实际 $second", second is SelfCheckOutcome.Busy)
            assertNull("Busy 不覆盖屏上文案", SelfCheckText.of(second))

            release.countDown()
            t.join(5_000)

            assertEquals("eval 只跑了一次", 1, evalCount.get())
            // 只跑的那一次正常收尾（回滚一次），不会因为第二次进来再滚一遍
            assertEquals("回滚只发生一次，结果回到落盘前", "旧人设", persona.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    // ── 附：红线判据（纯函数）───────────────────────────────────────────

    @Test
    fun `红线判据_技能目录与人设B_C放行_禁区与禁改前缀拦下`() {
        val ws = tempDir("redline")
        try {
            val skillDir = File(ws, "技能/写技能").apply { mkdirs() }
            val globalRoot = tempDir("global")
            try {
                val globalPersona = File(globalRoot, "人设.md").apply { parentFile!!.mkdirs() }
                wrote(globalPersona, "B")
                val projectPersona = File(ws, "抱朴记忆/人设-本项目.md")
                wrote(projectPersona, "C")

                assertTrue(
                    "技能正文在允许目录里，不该报红线",
                    ProposalRedline.violationsOf(
                        File(skillDir, "SKILL.md"), ws,
                        listOf(globalPersona, projectPersona),
                        listOf(File(ws, "技能")),
                    ).isEmpty(),
                )
                assertTrue(
                    "B 在 App 私有目录（不在工作区），按具体文件放行",
                    ProposalRedline.violationsOf(
                        globalPersona, ws, listOf(globalPersona, projectPersona), emptyList(),
                    ).isEmpty(),
                )
                assertTrue(
                    "C 在工作区里，按具体文件放行",
                    ProposalRedline.violationsOf(
                        projectPersona, ws, listOf(globalPersona, projectPersona), emptyList(),
                    ).isEmpty(),
                )

                val raw = ProposalRedline.violationsOf(
                    File(ws, "资料库/原始/a.txt"), ws,
                    listOf(globalPersona, projectPersona), listOf(File(ws, "技能")),
                )
                assertTrue("原始区必须拦：$raw", raw.isNotEmpty())

                val prefix = ProposalRedline.violationsOf(
                    File(ws, "eval/cases.json"), ws,
                    listOf(globalPersona, projectPersona), listOf(File(ws, "技能")),
                )
                assertTrue("eval/ 前缀必须拦：$prefix", prefix.any { it.contains("eval/") })

                val term = ProposalRedline.violationsOf(
                    File(ws, "terminal/x"), ws,
                    listOf(globalPersona, projectPersona), listOf(File(ws, "技能")),
                )
                assertTrue("terminal/ 前缀必须拦：$term", term.isNotEmpty())

                val outside = ProposalRedline.violationsOf(
                    File(globalRoot, "别的东西"), ws,
                    listOf(globalPersona, projectPersona), listOf(File(ws, "技能")),
                )
                assertTrue("工作区外且不在允许集合，必须拦：$outside", outside.isNotEmpty())

                // 禁改前缀优先于允许目录：就算有人把禁区塞进技能目录，也要报
                val nested = ProposalRedline.violationsOf(
                    File(File(ws, "技能"), "../rootfs/x"), ws,
                    listOf(globalPersona, projectPersona), listOf(File(ws, "技能")),
                )
                assertTrue("rootfs/ 前缀必须拦（哪怕在允许目录附近）：$nested", nested.isNotEmpty())
            } finally {
                globalRoot.deleteRecursively()
            }
        } finally {
            ws.deleteRecursively()
        }
    }

    // ── 附：人设拼装必须是 A+B+C ────────────────────────────────────────

    @Test
    fun `人设拼装_A内核加落盘后的B_C`() {
        val prompt = PersonaPrompt.of("/ws", "B-GLOBAL-UNIQUE", "C-PROJECT-UNIQUE")
        assertTrue("系统提示必须有 A 内核", prompt.contains("证道"))
        assertTrue("系统提示必须有 B（落盘后的全局人设）", prompt.contains("B-GLOBAL-UNIQUE"))
        assertTrue("系统提示必须有 C（落盘后的项目人设）", prompt.contains("C-PROJECT-UNIQUE"))
    }

    @Test
    fun `源码门禁_运行时自检不许自己拼A内核_必须走PersonaPrompt`() {
        val runtime = File("src/main/java/com/example/zhengdao/baopu/eval/RuntimeEval.kt").readText()
        val code = runtime.lines()
            .filterNot { l -> val t = l.trimStart(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }
            .joinToString("\n")
        assertFalse(
            "RuntimeEval 里不许直接调 BaopuPreset.personaText —— 那就退回「只用 A 内核」，" +
                "测的是改之前的人设（自检演戏）",
            code.contains("BaopuPreset.personaText("),
        )
        assertTrue("RuntimeEval 必须经 PersonaPrompt.ofDisk", runtime.contains("PersonaPrompt.ofDisk"))

        val promptFile =
            File("src/main/java/com/example/zhengdao/baopu/eval/PersonaPrompt.kt")
        assertTrue(
            "PersonaPrompt 必须用 PersonaRules.assemble（与线上同一拼装口）",
            promptFile.readText().contains("PersonaRules.assemble("),
        )
    }

    @Test
    fun `源码门禁_控制器接线必须经RuntimeEval_不许自己拼提示`() {
        val ctrl = File("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt").readText()
        val code = ctrl.lines()
            .filterNot { l -> val t = l.trimStart(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }
            .joinToString("\n")
        assertFalse(
            "控制器不许自己拼系统提示——那会绕过「落盘后人设」这一条",
            code.contains("BaopuPreset.personaText("),
        )
        assertTrue("接线必须经 RuntimeEval.runFor", code.contains("RuntimeEval.runFor("))
        assertTrue("接线必须先问通道可用性", code.contains("RuntimeEval.unusableReason("))
        assertTrue("采纳路径必须排自检", code.contains("scheduleSelfCheck("))
    }

    // ── 附：通道可用性判据 ──────────────────────────────────────────────

    @Test
    fun `通道可用性_没key与免费通道与Anthropic都不可用`() {
        val deepseek = com.example.zhengdao.baopu.Providers.DEEPSEEK.copy(apiKey = "sk-test")
        assertNull("填了 key 的 OpenAI 兼容通道可用", RuntimeEval.unusableReason(deepseek))
        assertTrue(
            "内置免费通道不可用（真机整条 403）",
            RuntimeEval.unusableReason(com.example.zhengdao.baopu.Providers.BUILTIN_FREE) != null,
        )
        assertTrue(
            "没填 key 不可用",
            RuntimeEval.unusableReason(deepseek.copy(apiKey = "")) != null,
        )
        assertTrue(
            "Anthropic 形状不可用（harness 只实现一种请求形状）",
            RuntimeEval.unusableReason(
                com.example.zhengdao.baopu.Providers.ANTHROPIC.copy(apiKey = "sk-x"),
            ) != null,
        )
    }

    @Test
    fun `总预算超时_没跑的用例算失败_不挂死`() {
        val cases = (1..3).map { i ->
            com.example.zhengdao.baopu.eval.EvalCase(
                id = "c$i",
                note = "",
                prompt = "p",
                maxSteps = 2,
                files = emptyList(),
                asserts = listOf(EvalAssert(kind = "final_contains", text = "x")),
            )
        }
        var now = 0L
        val out = RuntimeEval.run(
            cases = cases,
            cfg = com.example.zhengdao.baopu.eval.EvalConfig(apiKey = "sk-x", systemPrompt = "s"),
            budgetMs = 10_000,
            // 假时钟：第一例就"花掉"超过预算的时间
            now = { now += 60_000; now },
        )
        assertEquals(3, out.total)
        assertEquals("超预算后一例都不许再跑", 0, out.passed)
        assertTrue("每条都要写清超时原因", out.results.all { it.failures.any { f -> f.contains("总时长") } })
    }

    @Test
    fun `PersonaBackup与SkillStore_回滚原语本身可用`() {
        val dir = tempDir("primitives")
        try {
            val persona = File(dir, "人设.md")
            wrote(persona, "v1")
            val stamp = "20260101-000000"
            PersonaBackup.backupBefore(persona, stamp)
            wrote(persona, "v2")
            val restored = PersonaBackup.restore(persona, "20260102-000000")
            assertTrue("人设回滚应成功：$restored", restored is com.example.zhengdao.baopu.PersonaBackupResult.Done)
            assertEquals("v1", persona.readText())

            val body = File(dir, "SKILL.md")
            wrote(body, "old")
            wrote(File(dir, "写技能.SKILL.md.bak-$stamp"), "old")
            wrote(body, "new")
            // 注意：restore 自己也要备份当前版，stamp 必须与那份备份不同名
            // （同名会把老备份覆盖掉——`SkillStore.restore` 的口径，见其 KDoc）
            val r = SkillStore.restore(body, "20260102-000000")
            assertTrue("技能回滚应成功：$r", r is SkillWriteResult.Written)
            assertEquals("old", body.readText())
        } finally {
            dir.deleteRecursively()
        }
    }
}
