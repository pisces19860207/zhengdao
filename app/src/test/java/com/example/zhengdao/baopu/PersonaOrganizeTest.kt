// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百六十四批 ②（B/C 层自整理）的判据测试。
// 这一批的红线不是"整得动"，而是**整错了能自动退回、且退回后一个字节都不差**：
// 所以每条正向用例都配一条"故意让它丢信息 / 故意让它复验不过"的负例。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PersonaOrganizeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val globalRoot: File by lazy { tmp.newFolder("g") }
    private val projectRoot: File by lazy { tmp.newFolder("p") }

    // ── 边界：谁不能碰 ──

    /** 判据⑤ 的前半：A 内核永不参与整理（入口第一行就拒，连文件都不去拿）。 */
    @Test
    fun `A内核永不参与整理`() {
        val r = PersonaOrganize.organize(files(), PersonaLayer.A_KERNEL, STAMP)
        assertTrue("A 内核必须直接拒：$r", r is PersonaOrganizeResult.Rejected)
        assertEquals(PersonaOrganize.NO_A, (r as PersonaOrganizeResult.Rejected).reason)
    }

    @Test
    fun `没超预算的层_一个字节都不动`() {
        val files = files()
        val f = writeGlobal(files, smallPersona())
        val before = f.readText(Charsets.UTF_8)
        val r = PersonaOrganize.organize(files, PersonaLayer.B_GLOBAL, STAMP)
        assertTrue("没到触发线不该整理：$r", r is PersonaOrganizeResult.NoGain)
        val why = (r as PersonaOrganizeResult.NoGain).reason
        assertTrue("理由要说清没超预算：$why", why.contains("没超预算"))
        assertEquals("没整理就不能动文件", before, f.readText(Charsets.UTF_8))
    }

    /** 触发线 ＝ 预算的 80%（6400 token），两侧各钉一次。 */
    @Test
    fun `触发线是预算的百分之八十`() {
        assertEquals(80, PersonaOrganize.TRIGGER_PERCENT)
        assertEquals(8_000, PersonaRules.PERSONA_TOKEN_LIMIT)
        assertFalse("5564 个汉字（6399 token）还没到线", PersonaOrganize.overBudget("甲".repeat(5_564)))
        assertTrue("5565 个汉字（正好 6400 token）踩线即触发", PersonaOrganize.overBudget("甲".repeat(5_565)))
    }

    // ── 正向：判据②③⑤ ──

    @Test
    fun `判据二_整理后 token 降幅达标`() {
        val r = organizeBig()
        val done = r as? PersonaOrganizeResult.Done ?: error("超预算的长人设应整理成功：$r")
        val plan = done.plan
        assertTrue("降幅要 ≥20%：${plan.savingPercent()}%（${plan.tokensBefore}→${plan.tokensAfter}）",
            plan.savingPercent() >= PersonaOrganize.MIN_SAVING_PERCENT)
        assertTrue("省下来的必须是真 token：${plan.savedTokens}", plan.savedTokens > 0)
        assertTrue("diff 要能说人话：${plan.diffText()}", plan.diffText().isNotBlank())
    }

    @Test
    fun `判据三_归档逐字节等于整理前`() {
        val files = files()
        val f = writeGlobal(files, bigPersona())
        val before = f.readText(Charsets.UTF_8)
        val origin = tmp.newFile("origin-copy")
        origin.writeText(before, Charsets.UTF_8)
        val got = PersonaOrganize.organize(files, PersonaLayer.B_GLOBAL, STAMP)
        val done = got as? PersonaOrganizeResult.Done ?: error("应整理成功：$got")
        assertEquals("归档名＝本轮时间戳", PersonaOrganize.archiveName(STAMP), done.archive.name)
        assertEquals("归档就是整理前那份的逐字节副本", before, done.archive.readText(Charsets.UTF_8))
        assertEquals("hash 必须相等（判据③ 的字面口径）",
            SkillAudit.shaOf(origin), SkillAudit.shaOf(done.archive))
        assertEquals("归档落点在人设/归档/ 下",
            File(File(f.parentFile, "人设"), "归档").absolutePath,
            PersonaOrganize.archiveHome(f)!!.absolutePath)
        assertTrue("整理后的文件确实变了", f.readText(Charsets.UTF_8) != before)
    }

    @Test
    fun `判据三附加_每个指针都解得回原文`() {
        val plan = (organizeBig() as? PersonaOrganizeResult.Done)?.plan
            ?: error("应整理成功")
        assertTrue("这份 fixture 里必须有指针化产物", plan.condensed.isNotEmpty())
        val archiveLines = plan.before.split("\n")
        plan.condensed.forEach { c ->
            val resolved = PersonaPointer.resolve(c.pointer.mark(), plan.archiveName, archiveLines)
            assertTrue("指针解不出原文：${c.pointer.mark()}", resolved != null)
            assertEquals("指针指的必须就是那句原文", c.original.trim(), resolved!!.trim())
            assertFalse("结论里不许再套指针（禁 L1→L1）",
                PersonaPointer.hasMark(c.product.substringBefore(PersonaPointer.OPEN)))
        }
    }

    /** 判据⑤ 后半：锁头三条（B/C 前 3 条）改/删/换序 ⇒ 在这批里是**构造性**碰不到的。 */
    @Test
    fun `判据五_锁头三条字节不变`() {
        val before = bigPersona()
        val files = files()
        writeGlobal(files, before)
        val got = PersonaOrganize.organize(files, PersonaLayer.B_GLOBAL, STAMP)
        val done = got as? PersonaOrganizeResult.Done ?: error("应整理成功：$got")
        val head = PersonaOrganize.lockedHeadLines(before)
        assertEquals("锁头就是前三条非空非注释行", 3, head.size)
        assertEquals("整理后锁头逐行相等", head, PersonaOrganize.lockedHeadLines(done.plan.after))
        assertTrue("产物开头必须还是锁头那一段",
            done.plan.after.trim().startsWith(done.plan.lockedHead.trim()))
    }

    @Test
    fun `整理走唯一写口_所以写前必有备份可回滚`() {
        val files = files()
        writeGlobal(files, bigPersona())
        val got = PersonaOrganize.organize(files, PersonaLayer.B_GLOBAL, STAMP)
        val done = got as? PersonaOrganizeResult.Done ?: error("应整理成功：$got")
        val backup = done.backup
        assertTrue("整理前那一份必须留了备份（回滚用的就是它）：$backup", backup != null && backup.isFile)
        assertEquals("备份里就是整理前那份原文", bigPersona(), backup!!.readText(Charsets.UTF_8))
    }

    @Test
    fun `账上记的是自整理_不是模型也不是用户`() {
        PersonaChangeLedger.resetForTest()
        val files = files()
        writeGlobal(files, bigPersona())
        val r = PersonaOrganize.organize(files, PersonaLayer.B_GLOBAL, STAMP)
        assertTrue("整理应成功：$r", r is PersonaOrganizeResult.Done)
        val row = PersonaChangeLedger.replay(1).first()
        assertEquals("这一行的署名", PersonaWho.ORGANIZE, row.who)
        assertEquals("来源写的是自整理", "自整理", row.source)
        assertTrue("落成了要记 applied", row.applied)
        assertTrue("理由里要带省了多少：${row.reason}", row.reason.contains("token"))
    }

    // ── 负例：判据④ 保全失败 ⇒ 回滚 ──

    @Test
    fun `负例_丢否定句的方案_没落盘就被拒`() {
        val files = files()
        val f = writeGlobal(files, bigPersona())
        val before = f.readText(Charsets.UTF_8)
        val lossy = { l: PersonaLayer, b: String, n: String ->
            val p = PersonaOrganize.planOf(l, b, n)
            p.copy(after = p.after.replace("不许在没读过文件的时候下结论。", ""))
        }
        val r = PersonaOrganize.organize(files, PersonaLayer.B_GLOBAL, STAMP, planner = lossy)
        assertTrue("丢了否定句必须拒：$r", r is PersonaOrganizeResult.Rejected)
        val why = (r as PersonaOrganizeResult.Rejected).reason
        assertTrue("要说清丢的是否定/例外句：$why", why.contains("否定/例外那句丢了"))
        assertEquals("拒了就必须一个字节没动", before, f.readText(Charsets.UTF_8))
        assertFalse("没落盘就不该留下归档（免得像整理了）",
            File(PersonaOrganize.archiveHome(f)!!, PersonaOrganize.archiveName(STAMP)).exists())
    }

    @Test
    fun `负例_盘上复验不过_自动回滚到整理前一个字节`() {
        val files = files()
        val f = writeGlobal(files, bigPersona())
        val before = f.readText(Charsets.UTF_8)
        val r = PersonaOrganize.organize(files, PersonaLayer.B_GLOBAL, STAMP,
            postCheck = { _, _ -> "故意让复验不过" })
        assertTrue("复验不过要报拒：$r", r is PersonaOrganizeResult.Rejected)
        val why = (r as PersonaOrganizeResult.Rejected).reason
        assertTrue("要说清是保全校验失败：$why", why.contains("保全校验失败"))
        assertTrue("要写明回滚结果：$why", why.contains("已回滚"))
        assertEquals("回滚后文件必须逐字节回到整理前", before, f.readText(Charsets.UTF_8))
        assertTrue("归档留着不删（遗忘＝降权，原文永不自动删）",
            File(PersonaOrganize.archiveHome(f)!!, PersonaOrganize.archiveName(STAMP)).isFile)
    }

    @Test
    fun `负例_指针指向别的归档_不算解出`() {
        val before = bigPersona()
        val plan = PersonaOrganize.planOf(PersonaLayer.B_GLOBAL, before, "甲.md")
        assertTrue("这份方案里有指针", plan.condensed.isNotEmpty())
        val c = plan.condensed.first()
        assertNull("换个归档名就该解不出",
            PersonaPointer.resolve(PersonaPointer("乙.md", c.pointer.line).mark(), "甲.md", before.split("\n")))
        val wrong = plan.copy(condensed = listOf(c.copy(pointer = PersonaPointer("乙.md", c.pointer.line))))
        assertTrue("保全四查要抓到跨归档指针",
            PersonaOrganize.preserveProblems(wrong, before.split("\n")).any { it.contains("别的归档") })
    }

    @Test
    fun `负例_收益不足就不整理_一个字节不动`() {
        val files = files()
        val f = writeGlobal(files, bigPersona())
        val before = f.readText(Charsets.UTF_8)
        val poor = { l: PersonaLayer, b: String, n: String -> PersonaOrganize.planOf(l, b, n).copy(after = b) }
        val r = PersonaOrganize.organize(files, PersonaLayer.B_GLOBAL, STAMP, planner = poor)
        assertTrue("省不到 20% 就不该整理：$r", r is PersonaOrganizeResult.NoGain)
        val why = (r as PersonaOrganizeResult.NoGain).reason
        assertTrue("理由要报收益：$why", why.contains("收益不足"))
        assertEquals("不整理就不动文件", before, f.readText(Charsets.UTF_8))
    }

    @Test
    fun `负例_归档同名绝不覆盖_宁可这次不整理`() {
        val files = files()
        val f = writeGlobal(files, bigPersona())
        val before = f.readText(Charsets.UTF_8)
        val home = PersonaOrganize.archiveHome(f)!!
        home.mkdirs()
        val existing = File(home, PersonaOrganize.archiveName(STAMP))
        existing.writeText("上一轮留下的原文，一个字都不许被盖掉", Charsets.UTF_8)
        val r = PersonaOrganize.organize(files, PersonaLayer.B_GLOBAL, STAMP)
        assertTrue("撞名必须拒：$r", r is PersonaOrganizeResult.Rejected)
        assertTrue((r as PersonaOrganizeResult.Rejected).reason.contains("归档写不进去"))
        assertEquals("原文件不动", before, f.readText(Charsets.UTF_8))
        assertEquals("旧归档不被覆盖", "上一轮留下的原文，一个字都不许被盖掉", existing.readText(Charsets.UTF_8))
    }

    // ── 铁栅与组织形态（纯方案层，不过预算闸）──

    /** 三铁栅①：已经是指针产物的行**原样带走**，不存在二次提炼的代码路径。 */
    @Test
    fun `三铁栅一_上一轮产物原样带走不再提炼`() {
        val marked = "- 这一句上一轮已经提炼过了。〔原文：20261105-000000.md#L7〕" +
            "后面再垫长的话只为把它顶过提炼线，看看会不会被二次提炼。".repeat(5)
        val before = lockedHead() + "\n## 规矩\n$marked\n" +
            (1..4).joinToString("\n") { longLine(it) } + "\n"
        val plan = PersonaOrganize.planOf(PersonaLayer.B_GLOBAL, before, "S.md")
        assertTrue("这一行本来就够长（够被提炼，否则用例没牙）",
            marked.length >= PersonaOrganize.LONG_LINE_CHARS)
        assertTrue("带指针的行不许进提炼产物",
            plan.condensed.none { it.original.contains(PersonaPointer.OPEN) })
        assertTrue("它必须原样留在产物里", plan.after.contains(marked))
    }

    /** 三铁栅②：事实节整节不做指针化（一条来源不足以把原话换成结论）。 */
    @Test
    fun `三铁栅二_单来源不进事实池`() {
        val before = lockedHead() + "\n## 规矩\n" + longLine(1) + "\n\n## 事实\n" +
            (1..3).joinToString("\n") { factLine(it) } + "\n"
        val plan = PersonaOrganize.planOf(PersonaLayer.B_GLOBAL, before, "S.md")
        assertTrue("事实行的长度够被提炼（否则用例没牙）",
            factLine(1).length >= PersonaOrganize.LONG_LINE_CHARS)
        assertTrue("事实节一行都不许被指针化", plan.condensed.none { it.original.contains("进货") })
        assertTrue("要留一句为什么不动：${plan.notes}", plan.notes.any { it.contains("事实池") })
        assertTrue("事实原话必须逐字还在", plan.after.contains(factLine(3)))
    }

    /** 「合并同类」＋去重：整理动的是组织形态，不是意思。 */
    @Test
    fun `合并同类与节内去重`() {
        val dup = "- 回复不超过三段。"
        val before = lockedHead() + "\n## 规矩\n" + longLine(1) + "\n$dup\n$dup\n$dup\n\n## 风格\n- 别用敬语。\n\n## 规矩\n" +
            longLine(2) + "\n$dup\n"
        val plan = PersonaOrganize.planOf(PersonaLayer.B_GLOBAL, before, "S.md")
        assertEquals("同名小节并到一处", 1, Regex("## 规矩").findAll(plan.after).count())
        assertEquals("重复行只留一条", 1, Regex("回复不超过三段").findAll(plan.after).count())
        assertTrue("合并按首次出现排（规矩在风格之前）",
            plan.after.indexOf("## 规矩") < plan.after.indexOf("## 风格"))
        assertTrue("去重要落进 diff：${plan.notes}", plan.notes.any { it.contains("去掉 3 行重复") })
        assertEquals("被去掉的都是重复行，保全校验应全过", emptyList<String>(),
            PersonaOrganize.preserveProblems(plan, before.split("\n")))
    }

    // ── 触发点的位置（红线：不能挂进唯一写口自己 ⇒ 整理递归）──

    @Test
    fun `触发点挂在人设写口之后_不挂在唯一写口里面`() {
        val apply = code("src/main/java/com/example/zhengdao/baopu/PersonaPatchApply.kt")
        val store = code("src/main/java/com/example/zhengdao/baopu/PersonaFiles.kt")
        assertTrue("补丁写口之后要触发整理", apply.contains("PersonaOrganize.maybeOrganizeAfterWrite"))
        assertFalse("唯一写口里不许出现整理（那是整理自己落盘用的通道 ⇒ 会自己套自己）",
            store.contains("PersonaOrganize"))
    }

    @Test
    fun `触发点只给结论不抛异常`() {
        val files = files()
        val r = PersonaOrganize.maybeOrganizeAfterWrite(files, PersonaLayer.B_GLOBAL)
        assertTrue("没有文件时给的是 NoGain：$r", r is PersonaOrganizeResult.NoGain)
        assertTrue("A 层同样不抛（拿文件就会抛，所以入口必须先拦）",
            PersonaOrganize.maybeOrganizeAfterWrite(files, PersonaLayer.A_KERNEL)
                is PersonaOrganizeResult.Rejected)
    }

    // ── helpers ──

    private fun files(): PersonaFiles = PersonaFiles(globalRoot, projectRoot)

    private fun writeGlobal(files: PersonaFiles, text: String): File {
        val f = files.globalFile()
        f.parentFile!!.mkdirs()
        f.writeText(text, Charsets.UTF_8)
        return f
    }

    private fun organizeBig(): PersonaOrganizeResult {
        val files = files()
        writeGlobal(files, bigPersona())
        return PersonaOrganize.organize(files, PersonaLayer.B_GLOBAL, STAMP)
    }

    private fun smallPersona(): String = lockedHead() + "\n## 规矩\n- 说话别绕。\n- 例子用项目里的。\n"

    private fun lockedHead(): String =
        "## 基本规矩\n- 先给结论，再给理由。\n- 不堆术语。\n- 不许编造事实。\n"

    private fun longLine(i: Int): String = "- 第${i}号：" + "这是一条很长的规矩，用来测试指针化。".repeat(9)

    private fun factLine(i: Int): String = "- 第${i}号事实：店主说周三进货。" + "另有细节要核对。".repeat(20)

    /** 一份**超预算**的真实形状人设：34 条长规矩（分在两个同名节）＋ 1 条否定句 ＋ 4 行重复 ＋ 3 条事实。 */
    private fun bigPersona(): String = buildString {
        append(lockedHead())
        append("\n## 规矩\n")
        for (i in 1..20) append(longLine(i)).append("\n")
        append("- 不许在没读过文件的时候下结论。\n")
        append("- 回复不超过三段。\n- 回复不超过三段。\n- 回复不超过三段。\n- 回复不超过三段。\n")
        append("\n## 风格\n")
        for (i in 21..30) append(longLine(i)).append("\n")
        append("\n## 事实\n")
        for (i in 1..3) append(factLine(i)).append("\n")
        append("\n## 规矩\n")
        for (i in 31..34) append(longLine(i)).append("\n")
    }

    private fun code(rel: String): String =
        File(rel).let { if (it.isFile) it else File("app/$rel") }
            .readText(Charsets.UTF_8)
            .replace("\r\n", "\n")
            .let { Regex("(?s)/\\*.*?\\*/").replace(it, "") }
            .let { Regex("(?m)^[ \\t]*//.*$").replace(it, "") }

    private companion object {
        /** 取**过去**的时间戳：回滚靠「最近一份备份」，测试注入的 stamp 不能比真实写入更晚。 */
        const val STAMP = "20260101-000000"
    }
}
