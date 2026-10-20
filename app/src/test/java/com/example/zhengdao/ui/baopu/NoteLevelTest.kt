// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第六十一批 §2（错误留痕 1b）。
//
// 现场（基线 origin/main）：`libraryNote` / `skillNote` 两类一句话里混着**进度**
// （记下来中…／收料处理中…／导出中…）与**错误**（技能上限拒绝／写盘失败／资料库选项对不上），
// 而 UI 只有一条规矩：非空 `delay(8_000)` 后清空 ⇒ 错误和进度一起蒸发。
//
// §2 要的三条，一条条钉：
// - 级别只有两档（Info / Error），判据只有一处（[BaopuNote] 的 autoClears → [BaopuNotePolicy]）；
// - Error 不许挂 8 秒自清、必须可点关闭（复用 49 批那个 `BaopuIntrospectionNote` 的 ×）；
// - Info 保持现有 8 秒自清（进度不许钉在屏上）。
// `pendingNote` 已由 UI 第一批改成持久＋可点关闭，本批一行不动 ⇒ 也钉着（B6）。
//
// 读法：注释剥掉、字符串留着（要钉的是文案与代码混在一起的那些行）；
// 纯函数那几条（A1–A5）零 Android，直接打。
package com.example.zhengdao.ui.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NoteLevelTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    private fun code(rel: String): String = repoFile(rel).readText()
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private fun screen(): String = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuScreen.kt")

    /** 第六十三批那个通知位组件（三条错误便签的渲染点搬这儿来了）。 */
    private fun slot(): String = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuNoticeSlot.kt")

    private fun controller(): String =
        code("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt")

    // ── A1–A5：级别判据（纯函数） ────────────────────────────────────────

    @Test
    fun `A1_进度档自清_错误档不自清`() {
        assertTrue("Info 必须仍走 8 秒自清（进度不该钉住）", BaopuNotePolicy.shouldAutoClear(BaopuNoteLevel.Info))
        assertFalse("Error 绝不许自清（§2：点掉才清）", BaopuNotePolicy.shouldAutoClear(BaopuNoteLevel.Error))
    }

    @Test
    fun `A2_结果播报按有没有出 problem 件定级`() {
        assertEquals(BaopuNoteLevel.Info, BaopuNotePolicy.levelOfResult(0))
        assertEquals("有 1 件被拒/跳过/失败就得留痕", BaopuNoteLevel.Error, BaopuNotePolicy.levelOfResult(1))
        assertEquals(BaopuNoteLevel.Error, BaopuNotePolicy.levelOfResult(5))
        // 件数不可能为负，但判据不许因为负数翻档（负数 ⇒ 没做成 ⇒ Error 也算说得通，钉住行为）
        assertEquals(BaopuNoteLevel.Info, BaopuNotePolicy.levelOfResult(-1))
    }

    @Test
    fun `A3_便签自带级别_是否自清只问级别`() {
        assertTrue(BaopuNote("导出中…", BaopuNoteLevel.Info).autoClears)
        assertFalse(BaopuNote("没能对上选项", BaopuNoteLevel.Error).autoClears)
        // 级别跟着文案同一个对象 ⇒ 不可能出现「文案是错误、级别还留着 Info」那种半新半旧
        assertEquals("记下来中…", BaopuNote("记下来中…", BaopuNoteLevel.Info).text)
    }

    @Test
    fun `A4_只有两档`() {
        // 第三档（比如 Warn）一出现，UI 那条 `Error 才常驻` 的分支就会漏掉它——
        // 沉默地按 Info 走，等于把错误又塞回 8 秒。要加档必须先改这条判据。
        assertEquals(2, BaopuNoteLevel.values().size)
        assertTrue(BaopuNoteLevel.values().map { it.name }.toSet() == setOf("Info", "Error"))
    }

    @Test
    fun `A5_级别判据全仓只有一处`() {
        val c = controller()
        assertEquals("shouldAutoClear 的定义只许一处", 1, Regex("fun shouldAutoClear\\(").findAll(c).count())
        assertEquals("levelOfResult 的定义只许一处", 1, Regex("fun levelOfResult\\(").findAll(c).count())
        // UI 不许抄第二条判据（`== BaopuNoteLevel.Info` 就是抄一份）：它只问 autoClears
        assertFalse(
            "BaopuScreen 不许出现 level == BaopuNoteLevel.Info（那是第二条自清判据）",
            screen().contains("== BaopuNoteLevel.Info"),
        )
        assertEquals(
            "自清判据的调用点只许两处（libraryNote／skillNote 各一条定时器）",
            2,
            Regex("\\.autoClears").findAll(screen()).count(),
        )
    }

    // ── B1–B3：§2 源码门禁（留痕怎么留） ────────────────────────────────

    @Test
    fun `B1_错误路径不挂八秒定时器`() {
        val src = screen()
        // 8 秒定时器全文件只许剩这两条（第三条就是又开了一路自清）
        assertEquals(2, Regex("delay\\(8_000\\)").findAll(src).count())
        listOf("libraryNote", "skillNote").forEach { key ->
            val i = src.indexOf("LaunchedEffect($key)")
            assertTrue("$key 的定时器还在", i >= 0)
            val block = src.substring(i, minOf(src.length, i + 200))
            assertTrue(
                "$key 的 8 秒自清必须先问级别（错误不许自己走）",
                Regex("if \\($key != null && $key\\.autoClears\\)").containsMatchIn(block),
            )
        }
    }

    @Test
    fun `B2_进度档仍走那个八秒`() {
        val src = screen()
        // Info 保留：两条定时器各自仍调既有 clear*（不清就是进度钉死在屏上）
        listOf("libraryNote" to "controller.clearLibraryNote()", "skillNote" to "controller.clearSkillNote()")
            .forEach { (key, clearer) ->
                val i = src.indexOf("LaunchedEffect($key)")
                val block = src.substring(i, minOf(src.length, i + 200))
                assertTrue("$key 仍挂 delay(8_000)", block.contains("delay(8_000)"))
                assertTrue("$key 的清理入口没换（还是 $clearer）", block.contains(clearer))
            }
    }

    @Test
    fun `B3_错误靠点掉_且走既有clear`() {
        // 2026-10-20 第六十三批：首屏五条收进**一个通知位**（`BaopuNoticeSlot.kt`），
        // 三条错误便签的「渲染点」从 BaopuScreen 搬进了那个组件的详情卡——本用例的判据
        // 「错误靠点掉、点掉走既有 clear*、不新造组件、分支按级别」一条都没放宽，
        // 只有「组件在哪个文件里被调」跟着搬位改了目标（屏里 0 处、通知位里 1 处）。
        // 对照：第六十一批 §2 也是这么处理 49 批那句「两个 LaunchedEffect 一行不动」的。
        val src = screen()
        val slot = slot()
        listOf(
            "controller.clearSkillNote()" to "技能错误便签",
            "controller.clearLibraryNote()" to "资料库错误便签",
            "controller.clearPendingNote()" to "记忆错误便签（49 批那条）",
        ).forEach { (clearer, what) ->
            assertEquals(
                "$what：onDismiss 必须接既有 clear*，且只这一处",
                1,
                Regex("onDismiss = \\{ ${Regex.escape(clearer)} \\}").findAll(src).count(),
            )
        }
        // 不新造大组件：错误档用的还是 49 批那个现成的（Surface ＋ 标题 ＋ ×），只是调用点在通知位里
        assertEquals("屏里不该再自己渲染便签", 0, Regex("BaopuIntrospectionNote\\(").findAll(src).count())
        assertEquals("通知位里错误档仍复用那个组件（恰好一处）", 1, Regex("BaopuIntrospectionNote\\(").findAll(slot).count())
        assertEquals("屏里通知位出口只有一处", 1, Regex("BaopuNoticeSlot\\(").findAll(src).count())
        assertFalse(
            "不许在 BaopuScreen 里另造一个便签组件",
            Regex("fun \\w*Note\\(").containsMatchIn(src),
        )
        // 错误两族的分支判据是级别，不是文案内容（不许写 contains("失败") 那种话术匹配）
        assertEquals(
            "Error 分支各一处（技能／资料库）",
            2,
            Regex("note\\.level == BaopuNoteLevel\\.Error").findAll(src).count(),
        )
    }

    @Test
    fun `B4_清理入口只有既有三个`() {
        val c = controller()
        // 名字里带 Note 的清理通道只许那三条（`clearSkillNoteViaNewDoor` 这种新门也在这里现形：
        // 判据是"点掉错误便签走的是既有 clear"，多开一条就是第二处清的状态）
        assertEquals(
            "不许为错误便签新开 clear 通道（点掉走的还是那三个）",
            setOf("clearPendingNote", "clearLibraryNote", "clearSkillNote"),
            Regex("fun (clear[A-Za-z]*Note[A-Za-z]*)\\(").findAll(c).map { it.groupValues[1] }.toSet(),
        )
    }

    @Test
    fun `B5_每个赋值点都带级别`() {
        val c = controller()
        // 裸文案赋值 = 漏标级别。带级别那一型必须是 BaopuNote(...)，或清空(null)、或转手 out。
        listOf("skillNote", "libraryNote").forEach { field ->
            Regex("$field = ([^\\n]*)").findAll(c).forEach { m ->
                val rhs = m.groupValues[1].trim()
                assertTrue(
                    "$field 的赋值点必须带级别（看到的是：$rhs）",
                    rhs.startsWith("BaopuNote(") || rhs == "out" || rhs == "null" ||
                        rhs.startsWith("when {"),
                )
            }
        }
        // 状态载体本身也得是带级别那一型（回到 String? 就等于允许漏标）
        assertTrue(c.contains("var skillNote by mutableStateOf<BaopuNote?>(null)"))
        assertTrue(c.contains("var libraryNote by mutableStateOf<BaopuNote?>(null)"))
        // 级别不许再由第二个字段表达（§2 两选一：这里选 data class 那一型，就不能同时有 *NoteLevel 字段）
        assertFalse(
            "多一个 *NoteLevel 字段＝第二处级别，赋值点会漏改",
            c.contains("skillNoteLevel") || c.contains("libraryNoteLevel"),
        )
    }

    /**
     * 赋值点 → 级别 对照表（§2 交付物里那张表的可执行版）。
     *
     * 每条：在 [controller] 里找到那个赋值点的文案/键，往后 400 字内**第一个**级别标记
     * 必须就是表里那一档。`dynamic` = 按结果件数定级（`BaopuNotePolicy.levelOfResult(...)`）。
     */
    @Test
    fun `B6_赋值点级别对照表`() {
        val c = controller()
        val table = listOf(
            // ── 进度（Info，8 秒自清）──
            "记下来中…" to "Info",
            "收料处理中…" to "Info",
            "导出中…" to "Info",
            "已记下：" to "Info",
            // ── 结果播报（件数定级）──
            "parts.joinToString(\"\")" to "dynamic",
            "导出完成：" to "dynamic",
            // ── 错误（Error，常驻可点）──
            "不能写：" to "Error",
            "没能对上选项（" to "Error",
            "没能对上选项，请直接点" to "Error",
            "没有可导出的原件" to "Error",
            "skill_refine_dropped" to "Error",
            "skill_cap_reached" to "Error",
            "skill_apply_failed" to "Error",
            "BaopuNote(r.reason, BaopuNoteLevel.Error)" to "Error",
        )
        table.forEach { (needle, want) ->
            val i = c.indexOf(needle)
            assertTrue("赋值点不在了：$needle", i >= 0)
            val after = c.substring(i, minOf(c.length, i + 400))
            assertEquals("$needle 这一档定错了（want=$want）", want, firstLevel(after))
        }
        // skillNote = out 这一条：级别由 writeLearnedSkill 的返回值自带（转手不重新定级）
        assertTrue(c.contains("private fun writeLearnedSkill(p: com.example.zhengdao.baopu.SkillRefine.LearnProposal): BaopuNote"))
        assertEquals("采纳那条只能转手，不许在这儿重新定级", 1, Regex("skillNote = out\\b").findAll(c).count())
    }

    /**
     * 往后**最先**出现的那个级别标记：`Info` / `Error` / `dynamic`
     * （dynamic = 按结果件数定级，走 [BaopuNotePolicy.levelOfResult]）。
     */
    private fun firstLevel(s: String): String? {
        val cands = listOf(
            "Info" to s.indexOf("BaopuNoteLevel.Info"),
            "Error" to s.indexOf("BaopuNoteLevel.Error"),
            "dynamic" to s.indexOf("BaopuNotePolicy.levelOfResult("),
        ).filter { it.second >= 0 }
        return cands.minByOrNull { it.second }?.first
    }

    @Test
    fun `B7_pendingNote不重复改`() {
        val c = controller()
        // §2 原话：pendingNote 已由第一批改成持久＋可点关闭，不要重复改 ⇒ 它还是 String?
        assertTrue("pendingNote 的载体不许被顺手改型", c.contains("var pendingNote by mutableStateOf<String?>(null)"))
        val src = screen()
        assertFalse("pendingNote 不许重新挂上定时器", src.contains("LaunchedEffect(pendingNote)"))
        // 49 批那条 onDismiss 接法原样
        assertTrue(src.contains("onDismiss = { controller.clearPendingNote() }"))
    }

    @Test
    fun `B8_只改提示不改动作本身`() {
        val c = controller()
        // §2 红线：不改 SKILL 写盘／资料库动作本身。这几个动作入口必须原样在。
        listOf(
            "fun adoptLearn(id: String)",
            "private fun runImport(choice: MoveChoice)",
            "fun exportOriginals(textOnly: Boolean)",
            "fun confirmSkillProposal(",
            "val ok = pf.applyProposal(p)",
            "imp.execute(choice, System.currentTimeMillis())",
        ).forEach { assertTrue("动作入口没在：$it", c.contains(it)) }
        // 只许这一处返回类型改型（String → BaopuNote）：级别由写盘结果自己带
        assertEquals(1, Regex("fun writeLearnedSkill\\([^)]*\\): BaopuNote").findAll(c).count())
        assertEquals("writeLearnedSkill 不许再返回裸 String", 0, Regex("fun writeLearnedSkill\\([^)]*\\): String").findAll(c).count())
    }

    @Test
    fun `B9_错误便签标题中英都有`() {
        val src = screen()
        listOf("baopu_note_skill_error_label", "baopu_note_library_error_label").forEach { k ->
            assertTrue("$k 必须被用作标题", src.contains("R.string.$k"))
            for (rel in listOf("src/main/res/values/strings.xml", "src/main/res/values-en/strings.xml")) {
                assertTrue("$rel 缺 $k", repoFile(rel).readText().contains("name=\"$k\""))
            }
        }
    }
}
