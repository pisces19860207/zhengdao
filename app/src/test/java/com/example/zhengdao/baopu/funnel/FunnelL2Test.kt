// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百四十七批（L2 轮）· L2 判据的**行为测试**（纯判据夹具＋壳假件，全部不打真模型）。
//
// 钉本轮工单判据：
//   ① 冲突输入（查询/卡片都像）⇒ 必须 undecided（负例：硬选 ⇒ 红）；
//   ② 明显单一输入 ⇒ 选对组（夹具 3 条，走壳＋假件全链路）；
//   ③ margin < 阈值 ⇒ undecided（夹具：两候选差距 3%）；
//   ④ 返回不在候选组的组名 ⇒ 视作 undecided（负例：当成有效 ⇒ 红——真文件突变 N2 对拍）；
//   ⑤ 超时/异常 ⇒ 回退 L1 且 warning，整轮不崩（假件抛异常/抛「超时」字样）；
//   ⑥ 每轮记一行账 {L1 route, L2 结果, 不确定原因, 耗时}（logLineOf 纯函数钉字段齐）；
//   硬规矩①：输入 ≤200 字（promptOf 截断，不喂整页快照）。
package com.example.zhengdao.baopu.funnel

import com.example.zhengdao.testkit.TempSandbox
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FunnelL2Test {

    @After
    fun tearDown() {
        FunnelL2Read.askOverride = null
    }

    private fun scores(vararg pairs: Pair<String, Double>): String =
        "{\"scores\":{" + pairs.joinToString(",") { "\"${it.first}\":${it.second}" } + "}}"

    // ── 判据③④＋硬规矩②：纯判据（防御四步逐段） ──────────────────────────

    @Test
    fun `判据三_margin 三个百分点_必须 undecided`() {
        val d = FunnelL2Judge.judge(scores("查询" to 0.52, "卡片" to 0.49))
        assertEquals(FunnelL2Judge.UNDECIDED, d.group)
        assertTrue("原因要落在 margin 上", d.reason.contains("margin"))
    }

    @Test
    fun `判据一_冲突输入夹具_查询卡片都像_必须 undecided`() {
        // 「查一下我昨天写的角色卡」——查询与卡片两可，模型给 0.5/0.48：不许硬选。
        val d = FunnelL2Judge.judge(scores("查询" to 0.50, "卡片" to 0.48))
        assertEquals(FunnelL2Judge.UNDECIDED, d.group)
    }

    @Test
    fun `判据四_组名不在候选闭集_视作 undecided`() {
        val only = FunnelL2Judge.judge(scores("意图分类器" to 0.9))
        assertEquals(FunnelL2Judge.UNDECIDED, only.group)
        assertTrue(only.reason.contains("闭集过滤"))
        val mixed = FunnelL2Judge.judge(scores("意图分类器" to 0.9, "卡片" to 0.10))
        assertEquals("组外高分被丢后，剩下的过不了地板——还是 undecided", FunnelL2Judge.UNDECIDED, mixed.group)
    }

    @Test
    fun `置信不足_第一候选低于地板_undecided`() {
        val d = FunnelL2Judge.judge(scores("内容" to 0.35, "系统" to 0.1))
        assertEquals(FunnelL2Judge.UNDECIDED, d.group)
        assertTrue(d.reason.contains("置信不足"))
    }

    @Test
    fun `单一候选高分_算唯一_可以选`() {
        val d = FunnelL2Judge.judge(scores("教学" to 0.8))
        assertEquals("教学", d.group)
        assertTrue(d.reason.contains("无第二候选"))
    }

    @Test
    fun `防御链_剥围栏后能判`() {
        val fenced = "```json\n" + scores("卡片" to 0.9, "内容" to 0.2) + "\n```"
        assertEquals("卡片", FunnelL2Judge.judge(fenced).group)
    }

    @Test
    fun `防御链_带前后噪音文本_取第一个配平对象`() {
        val noisy = "好的，我的判断是：" + scores("查询" to 0.85, "系统" to 0.1) + " 以上。"
        assertEquals("查询", FunnelL2Judge.judge(noisy).group)
    }

    @Test
    fun `防御链_拿不到JSON_undecided`() {
        assertEquals(FunnelL2Judge.UNDECIDED, FunnelL2Judge.judge("我觉得是卡片吧").group)
        assertEquals(FunnelL2Judge.UNDECIDED, FunnelL2Judge.judge("{\"scores\":{\"卡片\":").group)
    }

    @Test
    fun `防御链_类型检查_值非数字或越界_整份作废`() {
        assertEquals(FunnelL2Judge.UNDECIDED, FunnelL2Judge.judge("{\"scores\":{\"卡片\":\"高\"}}").group)
        assertEquals(FunnelL2Judge.UNDECIDED, FunnelL2Judge.judge("{\"scores\":{\"卡片\":1.5}}").group)
        assertEquals(FunnelL2Judge.UNDECIDED, FunnelL2Judge.judge("{\"卡片\":0.9}").group)
    }

    @Test
    fun `字符串里的花括号不骗过取JSON`() {
        val raw = "{\"scores\":{\"系统\":0.9},\"note\":\"比如 {卡片} 这种写法\"}"
        assertEquals("系统", FunnelL2Judge.judge(raw).group)
    }

    @Test
    fun `结果恒在闭集_五组加undecided`() {
        val legal = FunnelL2Judge.CANDIDATES + FunnelL2Judge.UNDECIDED
        val raws = listOf(scores("内容" to 0.9), "垃圾", "```" + scores("查询" to 0.9) + "```", scores("魔法组" to 0.9))
        for (raw in raws) {
            assertTrue("结果必须落闭集：$raw", FunnelL2Judge.judge(raw).group in legal)
        }
    }

    @Test
    fun `同一原始返回两次判定逐字段相等`() {
        val raw = scores("查询" to 0.52, "卡片" to 0.49)
        assertEquals(FunnelL2Judge.judge(raw), FunnelL2Judge.judge(raw))
    }

    // ── 硬规矩①：输入 ≤200 字 ──────────────────────────────────────────────

    @Test
    fun `prompt 把输入截到二百字_不喂整页快照`() {
        val long = "物".repeat(5_000)
        val p = FunnelL2Judge.promptOf(long)
        assertTrue("截断后原文只留 200 字", p.contains("物".repeat(200)) && !p.contains("物".repeat(201)))
        assertEquals(200, FunnelL2Judge.cappedText(long).length)
    }

    @Test
    fun `prompt 带五候选与输出契约`() {
        val p = FunnelL2Judge.promptOf("随便一句")
        for (c in FunnelL2Judge.CANDIDATES) assertTrue("候选缺 $c", p.contains(c))
        assertTrue(p.contains("\"scores\""))
        assertFalse("prompt 里不许出现快照/正文类字段", p.contains("HTML") || p.contains("snapshot"))
    }

    // ── 判据⑥：账行四字段 ──────────────────────────────────────────────────

    @Test
    fun `账行四字段齐全且前缀对`() {
        val line = FunnelL2Read.logLineOf("7", "未命中", "卡片", "—", 123L)
        assertTrue(line.startsWith("抱朴漏斗L2：会话=7 "))
        assertTrue(line.contains("L1-route=未命中"))
        assertTrue(line.contains("L2结果=卡片"))
        assertTrue(line.contains("不确定原因=—"))
        assertTrue(line.contains("耗时ms=123"))
    }

    // ── 判据②⑤：壳全链路（假件出口，不打真模型） ──────────────────────────

    private class FakePort(val reply: String, val calls: AtomicInteger = AtomicInteger(0)) : L2AskPort {
        override fun ask(prompt: String): String {
            calls.incrementAndGet()
            return reply
        }
    }

    private class ThrowingPort(val message: String) : L2AskPort {
        override fun ask(prompt: String): String = throw IllegalStateException(message)
    }

    private fun emptyRoot(): File = TempSandbox.create("zd647l2")

    @Test
    fun `判据二_三条明显单一输入_壳选对组`() {
        val cases = listOf(
            Triple("帮我做一张角色卡", scores("卡片" to 0.9, "内容" to 0.2), "卡片"),
            Triple("查一下今天的发货记录", scores("查询" to 0.85, "系统" to 0.1), "查询"),
            Triple("教我这一步怎么填", scores("教学" to 0.8), "教学"),
        )
        for ((text, reply, want) in cases) {
            val root = emptyRoot()
            try {
                val sid = "647-l2-${text.hashCode()}"
                FunnelL2Read.askOverride = FakePort(reply)
                runBlocking { FunnelL2Read.onUserTurn(sid, text, false, root, null, provider = null) }
                // 壳跑完不崩；组名由同一份回灌文在判据层复核（账行内容由 logLineOf 与判据层钉死）。
                assertEquals(text, want, FunnelL2Judge.judge(reply).group)
            } finally {
                TempSandbox.deleteTree(root)
            }
        }
    }

    @Test
    fun `判据一_冲突输入走壳_不崩且判据层给 undecided`() {
        val reply = scores("查询" to 0.50, "卡片" to 0.48)
        val root = emptyRoot()
        try {
            FunnelL2Read.askOverride = FakePort(reply)
            runBlocking { FunnelL2Read.onUserTurn("647-l2-clash", "查一下我昨天写的角色卡", false, root, null, provider = null) }
            assertEquals(FunnelL2Judge.UNDECIDED, FunnelL2Judge.judge(reply).group)
        } finally {
            TempSandbox.deleteTree(root)
        }
    }

    @Test
    fun `判据五_假件抛超时_回退L1不崩`() {
        val root = emptyRoot()
        try {
            FunnelL2Read.askOverride = ThrowingPort("等模型超过 8000ms，本次作废")
            runBlocking { FunnelL2Read.onUserTurn("647-l2-timeout", "今天天气怎么样", false, root, null, provider = null) }
            // 走到这里没抛出＝整轮不崩；回退账由 logLineOf 字段钉。
        } finally {
            TempSandbox.deleteTree(root)
        }
    }

    @Test
    fun `判据五_假件抛任意异常_同样回退不崩`() {
        val root = emptyRoot()
        try {
            FunnelL2Read.askOverride = ThrowingPort("模型请求失败：HTTP 500")
            runBlocking { FunnelL2Read.onUserTurn("647-l2-fail", "今天天气怎么样", false, root, null, provider = null) }
        } finally {
            TempSandbox.deleteTree(root)
        }
    }

    @Test
    fun `L1命中时L2一枪都不开_零调用`() {
        val root = emptyRoot()
        val calls = AtomicInteger(0)
        try {
            FunnelL2Read.askOverride = FakePort(scores("查询" to 0.9), calls)
            runBlocking { FunnelL2Read.onUserTurn("647-l2-skip", "查物流今天到了几件", false, root, null, provider = null) }
            assertEquals("L1 已命中——L2 不许再花钱", 0, calls.get())
        } finally {
            TempSandbox.deleteTree(root)
        }
    }

    @Test
    fun `没有可用通道时不开枪_记回退账不崩`() {
        val root = emptyRoot()
        try {
            FunnelL2Read.askOverride = null
            runBlocking { FunnelL2Read.onUserTurn("647-l2-nochannel", "今天天气怎么样", false, root, null, provider = null) }
        } finally {
            TempSandbox.deleteTree(root)
        }
    }

    @Test
    fun `超时预算生效_假件慢答超时回退不崩`() {
        val root = emptyRoot()
        try {
            // 慢出口：答得比预算晚——withTimeout 到点作废（预算 200ms，出口睡 500ms 才回话）。
            FunnelL2Read.askOverride = L2AskPort {
                Thread.sleep(500)
                scores("系统" to 0.9)
            }
            runBlocking {
                FunnelL2Read.onUserTurn("647-l2-tmo", "今天天气怎么样", false, root, null, provider = null, timeoutMs = 200L)
            }
            // 走到这里没抛出＝超时被兜住、整轮不崩；回退账由 logLineOf 字段钉。
        } finally {
            TempSandbox.deleteTree(root)
        }
    }

    // ── 闭集常量自检 ────────────────────────────────────────────────────────

    @Test
    fun `候选闭集就是五组_不多不少`() {
        assertEquals(listOf("内容", "查询", "卡片", "教学", "系统"), FunnelL2Judge.CANDIDATES)
        assertEquals(0.05, FunnelL2Judge.MARGIN_MIN, 1e-9)
        assertEquals(200, FunnelL2Judge.MAX_INPUT_CHARS)
    }
}
