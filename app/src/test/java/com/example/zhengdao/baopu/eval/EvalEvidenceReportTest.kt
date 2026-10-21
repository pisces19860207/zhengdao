// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百一十三批（2026-10-21）：eval 后验入库的**证据钉子**。
//   ① 仓库里 `eval/report-2026-10-21-rerun.json` 是用户真跑（RERUN-2026-10-21）的报告，
//      99 的 [EvalResultFile.parse] 必须读得出它 ⇒ 生产 `eval` 不再为 null；
//   ② 它必须干净：无 key、无绝对临时路径——这份会随构建被 stage 进 APK，脏东西会跟到线上；
//   ③ 构建期「挑最新 report → eval-result.json」的规则选中它后，固定名仍可解析；
//   ④ 缺报告仍 DEFER：入库只是补证据，不放宽 policy 的判定；
//   ⑤ 负例：拿一条只有 id 的 fixture 冒充真读数，必须被「真跑标记」门禁判红。
package com.example.zhengdao.baopu.eval

import com.example.zhengdao.baopu.EvolutionEvidence
import com.example.zhengdao.baopu.EvolutionEval
import com.example.zhengdao.baopu.EvolutionPolicy
import com.example.zhengdao.baopu.EvolutionReasonCode
import com.example.zhengdao.baopu.EvolutionRecommendation
import com.example.zhengdao.baopu.ProposalCandidate
import com.example.zhengdao.baopu.ProposalEvidence
import com.example.zhengdao.baopu.ProposalKind
import com.example.zhengdao.baopu.SelfCheckEvidence
import com.example.zhengdao.baopu.SkillOutcome
import com.example.zhengdao.baopu.inputFor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class EvalEvidenceReportTest {

    private val reportName = "report-2026-10-21-rerun.json"

    private fun repoRoot(): File {
        var d: File? = File(".").absoluteFile
        repeat(6) {
            if (d != null && File(d, "eval/cases.json").isFile) return d!!
            d = d?.parentFile
        }
        return File("..").absoluteFile
    }

    private fun report(): File = File(repoRoot(), "eval/$reportName")

    private fun parsed(): EvolutionEval =
        requireNotNull(EvalResultFile.parse(report().readText(Charsets.UTF_8))) {
            "$reportName 读不出 passRate/cases —— 99 证据会退回 null"
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun candidate() = ProposalCandidate(
        id = "c113",
        kind = ProposalKind.SKILL_BODY,
        target = "做卡流程",
        payload = "# 做卡流程\n\n先填必填项，再选格式，最后导出。",
        evidence = ProposalEvidence(runLogIds = listOf(3L), skillEventIds = listOf(1L), atMs = 99L),
        createdAt = 1L,
    )

    private fun okSelfCheck() = SelfCheckEvidence(
        passed = true,
        rollbackAvailable = true,
        caseIds = listOf("selfcheck:structure", "selfcheck:required_section"),
    )

    // ── ① 99 能读到它 ─────────────────────────────────────────────────────

    @Test
    fun `真报告可被99读取_eval不再为null`() {
        val ev = parsed()
        assertEquals("passRate", 1.0, ev.score, 1e-9)

        val baseline = JSONObject(File(repoRoot(), "eval/baseline.json").readText(Charsets.UTF_8))
        val arr = baseline.getJSONArray("passing")
        val aIds = (0 until arr.length()).map { arr.getString(it) }
        assertTrue("A 段水位 10 条必须一条不少：$aIds", ev.caseIds.containsAll(aIds))
        assertEquals("A 段 10 ＋ B/C 层 8", 18, ev.caseIds.size)
        assertEquals("B/C 层用例 8 条", 8, ev.caseIds.count { it.startsWith("persona-") })
    }

    @Test
    fun `真报告指纹与来源绑死_不是fixture`() {
        val bytes = report().readBytes()
        assertEquals("字节数变了就是换了一份报告，得重新对账", 10367, bytes.size)
        assertEquals(
            "sha256 必须与用户口述前缀 a21f3be9599bfd1c 一致",
            "a21f3be9599bfd1c5d921078734415d9d141239a8452ccd0b63e8a7cd7aecba1",
            sha256(bytes),
        )

        val o = JSONObject(report().readText(Charsets.UTF_8))
        assertEquals("RERUN-2026-10-21", o.getString("source"))
        assertFalse(
            "seed 未发 ⇒ 必须诚实标非确定性",
            o.getJSONObject("determinism").getBoolean("deterministic"),
        )
        assertTrue("fixture 跑不出耗时", o.getLong("elapsedMs") > 0)
        val cases = o.getJSONArray("cases")
        assertEquals(18, cases.length())
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val id = c.getString("id")
            assertTrue("$id 缺 ok", c.has("ok"))
            assertTrue("$id 没有真跑步数", c.getInt("steps") >= 1)
            assertTrue("$id 没有真跑 token 读数", c.getInt("tokensIn") > 0)
        }
    }

    // ── ② 干净：无 key / 无本机路径 ───────────────────────────────────────

    @Test
    fun `真报告不含key与绝对路径`() {
        val text = report().readText(Charsets.UTF_8)
        listOf(
            "sk-", "apiKey", "api_key", "Authorization", "Bearer ",
            "ZHENGDAO_EVAL_KEY", "C:\\", "D:\\", "\\Temp\\", "/tmp/", "/home/",
            "/Users/", "guoli", "AndroidStudioProjects",
        ).forEach { marker ->
            assertFalse("报告不该出现「$marker」（会被 stage 进 APK）", text.contains(marker))
        }
        val cases = JSONObject(text).getJSONArray("cases")
        for (i in 0 until cases.length()) {
            assertFalse(
                "case 不该带临时工作区路径 dir：${cases.getJSONObject(i).getString("id")}",
                cases.getJSONObject(i).has("dir"),
            )
        }
    }

    // ── ③ 构建期桥：挑最新 → eval-result.json → 仍可解析 ─────────────────

    @Test
    fun `构建期只拿这一份时_必被选中且落成固定名仍可解析`() {
        // 在临时目录里复现 stage 的挑选规则：文件名数字时间戳最大者胜，非数字按最早。
        // 不直接扫仓库 eval/——本地真跑留下的 gitignored 报告不该让这条门禁红。
        val tmp = kotlin.io.path.createTempDirectory("eval113").toFile()
        try {
            File(tmp, reportName).writeBytes(report().readBytes())
            val reports = tmp.listFiles { f ->
                f.isFile && f.name.startsWith("report-") && f.name.endsWith(".json")
            }.orEmpty()
            assertTrue("至少有一份报告", reports.isNotEmpty())
            val latest = reports.maxByOrNull {
                it.name.removePrefix("report-").removeSuffix(".json").toLongOrNull() ?: Long.MIN_VALUE
            }!!
            assertEquals("挑中的必须是入库那份", reportName, latest.name)

            val staged = File(tmp, "eval-result.json")
            staged.writeBytes(latest.readBytes())
            assertEquals(
                "stage 是逐字节复制，固定名解析结果必须一模一样",
                parsed(),
                EvalResultFile.parse(staged.readText(Charsets.UTF_8)),
            )
        } finally {
            tmp.deleteRecursively()
        }
    }

    // ── ④ 缺报告仍 DEFER；真报告作证据能走到 EVAL_PASSED ─────────────────

    @Test
    fun `缺报告仍DEFER_入库不放宽判定`() {
        val judgement = EvolutionPolicy().judge(
            inputFor(
                candidate(),
                EvolutionEvidence(
                    effect = mapOf(SkillOutcome.SUCCESS to 3),
                    selfCheck = okSelfCheck(),
                    eval = null,
                ),
            ),
        )
        assertEquals(EvolutionRecommendation.DEFER, judgement.recommendation)
        assertTrue(
            "UI 要能看出「eval 未跑」",
            judgement.reasons.any { it.code == EvolutionReasonCode.EVAL_NOT_RUN },
        )
    }

    @Test
    fun `真报告作证据_判定链读到EVAL_PASSED`() {
        val judgement = EvolutionPolicy().judge(
            inputFor(
                candidate(),
                EvolutionEvidence(
                    effect = mapOf(SkillOutcome.SUCCESS to 3),
                    selfCheck = okSelfCheck(),
                    eval = parsed(),
                ),
            ),
        )
        assertEquals(EvolutionRecommendation.ADOPT, judgement.recommendation)
        assertTrue(
            "真报告过线要在原因链里可见",
            judgement.reasons.any { it.code == EvolutionReasonCode.EVAL_PASSED },
        )
        assertTrue("v1 恒人批", judgement.requiresApproval)
    }

    // ── ⑤ 负例：fixture 冒充真读数 ────────────────────────────────────────

    @Test
    fun `负例_fixture冒充真读数_被真跑标记门禁判红`() {
        val fixture = """{"passRate":1.0,"source":"FIXTURE","cases":[{"id":"case-1","ok":true}]}"""
        val err = runCatching { assertRealRunShape(fixture) }.exceptionOrNull()
        assertNotNull("只有 id 的 fixture 必须红", err)

        // 同一道门禁对入库报告必须绿（否则门禁本身太严，等于摆设）
        assertRealRunShape(report().readText(Charsets.UTF_8))
    }

    /**
     * 「真跑读数」的最低形状：来源可辨 + 有耗时 + 至少 A 段 10 条 + 每条都有步数与 token。
     * 这不是防盗（谁都能手写），是防**我们自己**把测试 fixture 当成交付证据。
     */
    private fun assertRealRunShape(json: String) {
        val o = JSONObject(json)
        val source = o.optString("source")
        if (!(source.startsWith("RERUN-") || source.startsWith("REAL-"))) {
            fail("来源必须是真跑标记（RERUN-/REAL-），实际「$source」")
        }
        if (o.optLong("elapsedMs") <= 0) fail("缺耗时：fixture 不会真的跑")
        val cases = o.optJSONArray("cases")
        assertNotNull("缺 cases", cases)
        if (cases!!.length() < 10) fail("真跑至少 A 段 10 条，实际 ${cases.length()}")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            if (!c.has("steps") || !c.has("tokensIn")) fail("${c.optString("id")} 缺真跑步数/token")
        }
    }
}
