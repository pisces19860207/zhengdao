// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百九十八批（修 G-690-6）· 「建草案取哪个会话」与「拒收必须发声」的实跑证据。
//
// 病的现场（690 报告 §二）：示教完**不切走**直接点「建草案」⇒ 拿的是上一轮会话 ⇒ 归纳出
// 与已有草案逐字相同的东西 ⇒ 被当重复吞掉，屏幕上一点反应都没有。
//
// 四条判据各一组夹具：
//   ① 活跃会话有行 ⇒ 取**它**（负例：取 lastSession ⇒ 草案 url 会是上一轮的 ⇒ 红）；
//   ② 活跃会话没行 ＋ 上一轮有 ⇒ 明说「没有新的观察」，不许拿旧会话硬生成；
//   ③ 同一段观察再归纳一次 ⇒ 可见反馈（撞了哪条 ＋ 「查看已有草案」），既有草案不动；
//   ④ 每次拒收账里正好一行（原因 ＋ 会话 id／草案 id）。
package com.example.zhengdao.baopu.learn

import com.example.zhengdao.testkit.TempSandbox
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class ObserveCenterDraftSourceTest {

    private val texts = LearnCardTexts(
        qAmbiguous = "第 {步} 步「{目标}」在页面上有 {条数} 条走法，你平时用哪条？",
        qNoEvidence = "第 {步} 步点了「{目标}」但页面没变化，这一步想做什么？",
        qParam = "第 {步} 步「{目标}」填的内容每次都不同吗？",
        qIntent = "这次是在「{页}」做哪件事？",
        optParam = "是参数（每次会变）",
        optFixed = "固定值（每次一样）",
        optTeach = "就教这一步",
        optDrop = "这一步不算",
        freeText = "或者直接说你的想法",
    )

    private var fakeNow: Long = 1_700_000_000_000L
    private var observeOn: Boolean = true
    private val drainQueue = java.util.ArrayDeque<String>()

    /** 身份面（页内 `faces()` 的微缩：有可及名才能取到意图名，也才过得了「定位不全空」）。 */
    private fun faces(name: String = "一条新闻"): JSONObject = JSONObject()
        .put("tag", "A").put("role", "link").put("name", name).put("aria", "")
        .put("testid", "").put("id", "").put("inputName", "").put("placeholder", "")
        .put("inputType", "").put("auto", "").put("formLabel", "")
        .put("xp", "div[1]>a[1]").put("cls", JSONArray())

    /**
     * 一条事件行：**键名照页内脚本的短键**（`d`/`s`）——697 的真根因就在这个键名上，
     * 夹具跟着现场形状走，才不会把「解析读不到 doc/seq」这种病盖住。
     */
    private fun ev(seq: Int, doc: String, url: String, nc: Int, t: Long, name: String = "一条新闻"): JSONObject =
        JSONObject().put("a", "click").put("url", url).put("el", faces(name))
            .put("nc", nc).put("t", t).put("d", doc).put("s", seq)

    private fun pageOf(url: String, nc: Int, t: Long): JSONObject =
        JSONObject().put("url", url).put("title", "页面").put("nc", nc).put("t", t)

    private fun drain(doc: String, events: List<JSONObject>, page: JSONObject?): String {
        val inner = JSONObject().put("doc", doc).put("ev", JSONArray(events))
        if (page != null) inner.put("pg", page)
        return JSONObject().put("result", inner.toString()).toString()
    }

    private fun installIn(root: File) {
        ObserveCenter.resetForTest()
        ObserveCenter.install(
            root = root,
            cardTexts = texts,
            clock = { fakeNow },
            harvest = { _ -> drainQueue.poll() },
            nodeTable = { _ -> null },
            enabled = { observeOn },
        )
    }

    private fun tickNow() = kotlinx.coroutines.runBlocking { ObserveCenter.tick(7) }

    private fun sessionFile(root: File, id: String): File = File(root, ObserveSession.fileNameFor(id))

    private fun rejectLines(root: File): List<String> =
        DraftRejectLog(File(File(root, SkillDraftStore.DIR_NAME), DraftRejectLog.FILE_NAME)).lines()

    /** 先记一轮（收口成 lastSession），返回那一轮的会话 id。 */
    private fun seedClosedSession(root: File, url: String): String {
        drainQueue.add(drain("docOld", listOf(ev(1, "docOld", url, 10, fakeNow - 300)), pageOf(url, 10, fakeNow - 200)))
        tickNow()
        val id = fakeNow.toString(36)
        assertTrue("上一轮要真落盘（夹具前提）", sessionFile(root, id).isFile)
        // 浏览器离树 ⇒ 就地收会话（**不走开关**：关开关会连带把下一批丢弃，见 ObserveCenterTest 判据①）
        ObserveCenter.onBrowserGone()
        return id
    }

    // ── 判据①：示教完不切走，建草案取**当前**会话 ─────────────────────────────

    @Test
    fun `判据1_示教完不切走_建草案取当前会话而不是上一轮`() {
        TempSandbox.use("zd698-src-live") { root ->
            installIn(root)
            val oldUrl = "https://old.test/list"
            val oldId = seedClosedSession(root, oldUrl)

            // 第二轮：浏览器还开着、会话没收口（正是 690 的现场）
            fakeNow += 60_000
            val liveUrl = "https://live.test/item"
            val liveId = fakeNow.toString(36)
            assertNotEquals("两轮是不同的会话（夹具前提）", oldId, liveId)
            drainQueue.add(drain("docLive", listOf(ev(1, "docLive", liveUrl, 42, fakeNow - 300)), pageOf(liveUrl, 42, fakeNow - 200)))
            tickNow()

            val out = ObserveCenter.induceDraft("点开一条链接")
            assertTrue("活跃会话有行 ⇒ 必须建得成（实见 $out）", out is SkillDraftStore.AddOutcome.Created)
            val draft = ObserveCenter.drafts.value.single()
            assertEquals(
                "取的是当前会话；拿 lastSession 这里会是 $oldUrl ⇒ 红",
                listOf(liveUrl),
                draft.skeleton.map { it.url },
            )
            assertEquals("来源会话＝当前那一轮", listOf(liveId), draft.sourceSessions)
            assertTrue(
                "证据指回当前会话的那本账（实见 ${draft.skeleton.single().evidence}）",
                draft.skeleton.single().evidence.first().contains(ObserveSession.fileNameFor(liveId)),
            )
            assertEquals("成功路径照旧给「已入草稿区」", LearnToastKind.DRAFT_CREATED, ObserveCenter.lastToast.value?.kind)
        }
    }

    // ── 判据②：当前会话没行，不许拿上一轮硬生成 ────────────────────────────────

    @Test
    fun `判据2_当前会话没有新行_明说没有新的观察`() {
        TempSandbox.use("zd698-src-empty") { root ->
            installIn(root)
            seedClosedSession(root, "https://old.test/list") // lastSession 里**有**一步

            // 这一轮只有页事实、没有任何操作行
            fakeNow += 60_000
            drainQueue.add(drain("docLive", emptyList(), pageOf("https://live.test/blank", 8, fakeNow)))
            tickNow()

            val out = ObserveCenter.induceDraft("点开一条链接")
            assertTrue("必须拒（实见 $out）", out is SkillDraftStore.AddOutcome.Rejected)
            assertTrue(
                "拒因要说「没有新的观察」：${(out as SkillDraftStore.AddOutcome.Rejected).problems}",
                out.problems.any { it.contains(NO_NEW_OBSERVATION) },
            )
            assertEquals("不许拿上一轮硬生成", 0, ObserveCenter.drafts.value.size)
            assertEquals(
                "可见反馈是「没有新的观察」而不是「结构校验拒收」",
                LearnToastKind.DRAFT_NO_NEW_OBSERVATION,
                ObserveCenter.lastToast.value?.kind,
            )
            assertEquals("账里正好一行", 1, rejectLines(root).size)
        }
    }

    // ── 判据③④：重复草案要发声、能去看；每次拒收账里一行 ───────────────────────

    @Test
    fun `判据3_重复草案可见反馈且指得出既有那条`() {
        TempSandbox.use("zd698-src-dup") { root ->
            installIn(root)
            val url = "https://live.test/item"
            drainQueue.add(drain("docLive", listOf(ev(1, "docLive", url, 42, fakeNow - 300)), pageOf(url, 42, fakeNow - 200)))
            tickNow()
            assertTrue(ObserveCenter.induceDraft("点开一条链接") is SkillDraftStore.AddOutcome.Created)
            val existingId = ObserveCenter.drafts.value.single().id

            val second = ObserveCenter.induceDraft("点开一条链接")
            assertTrue("重复要拒（不许悄悄归并成 2 次观察）", second is SkillDraftStore.AddOutcome.Rejected)
            val reason = (second as SkillDraftStore.AddOutcome.Rejected).problems.joinToString("；")
            assertTrue("原因说清撞了哪条：$reason", reason.contains(DUPLICATE_PREFIX))
            assertTrue("原因点名草案 id：$reason", reason.contains(existingId))
            assertTrue("原因给出去处：$reason", reason.contains("查看已有草案"))

            assertEquals("toast 带上既有草案 id", existingId, ObserveCenter.lastToast.value?.arg)
            assertEquals("toast 是「与已有草案一致」那一款", LearnToastKind.DRAFT_DUPLICATE, ObserveCenter.lastToast.value?.kind)
            assertEquals("既有草案还在（人点开就是它）", listOf(existingId), ObserveCenter.drafts.value.map { it.id })
        }
    }

    @Test
    fun `判据4_每次拒收账里一行`() {
        TempSandbox.use("zd698-src-ledger") { root ->
            installIn(root)
            val url = "https://live.test/item"
            drainQueue.add(drain("docLive", listOf(ev(1, "docLive", url, 42, fakeNow - 300)), pageOf(url, 42, fakeNow - 200)))
            tickNow()
            val liveSession = fakeNow.toString(36) // 会话 id＝建会话那一刻（base36）
            ObserveCenter.induceDraft("点开一条链接") // 成功，不记账
            assertEquals("成功不写拒收账", 0, rejectLines(root).size)

            val existingId = ObserveCenter.drafts.value.single().id
            ObserveCenter.induceDraft("点开一条链接") // 重复 ⇒ 一行
            fakeNow += 1_000 // 草案 id 取时刻（base36）：时间往前走一格，第二条才不撞文件名
            ObserveCenter.induceDraft("点开另一条") // 同一段观察换个意图 ⇒ 不是重复，照样建成
            assertEquals("第二份草案也确实建了（换意图不算撞车）", 2, ObserveCenter.drafts.value.size)

            val lines = rejectLines(root)
            assertEquals("只有那一次拒收记了账", 1, lines.size)
            val row = JSONObject(lines.single())
            assertTrue("账里写清原因", row.optString("reason").contains(DUPLICATE_PREFIX))
            assertTrue("账里写清草案 id", row.optString("draft") == existingId)
            assertEquals("账里写清是哪一段观察", liveSession, row.optString("session"))
            assertTrue("账里有时间", row.optLong("t") > 0)
        }
    }
}
