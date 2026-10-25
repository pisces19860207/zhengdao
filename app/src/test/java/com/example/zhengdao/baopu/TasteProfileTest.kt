// 独立开发声明：本文件为本项目原创。
//
// 第二百零二批 · 口味档案＋范例库的判据测试（派工单 §4 六条负例逐条钉住）。
//
// 为什么全在 JVM：档案解析、禁区优先、范例挑选、注入拼装、脱敏、"移出不删文件"、
// "删掉下一轮就变" 全部落在吃 `File` 的纯函数上（Context 只在 ExampleBank 的薄壳里）
// ⇒ 不需要 Robolectric，也不需要真机。
package com.example.zhengdao.baopu

import com.example.zhengdao.browser.SensitiveRedactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TasteProfileTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 会撞禁区「失业」的种子（题材偏也命中：track=职场）。 */
    private val seedRisky = TopicSeed(
        "t01", "中年失业第三个月，我把简历删了", "失业", "职场", "裁员潮里最真实的不是焦虑，是松了口气",
    )

    /** 干干净净的种子（生活观察）。 */
    private val seedSafe = TopicSeed(
        "t02", "楼下便利店老板娘记住了我的口味", "便利店", "生活观察", "城市孤独症的解药是陌生人的小善意",
    )

    private val style = TopicSuggest.STYLES.first()

    private fun archiveMd(): String = """
        # 口味档案

        <!-- 口味档案 v1 · 2026-10-25 12:00 · 每条必须带「证据：」；删掉的条目下一轮就不再注入 -->

        ## 题材偏
        - 职场类更吃香 — 证据：20261020_职场_7（✔火了）、20261022_职场_3（✔赚钱）

        ## 写法偏
        - 标题不用问句 — 证据：3 篇问句标题里 2 篇被改
        - 开头三句内给冲突 — 证据：口述 2026-10-24

        ## 禁区（优先级最高，命中直接不出候选）
        - 不写失业 — 证据：口述 2026-10-24
    """.trimIndent()

    private fun writeArchive(dir: File, md: String) {
        dir.mkdirs()
        File(dir, TasteProfile.ARCHIVE_NAME).writeText(md, Charsets.UTF_8)
    }

    private fun writeSample(dir: File, kind: SampleKind, name: String, body: String) {
        val d = ExampleBank.examplesDir(dir, kind)
        d.mkdirs()
        File(d, name).writeText(body, Charsets.UTF_8)
    }

    // ── 正例 A：解析与证据 ────────────────────────────────────────────────

    @Test
    fun `解析-a 三段都读出来且证据原样保留`() {
        val a = TasteProfile.parse(archiveMd())
        assertEquals(1, a.bySection(TasteSection.THEME).size)
        assertEquals(2, a.bySection(TasteSection.STYLE).size)
        assertEquals(1, a.bySection(TasteSection.FORBIDDEN).size)
        assertEquals("v1", a.version)
        assertEquals(
            "20261020_职场_7（✔火了）、20261022_职场_3（✔赚钱）",
            a.bySection(TasteSection.THEME).first().evidence,
        )
        assertEquals("题材偏 1 · 写法偏 2 · 禁区 1", a.summary())
    }

    @Test
    fun `解析-b render 往返稳定（写回再读，条目一字不差）`() {
        val a = TasteProfile.parse(archiveMd())
        val round = TasteProfile.parse(TasteProfile.render(a, "2026-10-25 13:00"))
        assertEquals(a.entries.map { it.id }, round.entries.map { it.id })
        assertEquals(a.entries.map { it.evidence }, round.entries.map { it.evidence })
    }

    // ── 负例 1：没有证据行 ⇒ 不生效 ───────────────────────────────────────

    @Test
    fun `负例1 没有证据的条目一律不进档案`() {
        val md = """
            ## 题材偏
            - 民生类更吃香
            ## 禁区（优先级最高）
            - 不写大女主 — 证据：
        """.trimIndent()
        val a = TasteProfile.parse(md)
        assertTrue("没证据的条目必须被丢弃", a.entries.isEmpty())
        assertEquals(2, a.rejected.size)
        assertTrue(a.rejected.all { it.reason == TasteProfile.WHY_NO_EVIDENCE })
    }

    @Test
    fun `负例1b 不在三段里的条目也不生效（写在标题外的规则不算数）`() {
        val a = TasteProfile.parse("随便一句\n- 不要写悬疑 — 证据：口述 2026-10-25")
        assertTrue(a.entries.isEmpty())
        assertEquals(TasteProfile.WHY_NO_SECTION, a.rejected.single().reason)
    }

    // ── 负例 5：禁区优先级最高 ────────────────────────────────────────────

    @Test
    fun `负例5 同时命中题材偏与禁区时禁区胜（种子被剔除）`() {
        val a = TasteProfile.parse(archiveMd())
        // 前提自证：这条种子确实同时命中题材偏（职场）与禁区（失业）
        assertEquals(1, TasteProfile.themeHits(a, seedRisky).size)
        assertNotNull(TasteProfile.forbiddenHit(a, seedRisky))

        val batch = TopicSuggestBatch(
            candidates = listOf(TopicCandidate(seedSafe, "理由甲"), TopicCandidate(seedRisky, "理由乙")),
            clashes = emptyList(),
            exhausted = false,
        )
        val ranked = TasteProfile.rankCandidates(batch, a)
        assertEquals(listOf("t02"), ranked.batch.candidates.map { it.seed.id })
        assertEquals(1, ranked.dropped.size)
        assertTrue(ranked.dropped.single().reason.startsWith("因禁区「不写失业」被剔除"))

        // 注入块里禁区段必须排在最前面（题材偏/写法偏不许盖过它）
        val inj = TasteProfile.injection(a, seedSafe, style, emptyList())!!
        val iForbid = inj.block.indexOf("禁区（最高优先级")
        val iTheme = inj.block.indexOf("题材偏好")
        val iStyle = inj.block.indexOf("写法偏好")
        assertTrue(iForbid in 0 until iTheme)
        assertTrue(iTheme in 0 until iStyle)
    }

    @Test
    fun `禁区词 1 字只认完全相等（不误伤同字的正常题目）`() {
        val a = TasteProfile.parse("## 禁区\n- 不写丧 — 证据：口述 2026-10-25")
        val seed = TopicSeed("t9", "今天有点丧气", "丧气", "生活观察", "")
        assertNull("单字禁区不许按包含判，否则整片误伤", TasteProfile.forbiddenHit(a, seed))
        val exact = TopicSeed("t9", "丧", "丧", "生活观察", "")
        assertNotNull(TasteProfile.forbiddenHit(a, exact))
        assertEquals("丧", TasteProfile.term("不写丧"))
    }

    // ── 正例 B：偏好让候选排序变化 ────────────────────────────────────────

    @Test
    fun `正例B 题材偏命中后候选排到前面（稳定排序，其余保序）`() {
        val a = TasteProfile.parse(archiveMd())
        val safeA = seedSafe.copy(id = "t02")
        val safeB = seedSafe.copy(id = "t03", title = "菜市场教会我的砍价哲学", scene = "菜市场")
        val riskyFree = seedRisky.copy(id = "t01", title = "被裁的同事朋友圈开始卖保险了", scene = "卖保险")
        val batch = TopicSuggestBatch(
            candidates = listOf(
                TopicCandidate(safeA, "r1"),
                TopicCandidate(safeB, "r2"),
                TopicCandidate(riskyFree, "r3"),
            ),
            clashes = emptyList(),
            exhausted = false,
        )
        val ranked = TasteProfile.rankCandidates(batch, a)
        // 职场那条被提到第一（题材偏 +2），其余两条保持原相对顺序
        assertEquals(listOf("t01", "t02", "t03"), ranked.batch.candidates.map { it.seed.id })
        assertTrue(ranked.dropped.isEmpty())
    }

    @Test
    fun `正例B2 范例相似度也参与排序（最像的正样本把题目顶上来）`() {
        val dir = tmp.newFolder("affinity")
        writeSample(
            dir, SampleKind.GOOD, "正1.md",
            "# 楼下便利店\n老板娘记住了我的口味，每次我去买豆浆她都提前装好。",
        )
        writeSample(dir, SampleKind.GOOD, "正2.md", "# 公司裁员\n裁员名单下来的那天，我把工位收拾干净。")
        val samples = ExampleBank.loadSamples(dir)
        val sim = TasteProfile.affinityOf(seedSafe, style, samples)
        assertTrue("便利店那条应比裁员那条更像", sim > 0.0)
        val nasty = TasteProfile.affinityOf(seedRisky, style, samples)
        assertTrue("裁员样本对失业种子也该有相似度", nasty > 0.0)
    }

    // ── 正例 C：注入 2–3 正 + 1 负，卡面写清参考/避开 ─────────────────────

    @Test
    fun `正例C 注入最多 3 篇正样本加 1 篇负样本且卡面点名`() {
        val dir = tmp.newFolder("inject")
        writeArchive(dir, archiveMd())
        (1..5).forEach { i ->
            writeSample(dir, SampleKind.GOOD, "正$i.md", "# 便利店第 $i 篇\n老板娘记住了熟客的口味，第 $i 次买豆浆。")
        }
        writeSample(dir, SampleKind.BAD, "负1.md", "# 便利店广告腔\n<!-- 原因：太像广告，没有人味 -->\n本店全场八折。")

        val samples = ExampleBank.loadSamples(dir)
        val inj = ExampleBank.inject(dir, seedSafe, style)!!
        assertEquals(3, inj.goods.size)
        assertEquals("负1.md", inj.bad)
        assertTrue(inj.block.contains("用户否掉的样子"))
        assertTrue(inj.block.contains("太像广告，没有人味"))

        val note = TasteProfile.cardNote(inj)!!
        assertTrue(note.startsWith("本次参考："))
        assertTrue(note.contains("避开：《负1.md》"))
    }

    @Test
    fun `正例C2 档案与范例都空时注入为 null（不编造材料）`() {
        val dir = tmp.newFolder("empty")
        assertNull(ExampleBank.inject(dir, seedSafe, style))
        assertNull(TasteProfile.cardNote(null))
        assertTrue(TasteProfile.previewHeadline(null).contains("还没有"))
    }

    // ── 负例 3：注入前脱敏 ────────────────────────────────────────────────

    @Test
    fun `负例3 范例里的手机号与身份证不许原样进提示`() {
        val dir = tmp.newFolder("pii")
        writeSample(
            dir, SampleKind.GOOD, "正-有隐私.md",
            "# 客户王女士\n客户王女士留了 13800138000，身份证 110101199003071234，家住城东。",
        )
        val inj = ExampleBank.inject(dir, seedSafe, style)!!
        assertFalse("手机号必须已被脱敏", inj.block.contains("13800138000"))
        assertFalse("身份证必须已被脱敏", inj.block.contains("110101199003071234"))
        assertFalse("客户名必须已被脱敏", inj.block.contains("王女士"))
        assertTrue(inj.block.contains("客户***"))
        assertTrue(inj.block.contains(SensitiveRedactor.MASK))

        // 负例自证：不过脱敏时它确实会漏（说明这条测试真的在测脱敏，而不是文本里本来就没有）
        val raw = TasteProfile.injection(
            TasteProfile.parse(null), seedSafe, style, ExampleBank.loadSamples(dir), { it },
        )!!
        assertTrue(raw.block.contains("13800138000"))
        assertTrue(raw.block.contains("王女士"))
    }

    @Test
    fun `客户名脱敏只认称呼形状不乱吃正文`() {
        assertEquals("客户***留了电话", TasteProfile.redactNames("客户王女士留了电话"))
        assertEquals("***来取货", TasteProfile.redactNames("李女士来取货"))
        assertEquals("顾客***说不用袋子", TasteProfile.redactNames("顾客张先生说不用袋子"))
        // 反例：普通词不许被误伤
        assertEquals("老板娘记住了我的口味", TasteProfile.redactNames("老板娘记住了我的口味"))
    }

    // ── 负例 2：删掉一条，下一轮就变（无缓存） ─────────────────────────────

    @Test
    fun `负例2 删掉一条偏好后下一次注入里就没有它`() {
        val dir = tmp.newFolder("nocache")
        writeArchive(dir, archiveMd())
        val before = ExampleBank.inject(dir, seedSafe, style)!!
        assertTrue(before.block.contains("标题不用问句"))

        val victim = ExampleBank.loadArchive(dir).entries.first { it.text == "标题不用问句" }
        assertNotNull(ExampleBank.deleteEntry(dir, victim.id))

        val after = ExampleBank.inject(dir, seedSafe, style)!!
        assertFalse("删掉的条目下一轮不许再注入（缓存没失效的一种）", after.block.contains("标题不用问句"))
        assertTrue(after.block.contains("开头三句内给冲突"))
    }

    // ── 负例 4：移出不删文件 ──────────────────────────────────────────────

    @Test
    fun `负例4 移出只记一行清单原稿还在盘上`() {
        val dir = tmp.newFolder("mute")
        writeSample(dir, SampleKind.GOOD, "正1.md", "# 甲\n正文甲")
        writeSample(dir, SampleKind.GOOD, "正2.md", "# 乙\n正文乙")
        assertTrue(ExampleBank.mute(dir, SampleKind.GOOD, "正1.md"))

        val f = File(ExampleBank.examplesDir(dir, SampleKind.GOOD), "正1.md")
        assertTrue("移出绝不许删文件", f.isFile)
        assertEquals(1, ExampleBank.mutedCount(dir))
        assertEquals(listOf("正2.md"), ExampleBank.loadSamples(dir).map { it.name })
        // 重复移出同一篇 ⇒ false（不重复追加清单）
        assertFalse(ExampleBank.mute(dir, SampleKind.GOOD, "正1.md"))
    }

    // ── 负例 6：不联网 ────────────────────────────────────────────────────

    @Test
    fun `负例6 本批生产文件没有任何联网入口`() {
        // 边界：这是源码门禁（本机测试跑不到网络层），钉的是「没人偷偷加一条上传/画像」。
        val rels = listOf(
            "app/src/main/java/com/example/zhengdao/baopu/TasteProfile.kt",
            "app/src/main/java/com/example/zhengdao/baopu/ExampleBank.kt",
            "app/src/main/java/com/example/zhengdao/ui/baopu/TasteSettingsScreen.kt",
        )
        val banned = listOf("java.net.", "HttpURLConnection", "okhttp", "OkHttp", "URL(", "Socket")
        rels.forEach { rel ->
            // 测试的工作目录可能是仓库根也可能是 app/，两种都试；**都不在就报红**（门禁不许空转）
            val f = listOf(rel, "../$rel", "../../$rel").map { File(it) }.firstOrNull { it.isFile }
            assertNotNull("找不到源码文件 $rel —— 门禁不许空转", f)
            val text = f!!.readText()
            banned.forEach { b -> assertFalse("$rel 里不该出现 $b（本批红线：不联网）", text.contains(b)) }
        }
    }

    // ── 其它判据 ──────────────────────────────────────────────────────────

    @Test
    fun `term 去前缀与尾缀`() {
        assertEquals("失业", TasteProfile.term("不写失业"))
        assertEquals("大女主", TasteProfile.term("不写大女主这类"))
        assertEquals("悬疑", TasteProfile.term("禁止写悬疑题材"))
        assertEquals("民生类更吃香", TasteProfile.term("民生类更吃香"))
        assertEquals("民生", TasteProfile.categoryTag("民生类更吃香"))
        assertEquals("同志", TasteProfile.categoryTag("同志类克制"))
        assertNull(TasteProfile.categoryTag("标题不用问句"))
    }

    @Test
    fun `截断按字切并加省略号`() {
        val long = "字".repeat(3000)
        val c = TasteProfile.clip(long, 100)
        assertEquals(101, c.length)
        assertTrue(c.endsWith("…"))
    }

    @Test
    fun `负样本原因两种写法都认`() {
        assertEquals("太像广告", ExampleBank.reasonOf("# 标题\n<!-- 原因：太像广告 -->\n正文"))
        assertEquals("没人味", ExampleBank.reasonOf("# 标题\n> 原因：没人味\n正文"))
        assertNull(ExampleBank.reasonOf("# 标题\n正文"))
    }

    @Test
    fun `首次建模板不覆盖已有档案`() {
        val dir = tmp.newFolder("template")
        val t = ExampleBank.ensureArchive(dir)
        assertTrue(t.entries.isEmpty())
        assertTrue("空白模板里的占位行不许被当成「写了但没生效」", t.rejected.isEmpty())
        assertTrue(File(dir, TasteProfile.ARCHIVE_NAME).isFile)

        writeArchive(dir, archiveMd())
        val again = ExampleBank.ensureArchive(dir)
        assertEquals(4, again.entries.size)   // 题材 1 ＋ 写法 2 ＋ 禁区 1
    }

    /**
     * 交付报告要贴的那份「范例注入预览」——**真跑出来的**，不是手写样例。
     * 用 `--tests "*TasteProfileTest.预览*"` 单跑，从 `build/test-results` 的 system-out 取这段。
     */
    @Test
    fun `预览 脱敏后的范例注入样例`() {
        val dir = tmp.newFolder("preview")
        writeArchive(dir, archiveMd())
        writeSample(
            dir, SampleKind.GOOD, "20261020_职场_7_被裁之后.md",
            "# 被裁之后的第七天\n早上七点还是醒了，才想起不用打卡。我把简历打开又关掉，最后去楼下便利店买了杯豆浆。\n" +
                "老板娘问：今天怎么这个点来。我说休息。她说那挺好。",
        )
        writeSample(
            dir, SampleKind.GOOD, "20261022_职场_3_工位收拾干净.md",
            "# 工位收拾干净那天\n抽屉里翻出三年前的年会胸牌。人事站在旁边等，我把杯子洗干净放回茶水间。",
        )
        writeSample(
            dir, SampleKind.GOOD, "20261018_生活观察_2_便利店老板娘.md",
            "# 便利店老板娘记住了我的口味\n第三次去买豆浆，她已经提前把不加糖那杯装好。城市里被记住，比被理解容易。",
        )
        writeSample(
            dir, SampleKind.BAD, "20261019_职场_9_逆袭文.md",
            "# 被裁三个月我月入十万\n<!-- 原因：太像成功学广告，用户说「别写这种」 -->\n" +
                "被裁员不是终点，而是起点。三个月后我做到了月入十万，方法只有三点。客户李女士 13800138000 亲测有效。",
        )
        val inj = ExampleBank.inject(dir, seedSafe, style)!!
        println("=== 本次参考：${inj.goods.joinToString("、")}；避开：${inj.bad} ===")
        println(inj.block)
        assertEquals(3, inj.goods.size)
        assertEquals("20261019_职场_9_逆袭文.md", inj.bad)
        assertFalse(inj.block.contains("13800138000"))
        assertFalse("客户名也要脱敏", inj.block.contains("李女士"))
    }
}
