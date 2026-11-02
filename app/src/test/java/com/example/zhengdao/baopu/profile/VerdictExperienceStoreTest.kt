// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百八十批 · 判决经验跨重装的判据测试（判据③「重装后经验还在」）。
// 临时目录用真盘（房型照 DecisionLedgerTest）。
package com.example.zhengdao.baopu.profile

import com.example.zhengdao.baopu.CardDisposition
import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.agent.RoyalDecision
import com.example.zhengdao.baopu.agent.RoyalDecisionLedger
import com.example.zhengdao.baopu.agent.SelfRulingLedger
import com.example.zhengdao.baopu.agent.OpinionWiring
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerdictExperienceStoreTest {

    private fun tmp(tag: String): File =
        File.createTempFile("zd280-$tag", "").let { it.delete(); it.mkdirs(); it }

    /** 御批账的一行（与 RoyalDecision.toJson 同形状）。 */
    private fun royalRow(op: String, verdict: String, at: Long): String = JSONObject()
        .put("v", 1)
        .put("op", op)
        .put("verdict", verdict)
        .put("at", at)
        .put("day", "2026-10-29")
        .put("card", op)
        .toString()

    /** 追认队列的一行（与 SelfRuling.toJson 同形状；有 `id`）。 */
    private fun selfRulingRow(id: String, at: Long): String = JSONObject()
        .put("v", 1)
        .put("id", id)
        .put("at", at)
        .put("day", "2026-10-29")
        .put("round", "process")
        .put("tool", "kb_search")
        .put("gist", "判定摘要")
        .put("law", "L2")
        .put("why", "already_refused")
        .put("reason", "已经问过并遭拒，不再打扰")
        .put("op", "op-$id")
        .toString()

    private fun lines(f: File): List<String> =
        if (!f.isFile) emptyList() else f.readLines(Charsets.UTF_8).filter { it.isNotBlank() }

    // ── 判据③：重装后经验还在 ──────────────────────────────────────────────

    @Test
    fun `判据③_重装后私有账从公共副本回灌`() {
        val ws = tmp("ws")
        val mem = tmp("mem1")
        // 第一世：私有账 2 行御批 → 导出 2 行
        File(mem, "御批.jsonl").writeText(royalRow("op1", "approve", 1_000L) + "\n", Charsets.UTF_8)
        File(mem, "御批.jsonl").appendText(royalRow("op2", "reject", 2_000L) + "\n", Charsets.UTF_8)
        VerdictExperienceStore.install(ws, mem)
        val first = VerdictExperienceStore.sync()
        assertEquals(2, first.exported)
        assertEquals(0, first.restored)
        assertEquals(2, lines(File(ws, "判决经验/御批.jsonl")).size)
        // 重装：私有目录整个清掉（文件/记忆全没），公共副本不动
        mem.deleteRecursively()
        mem.mkdirs()
        VerdictExperienceStore.install(ws, mem)
        val second = VerdictExperienceStore.sync()
        assertEquals(0, second.exported)
        assertEquals(2, second.restored)
        // 回灌的行能被御批自己的解析器读回——这就是"历史回来了"
        val parsed = lines(File(mem, "御批.jsonl")).mapNotNull(RoyalDecision::parse)
        assertEquals(2, parsed.size)
        assertEquals(listOf("op1", "op2"), parsed.map { it.opinionId }.sorted())
    }

    @Test
    fun `sync幂等_重复跑不双写`() {
        val ws = tmp("ws")
        val mem = tmp("mem2")
        File(mem, "御批.jsonl").writeText(royalRow("op1", "approve", 1_000L) + "\n", Charsets.UTF_8)
        VerdictExperienceStore.install(ws, mem)
        assertEquals(1, VerdictExperienceStore.sync().exported)
        val again = VerdictExperienceStore.sync()
        assertEquals(0, again.exported)
        assertEquals(0, again.restored)
        assertEquals(1, lines(File(ws, "判决经验/御批.jsonl")).size)
    }

    @Test
    fun `双向并集_两侧各补各缺的行`() {
        val ws = tmp("ws")
        val mem = tmp("mem3")
        VerdictExperienceStore.install(ws, mem)
        // 私有先有 1 行并导出
        File(mem, "御批.jsonl").writeText(royalRow("op1", "approve", 1_000L) + "\n", Charsets.UTF_8)
        VerdictExperienceStore.sync()
        // 之后两侧各自新增：私有 op3、公共 op2（上一世的遗留）
        File(mem, "御批.jsonl").appendText(royalRow("op3", "hold", 3_000L) + "\n", Charsets.UTF_8)
        File(ws, "判决经验/御批.jsonl").appendText(royalRow("op2", "reject", 2_000L) + "\n", Charsets.UTF_8)
        val r = VerdictExperienceStore.sync()
        assertEquals(1, r.exported) // op3 去公共
        assertEquals(1, r.restored) // op2 回私有
        assertEquals(setOf("op1", "op2", "op3"), lines(File(mem, "御批.jsonl")).mapNotNull { l ->
            JSONObject(l).optString("op").ifEmpty { null }
        }.toSet())
    }

    @Test
    fun `坏行也镜像_按整行哈希去重`() {
        val ws = tmp("ws")
        val mem = tmp("mem4")
        val bad = "这行不是 JSON，但它是账本历史的一部分"
        File(mem, "追认队列.jsonl").writeText(selfRulingRow("sr1", 5_000L) + "\n$bad\n", Charsets.UTF_8)
        VerdictExperienceStore.install(ws, mem)
        assertEquals(2, VerdictExperienceStore.sync().exported)
        val copied = lines(File(ws, "判决经验/追认队列.jsonl"))
        assertEquals(2, copied.size)
        assertEquals("sr1", JSONObject(copied[0]).optString("id")) // 好行原样过来
        assertEquals(bad, copied[1]) // 坏行一个字节不动
    }

    @Test
    fun `四本账同名同文件名_与各自主对象钉死`() {
        assertEquals(
            // 第三百二十六批加第四本：卡处置账（318 学习信号的唯一来源）也必须跟着人走。
            listOf(
                RoyalDecisionLedger.FILE_NAME,
                OpinionWiring.FILE_NAME,
                SelfRulingLedger.FILE_NAME,
                CardDisposition.FILE_NAME,
            ),
            VerdictExperienceStore.MIRRORED,
        )
    }

    // ── 第三百二十六批 判据①：处置账进备份名单，行数与内容一致 ─────────────

    /** 造行：走真写入口（[CardDisposition.record]），落到 208 装配的那棵私有根。 */
    private fun seedDispositions(mem: File, n: Int = 3): List<String> {
        DecisionLedger.install(mem)
        CardDisposition.resetForTest()
        try {
            val choices = listOf(
                Triple(CardDisposition.Choice.APPROVE, CardDisposition.Actor.USER, "op-a"),
                Triple(CardDisposition.Choice.REJECT, CardDisposition.Actor.USER, "op-b"),
                Triple(CardDisposition.Choice.DISMISS, CardDisposition.Actor.CLOCK, "op-c"),
            )
            repeat(n) { i ->
                val (choice, actor, card) = choices[i]
                CardDisposition.record(
                    choice = choice,
                    face = CardDisposition.Face.CONFIRM,
                    cardId = card,
                    actor = actor,
                    subject = "删 资料库/$card.md",
                    atMs = 1_000L + i,
                )
            }
        } finally {
            DecisionLedger.uninstallForTest()
            CardDisposition.resetForTest()
        }
        return lines(File(mem, CardDisposition.FILE_NAME))
    }

    @Test
    fun `判据①_处置账造三行_备份恢复后行数与内容逐字一致`() {
        val ws = tmp("ws326")
        val mem = tmp("mem326")
        val made = seedDispositions(mem, 3)
        assertEquals("先得真造出 3 行（不然下面两条断言恒真）", 3, made.size)
        assertTrue("造的行要能被本账自己的解析器读回", made.all { CardDisposition.CardDispositionRow.parse(it) != null })

        // ① 备份（导出）：私有 → 公共副本
        VerdictExperienceStore.install(ws, mem)
        val backup = VerdictExperienceStore.sync()
        assertEquals("3 行都该导出", 3, backup.exported)
        val copied = lines(File(File(ws, VerdictExperienceStore.DIR_NAME), CardDisposition.FILE_NAME))
        assertEquals("备份里行数一致", 3, copied.size)
        assertEquals("备份里内容逐字一致", made, copied)

        // ② 重装（私有目录整个清掉）→ 恢复（回灌）
        mem.deleteRecursively()
        mem.mkdirs()
        VerdictExperienceStore.install(ws, mem)
        val restored = VerdictExperienceStore.sync()
        assertEquals("3 行都该回灌", 3, restored.restored)
        val back = lines(File(mem, CardDisposition.FILE_NAME))
        assertEquals("恢复后行数一致", 3, back.size)
        assertEquals("恢复后内容逐字一致", made, back)

        // ③ 回来的是**真能用的**学习信号：解析得出来、处置格没被搬丢
        val parsed = back.mapNotNull(CardDisposition.CardDispositionRow::parse)
        assertEquals(3, parsed.size)
        assertEquals(
            listOf("op-a", "op-b", "op-c"),
            parsed.sortedBy { it.cardId }.map { it.cardId },
        )
    }

    @Test
    fun `判据①_处置账在备份名单里_不是顺手镜像的副产品`() {
        // 负例靶心：把 `卡处置.jsonl` 从 MIRRORED 里拿掉 ⇒ 上面那条一致性闸与这条同时红。
        assertTrue(
            "卡处置账是 318 学习信号的唯一来源，丢了等于学习失忆",
            CardDisposition.FILE_NAME in VerdictExperienceStore.MIRRORED,
        )
    }

    @Test
    fun `同一棵目录时同步空转`() {
        val same = tmp("same")
        VerdictExperienceStore.install(same, same)
        File(same, "御批.jsonl").writeText(royalRow("op1", "approve", 1_000L) + "\n", Charsets.UTF_8)
        val r = VerdictExperienceStore.sync()
        assertEquals(0, r.exported)
        assertEquals(0, r.restored)
        assertEquals(1, lines(File(same, "御批.jsonl")).size) // 没有写出双份
    }

    @Test
    fun `结论只出计数不出内容`() {
        val ws = tmp("ws")
        val mem = tmp("mem5")
        val secret = "已经问过并遭拒，不再打扰"
        File(mem, "追认队列.jsonl").writeText(selfRulingRow("sr1", 5_000L) + "\n", Charsets.UTF_8)
        assertFalse(secret.isEmpty()) // 该行的 reason 字段就是这句
        VerdictExperienceStore.install(ws, mem)
        VerdictExperienceStore.sync()
        val line = VerdictExperienceStore.conclusion()
        assertFalse(line.contains(secret))
        assertFalse(line.contains("判定摘要"))
        assertTrue(line.contains("追认队列 公1/私1"))
    }
}
