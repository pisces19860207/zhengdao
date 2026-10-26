// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百二十七批 §0.6 的判据：7 天内 3 条 rejected ⇒ 出现一次；2 条 ⇒ 不出现；
// 换 kind（或换域）⇒ 不出现；外加：只走 R2/R3、≤40 字、不摊条目/时间/证据 id。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.agent.ToolCall
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditHintTest {

    private val now = 10_000_000_000L

    private fun tc(name: String, path: String? = null) = ToolCall(
        "c",
        name,
        JSONObject().apply { if (path != null) put("path", path) },
    )

    private fun autoAct(id: String, tool: String, target: String, at: Long) = DecisionRecord(
        id = id,
        kind = DecisionKind.AUTO_ACT,
        group = DecisionGroup.ARTIFACT,
        actor = DecisionActor.MODEL,
        reason = "工具 $tool 执行成功",
        evidence = "tool:$tool:$target",
        before = target,
        after = "已执行 $tool",
        atMs = at,
        sessionId = -1L,
        reversible = false,
        rollbackRef = "",
    )

    private fun rollback(id: String, of: String, at: Long) = DecisionRecord(
        id = id,
        kind = DecisionKind.ROLLBACK_OF,
        group = DecisionGroup.ARTIFACT,
        actor = DecisionActor.USER,
        reason = "回滚",
        evidence = of,
        before = "",
        after = "",
        atMs = at,
        sessionId = -1L,
        reversible = false,
        rollbackRef = "",
        rollbackOf = of,
    )

    private fun wrong(recordId: String, at: Long) = DecisionReview.Entry(
        recordId = recordId,
        kind = "auto_act",
        verdict = DecisionRecord.VERDICT_WRONG,
        actionSummary = "",
        matrixVersion = "m0",
        reasonCode = "auto_act",
        evidence = "",
        atMs = at,
    )

    private fun threeWrites() = listOf(
        autoAct("w1", "write_file", "资料库/a.md", now - 5_000),
        autoAct("w2", "write_file", "资料库/b.md", now - 4_000),
        autoAct("w3", "write_file", "资料库/c.md", now - 3_000),
        rollback("r1", "w1", now - 2_000),
        rollback("r2", "w2", now - 1_500),
        rollback("r3", "w3", now - 1_000),
    )

    @Test
    fun `判据_7天内3条被拒出现一次`() {
        val call = tc("write_file", "资料库/x.md")
        val impact = Impact(Impact.Kind.WRITE, newFiles = 1)
        val line = AuditHint.lineFor(call, impact, threeWrites(), emptyList(), now)
        assertEquals("这类写入你近 7 天拒过 3 次", line)

        ImpactInject.reset()
        val first = ImpactInject.noteFor(call, impact, now, historyHint = line)
        val second = ImpactInject.noteFor(call, impact, now + 1_000, historyHint = line)
        assertNotNull("第一次要注入", first)
        assertNull("同一句 8 秒内只出现一次", second)
        assertNotNull("过了去重窗可以再说一次", ImpactInject.noteFor(call, impact, now + 9_000, historyHint = line))
    }

    @Test
    fun `判据_7天内2条不出现`() {
        val records = threeWrites().filterNot { it.id == "r3" || it.id == "w3" }
        val line = AuditHint.lineFor(
            tc("write_file", "资料库/x.md"),
            Impact(Impact.Kind.WRITE),
            records,
            emptyList(),
            now,
        )
        assertNull(line)
    }

    @Test
    fun `判据_换kind不出现`() {
        val records = listOf(
            autoAct("d1", "delete_path", "资料库/a.md", now - 5_000),
            autoAct("d2", "delete_path", "资料库/b.md", now - 4_000),
            autoAct("d3", "delete_path", "资料库/c.md", now - 3_000),
        ) + listOf(
            rollback("rd1", "d1", now - 2_000),
            rollback("rd2", "d2", now - 1_500),
            rollback("rd3", "d3", now - 1_000),
        )
        assertNull(
            AuditHint.lineFor(
                tc("write_file", "资料库/x.md"),
                Impact(Impact.Kind.WRITE),
                records,
                emptyList(),
                now,
            ),
        )
    }

    @Test
    fun `判据_换域不出现`() {
        val records = listOf(
            autoAct("w1", "write_file", "日记/a.md", now - 5_000),
            autoAct("w2", "write_file", "日记/b.md", now - 4_000),
            autoAct("w3", "write_file", "日记/c.md", now - 3_000),
        ) + listOf(
            rollback("r1", "w1", now - 2_000),
            rollback("r2", "w2", now - 1_500),
            rollback("r3", "w3", now - 1_000),
        )
        assertNull(
            AuditHint.lineFor(
                tc("write_file", "资料库/x.md"),
                Impact(Impact.Kind.WRITE),
                records,
                emptyList(),
                now,
            ),
        )
    }

    @Test
    fun `判据_窗口外不出现`() {
        val old = now - AuditHint.WINDOW_MS - 1_000
        val records = listOf(
            autoAct("w1", "write_file", "资料库/a.md", old),
            autoAct("w2", "write_file", "资料库/b.md", old),
            autoAct("w3", "write_file", "资料库/c.md", old),
        ) + listOf(
            rollback("r1", "w1", old),
            rollback("r2", "w2", old),
            rollback("r3", "w3", old),
        )
        assertNull(
            AuditHint.lineFor(
                tc("write_file", "资料库/x.md"),
                Impact(Impact.Kind.WRITE),
                records,
                emptyList(),
                now,
            ),
        )
    }

    @Test
    fun `判据_判错也计数_同一条不重复计数`() {
        val records = listOf(
            autoAct("w1", "write_file", "资料库/a.md", now - 5_000),
            autoAct("w2", "write_file", "资料库/b.md", now - 4_000),
            autoAct("w3", "write_file", "资料库/c.md", now - 3_000),
        )
        val reviews = listOf(
            wrong("w1", now - 2_000),
            wrong("w2", now - 1_500),
            wrong("w3", now - 1_000),
        )
        assertEquals(
            "这类写入你近 7 天拒过 3 次",
            AuditHint.lineFor(tc("write_file", "资料库/x.md"), Impact(Impact.Kind.WRITE), records, reviews, now),
        )

        // 同一条既被回滚又被判错 ⇒ 仍只算一次（2 条不够 3 次）
        val two = records.take(2) + listOf(rollback("r1", "w1", now - 1_200), rollback("r2", "w2", now - 1_100))
        val both = listOf(wrong("w1", now - 900), wrong("w2", now - 800))
        assertNull(
            AuditHint.lineFor(tc("write_file", "资料库/x.md"), Impact(Impact.Kind.WRITE), two, both, now),
        )
    }

    @Test
    fun `判据_只走R2R3_只读不注入`() {
        val line = "这类写入你近 7 天拒过 3 次"
        ImpactInject.reset()
        assertNull(ImpactInject.noteFor(tc("read_file"), Impact(Impact.Kind.WRITE), now, historyHint = line))
        assertNotNull(ImpactInject.noteFor(tc("write_file"), Impact(Impact.Kind.WRITE), now, historyHint = line))
    }

    @Test
    fun `判据_不列条目不带时间与证据id_且不超40字`() {
        val line = AuditHint.lineFor(
            tc("write_file", "资料库/秘密文件.md"),
            Impact(Impact.Kind.WRITE),
            threeWrites(),
            emptyList(),
            now,
        )
        assertNotNull(line)
        assertTrue(line!!.length <= AuditHint.MAX_CHARS)
        assertFalse("不许带文件名/目录", line.contains("资料库") || line.contains(".md") || line.contains("秘密"))
        assertFalse("不许带记录 id", line.contains("w1") || line.contains("r1"))
        assertFalse("不许带证据前缀", line.contains("tool:"))
        assertFalse("不许带时刻", line.contains(now.toString()))
        assertFalse("不许换行", line.contains('\n') || line.contains('\r'))
    }
}
