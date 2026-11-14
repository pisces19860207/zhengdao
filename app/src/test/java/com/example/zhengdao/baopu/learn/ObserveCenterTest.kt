// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百二十四批 · 判据①⑥的运行时半边：开关关 ⇒ 一次不收割、0 记录；
// 「就教这一步」只补该步（关观察也能教，且补录不落观察账）；武装超时自动解除。
package com.example.zhengdao.baopu.learn

import com.example.zhengdao.testkit.TempSandbox
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class ObserveCenterTest {

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

    private var fakeNow: Long = 1_000_000L
    private var observeOn: Boolean = true
    private val drainCalls = java.util.Collections.synchronizedList(ArrayList<String>())
    private val drainQueue = java.util.ArrayDeque<String>()

    /** 节点表通道（OBSERVE）的调用剧本；null＝该次取不到（端口闸停如实归 null）。 */
    private val nodeQueue = java.util.ArrayDeque<String>()

    private fun facesJson(
        tag: String = "BUTTON",
        name: String = "查询",
        inputType: String = "",
        inputName: String = "",
        placeholder: String = "",
        xp: String = "/html/body/button[1]",
    ): JSONObject = JSONObject()
        .put("tag", tag).put("role", "button").put("name", name).put("aria", "")
        .put("testid", "").put("id", "").put("inputName", inputName)
        .put("placeholder", placeholder).put("inputType", inputType).put("auto", "")
        .put("formLabel", "").put("xp", xp).put("cls", JSONArray())

    private fun batch(
        vararg events: Pair<String, Int>,
        page: Boolean = false,
        url: String = "https://example.test/list",
        nc: Int = 10,
    ): String {
        val evs = JSONArray()
        events.forEach { (kind, seq) ->
            val el = if (kind == "type") facesJson(tag = "INPUT", inputType = "text", inputName = "单号") else facesJson()
            val ev = JSONObject()
                .put("a", kind).put("url", url).put("el", el).put("nc", nc)
                .put("t", fakeNow - 500).put("d", "doc1").put("s", seq)
            evs.put(ev)
        }
        val inner = JSONObject().put("doc", "doc1").put("seq", events.maxOfOrNull { it.second } ?: 0).put("ev", evs)
        if (page) {
            inner.put(
                "pg",
                JSONObject().put("url", url).put("title", "列表页").put("nc", nc)
                    .put("t", fakeNow - 200).put("nodes", JSONArray()),
            )
        }
        return JSONObject().put("result", inner.toString()).toString()
    }

    /** 装 ObserveCenter（TempSandbox 根＋假钟＋剧本收割；单例跨测试先复位）。 */
    private fun installIn(root: File) {
        ObserveCenter.resetForTest()
        ObserveCenter.install(
            root = root,
            cardTexts = texts,
            clock = { fakeNow },
            harvest = { _ ->
                drainCalls.add("harvest")
                drainQueue.poll()
            },
            nodeTable = { _ -> nodeQueue.poll() },
            enabled = { observeOn },
        )
    }

    private fun seedItem(root: File, id: String): NotLearnedItem {
        val store = NotLearnedStore(File(root, NotLearnedStore.FILE_NAME))
        val item = NotLearnedItem(
            id = id,
            type = LearnTriggerType.NO_EVIDENCE,
            createdAt = fakeNow,
            sessionId = "s",
            stepSeq = 3,
            url = "https://example.test/list",
            pageName = "列表页",
            kind = "click",
            faces = null,
            question = "第 3 步点了「提交」但页面没变化，这一步想做什么？",
            options = listOf("就教这一步", "这一步不算"),
            freeTextPrompt = "或者直接说你的想法",
        )
        store.add(listOf(item))
        return item
    }

    /** tick 是挂起函数（收割在 IO）：测试里用 runBlocking 直呼。 */
    private fun tickNow(tabId: Int? = 7) = kotlinx.coroutines.runBlocking { ObserveCenter.tick(tabId) }

    private fun sessionFiles(root: File): List<File> =
        (root.listFiles() ?: emptyArray()).filter { it.name.startsWith(ObserveSession.FILE_PREFIX) }

    // ── 判据①：关 ⇒ 一次不收割、0 记录 ─────────────────────────────────────────

    @Test
    fun `判据1_开关关_一次都不收割`() {
        TempSandbox.use("zd624-center-off") { root ->
            observeOn = false
            installIn(root)
            tickNow()
            assertEquals("关着时一次收割都不发生", 0, drainCalls.size)
            assertEquals(0, ObserveCenter.dataFileCount())
            assertFalse(ObserveCenter.sessionActive.value)
        }
    }

    @Test
    fun `判据1_关着时积在页内的批次被丢弃_不补记`() {
        TempSandbox.use("zd624-center-discard") { root ->
            observeOn = true
            installIn(root)
            drainQueue.add(batch("click" to 1, url = "https://on.test/a"))
            tickNow()
            assertTrue(sessionFiles(root).single().readText().contains("https://on.test/a"))

            observeOn = false
            ObserveCenter.refresh() // 关 ⇒ 收会话＋下一批丢弃
            drainQueue.add(batch("click" to 2, url = "https://off.test/b"))
            tickNow()
            assertFalse("关着时积的批次不入账（判据①）", sessionFiles(root).any { it.readText().contains("https://off.test/b") })

            observeOn = true
            drainQueue.add(batch("click" to 3, url = "https://on.test/c"))
            tickNow() // 第一轮吃掉的是「丢弃位」（b 那一批）
            drainQueue.add(batch("click" to 4, url = "https://on.test/c"))
            tickNow() // 重开之后的第一批照常入账
            assertTrue("重开之后照常记", sessionFiles(root).any { it.readText().contains("https://on.test/c") })
        }
    }

    // ── 收会话出卡（E→F 的接线）───────────────────────────────────────────────────

    @Test
    fun `收会话_结构校验的触发进清单`() {
        TempSandbox.use("zd624-center-close") { root ->
            observeOn = true
            installIn(root)
            // 先割一份节点表（nc=10）→ 点击 → 心跳再割一份（仍 nc=10）＝「看过，没变化」
            drainQueue.add(batch(page = true, nc = 10))
            tickNow()
            fakeNow += 10_000
            drainQueue.add(batch("click" to 2, nc = 10))
            tickNow()
            fakeNow += 10_000
            drainQueue.add(batch(page = true, nc = 10)) // 心跳档：同 URL 同节点数
            tickNow()
            assertEquals(0, ObserveCenter.notLearned.value.count) // 会话还开着，不出卡

            observeOn = false
            ObserveCenter.refresh() // 收会话 ⇒ 校验 ⇒ 出卡
            assertEquals(1, ObserveCenter.notLearned.value.count)
            assertEquals(
                LearnTriggerType.NO_EVIDENCE,
                ObserveCenter.notLearned.value.items.single().type,
            )
        }
    }

    // ── 判据⑥：「就教这一步」只补该步 ─────────────────────────────────────────────

    @Test
    fun `判据6_补录一条即消项_且不落观察账`() {
        TempSandbox.use("zd624-center-teach") { root ->
            observeOn = false // 关观察也能教（显式教学不受开关牵连）
            seedItem(root, "t1") // 先落清单文件，再 install（install 读回）
            installIn(root)
            assertEquals(1, ObserveCenter.notLearned.value.count)

            ObserveCenter.requestTeachStep("t1")
            assertTrue("武装成功", ObserveCenter.armed.value != null)
            drainQueue.add(batch("click" to 9)) // 页面上补做的那一步
            tickNow()

            assertEquals("教完数字减一", 0, ObserveCenter.notLearned.value.count)
            assertNull(ObserveCenter.armed.value)
            assertEquals("补录不落观察账（只补该步，不重录全程）", 0, ObserveCenter.dataFileCount())
        }
    }

    @Test
    fun `武装超时自动解除_条目保留`() {
        TempSandbox.use("zd624-center-timeout") { root ->
            observeOn = false
            seedItem(root, "t1")
            installIn(root)
            ObserveCenter.requestTeachStep("t1")
            fakeNow += ObserveCenter.ARM_TIMEOUT_MS + 1
            drainQueue.add(batch()) // 空批次（页面没动静）
            tickNow()
            assertNull("到点解除", ObserveCenter.armed.value)
            assertEquals("条目没教成，留在清单", 1, ObserveCenter.notLearned.value.count)
        }
    }
}
