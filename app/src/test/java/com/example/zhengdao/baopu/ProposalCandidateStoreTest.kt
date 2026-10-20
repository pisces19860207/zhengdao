// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第八十六批 §2/§4：候选仓的读写、同 hash 去重、坏档备份、证据只存 id。
// 全在临时目录里跑（JVM，无 Android/DB）。
package com.example.zhengdao.baopu

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProposalCandidateStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val logs = mutableListOf<String>()

    private fun store() = ProposalCandidateStore(tmp.root, log = { logs += it })

    private fun candidate(
        id: String = "0123456789abcdef",
        target: String = "做卡流程",
        payload: String = "技能规则：先看名册",
        createdAt: Long = 1L,
        runIds: List<Long> = listOf(3L, 4L),
        eventIds: List<Long> = listOf(1L, 2L),
        atMs: Long = 99L,
    ) = ProposalCandidate(
        id = id,
        kind = ProposalKind.SKILL_BODY,
        target = target,
        payload = payload,
        evidence = ProposalEvidence(runLogIds = runIds, skillEventIds = eventIds, atMs = atMs),
        status = ProposalStatus.PENDING,
        createdAt = createdAt,
    )

    // ── 读写 ────────────────────────────────────────────────────────────────

    @Test
    fun `读写往返_字段与证据id一个不丢`() {
        val c = candidate()
        assertEquals(1, store().appendNew(listOf(c), stamp = 5L))
        assertEquals(listOf(c), store().listPending())
        assertFalse("临时文件不许残留", File(tmp.root, "${ProposalCandidateStore.FILE_NAME}.tmp").exists())
    }

    @Test
    fun `编码形态_证据只有runLogIds_skillEventIds_atMs`() {
        store().appendNew(listOf(candidate()), stamp = 5L)
        val rootObj = JSONObject(store().file().readText(Charsets.UTF_8))
        assertEquals("格式版本", 1, rootObj.getInt("version"))
        val arr = rootObj.getJSONArray("candidates")
        assertEquals(1, arr.length())
        val c = arr.getJSONObject(0)
        val keys = c.keys().asSequence().toSet()
        assertEquals(
            "候选字段只有派工单 §2 那七个",
            setOf("id", "kind", "target", "payload", "evidence", "status", "created_at"),
            keys,
        )
        val ev = c.getJSONObject("evidence")
        assertEquals(
            "证据只存 id/时间，不复制正文",
            setOf("run_log_ids", "skill_event_ids", "at_ms"),
            ev.keys().asSequence().toSet(),
        )
        assertEquals(2, ev.getJSONArray("run_log_ids").length())
        assertEquals(2, ev.getJSONArray("skill_event_ids").length())
        assertEquals(99L, ev.getLong("at_ms"))
        assertEquals("PENDING", c.getString("status"))
    }

    @Test
    fun `编解码往返_两条顺序与内容不变`() {
        val list = listOf(candidate(id = "aaaaaaaaaaaaaaaa", createdAt = 2L), candidate(id = "bbbbbbbbbbbbbbbb", createdAt = 1L))
        val decoded = store().decodeAll(store().encodeAll(list))
        assertNotNull(decoded)
        assertEquals(list, decoded)
    }

    @Test
    fun `listPending_按createdAt再按id稳定排序`() {
        store().appendNew(
            listOf(candidate(id = "bbbbbbbbbbbbbbbb", createdAt = 2L), candidate(id = "aaaaaaaaaaaaaaaa", createdAt = 1L)),
            stamp = 5L,
        )
        assertEquals(listOf("aaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb"), store().listPending().map { it.id })
    }

    // ── 去重 ────────────────────────────────────────────────────────────────

    @Test
    fun `同id不重复写_跨次追加也只留第一条`() {
        val s = store()
        assertEquals(1, s.appendNew(listOf(candidate()), stamp = 5L))
        assertEquals(
            "同 hash 已在 PENDING ⇒ 不再写",
            0,
            s.appendNew(listOf(candidate(payload = "换了正文也不许顶掉")), stamp = 6L),
        )
        assertEquals("原来那条保持不变", "技能规则：先看名册", s.listPending().single().payload)
        assertEquals(0, s.appendNew(emptyList(), stamp = 7L))
        assertEquals(1, s.listPending().size)
    }

    @Test
    fun `空id或空正文不进仓`() {
        val s = store()
        assertEquals(
            0,
            s.appendNew(
                listOf(candidate(id = " "), candidate(id = "cccccccccccccccc", payload = "  ")),
                stamp = 5L,
            ),
        )
        assertTrue(s.listPending().isEmpty())
        assertFalse(s.file().exists())
    }

    @Test
    fun `未知状态不冒充PENDING`() {
        val good = JSONObject()
            .put("id", "aaaaaaaaaaaaaaaa")
            .put("kind", "SKILL_BODY")
            .put("target", "做卡流程")
            .put("payload", "正文")
            .put("evidence", JSONObject())
            .put("status", "PENDING")
            .put("created_at", 1L)
        val future = JSONObject()
            .put("id", "bbbbbbbbbbbbbbbb")
            .put("kind", "SKILL_BODY")
            .put("target", "做卡流程")
            .put("payload", "正文")
            .put("evidence", JSONObject())
            .put("status", "APPLIED")
            .put("created_at", 2L)
        val text = JSONObject()
            .put("version", 1)
            .put("candidates", JSONArray().put(good).put(future))
            .toString()
        store().file().parentFile?.mkdirs()
        store().file().writeText(text, Charsets.UTF_8)
        assertEquals(1, store().listPending().size)
        assertEquals("aaaaaaaaaaaaaaaa", store().listPending().single().id)
    }

    // ── 坏档 ────────────────────────────────────────────────────────────────

    @Test
    fun `坏JSON_读时当空表_追加前先备份原字节`() {
        val badText = "{ 这不是 json"
        val s = store()
        s.file().parentFile?.mkdirs()
        s.file().writeText(badText, Charsets.UTF_8)
        assertTrue("读路径不改盘", s.listPending().isEmpty())
        assertEquals("坏档原样还在（读不写）", badText, s.file().readText(Charsets.UTF_8))

        assertEquals(1, s.appendNew(listOf(candidate()), stamp = 42L))
        val backup = File(tmp.root, "${ProposalCandidateStore.FILE_NAME}.bad-42")
        assertTrue("坏档要留备份", backup.isFile)
        assertEquals("备份必须是原字节，不是重编码", badText, backup.readText(Charsets.UTF_8))
        assertEquals(1, s.listPending().size)
        assertTrue(logs.any { it.contains("坏档已备份") })
    }

    @Test
    fun `坏JSON_备份失败就本轮不写_绝不覆盖原档`() {
        val badText = "{ 坏档"
        val s = store()
        s.file().parentFile?.mkdirs()
        s.file().writeText(badText, Charsets.UTF_8)
        // 占住备份落点（非空目录 ⇒ Files.move 替换失败）
        val blocked = File(tmp.root, "${ProposalCandidateStore.FILE_NAME}.bad-42")
        blocked.mkdirs()
        File(blocked, "占位").writeText("x")

        assertEquals(0, s.appendNew(listOf(candidate()), stamp = 42L))
        assertEquals("宁可不写，也不覆盖", badText, s.file().readText(Charsets.UTF_8))
        assertTrue(logs.any { it.contains("备份失败") })
    }

    @Test
    fun `单条坏行只跳过自己_其余照读`() {
        val good = JSONObject()
            .put("id", "aaaaaaaaaaaaaaaa")
            .put("kind", "SKILL_BODY")
            .put("target", "做卡流程")
            .put("payload", "正文")
            .put("evidence", JSONObject())
            .put("status", "PENDING")
            .put("created_at", 1L)
        val noId = JSONObject().put("kind", "SKILL_BODY").put("status", "PENDING")
        val badKind = JSONObject()
            .put("id", "cccccccccccccccc")
            .put("kind", "NOT_A_KIND")
            .put("status", "PENDING")
        val text = JSONObject()
            .put("version", 1)
            .put("candidates", JSONArray().put(good).put(noId).put(badKind))
            .toString()
        store().file().parentFile?.mkdirs()
        store().file().writeText(text, Charsets.UTF_8)
        assertEquals(1, store().listPending().size)
    }

    @Test
    fun `没有文件时_读是空表`() {
        assertTrue(store().listPending().isEmpty())
        assertFalse(store().file().exists())
    }

    // ── 只碰候选文件 ────────────────────────────────────────────────────────

    @Test
    fun `追加候选_技能与人设文件零变化`() {
        val skill = File(tmp.root, "技能/做卡流程/SKILL.md")
        skill.parentFile?.mkdirs()
        skill.writeText("# 技能\n原有正文\n", Charsets.UTF_8)
        val persona = File(tmp.root, "全局/人设.md")
        persona.parentFile?.mkdirs()
        persona.writeText("# 人设\n原有内容\n", Charsets.UTF_8)
        val beforeSkill = skill.readBytes()
        val beforePersona = persona.readBytes()

        assertEquals(1, store().appendNew(listOf(candidate()), stamp = 5L))

        assertArrayEquals("技能正文一个字节不动", beforeSkill, skill.readBytes())
        assertArrayEquals("人设一个字节不动", beforePersona, persona.readBytes())
        val files = tmp.root.walkTopDown().filter { it.isFile }.map { it.relativeTo(tmp.root).invariantSeparatorsPath }.toSet()
        assertEquals(
            "只多出候选 JSON 一个文件",
            setOf("技能/做卡流程/SKILL.md", "全局/人设.md", ProposalCandidateStore.FILE_NAME),
            files,
        )
    }
}
