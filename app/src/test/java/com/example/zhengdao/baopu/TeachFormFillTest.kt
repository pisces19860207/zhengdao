// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百零一批 · 填表器与批量跑卡的单测（正例 ＋ 六条负例全部贴红）。
//
// 判据口径：所有断言都打在**行为**上（回读了什么、报了什么、落盘了什么），
// 不打在「代码里有没有某个字符串」上——那类自检在 196 就已经被证明会恒真。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TeachFormFillTest {

    private fun tempDir(): File = Files.createTempDirectory("form201").toFile()

    // ── 正例：5 字段一录一放，回读全一致 ────────────────────────────────────

    @Test
    fun `正例_五字段填完回读一致_含重复块两段`() {
        val dom = FormFixtures.dom()
        val t = FormFixtures.template(dom)
        val card = FormFixtures.card()
        val outcome = FormFiller(dom).fillCard(t, card)

        assertTrue("五类控件（8 个字段）应全过：${outcome.fields.filter { !it.ok }.joinToString { it.line() }}", outcome.ok)
        assertEquals(8, outcome.fields.size)
        assertTrue("每一条都要有回读值", outcome.fields.all { it.actual.isNotBlank() })
        assertEquals(listOf<Int>(), outcome.missingSegments)
        // 真值落到页面上（不是只在报告里说填了）
        assertEquals("小明", dom.elements.first { it.nameAttr == "char_name" }.value)
        assertTrue(dom.elements.first { it.nameAttr == "persona" }.value.contains("怕水，但会撑船。"))
        assertEquals("1", dom.elements.first { it.nameAttr == "style" }.value)
        assertEquals("f", dom.elements.first { it.nameAttr == "gender" && it.value == "f" }.value)
        assertTrue(dom.elements.first { it.nameAttr == "gender" && it.value == "f" }.checked)
        assertTrue(
            "第 2 段内容要整段落下去：${dom.elements.first { it.nameAttr == "worldbook[1][content]" }.value}",
            dom.elements.first { it.nameAttr == "worldbook[1][content]" }.value.startsWith("摆渡人"),
        )
    }

    @Test
    fun `正例_换一张卡同名模板照填_参数化复用`() {
        val dom = FormFixtures.dom()
        val t = FormFixtures.template(dom)
        val second = FormFixtures.card("小红").let { c ->
            c.copy(
                values = c.values + mapOf(
                    "char_name" to "小红",
                    "persona" to "十七岁。\n会游泳。",
                    "style" to "江湖",
                    "gender" to "男",
                ),
            )
        }
        val outcome = FormFiller(dom).fillCard(t, second)
        assertTrue(outcome.ok)
        assertEquals("小红", dom.elements.first { it.nameAttr == "char_name" }.value)
        assertEquals("2", dom.elements.first { it.nameAttr == "style" }.value)
        assertTrue(dom.elements.first { it.nameAttr == "gender" && it.value == "m" }.checked)
    }

    // ── 负例 1：点到「创建/保存/发布」按钮（诱饵按钮）──────────────────────

    @Test
    fun `负例1_诱饵按钮不进字段表_全程零点击_零填按钮`() {
        val dom = FormFixtures.dom(withDecoys = true)
        val scan = TeachFormRules.buildFields(dom.scan().orEmpty())
        val t = TeachFormRules.templateFrom(scan, "风月制卡", FormFixtures.START_URL, listOf("demo.example"), 1L)
        val outcome = FormFiller(dom).fillCard(t, FormFixtures.card())

        assertTrue("正常字段照填", outcome.ok)
        assertEquals("端口没有点击通道", 0, dom.clickCount)
        assertTrue("提交类控件不许被填过：${dom.poisonFilled}", dom.poisonFilled.isEmpty())
        assertTrue(
            "诱饵按钮不许出现在填表落点里：${dom.fillCalls}",
            dom.fillCalls.none { it.startsWith("BUTTON") },
        )
        // 三个诱饵都以「不点/不填」的理由被排除
        assertEquals(3, scan.excluded.size)
    }

    @Test
    fun `负例1_模板指到提交按钮也拒填_动手前就停`() {
        // 手改坏了的模板：字段定位器指到「创建」按钮上
        val dom = FormFixtures.dom(withDecoys = true)
        val btn = dom.elements.first { it.id == "btn-create" }
        val field = FormField(
            key = "char_name",
            label = "角色名",
            control = FormControl.SINGLE_LINE,
            locators = listOf(TeachLocator(TeachLocatorKind.TESTID, btn.testid!!)),
        )
        val t = FormTemplate(
            name = "坏模板",
            startUrl = FormFixtures.START_URL,
            allowedDomains = listOf("demo.example"),
            fields = listOf(field),
        )
        val outcome = FormFiller(dom).fillCard(t, FormCard("坏卡", mapOf("char_name" to "小明")))
        assertFalse("填到按钮上必须失败", outcome.ok)
        assertTrue(
            "原因要说是提交类：${outcome.fields.first().reason}",
            outcome.fields.first().reason.orEmpty().contains("提交/创建"),
        )
        assertTrue("一个字节都不许落到按钮上", dom.fillCalls.isEmpty())
    }

    // ── 负例 2：填进密码/验证码框 ──────────────────────────────────────────

    @Test
    fun `负例2_密码框不进字段表_也不许被填`() {
        val els = FormFixtures.elements() + FakeFormEl(
            tag = "INPUT", nameAttr = "account_pwd", label = "密码", type = "password", id = "pwd",
        )
        val dom = FakeFormDom(els.toMutableList())
        val scan = TeachFormRules.buildFields(dom.scan().orEmpty())
        assertTrue("密码框一个都不许进字段表", scan.fields.none { it.inputName == "account_pwd" })
        assertTrue(scan.excluded.any { it.reason.contains("密码") })

        // 手改坏的模板硬指过去：探针层再拒一次（防御纵深）
        val pwdField = FormField(
            key = "account_pwd",
            label = "密码",
            control = FormControl.SINGLE_LINE,
            locators = listOf(TeachLocator(TeachLocatorKind.ELEM_ID, "pwd")),
        )
        val t = FormTemplate(
            name = "坏模板", startUrl = FormFixtures.START_URL, allowedDomains = listOf("demo.example"),
            fields = listOf(pwdField),
        )
        val outcome = FormFiller(dom).fillCard(t, FormCard("坏卡", mapOf("account_pwd" to "hunter2")))
        assertFalse(outcome.ok)
        assertTrue(
            "原因要点明密码/凭证：${outcome.fields.first().reason}",
            outcome.fields.first().reason.orEmpty().contains("拒填"),
        )
        assertEquals("密码框里一个字都不许有", "", dom.elements.first { it.id == "pwd" }.value)
    }

    // ── 负例 3：富文本被塞成纯文本（丢了换行/结构）─────────────────────────

    @Test
    fun `负例3_富文本降级要红_不能报填好`() {
        val dom = FormFixtures.dom()
        // 模拟站点编辑器把分段吃掉：换行变空格
        dom.elements.first { it.nameAttr == "persona" }.let {
            val replacement = FakeFormEl(
                tag = "TEXTAREA", nameAttr = "persona", label = "人设", id = "f-persona",
                store = { v -> v.lines().joinToString(" ") },
            )
            dom.elements[dom.elements.indexOf(it)] = replacement
        }
        val t = FormFixtures.template(dom)
        val outcome = FormFiller(dom).fillCard(t, FormFixtures.card())
        val persona = outcome.fields.first { it.key == "persona" }
        assertFalse("降级的字段必须失败", persona.ok)
        assertTrue(
            "原因要点明富文本降级：${persona.reason}",
            persona.reason.orEmpty().contains("富文本被塞成纯文本"),
        )
        assertFalse("整张卡不许报填好", outcome.ok)
    }

    // ── 负例 4：多段块只填第一段，其余静默丢失 ─────────────────────────────

    @Test
    fun `负例4_只给第一段的卡_预检就拦下_一个页面动作都不做`() {
        val dom = FormFixtures.dom()
        val t = FormFixtures.template(dom)
        val outcome = FormFiller(dom).fillCard(t, FormFixtures.cardFirstSegmentOnly())
        assertFalse(outcome.ok)
        assertTrue("原因要指名第 2 段：${outcome.stopReason}", outcome.stopReason.orEmpty().contains("第 2 段"))
        assertTrue("预检拦下 ⇒ 不许动页面", dom.fillCalls.isEmpty())
    }

    @Test
    fun `负例4_页面静默丢第二段_要报出哪段没填上`() {
        val dom = FormFixtures.dom()
        // 第 2 段内容框是「装饰框」：填了不报错、值也不落
        val idx = dom.elements.indexOfFirst { it.nameAttr == "worldbook[1][content]" }
        dom.elements[idx] = FakeFormEl(
            tag = "TEXTAREA", nameAttr = "worldbook[1][content]", label = "第 2 条内容", ignoreFill = true,
        )
        val t = FormFixtures.template(dom)
        val outcome = FormFiller(dom).fillCard(t, FormFixtures.card())

        assertFalse("静默丢失必须被抓到", outcome.ok)
        assertEquals("要报出缺的是哪一段", listOf(2), outcome.missingSegments)
        val lost = outcome.fields.first { it.key == "worldbook2_content" }
        assertFalse(lost.ok)
        assertTrue("空回读＝没填上：${lost.reason}", lost.reason.orEmpty().contains("回读不一致"))
    }

    // ── 负例 5：回读不一致却报「填好」─────────────────────────────────────

    @Test
    fun `负例5_回读不一致必须失败_不许报填好`() {
        val dom = FormFixtures.dom()
        val t = FormFixtures.template(dom)
        // 页面把名字截断了（maxlength 之类）
        dom.readOverride = { el, control ->
            if (el.nameAttr == "char_name") FormReadback(text = "小", value = "小") else null
        }
        val outcome = FormFiller(dom).fillCard(t, FormFixtures.card())
        assertFalse("回读不一致绝不许报 ok", outcome.ok)
        val name = outcome.fields.first { it.key == "char_name" }
        assertFalse(name.ok)
        assertEquals("期望值与回读值都要留证据", "小", name.actual)
        assertTrue(name.expected.startsWith("小明"))
        assertTrue("整卡原因要能看懂：${outcome.stopReason}", outcome.stopReason != null)
    }

    @Test
    fun `负例5_页面回 read 失败也算没填好`() {
        val dom = object : FormDom by FormFixtures.dom() {
            override fun read(locators: List<TeachLocator>, control: FormControl): FormReply =
                FormReply.Failed("页面超时")
        }
        val t = FormFixtures.template(FormFixtures.dom())
        val outcome = FormFiller(dom).fillCard(t, FormFixtures.card())
        assertFalse(outcome.ok)
        assertTrue(outcome.fields.all { it.reason.orEmpty().contains("回读失败") })
    }

    // ── 负例 6：批量重名覆盖（截图/落盘）───────────────────────────────────

    @Test
    fun `负例6_批量重名不覆盖_第二张落成2号`() {
        val dom = FormFixtures.dom()
        val t = FormFixtures.template(dom)
        val root = File(tempDir(), "填表")
        val shots = mutableListOf<String>()
        val batch = FormBatch(
            template = t,
            cards = listOf(
                FormFixtures.card("角色卡").let { it.copy(name = "角色卡", values = it.values + ("char_name" to "小明")) },
                FormFixtures.card("角色卡").let { it.copy(name = "角色卡", values = it.values + ("char_name" to "小红")) },
            ),
            filler = FormFiller(dom),
            evidence = FileFormEvidence(root),
            shot = { name, _ ->
                "shot-$name.png".also { shots += it }
            },
            clock = { 1_700_000_000_000L },
        )

        val first = batch.next()
        assertTrue(first is FormBatch.Step.Filled)
        assertEquals("待发布 · 角色卡", (first as FormBatch.Step.Filled).card.title)
        assertTrue("上一张没发不许越过去", batch.next() is FormBatch.Step.Blocked)
        assertTrue(batch.advance())

        val second = batch.next()
        assertTrue(second is FormBatch.Step.Filled)
        assertEquals("第二张必须改名，不许覆盖第一张", "待发布 · 角色卡-2", (second as FormBatch.Step.Filled).card.title)
        assertEquals("截图纸也各一张", listOf("shot-角色卡.png", "shot-角色卡-2.png"), shots)

        val dirs = root.listFiles().orEmpty().filter { it.isDirectory }
        assertEquals("两张卡两份证据目录", 2, dirs.size)
        // 按名字认，不按排序认：「角色卡-2-<时间>」在字典序里会排到「角色卡-<时间>」前面（'-' < '0'）
        val secondDir = dirs.firstOrNull { it.name.startsWith("角色卡-2-") }
        val firstDir = dirs.firstOrNull { it !== secondDir }
        assertNotNull("第二张要落成 -2 号目录：${dirs.map { it.name }}", secondDir)
        assertNotNull("第一张要落成原名目录：${dirs.map { it.name }}", firstDir)
        val firstText = File(firstDir!!, "fields.txt").readText()
        val secondText = File(secondDir!!, "fields.txt").readText()
        assertTrue("第一份没被第二张盖掉：$firstText", firstText.contains("小明") && !firstText.contains("小红"))
        assertTrue("第二份是自己的：$secondText", secondText.contains("小红"))
    }

    // ── 批量流程：逐张核对、逐张等你点 ────────────────────────────────────

    @Test
    fun `批量三张_逐张出卡_每张都有回读与截图`() {
        val cards = listOf(
            FormFixtures.card("一号").let { it.copy(values = it.values + ("char_name" to "甲")) },
            FormFixtures.card("二号").let { it.copy(values = it.values + ("char_name" to "乙")) },
            FormFixtures.card("三号").let { it.copy(values = it.values + ("char_name" to "丙")) },
        )
        val dom = FormFixtures.dom()
        val seen = mutableListOf<PublishCard>()
        val batch = FormBatch(
            template = FormFixtures.template(dom),
            cards = cards,
            filler = FormFiller(dom),
            shot = { name, _ -> "/tmp/$name.png" },
        )
        cards.indices.forEach { i ->
            val step = batch.next()
            assertTrue("第 ${i + 1} 张应出卡：$step", step is FormBatch.Step.Filled)
            val card = (step as FormBatch.Step.Filled).card
            assertNotNull(card.shotPath)
            assertTrue("每张卡都要有回读行", card.rows.all { it.ok } && card.rows.size == 8)
            assertTrue("每张卡都带「复制全文」的内容", card.copyText.contains(card.title.removePrefix("待发布 · ")))
            seen += card
            assertTrue(batch.next() is FormBatch.Step.Blocked) // 没点发布不许下一张
            assertTrue(batch.advance())
        }
        assertTrue(batch.next() is FormBatch.Step.Done)
        assertEquals(3, seen.size)
        assertEquals(3, seen.map { it.title }.toSet().size)
        assertTrue(batch.summary().contains("已出卡 3/3 张"))
    }

    @Test
    fun `待发布卡_两个动作齐全_没有第三个动作`() {
        val dom = FormFixtures.dom()
        val t = FormFixtures.template(dom)
        val outcome = FormFiller(dom).fillCard(t, FormFixtures.card())
        val card = TeachFormRules.publishCard(t, FormFixtures.card(), outcome, "/tmp/shot.png")

        assertEquals("待发布 · 小明", card.title)
        assertEquals(FormFixtures.START_URL, card.openUrl)
        assertTrue("复制全文里要有全部字段", card.copyText.contains("角色名：小明"))
        assertTrue(card.copyText.contains("〔第 2 段"))
        assertEquals(8, card.rows.size)
        assertTrue(card.ok)
        assertTrue("没有红行", card.badRows.isEmpty())
        assertTrue("截图带上了", card.shotPath!!.endsWith("shot.png"))
    }

    @Test
    fun `待发布卡_有红行时不许说ok`() {
        val dom = FormFixtures.dom()
        val t = FormFixtures.template(dom)
        dom.readOverride = { el, _ ->
            if (el.nameAttr == "char_name") FormReadback("小红") else null
        }
        val outcome = FormFiller(dom).fillCard(t, FormFixtures.card())
        val card = TeachFormRules.publishCard(t, FormFixtures.card(), outcome, null)
        assertFalse(card.ok)
        assertEquals(1, card.badRows.size)
        assertTrue("要说明截图没取到", card.notes.any { it.contains("截图") })
        assertNull("没截图就别假装有", card.shotPath)
    }

    @Test
    fun `证据_每字段一行且失败带原因`() {
        val root = tempDir()
        val dom = FormFixtures.dom()
        dom.readOverride = { el, _ -> if (el.nameAttr == "char_name") FormReadback("小红") else null }
        val sink = FileFormEvidence(root)
        val dir = sink.begin("小明", "20261025-120000")
        assertNotNull(dir)
        val outcome = FormFiller(dom).fillCard(FormFixtures.template(dom), FormFixtures.card())
        outcome.fields.forEach { sink.field(dir, it) }
        sink.finish(dir, outcome)

        val lines = File(dir!!, "fields.txt").readLines().filter { it.isNotBlank() }
        assertEquals(8, lines.size)
        assertTrue(lines.any { it.startsWith("✗") && it.contains("回读不一致") })
        assertTrue(lines.count { it.startsWith("✓") } == 7)
        assertTrue(File(dir, "card.json").readText().contains("\"ok\":false"))
    }
}
