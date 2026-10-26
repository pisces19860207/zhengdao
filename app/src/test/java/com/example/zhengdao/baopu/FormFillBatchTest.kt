// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百零一批 ① · `form_fill` 的 batch 分支（全 JVM，假 DOM ＋ 临时工作区）。
//
// 这一条是 201 那三层（规则/填表器/批量）的**第一个工具出口**：之前 `FormBatch` 合进了
// main 却一个调用点都没有。测试盯的是出口本身的四件事——
//   · 一次只填一张（不放行 ⇒ Blocked，页面一个字段都不动）；
//   · 每张卡带自己的截图与逐字段回读记录，落 `产物/填表/`，重名加 `-2` 不覆盖（负例 6）；
//   · 缺段/多余字段/选项表里没有 ⇒ 这张卡判失败并说清是哪张（负例 4、缺一张报哪张）；
//   · 回执里 `submitted` 恒 false、卡只有「复制全文／打开页面」两个动作（负例 1 的出口面）。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.agent.FormFillTool
import com.example.zhengdao.baopu.agent.ToolOutcome
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FormFillBatchTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val url = FormFixtures.START_URL

    /** 一张完整的卡（8 格全给）；[override] 用来做坏卡（缺段、错选项、多余字段）。 */
    private fun cardObj(name: String, override: Map<String, String> = emptyMap()): JSONObject {
        val values = linkedMapOf(
            "char_name" to "小明",
            "persona" to "十六岁，住在河边。\n怕水，但会撑船。\n嘴上不认。",
            "style" to "古风",
            "gender" to "女",
            "worldbook1_title" to "河湾",
            "worldbook1_content" to "一条大河\n两座石桥",
            "worldbook2_title" to "老周",
            "worldbook2_content" to "摆渡人\n欠他三块钱",
        )
        for ((k, v) in override) {
            if (v.isEmpty()) values.remove(k) else values[k] = v
        }
        return JSONObject().put("card", name).put(
            "values",
            JSONObject().apply { values.forEach { (k, v) -> put(k, v) } },
        )
    }

    private fun batch(
        action: String,
        cards: List<JSONObject> = emptyList(),
        sessionId: String? = null,
        name: String? = null,
        extra: Pair<String, Any>? = null,
    ): JSONObject = JSONObject().apply {
        put("action", action)
        if (cards.isNotEmpty()) put("cards", JSONArray().apply { cards.forEach { put(it) } })
        sessionId?.let { put("sessionId", it) }
        name?.let { put("name", it) }
        extra?.let { put(it.first, it.second) }
    }

    private fun run(
        batch: JSONObject,
        dom: FakeFormDom,
        ws: File,
        shot: (String, Int) -> String? = { name, i -> "证道/截屏/$name-$i.png" },
    ): ToolOutcome = FormFillTool.execute(
        JSONObject().put("batch", batch), dom, ws, { url }, shot,
    )

    private fun ok(out: ToolOutcome): JSONObject {
        assertTrue("批量分支必须给成功回执，实际 $out", out is ToolOutcome.Ok)
        return JSONObject((out as ToolOutcome.Ok).text)
    }

    private fun err(out: ToolOutcome): String {
        assertTrue("必须直接失败（不许悄悄当成没批量）", out is ToolOutcome.Failed)
        return (out as ToolOutcome.Failed).error
    }

    /** 一张一张填到底（每张之间 advance ＝ 用户在页面上点过创建）。 */
    private fun drain(dom: FakeFormDom, ws: File, cards: List<JSONObject>): List<JSONObject> {
        val out = mutableListOf(ok(run(batch("start", cards), dom, ws)))
        while (!out.last().getBoolean("finished")) {
            ok(run(batch("advance", sessionId = out.last().getString("sessionId")), dom, ws))
            out += ok(run(batch("next", sessionId = out.last().getString("sessionId")), dom, ws))
        }
        return out
    }

    /** `产物/填表/` 下的卡目录（名字里带卡名与时间戳）。 */
    private fun evidenceDirs(ws: File): List<File> =
        File(ws, "产物/填表").listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()

    /** 按卡名找它的落盘目录（目录名按 unicode 排，跟填的顺序无关，只能读 card.json 认）。 */
    private fun dirForCard(ws: File, name: String): File =
        evidenceDirs(ws).first {
            JSONObject(File(it, "card.json").readText(Charsets.UTF_8)).getString("card") == name
        }

    // ── 正例：start 现扫排队并填第一张 ────────────────────────────────────────

    @Test
    fun `正例_start现扫当前页成模板并填第一张_回执带卡与截图`() {
        val dom = FormFixtures.dom()
        val ws = tmp.newFolder("ws")
        val j = ok(
            run(
                batch("start", name = "风月制卡", cards = listOf(cardObj("甲"), cardObj("乙"), cardObj("丙"))),
                dom, ws,
            ),
        )
        assertEquals("batch", j.getString("mode"))
        assertEquals("start", j.getString("action"))
        assertEquals("风月制卡", j.getString("template"))
        assertFalse("回执不许宣布已提交", j.getBoolean("submitted"))
        assertEquals(3, j.getInt("total"))
        assertEquals(1, j.getInt("cardsOut"))
        assertEquals("第 1 / 3 张", j.getString("progress"))
        assertTrue("没被规则挡下才该有卡", j.isNull("blocked"))

        val card = j.getJSONObject("card")
        assertEquals("待发布 · 甲", card.getString("title"))
        assertTrue(card.getBoolean("ok"))
        assertEquals(8, card.getJSONArray("rows").length())
        assertEquals(
            listOf("复制全文", "打开页面"),
            (0 until card.getJSONArray("actions").length()).map { card.getJSONArray("actions").getString(it) },
        )
        assertTrue("截图带的是这一张的名字：${card.getString("shotPath")}",
            card.getString("shotPath").contains("甲"))

        // 页面：8 格都动手了，一个按钮都没点、提交类控件一次没被填
        assertEquals(8, dom.fillCalls.size)
        assertEquals(0, dom.clickCount)
        assertTrue(dom.poisonFilled.isEmpty())

        // 落盘：这一张的逐字段回读记录（报告 §3「不信任我填了」的那份证据）
        val dirs = evidenceDirs(ws)
        assertEquals("一张卡一个目录：$dirs", 1, dirs.size)
        val fields = File(dirs[0], "fields.txt").readText(Charsets.UTF_8)
        assertEquals(8, fields.lines().count { it.isNotBlank() })
        assertFalse("记录里不许有红行：\n$fields", fields.contains("✗"))
        val meta = JSONObject(File(dirs[0], "card.json").readText(Charsets.UTF_8))
        assertTrue(meta.getBoolean("ok"))
        assertEquals(8, meta.getInt("okFields"))
    }

    // ── 逐张核对：不放行就不许碰第二张 ────────────────────────────────────────

    @Test
    fun `逐张核对_上一张还挂着_next被拦且页面不动`() {
        val dom = FormFixtures.dom()
        val ws = tmp.newFolder("ws")
        val first = ok(run(batch("start", cards = listOf(cardObj("甲"), cardObj("乙"))), dom, ws))
        val sid = first.getString("sessionId")
        val fillsAfterFirst = dom.fillCalls.size

        val second = ok(run(batch("next", sessionId = sid), dom, ws))
        assertFalse("没放行就该有 blocked 原因", second.isNull("blocked"))
        assertEquals("拦下时不许多填一格", fillsAfterFirst, dom.fillCalls.size)
        assertEquals("还挂着上一张", 1, second.getInt("cardsOut"))
        // 被拦时仍把挂着的那张卡附上：用户要看的正是它
        assertEquals("待发布 · 甲", second.getJSONObject("card").getString("title"))
        assertTrue("回执要把「你不说放行就不填第二张」念出来：${second.getString("note")}",
            second.getString("note").contains("你不说放行"))
    }

    @Test
    fun `逐张核对_advance代表人已点创建_放行后才填下一张`() {
        val dom = FormFixtures.dom()
        val ws = tmp.newFolder("ws")
        val cards = listOf(cardObj("甲"), cardObj("乙"), cardObj("丙"))
        val out = drain(dom, ws, cards)
        assertEquals(3, out.size)
        assertEquals(
            listOf("待发布 · 甲", "待发布 · 乙", "待发布 · 丙"),
            out.map { it.getJSONObject("card").getString("title") },
        )
        assertTrue("最后一张回执要说收工：${out.last().getString("summary")}",
            out.last().getBoolean("finished") &&
                out.last().getString("summary").startsWith("已出卡 3/3 张") &&
                out.last().getString("summary").contains("全部回读一致"))
        // 每张各一个目录、各自带自己的截图（负例 6：第二张不许覆盖第一张）
        val dirs = evidenceDirs(ws)
        assertEquals(3, dirs.size)
        assertEquals(3, dirs.map { it.name }.distinct().size)
        val shots = out.map { it.getJSONObject("card").getString("shotPath") }
        assertEquals("三张三个截图名：$shots", 3, shots.distinct().size)
        for ((i, name) in listOf("甲", "乙", "丙").withIndex()) {
            assertTrue("第 ${i + 1} 张的截图该带它自己的卡名：${shots[i]}", shots[i].contains(name))
        }
        for (name in listOf("甲", "乙", "丙")) {
            val dir = dirForCard(ws, name)
            assertTrue("「$name」的回读记录得单独落在它自己的目录里：${dir.name}",
                File(dir, "fields.txt").isFile)
        }
    }

    @Test
    fun `负例6_三张卡同名_卡名加段号且目录不覆盖`() {
        val dom = FormFixtures.dom()
        val ws = tmp.newFolder("ws")
        val out = drain(dom, ws, listOf(cardObj("甲"), cardObj("甲"), cardObj("甲")))
        assertEquals(
            listOf("待发布 · 甲", "待发布 · 甲-2", "待发布 · 甲-3"),
            out.map { it.getJSONObject("card").getString("title") },
        )
        val dirs = evidenceDirs(ws)
        assertEquals("三张三个目录（重名不许并成一个）：$dirs", 3, dirs.size)
        assertEquals(3, dirs.map { File(it, "card.json").readText(Charsets.UTF_8) }.distinct().size)
        val names = dirs.map { File(it, "card.json").readText(Charsets.UTF_8) }
        for (n in listOf("甲", "甲-2", "甲-3")) assertTrue("落盘记录里得有「$n」：$names", names.any { it.contains("\"$n\"") })
    }

    @Test
    fun `负例1_批量回执只有两个动作_且恒说没提交`() {
        val dom = FormFixtures.dom()
        val ws = tmp.newFolder("ws")
        for (j in drain(dom, ws, listOf(cardObj("甲"), cardObj("乙")))) {
            assertFalse(j.getBoolean("submitted"))
            assertEquals(listOf("复制全文", "打开页面"),
                (0 until j.getJSONObject("card").getJSONArray("actions").length())
                    .map { j.getJSONObject("card").getJSONArray("actions").getString(it) })
        }
        // 结构面：整条批量路径跑的也是 FormDom（四个方法里没有 click），行为面：零点击
        assertEquals(0, dom.clickCount)
        assertTrue(dom.poisonFilled.isEmpty())
    }

    // ── 缺一张就报哪张 ────────────────────────────────────────────────────────

    /**
     * 负例 3 在**批量出口**这一侧的样子（main 的 `TeachFormFillTest.负例3_富文本降级要红_不能报填好`
     * 打的是填表器本身，手法一致：这一格的 `store` 把换行折成空格）。
     *
     * 这一条要盯的是出口独有的三件事：回执不许因为「整批跑完了」就把这张报成好；红行必须
     * **落进这一张自己的 `fields.txt`**（逐张核对的人看的是盘上那份，不是模型转述）；
     * 且 `summary()` 要点名是哪张——「缺一张报哪张」在同一条判据上成立。
     */
    @Test
    fun `负例3_站点把人设压成一行_批量这张判失败且红行进盘`() {
        val dom = FormFixtures.dom()
        val persona = dom.elements.first { it.nameAttr == "persona" }
        dom.elements[dom.elements.indexOf(persona)] = FakeFormEl(
            tag = "TEXTAREA", nameAttr = "persona", label = "人设", id = "f-persona",
            store = { v -> v.lines().joinToString(" ") },
        )
        val ws = tmp.newFolder("ws")
        val j = ok(run(batch("start", cards = listOf(cardObj("甲"))), dom, ws))
        assertFalse("出口不许宣布已提交", j.getBoolean("submitted"))
        val card = j.getJSONObject("card")
        assertFalse("压成一行就是没填对，整张不许报好", card.getBoolean("ok"))
        val rows = card.getJSONArray("rows")
        val bad = (0 until rows.length()).map { rows.getJSONObject(it) }
            .first { !it.getBoolean("ok") }
        assertEquals("人设", bad.getString("label"))
        assertTrue("原因要点明降级：${bad.optString("reason")}",
            bad.optString("reason").contains("富文本被塞成纯文本"))
        assertTrue("汇总要点名是哪张：${j.getString("summary")}",
            j.getString("summary").contains("甲"))
        val fields = File(dirForCard(ws, "甲"), "fields.txt").readText(Charsets.UTF_8)
        assertTrue("红行要落进这一张的盘上记录：\n$fields",
            fields.lineSequence().any { it.startsWith("✗ 人设") && it.contains("富文本") })
    }

    @Test
    fun `负例4_多段块只填第一段_判失败并点名缺哪段`() {
        val dom = FormFixtures.dom()
        val ws = tmp.newFolder("ws")
        val j = ok(
            run(
                batch(
                    "start",
                    cards = listOf(
                        cardObj("甲", override = mapOf("worldbook2_title" to "", "worldbook2_content" to "")),
                    ),
                ),
                dom, ws,
            ),
        )
        val card = j.getJSONObject("card")
        assertFalse("缺段不许算填好", card.getBoolean("ok"))
        assertTrue("得点名缺的那一段：${card.getJSONArray("notes")}",
            (0 until card.getJSONArray("notes").length()).map { card.getJSONArray("notes").getString(it) }
                .any { it.contains("第 2 段") })
        assertEquals("预检没过 ⇒ 页面一格都不动", 0, dom.fillCalls.size)
    }

    @Test
    fun `缺一张报哪张_第二张的下拉回读回来是别的选项`() {
        val dom = FormFixtures.dom()
        val ws = tmp.newFolder("ws")
        // 页面自己读回别的选中项（预检拦不住这一档：值合法、落进去的却是另一项）。
        // 只在**第二张**上出错 ⇒ 正好看清「报的是哪一张没填对」。
        var styleReads = 0
        dom.readOverride = { el, _ ->
            if (el.nameAttr == "style" && ++styleReads == 2) FormReadback(text = "江湖", value = "2") else null
        }
        val out = drain(
            dom, ws,
            listOf(cardObj("甲"), cardObj("乙"), cardObj("丙")),
        )
        assertEquals(3, out.size)
        assertFalse("乙 那张必须判失败", out[1].getJSONObject("card").getBoolean("ok"))
        assertTrue("甲、丙 两张不许被牵连", out[0].getJSONObject("card").getBoolean("ok") &&
            out[2].getJSONObject("card").getBoolean("ok"))
        assertTrue("收工一句得点名是哪张没填对：${out.last().getString("summary")}",
            out.last().getString("summary").contains("乙"))
        val fields = File(dirForCard(ws, "乙"), "fields.txt").readText(Charsets.UTF_8)
        assertTrue("红行要留在这一张的记录里：\n$fields", fields.contains("✗"))
        for (name in listOf("甲", "丙")) {
            val f = File(dirForCard(ws, name), "fields.txt").readText(Charsets.UTF_8)
            assertFalse("「$name」的记录里不该有红行：\n$f", f.contains("✗"))
        }
    }

    // ── 负例 2：密码/验证码 ───────────────────────────────────────────────────

    @Test
    fun `负例2_整页只有密码框_start报没有能填的字段并带排除名单`() {
        val dom = FakeFormDom(mutableListOf(FakeFormEl(tag = "INPUT", nameAttr = "pwd", label = "密码", type = "password")))
        val ws = tmp.newFolder("ws")
        val e = err(run(batch("start", cards = listOf(cardObj("甲"))), dom, ws))
        assertTrue("得说清这页没得填：$e", e.contains("没有能填的字段"))
        assertTrue("排除名单里要点名密码：$e", e.contains("密码"))
        assertEquals(0, dom.fillCalls.size)
    }

    @Test
    fun `负例2_卡里塞了密码字段_模板没它就不填`() {
        val els = FormFixtures.elements()
        els += FakeFormEl(tag = "INPUT", nameAttr = "pwd", label = "密码", type = "password")
        els += FakeFormEl(tag = "INPUT", nameAttr = "otp", label = "验证码", type = "text", autocomplete = "one-time-code")
        els += FormFixtures.decoys()
        val dom = FakeFormDom(els)
        val ws = tmp.newFolder("ws")
        val j = ok(
            run(
                batch("start", cards = listOf(cardObj("甲", override = mapOf("pwd" to "123456", "otp" to "8888")))),
                dom, ws,
            ),
        )
        assertFalse("卡里有多余字段 ⇒ 整张判失败", j.getJSONObject("card").getBoolean("ok"))
        assertTrue("得说是模板没有的字段：${j.getJSONObject("card").getJSONArray("notes")}",
            (0 until j.getJSONObject("card").getJSONArray("notes").length())
                .map { j.getJSONObject("card").getJSONArray("notes").getString(it) }
                .any { it.contains("模板没有的字段") })
        assertEquals("预检没过 ⇒ 一格都没动", 0, dom.fillCalls.size)
        assertEquals(0, dom.clickCount)
    }

    // ── 工具面的闸 ────────────────────────────────────────────────────────────

    @Test
    fun `闸_submit为true时批量分支也先拒`() {
        val dom = FormFixtures.dom()
        val ws = tmp.newFolder("ws")
        val out = FormFillTool.execute(
            JSONObject().put("submit", true)
                .put("batch", batch("start", cards = listOf(cardObj("甲")))),
            dom, ws, { url },
        )
        assertTrue("必须是被拒", out is ToolOutcome.Rejected)
        assertEquals(FormFillTool.SUBMIT_REFUSAL, (out as ToolOutcome.Rejected).reason)
        assertEquals(0, dom.fillCalls.size)
        assertEquals(0, dom.clickCount)
    }

    @Test
    fun `闸_fields与batch都不给就拒`() {
        val e = err(FormFillTool.execute(JSONObject(), FormFixtures.dom(), tmp.newFolder("ws"), { url }))
        assertTrue("得同时点名两个入口：$e", e.contains("fields") && e.contains("batch"))
    }

    @Test
    fun `闸_batch里拼错的键不许静吃掉`() {
        val e = err(run(batch("start", extra = "cardds" to JSONArray().put(cardObj("甲"))), FormFixtures.dom(), tmp.newFolder("ws")))
        assertTrue("要点名不认识的键：$e", e.contains("cardds"))
    }

    @Test
    fun `闸_start不给cards就拒_动作名不认识也拒`() {
        val ws = tmp.newFolder("ws")
        val e = err(run(batch("start"), FormFixtures.dom(), ws))
        assertTrue("得说清要给什么：$e", e.contains("batch.cards"))
        val e2 = err(run(batch("step-by-step", sessionId = "fb-1"), FormFixtures.dom(), ws))
        assertTrue("四个动作之外的一律拒：$e2", e2.contains("只认四个"))
    }

    @Test
    fun `闸_没有会话就报没有会话_不新排一次队`() {
        val dom = FormFixtures.dom()
        val ws = tmp.newFolder("ws")
        val e = err(run(batch("next"), dom, ws))
        assertTrue("得说要带 sessionId：$e", e.contains("sessionId"))
        val e2 = err(run(batch("next", sessionId = "fb-999999"), dom, ws))
        assertTrue("查无此会话要指路重新排队：$e2", e2.contains("没有这个批量会话") && e2.contains("重新 action=start"))
        assertEquals("查会话没查着 ⇒ 页面一格没动", 0, dom.fillCalls.size)
    }

    @Test
    fun `闸_没有待发布的卡时advance拒`() {
        val dom = FormFixtures.dom()
        val ws = tmp.newFolder("ws")
        val first = ok(run(batch("start", cards = listOf(cardObj("甲"), cardObj("乙"))), dom, ws))
        val sid = first.getString("sessionId")
        ok(run(batch("advance", sessionId = sid), dom, ws))
        val e = err(run(batch("advance", sessionId = sid), dom, ws))
        assertTrue("得说现在没有等着发布的卡：$e", e.contains("没有「等着发布」的卡"))
        assertEquals("重复放行不许多填一张", 8, dom.fillCalls.size)
    }

    @Test
    fun `闸_status只看不碰页面`() {
        val dom = FormFixtures.dom()
        val ws = tmp.newFolder("ws")
        val first = ok(run(batch("start", cards = listOf(cardObj("甲"), cardObj("乙"))), dom, ws))
        val sid = first.getString("sessionId")
        val fills = dom.fillCalls.size
        val st = ok(run(batch("status", sessionId = sid), dom, ws))
        assertEquals("status 之后页面一格都没多动", fills, dom.fillCalls.size)
        assertEquals("status", st.getString("action"))
        assertTrue("挂着的那张卡要给出来", st.getJSONObject("card").getString("title") == "待发布 · 甲")
        // 放行之后再看一次：没卡可给了，回执只剩「这一趟没碰页面」这一句
        ok(run(batch("advance", sessionId = sid), dom, ws))
        val st2 = ok(run(batch("status", sessionId = sid), dom, ws))
        assertEquals("看第二次也不许多动一格", fills, dom.fillCalls.size)
        assertTrue("没卡可看时不许凭空造一张", st2.isNull("card"))
        assertTrue("note 要说明只看不碰：${st2.getString("note")}",
            st2.getString("note").contains("这一趟没填新卡（只看不碰页面）"))
    }

    @Test
    fun `闸_装配层没给工作区就明说_单发分支不受影响`() {
        val dom = FormFixtures.dom()
        val e = err(
            FormFillTool.execute(
                JSONObject().put("batch", batch("start", cards = listOf(cardObj("甲")))),
                dom,
            ),
        )
        assertTrue("得说清批量要落盘：$e", e.contains("产物/填表") && e.contains("单发的 fields 分支"))
        assertEquals("拿不到工作区就一格都不动", 0, dom.fillCalls.size)
    }

    @Test
    fun `会话表有上限_挤掉最老的那条并说清卡没丢`() {
        val dom = FormFixtures.dom()
        val ws = tmp.newFolder("ws")
        val ids = mutableListOf<String>()
        repeat(9) {
            ids += ok(run(batch("start", cards = listOf(cardObj("卡$it"))), dom, ws)).getString("sessionId")
        }
        val e = err(run(batch("status", sessionId = ids.first()), dom, ws))
        assertTrue("最老那条该被挤掉：$e", e.contains("没有这个批量会话") && e.contains("产物/填表"))
        // 最新那条还在（上限挤的是最老的，不是全部）
        assertEquals("status", ok(run(batch("status", sessionId = ids.last()), dom, ws)).getString("action"))
    }
}
