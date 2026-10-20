// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第八十六批 §4 的**源码门禁**：有些口径跑不出结果（控制器要 Android 上下文，
// 「以后没人把生成接到写盘」这种断言只能盯源码）。每条门禁都配一条负例样本——
// 门禁本身必须能红，否则只是三行 assertTrue。
package com.example.zhengdao.baopu

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProposalCandidateWiringTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    private fun raw(rel: String): String = repoFile(rel).readText(Charsets.UTF_8).replace("\r\n", "\n")

    private fun slice(src: String, start: String, end: String): String {
        val i = src.indexOf(start)
        assertTrue("源码里找不到起点：$start", i >= 0)
        val j = src.indexOf(end, startIndex = i + start.length)
        assertTrue("源码里找不到终点：$end（起点之后）", j > i)
        return src.substring(i, j)
    }

    private val controller = raw("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt")
    private val engine = raw("src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt")
    private val screen = raw("src/main/java/com/example/zhengdao/ui/baopu/BaopuScreen.kt")
    private val learnCard = raw("src/main/java/com/example/zhengdao/ui/baopu/BaopuLearnCard.kt")
    private val db = raw("src/main/java/com/example/zhengdao/baopu/BaopuDb.kt")

    private val candidateSrc = raw("src/main/java/com/example/zhengdao/baopu/ProposalCandidate.kt")
    private val rulesSrc = raw("src/main/java/com/example/zhengdao/baopu/ProposalRules.kt")
    private val storeSrc = raw("src/main/java/com/example/zhengdao/baopu/ProposalCandidateStore.kt")

    private val generateBlock = slice(
        controller,
        "private fun generateProposalCandidatesOnSettle()",
        "* 读记录 → 纯判据生成",
    )
    private val appendBlock = slice(
        controller,
        "private fun appendProposalCandidates(",
        "/** 两层技能正文",
    )
    private val readBodiesBlock = slice(
        controller,
        "private fun readExistingSkillBodies(",
        "// ── 技能沉淀",
    )
    private val chain = generateBlock + appendBlock + readBodiesBlock

    // ── 接线 ────────────────────────────────────────────────────────────────

    @Test
    fun `收尾接线_Settled里且排在refineOnSettle之后`() {
        val forceBlock = slice(
            controller,
            "val force = ev is EngineEvent.Settled",
            "consumePendingSwitch()",
        )
        assertTrue("收尾块里要有生成调用", forceBlock.contains("generateProposalCandidatesOnSettle()"))
        assertTrue(
            "顺序：先记忆提炼，再候选生成",
            forceBlock.indexOf("refineOnSettle()") < forceBlock.indexOf("generateProposalCandidatesOnSettle()"),
        )
    }

    @Test
    fun `生成链_只读已有记录_不抓用户正文不跑模型`() {
        for (token in listOf(
            "engine.learn.value",
            "skill_event",
            "run_log",
            "readRejectedDecisionHashes",
            "store.listPending()",
            "ProposalRules.generate",
            "store.appendNew",
        )) {
            assertTrue("生成链缺少已有记录来源：$token", chain.contains(token))
        }
        for (forbidden in listOf(
            "readUserTextsFor",
            "FROM message",
            "JOIN part",
            "message.role = 'user'",
            "userTexts",
            "OkHttp",
            "java.net",
        )) {
            assertFalse("生成链不许新抓用户正文/联网：$forbidden", chain.contains(forbidden))
        }
    }

    @Test
    fun `读接口只有listPending_没有改状态或删除口`() {
        assertTrue(storeSrc.contains("fun listPending("))
        for (forbidden in listOf(
            "fun update(",
            "fun delete(",
            "fun setStatus(",
            "fun markApplied(",
            "fun archive(",
        )) {
            assertFalse("候选仓不该有第二套写口：$forbidden", storeSrc.contains(forbidden))
        }
    }

    @Test
    fun `存储没有新增表_也没有migration`() {
        assertFalse("本批不加表", db.contains("proposal_candidate"))
        assertFalse("候选仓不摸 SQLite", storeSrc.contains("SQLite"))
        assertFalse("候选仓不执行 SQL", storeSrc.contains("execSQL"))
    }

    // ── 不自动应用（门禁＋负例） ─────────────────────────────────────────────

    private fun noAutoApply(src: String): Boolean = listOf(
        "applyProposal(",
        "SkillStore.apply(",
        "SkillStore.plan(",
        "writeLearnedSkill(",
        ".restore(",
    ).none { src.contains(it) }

    @Test
    fun `生成链不碰技能人设写盘_负例必须红`() {
        val all = chain + candidateSrc + rulesSrc + storeSrc
        assertTrue("真源码必须过门禁", noAutoApply(all))
        assertFalse(
            "负例：把生成接到 applyProposal 必须被门禁抓住",
            noAutoApply("val ok = PersonaFiles.applyProposal(proposal)"),
        )
        assertFalse(
            "负例：接到 SkillStore.apply 也要红",
            noAutoApply("SkillStore.apply(plan, pf, text)"),
        )
    }

    /// 证据对象只许出现这三个键；出现正文类键一律红。
    private fun evidenceIdsOnly(json: JSONObject): Boolean {
        val ev = json.optJSONObject("evidence") ?: return false
        val keys = ev.keys().asSequence().toSet()
        if (keys != setOf("run_log_ids", "skill_event_ids", "at_ms")) return false
        return keys.none { k ->
            listOf("body", "text", "payload", "content", "detail").any { k.contains(it) }
        }
    }

    @Test
    fun `证据只存id和时间_负例把正文塞进证据会红`() {
        val c = ProposalCandidate(
            id = "0123456789abcdef",
            kind = ProposalKind.SKILL_BODY,
            target = "做卡流程",
            payload = "技能规则：先看名册",
            evidence = ProposalEvidence(runLogIds = listOf(1L), skillEventIds = listOf(2L), atMs = 3L),
            createdAt = 4L,
        )
        val encoded = ProposalCandidateStore(File("."), log = {}).encodeAll(listOf(c))
        val obj = JSONObject(encoded).getJSONArray("candidates").getJSONObject(0)
        assertTrue("真编码必须过证据门禁：$encoded", evidenceIdsOnly(obj))
        assertFalse(
            "负例：证据里写正文必须红",
            evidenceIdsOnly(JSONObject().put("evidence", JSONObject().put("body", "用户原话"))),
        )
        assertFalse(
            "负例：证据里再塞 payload 也必须红",
            evidenceIdsOnly(JSONObject().put("evidence", JSONObject().put("payload", "用户原话"))),
        )

        // 模型层也不给正文留位置：ProposalEvidence 只有 id 列表与时间
        val ev = slice(candidateSrc, "internal data class ProposalEvidence(", "internal data class ProposalCandidate(")
        assertFalse("证据类不许有 String 字段", ev.contains("String"))
        for (f in listOf("runLogIds", "skillEventIds", "atMs")) {
            assertTrue("证据字段：$f", ev.contains(f))
        }
    }

    @Test
    fun `候选形状_七字段齐全_状态恒PENDING`() {
        for (f in listOf("id", "kind", "target", "payload", "evidence", "status", "createdAt")) {
            assertTrue("候选缺字段：$f", candidateSrc.contains(f))
        }
        assertTrue(
            "默认状态必须是 PENDING（本批不产生别的状态）",
            candidateSrc.contains("val status: ProposalStatus = ProposalStatus.PENDING"),
        )
        assertTrue(
            "id = 名称＋正文的稳定 hash（与 57 的丢弃留痕同一把尺）",
            rulesSrc.contains("fun idOf(target: String, payload: String): String = SkillRefine.dedupeKey(target, payload)"),
        )
    }

    // ── 纯函数 / 不碰 UI ────────────────────────────────────────────────────

    @Test
    fun `生成器零IO零网络_可在JVM里直接钉`() {
        for (forbidden in listOf("java.io", "android.", "File(", "okhttp", "java.net", "Context")) {
            assertFalse("生成器不许摸这些：$forbidden", rulesSrc.contains(forbidden))
        }
    }

    @Test
    fun `引擎和UI没被接线_视觉零变化`() {
        for (forbidden in listOf("ProposalCandidate", "ProposalRules", "提案候选")) {
            assertFalse("RunEngine 不许接线：$forbidden", engine.contains(forbidden))
        }
        for (src in listOf(screen, learnCard)) {
            for (forbidden in listOf("ProposalCandidate", "ProposalRules", "提案候选")) {
                assertFalse("UI 不许出现候选：$forbidden", src.contains(forbidden))
            }
        }
        for (src in listOf(candidateSrc, rulesSrc, storeSrc)) {
            assertFalse("新逻辑不许引 Compose/UI", src.contains("androidx.compose"))
            assertFalse("新逻辑不许引 UI 包", src.contains("ui.baopu"))
        }
    }
}
