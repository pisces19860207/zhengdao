// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// CardValidate 批（2026-11-06）· **表单卡提交路径接上 212 的校验壳**。
//
// 派工单四条判据在这里各有一张嘴：
// · 非法输入拦并给原因 ⇒ 缺必填／类型错／越界／危险值 四探（每探都断言「拦住 ＋ 那句人话」，
//   不只看布尔：判据红了但原因咽了，模型照样只能瞎猜）；
// · 不许静默丢弃 ⇒ 「三处错＝合计一行＋三句原话」那探；
// · 合法输入 0 变化 ⇒ 完整卡照旧 `Ok`、指令键序＝模板字段序，且既有句子逐字没动；
// · 不接进 UI ⇒ 界面目录里 `CardValidate.planOf(` 只许出现在注释行里（212 批那句 KDoc 早就提过它，
//   那是话不是接线），真调用的行一条都不许有（U 批的地盘，本批一步都不许过）。
//
// 合计与首错是**壳算的**还是卡里自己数的，行为上看不出来——所以那一条走源级闸＋变异自证：
// 把 `CardValidate.planOf(` 摘掉、或把 `plan.needed` 换成 `rejects.size`，都要变红。
package com.example.zhengdao.baopu

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TeachFormCardValidateWiringTest {

    private val template by lazy { FormFixtures.template() }
    private val teachForm by lazy { src("src/main/java/com/example/zhengdao/baopu/TeachForm.kt") }

    private fun reasonsOf(card: FormCard, t: FormTemplate = template): List<String> =
        when (val p = TeachFormRules.precheck(t, card)) {
            is TeachFormRules.Precheck.Rejected -> p.reasons
            // Assert.fail 是 void（不是 Nothing），当表达式用会编译成 Any ⇒ 这里直接抛
            is TeachFormRules.Precheck.Ok -> throw AssertionError("本该拦下，却放行了：${p.ops.size} 条指令")
        }

    private fun value(card: FormCard, key: String, v: String): FormCard =
        card.copy(values = card.values + (key to v))

    private fun drop(card: FormCard, key: String): FormCard =
        card.copy(values = card.values - key)

    // ── 判据①：四类非法输入各拦一条，且给得出原因 ──────────────────────────

    @Test
    fun `负例一_缺必填_拦下并点名`() {
        val reasons = reasonsOf(drop(FormFixtures.card(), "char_name"))
        assertEquals("还差 1 项，先修「角色名」", reasons.first())
        assertTrue("要说缺哪个字段：$reasons", reasons.any { it.contains("缺「角色名」（char_name）") })
    }

    @Test
    fun `负例二_类型错_声明了数字给了文字`() {
        val t = template.copy(params = template.params + FormParamDecl("char_name", "角色名", TeachParamShape.NUMBER))
        val reasons = reasonsOf(FormFixtures.card(), t)
        assertTrue(
            "要说清要什么形状：$reasons",
            reasons.any { it.contains("「角色名」的值形状不对（要求 number）：小明") },
        )
        assertEquals("还差 1 项，先修「角色名」", reasons.first())
    }

    @Test
    fun `负例三_越界_超出录下来的长度上限`() {
        val reasons = reasonsOf(value(FormFixtures.card(), "char_name", "小明小明小明"), capped(template))
        assertTrue("要说上限与现长：$reasons", reasons.any { it.contains("「角色名」超过长度上限 4（现在 6 字）") })
        assertEquals("还差 1 项，先修「角色名」", reasons.first())
    }

    @Test
    fun `负例四_危险值_明文凭证拦下且不泄露原文`() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJl"
        val reasons = reasonsOf(value(FormFixtures.card(), "char_name", jwt))
        assertTrue("要说这是凭证形状：$reasons", reasons.any { it.contains("「角色名」值疑似明文凭证") })
        val joined = reasons.joinToString("｜")
        assertFalse("原因里不许有完整凭证：$joined", joined.contains(jwt))
        assertTrue("脱敏后仍要看得出是哪一串：$joined", joined.contains("ey") && joined.contains("*"))
    }

    // ── 判据②：不许静默丢弃（一条错＝一句原话，合计另算）────────────────────

    @Test
    fun `三处错_合计一行加三句原话_一条都不咽`() {
        val card = drop(FormFixtures.card(), "char_name")
            .let { value(it, "style", "赛博") }
            .let { value(it, "ghost_key", "x") }
        val reasons = reasonsOf(card)
        assertEquals("合计行 ＋ 每一处的原话：$reasons", 4, reasons.size)
        assertTrue(reasons.any { it.contains("缺「角色名」") })
        assertTrue(reasons.any { it.contains("形状不对") })
        assertTrue(reasons.any { it.contains("ghost_key") })
    }

    @Test
    fun `首错按模板字段序_不按错的先后`() {
        // 多余键那条先进清单（她手滑多塞了一个），但「先修」要给**从上往下第一个空**——
        // 这是壳 `jumpTarget` 的判据，卡里抄一份就会给出 ghost_key。
        val reasons = reasonsOf(value(drop(FormFixtures.card(), "char_name"), "ghost_key", "x"))
        assertEquals("还差 2 项，先修「角色名」", reasons.first())
    }

    @Test
    fun `段里的值不合规_精确原因不折成没填全`() {
        val t = template.copy(
            params = template.params + FormParamDecl("worldbook1_title", "第 1 条标题", TeachParamShape.DATE),
        )
        val reasons = reasonsOf(value(FormFixtures.card(), "worldbook1_title", "河湾"), t)
        assertTrue("要照旧说这段不动手：$reasons", reasons.any { it.contains("第 1 段") && it.contains("没填全") })
        assertTrue(
            "另起一句说清为什么（只留「没填全」会让她去补一个本来就填着的空）：$reasons",
            reasons.any { it.contains("「第 1 条标题」的值形状不对（要求 date）：河湾") },
        )
    }

    // ── 派工单「合法输入 0 变化」──────────────────────────────────────────

    @Test
    fun `合法卡_照旧过预检_八条指令键序不变`() {
        val p = TeachFormRules.precheck(template, FormFixtures.card())
        if (p is TeachFormRules.Precheck.Rejected) fail("合法卡被拦了：${p.reasons}")
        val ops = (p as TeachFormRules.Precheck.Ok).ops
        assertEquals(8, ops.size)
        assertEquals(template.allFields.map { it.key }, ops.map { it.field.key })
    }

    @Test
    fun `既有的话一个字不改_缺段与拿错卡`() {
        val seg = reasonsOf(FormFixtures.cardFirstSegmentOnly())
        val b = template.blocks.first { it.index == 2 }
        assertTrue(
            "缺段那句要逐字还在：$seg",
            seg.any { it == "第 2 段（${b.label}）没填全：${b.fields.joinToString("、") { it.label }}" },
        )
        val extra = reasonsOf(value(FormFixtures.card(), "unknown_key", "x"))
        assertTrue(
            "拿错卡那句要逐字还在：$extra",
            extra.any { it == "卡里有模板没有的字段：unknown_key（是不是拿错了卡？）" },
        )
    }

    // ── 判据④：接线（源级）＋ 没接进 UI ──────────────────────────────────

    @Test
    fun `接线_壳真的在数_合计与首错都取自planOf`() {
        val head = bodyOf(teachForm, "private fun modelLines(")
        assertTrue("没调用壳：$head", head.contains("CardValidate.planOf("))
        assertTrue("条数要取壳算的：$head", head.contains("plan.needed"))
        assertTrue("首错要取壳定的：$head", head.contains("plan.firstErrorKey"))
        assertFalse("卡里不许自己数一遍：$head", head.contains("rejects.size"))
    }

    @Test
    fun `接线_四类错各归各档_上限数不塞整句`() {
        assertTrue(teachForm.contains("CardFieldError(f.key, CardErrorKind.REQUIRED)"))
        assertTrue(teachForm.contains("CardFieldError(f.key, kind)"))
        assertTrue(teachForm.contains("CardFieldError(f.key, CardErrorKind.CUSTOM, line)"))
        // TOO_MANY 的 detail 按 212 的契约是**上限数**：整句塞进去，U 批 format 出来就是「最多选 3 项」
        assertTrue("越界那条要把上限数放进 detail", TOO_MANY_CONTRACT.containsMatchIn(teachForm))
        assertTrue("值侧两道判据要串起来", teachForm.contains(VALUE_CHAIN))
    }

    @Test
    fun `判据三_没有接进界面`() {
        val ui = uiSources()
        assertTrue("界面目录只读到 ${ui.size} 个 .kt ⇒ 路径八成写错了，这条探成了摆设", ui.size > 20)
        val calls = callLines(ui, "CardValidate.planOf(") + callLines(ui, "TeachFormRules.precheck(")
        assertEquals("校验壳接进界面是 U 批的事，本批一步都不许过：$calls", emptyList<String>(), calls)
        assertTrue(
            "扫描没扫到那行 KDoc ⇒ 上面那个「空」是瞎扫出来的，不算数",
            commentLines(ui, "CardValidate.planOf(").isNotEmpty(),
        )
    }

    // ── 危险值判据本身（沿用 196 的名单，一条不新增）───────────────────────

    @Test
    fun `凭证判据_认结构不认启发`() {
        listOf(
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abc",
            "AKIAIOSFODNN7EXAMPLE",
            "Bearer abcdefg12345",
        ).forEach { assertTrue("该认出 $it", TeachSafety.credentialShapeReason(it) != null) }
        listOf("小明", "一条大河", "PD20261106001", "京A12345", "古风", "").forEach {
            assertTrue("不该误拒「$it」（查货单号就是字母＋数字）", TeachSafety.credentialShapeReason(it) == null)
        }
    }

    // ── 变异自证（防恒真）────────────────────────────────────────────────

    @Test
    fun `变异自证_五处改坏各自变红`() {
        val head = bodyOf(teachForm, "private fun modelLines(")
        assertFalse("摘掉壳的调用要红", wired(head.replace("CardValidate.planOf(", "localCount(")))
        assertFalse("卡里自己数条数要红", wired(head.replace("plan.needed", "rejects.size")))
        assertFalse("上限数塞成整句要红", TOO_MANY_CONTRACT.containsMatchIn(teachForm.replace("max.toString()", "line")))
        assertFalse(
            "值侧少一道判据要红",
            teachForm.replace("lengthReject(f, op.value) ?: ", "").contains(VALUE_CHAIN),
        )
        // 「不接进 UI」那条若只会数注释行，插一行真调用也照样绿——所以拿假文件验它咬得住
        val wiredUi = callLines(
            uiSources() + ("FakeScreen.kt" to "        val plan = CardValidate.planOf(errors, order)"),
            "CardValidate.planOf(",
        )
        assertFalse("界面里多一行真调用要红", wiredUi.isEmpty())
    }

    private fun wired(head: String): Boolean =
        head.contains("CardValidate.planOf(") && head.contains("plan.needed") &&
            head.contains("plan.firstErrorKey") && !head.contains("rejects.size")

    // ── 小工具 ──────────────────────────────────────────────────────────

    /** 界面目录（U 批的地盘）里的 (文件名, 源码)。 */
    private fun uiSources(): List<Pair<String, String>> {
        val dir = File("src/main/java/com/example/zhengdao/ui/baopu").let {
            if (it.isDirectory) it else File("app/src/main/java/com/example/zhengdao/ui/baopu")
        }
        assertTrue("找不到界面目录：${dir.path}", dir.isDirectory)
        return dir.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .map { it.name to it.readText() }
            .toList()
    }

    /** 含 [token] 的**真调用**行：`//`／`*` 打头的注释行不算（212 批在 KDoc 里提过 planOf 的出处，那是话不是接线）。 */
    private fun callLines(sources: List<Pair<String, String>>, token: String): List<String> =
        sources.flatMap { (name, text) ->
            text.lines().filter { it.contains(token) && !isCommentLine(it) }.map { "$name: ${it.trim()}" }
        }

    /** 含 [token] 的注释行——拿它当对照，证明上面那个「空」是扫出来的、不是路径写错扫不到。 */
    private fun commentLines(sources: List<Pair<String, String>>, token: String): List<String> =
        sources.flatMap { (name, text) ->
            text.lines().filter { it.contains(token) && isCommentLine(it) }.map { "$name: ${it.trim()}" }
        }

    private fun isCommentLine(line: String): Boolean {
        val t = line.trim()
        return t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
    }

    private fun capped(t: FormTemplate, max: Int = 4): FormTemplate =
        t.copy(fields = t.fields.map { if (it.key == "char_name") it.copy(maxLen = max) else it })

    private fun src(rel: String): String {
        val f = File(rel).let { if (it.exists()) it else File("app/$rel") }
        assertTrue("找不到 $rel：${f.path}", f.isFile)
        return f.readText()
    }

    /** 从 [marker] 到下一个同级函数（源级断言只看这一段，改坏别处蒙不过去）。 */
    private fun bodyOf(source: String, marker: String): String {
        val at = source.indexOf(marker)
        assertTrue("找不到 $marker", at >= 0)
        val rest = source.substring(at)
        val next = Regex("\n    (private )?fun ").find(rest, marker.length)
        return rest.substring(0, next?.range?.first ?: rest.length)
    }

    private companion object {
        /** 越界那条的错误形状：键 ＋ TOO_MANY ＋ 上限数（不是整句）。 */
        val TOO_MANY_CONTRACT = Regex("CardFieldError\\(f\\.key, CardErrorKind\\.TOO_MANY, max\\.toString\\(\\)\\)")

        /** 值侧两道新判据串在一处（叶子与段共用，别在两处各写一遍）。 */
        const val VALUE_CHAIN = "lengthReject(f, op.value) ?: credentialReject(f, op.value)"
    }
}
