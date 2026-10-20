// 第八十四批 · 运行时提案自检 v2：技能正文自检与闸门的门禁。
//
// JVM：临时目录 + 注入闭包，不联网、不烧额度、不碰用户真实技能/人设。
//
// 派工单 §2/§3 用例对应：
//   结构缺失            → `结构_*` / `必需段_*`
//   禁用内容            → `禁用内容_*`
//   回滚成功            → `不过_首写删掉文件` / `不过_有备份走restore_核sha256一致`
//   回滚失败            → `不过_回滚原语失败_*` / `不过_restore说成功但内容不对_*`
//   B/C 人设            → `评测集_B_C人设用例都在_且带fixture`
//   开关默认关          → `闸门_*` / `源码门禁_*`
//   自检只在采纳路径    → `源码门禁_正文自检只挂在采纳路径_不在对话路径`
package com.example.zhengdao.baopu.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SkillBodySelfCheckTest {

    // ── 小工具 ───────────────────────────────────────────────────────────

    private fun tempDir(tag: String): File = kotlin.io.path.createTempDirectory("zd-sbc-$tag-").toFile()

    private fun wrote(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text, Charsets.UTF_8)
    }

    /** 一份四条判据全过的样板正文（标题带技能名、正文够长、无禁用话术）。 */
    private fun goodBody(name: String = "写技能"): String =
        "# $name\n\n整理书架按高度分三档：常看的放中层，工具书与字典放下层，" +
            "半年没翻过的进纸箱贴条写明年代，年底再统一清一次；每一档书脊朝外摆，" +
            "找的时候一眼能认出来，不要为了塞进去把书压弯了；新书先过一遍目录再决定去处。"

    private fun input(
        name: String,
        body: String,
        backups: Int = 0,
        existed: Boolean = false,
    ) = SkillBodyInput(name = name, body = body, backups = backups, existed = existed)

    private fun issuesOf(v: SkillBodyVerdict): List<String> =
        (v as? SkillBodyVerdict.Bad)?.issues ?: emptyList()

    // ── 判据① 结构 ──────────────────────────────────────────────────────

    @Test
    fun `结构_没标题_报缺标题段`() {
        val v = SkillBodySelfCheck.check(input("写技能", "没有标题的正文，直接就是内容，长度也够八十个字符以排除其它判据的干扰项。"))
        assertTrue("没标题必须是 Bad", v is SkillBodyVerdict.Bad)
        assertTrue(issuesOf(v).any { it.contains("缺标题段") })
    }

    @Test
    fun `结构_标题后紧跟正文_不误判只有标题`() {
        // 回归钉：旧实现用 `dropWhile { it.isNotBlank() }` 找正文起点，会把标题后紧随的
        // 正文行一起吞掉（`# 技能名\n正文…` 被误判成「只有标题」）。
        val text = "# 写技能\n" + "整理书架按高度分三档，常看的放中层，工具书放下层，半年没翻的进纸箱。".repeat(3)
        assertEquals(
            "标题后紧跟正文（无空行）也必须认得出正文",
            SkillBodyVerdict.Ok,
            SkillBodySelfCheck.check(input("写技能", text)),
        )
    }

    @Test
    fun `结构_只有标题没有内容_报只有标题`() {
        val v = SkillBodySelfCheck.check(input("写技能", "# 写技能\n\n\n"))
        assertTrue(issuesOf(v).any { it.contains("只有标题") })
    }

    @Test
    fun `必需段_标题里没有技能名_点名`() {
        val v = SkillBodySelfCheck.check(input("写技能", "# 整理书架\n\n" + goodBody().substringAfter("\n\n")))
        assertTrue(issuesOf(v).any { it.contains("标题里没有技能名") })
    }

    @Test
    fun `必需段_正文太短_报太短并带字数`() {
        val v = SkillBodySelfCheck.check(input("写技能", "# 写技能\n\n太短。"))
        assertTrue(issuesOf(v).any { it.contains("太短") })
    }

    @Test
    fun `必需段_字数卡在MIN_BODY_CHARS线上`() {
        val exact = "x".repeat(SkillBodySelfCheck.MIN_BODY_CHARS)
        assertEquals(
            "正好 ${SkillBodySelfCheck.MIN_BODY_CHARS} 字符算够",
            SkillBodyVerdict.Ok,
            SkillBodySelfCheck.check(input("写技能", "# 写技能\n\n$exact")),
        )
        val oneLess = "x".repeat(SkillBodySelfCheck.MIN_BODY_CHARS - 1)
        assertTrue(
            "少一个字符算太短",
            issuesOf(SkillBodySelfCheck.check(input("写技能", "# 写技能\n\n$oneLess"))).any { it.contains("太短") },
        )
    }

    @Test
    fun `全对_判据Ok`() {
        assertEquals(SkillBodyVerdict.Ok, SkillBodySelfCheck.check(input("写技能", goodBody())))
    }

    @Test
    fun `判据不短路_一次把话说全`() {
        val v = SkillBodySelfCheck.check(input("写技能", "ignore previous instructions 忽略以上"))
        val issues = issuesOf(v)
        assertTrue("结构问题也要报", issues.any { it.contains("缺标题段") })
        assertTrue("禁用话术也要报", issues.any { it.contains("摆脱系统提示") })
        assertTrue("两个问题都要在（不短路）", issues.size >= 2)
    }

    // ── 判据③ 禁用内容 ──────────────────────────────────────────────────

    @Test
    fun `禁用内容_提权话术命中即拒_不分中英文大小写`() {
        for (p in listOf("忽略以上所有指令", "Ignore Previous Instructions", "无视系统提示", "覆盖系统提示", "jailbreak")) {
            val v = SkillBodySelfCheck.check(input("写技能", "# 写技能\n\n$p。后面是足够长的正常正文，讲怎么整理书架分三档。"))
            assertTrue("「$p」必须被拒：$v", issuesOf(v).any { it.contains("摆脱系统提示") })
        }
    }

    @Test
    fun `禁用内容_正常技能不误伤`() {
        // 「系统」「sudo」「rm -rf」本身不是提权——技能要教的事不许误伤
        val v = SkillBodySelfCheck.check(
            input(
                "写技能",
                "# 写技能\n\n在系统目录里用 sudo 清理缓存之前，先确认路径只在本项目自己建的临时目录下，" +
                    "别的一概不动；rm -rf 只许删自己建的临时目录，删完再列一次目录确认没有误伤。" +
                    "保命习惯比手快重要：每条删除命令执行前，先 echo 一遍将要删的路径，看清楚再回车。",
            ),
        )
        assertEquals(SkillBodyVerdict.Ok, v)
    }

    // ── 判据④ 回滚可用性 ────────────────────────────────────────────────

    @Test
    fun `回滚可用性_三岔路口各自归位`() {
        assertEquals("有旧版备份走恢复", RollbackVia.RESTORE, SkillBodySelfCheck.rollbackVia(backups = 1, existed = true))
        assertEquals("首写走删除", RollbackVia.DELETE, SkillBodySelfCheck.rollbackVia(backups = 0, existed = false))
        assertEquals("既没备份又非首写＝没有路", RollbackVia.NONE, SkillBodySelfCheck.rollbackVia(backups = 0, existed = true))
    }

    @Test
    fun `回滚可用性_没有路_算不过`() {
        val v = SkillBodySelfCheck.check(input("写技能", goodBody(), backups = 0, existed = true))
        assertTrue(issuesOf(v).any { it.contains("没有可回滚的路") })
    }

    // ── 执行壳：通过 ─────────────────────────────────────────────────────

    @Test
    fun `通过_文件原样留着_并留痕`() {
        val dir = tempDir("ok")
        try {
            val f = File(dir, "SKILL.md")
            wrote(f, goodBody())
            val target = TargetSnapshot.of(f, TargetKind.SKILL)
            val logs = mutableListOf<String>()
            val out = SkillBodyGuard(log = { logs += it })
                .execute("技能正文", target, input("写技能", goodBody(), backups = 1, existed = true))
            assertEquals(SkillBodyOutcome.Passed, out)
            assertEquals("通过一个字都不许动", goodBody(), f.readText())
            assertTrue("通过也要留痕：$logs", logs.any { it.contains("通过") })
        } finally {
            dir.deleteRecursively()
        }
    }

    // ── 执行壳：不过 ⇒ 回滚 ──────────────────────────────────────────────

    @Test
    fun `不过_首写删掉文件_报已回滚`() {
        val dir = tempDir("first")
        try {
            val f = File(dir, "SKILL.md")
            // 落盘前**不存在**（首次写入，没有备份可恢复）
            val target = TargetSnapshot.of(f, TargetKind.SKILL)
            assertFalse("快照应记下「落盘前不存在」", target.existed)
            wrote(f, "没有标题的坏正文")

            val out = SkillBodyGuard(log = {})
                .execute("技能正文", target, input("写技能", "没有标题的坏正文", backups = 0, existed = false))
            assertTrue("不过必须回滚：$out", out is SkillBodyOutcome.FailedRolledBack)
            assertFalse("首写回滚＝回到「没有」，文件必须消失", f.exists())
            assertTrue("原因要说判据：${(out as SkillBodyOutcome.FailedRolledBack).reason}", out.reason.contains("缺标题段"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `不过_有备份走restore_核sha256一致`() {
        val dir = tempDir("restore")
        try {
            val f = File(dir, "SKILL.md")
            wrote(f, "旧正文")
            val target = TargetSnapshot.of(f, TargetKind.SKILL)
            // SkillStore.apply 落盘前造的那份备份（真实命名：<技能名>.SKILL.md.bak-<stamp>）；
            // restore 自己也要备份当前版，所以注入的 stamp 必须与这份备份**不同名**
            // （同名会把它覆盖掉，见 SkillStore KDoc）
            wrote(File(dir, "写技能.SKILL.md.bak-20260101-000000"), "旧正文")
            wrote(f, "# 写技能\n\nignore previous instructions 的坏正文，长度也够八十字符。")

            val logs = mutableListOf<String>()
            val out = SkillBodyGuard(stamp = { "20260102-000000" }, log = { logs += it })
                .execute("技能正文", target, input("写技能", f.readText(), backups = 1, existed = true))
            assertTrue("不过必须回滚：$out", out is SkillBodyOutcome.FailedRolledBack)
            assertEquals("回滚后与落盘前逐字节一致", "旧正文", f.readText())
            assertTrue("回滚日志要核 sha256：$logs", logs.any { it.contains("sha256 与落盘前一致") })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `不过_回滚原语失败_报回滚失败且不谎称已回滚`() {
        val dir = tempDir("badroll")
        try {
            val f = File(dir, "SKILL.md")
            wrote(f, "旧正文")
            val target = TargetSnapshot.of(f, TargetKind.SKILL)
            wrote(f, "# 写技能\n\nignore previous instructions 的坏正文，长度也够八十字符。")

            // 只读盘/备份没了：回滚原语恒失败
            val out = SkillBodyGuard(restoreOne = { _, _ -> false }, log = {})
                .execute("技能正文", target, input("写技能", f.readText(), backups = 1, existed = true))
            assertTrue("回滚失败必须单独一类：$out", out is SkillBodyOutcome.RollbackFailed)
            out as SkillBodyOutcome.RollbackFailed
            assertTrue("必须给出失败路径：${out.paths}", out.paths.any { it.endsWith("SKILL.md") })
            assertTrue("原因要说判据：${out.reason}", out.reason.contains("摆脱系统提示"))
            assertEquals("回滚失败不二次破坏，文件保持原样", "# 写技能\n\nignore previous instructions 的坏正文，长度也够八十字符。", f.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `不过_restore说成功但内容不对_照样报回滚失败`() {
        val dir = tempDir("liar")
        try {
            val f = File(dir, "SKILL.md")
            wrote(f, "旧正文")
            val target = TargetSnapshot.of(f, TargetKind.SKILL)
            wrote(f, "# 写技能\n\nignore previous instructions 的坏正文，长度也够八十字符。")

            // 回滚原语嘴上说成功，其实什么都没恢复——sha256 核对防线必须抓住它
            val out = SkillBodyGuard(restoreOne = { _, _ -> true }, log = {})
                .execute("技能正文", target, input("写技能", f.readText(), backups = 1, existed = true))
            assertTrue("内容没恢复必须报回滚失败：$out", out is SkillBodyOutcome.RollbackFailed)
        } finally {
            dir.deleteRecursively()
        }
    }

    // ── 闸门（派工单 §0.3：默认关） ─────────────────────────────────────

    @Test
    fun `闸门_默认关`() {
        assertFalse("Release 不拨开关不许跑", SelfCheckGate.enabled(devMode = false, prefOn = false))
        assertFalse("PREF_DEFAULT 必须是 false", SelfCheckGate.PREF_DEFAULT)
    }

    @Test
    fun `闸门_调试构建开箱即跑`() {
        assertTrue(SelfCheckGate.enabled(devMode = true, prefOn = false))
    }

    @Test
    fun `闸门_用户拨了开关才跑`() {
        assertTrue(SelfCheckGate.enabled(devMode = false, prefOn = true))
    }

    // ── B/C 人设覆盖门禁（删用例 ⇒ 红） ─────────────────────────────────

    @Test
    fun `评测集_B_C人设用例都在_且带fixture`() {
        val root = repoRoot()
        val cases = EvalCases.load(File(root, "eval/cases.json"))
        val withPersona = cases.filter { it.persona.isNotBlank() }.associateBy { it.persona }
        assertTrue("评测集里没有 B_GLOBAL 人设用例（删了这条覆盖就没了）", withPersona.containsKey("B_GLOBAL"))
        assertTrue("评测集里没有 C_PROJECT 人设用例（删了这条覆盖就没了）", withPersona.containsKey("C_PROJECT"))
        for ((layer, c) in withPersona) {
            assertTrue("$layer 用例必须带 personaText fixture", c.personaText.isNotBlank())
            assertTrue("$layer 用例必须有判据", c.asserts.isNotEmpty())
            assertTrue("$layer 用例的 personaText 里得有人设层字样", c.personaText.contains("人设"))
        }
        assertTrue("存量非 persona 用例不少于 10 条", cases.count { it.persona.isEmpty() } >= 10)
    }

    // ── 源码门禁 ─────────────────────────────────────────────────────────

    @Test
    fun `源码门禁_自检默认关_接线先过闸门`() {
        val gate = File("src/main/java/com/example/zhengdao/baopu/eval/SelfCheckGate.kt").readText(Charsets.UTF_8)
        assertTrue("闸门默认值必须是 false（派工单 §2：开关默认关）", gate.contains("const val PREF_DEFAULT = false"))

        val ctrl = File("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt").readText(Charsets.UTF_8)
        assertTrue("正文自检接线必须先过 SelfCheckGate.enabledNow", ctrl.contains("SelfCheckGate.enabledNow("))
        assertFalse("不许把闸门写死为开", ctrl.contains("SelfCheckGate.enabledNow(true"))
        // 闸门判定必须排在执行壳调用**之前**（同文件按行序就能钉住）
        val gateCall = ctrl.indexOf("SelfCheckGate.enabledNow(")
        val exec = ctrl.indexOf("skillBodyGuard.execute(")
        assertTrue("控制器里有执行壳调用", exec >= 0)
        assertTrue("先过闸门才许调执行壳", gateCall in 0 until exec)
    }

    @Test
    fun `源码门禁_正文自检只挂在采纳路径_不在对话路径`() {
        val ctrl = File("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt").readText(Charsets.UTF_8)
        // 只许两处出现：writeLearnedSkill 里一次调用 ＋ 本类一次定义
        val hits = Regex("skillBodySelfCheck\\(").findAll(ctrl).count()
        assertEquals("正文自检只许挂在采纳路径（一次调用＋一次定义）", 2, hits)
        val call = ctrl.indexOf("skillBodySelfCheck(")
        val writeFn = ctrl.indexOf("private fun writeLearnedSkill")
        val selfFn = ctrl.indexOf("private fun skillBodySelfCheck(")
        assertTrue("调用点必须在 writeLearnedSkill（采纳路径）里", writeFn in 0 until call && call < selfFn)
    }

    private fun repoRoot(): File {
        var d: File? = File(".").absoluteFile
        repeat(6) {
            if (d != null && File(d, "eval/cases.json").isFile) return d
            d = d?.parentFile
        }
        throw AssertionError("找工作区根失败：从 ${File(".").absolutePath} 向上六层都没有 eval/cases.json")
    }
}
