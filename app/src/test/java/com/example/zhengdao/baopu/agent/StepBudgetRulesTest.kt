// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百八十九批 · 步数分段预算＋无进展熔断：纯判据层（[RunRules] 新段）的 JVM 直测。
// 判据/负例编号对应派工单 §1.1/§1.2/§1.3 与交付报告的「判据/负例/读数」表。
package com.example.zhengdao.baopu.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun budget() = com.example.zhengdao.baopu.kernel.BaopuKernel.Budget

class StepBudgetRulesTest {

    // ── §1.1 重复调用熔断：key 规范化 ─────────────────────────────────────

    @Test
    fun `同工具同参_键序不同算同一次`() {
        val a = JSONObject("{\"path\":\"/a/x.txt\",\"limit\":10}")
        val b = JSONObject("{\"limit\":10,\"path\":\"/a/x.txt\"}")
        assertEquals(
            "同一参数不同键序必须算同一次（派工单 §1.1 原话）",
            RunRules.repeatCallKey("read_file", a),
            RunRules.repeatCallKey("read_file", b),
        )
    }

    @Test
    fun `同工具同参_空白差异算同一次`() {
        val a = JSONObject("{ \"path\" : \"/a/x.txt\" }")
        val b = JSONObject("{\"path\":\"/a/x.txt\"}")
        assertEquals(RunRules.repeatCallKey("read_file", a), RunRules.repeatCallKey("read_file", b))
    }

    @Test
    fun `参数变一个字符_key必不同`() {
        val a = JSONObject("{\"path\":\"/a/x.txt\"}")
        val b = JSONObject("{\"path\":\"/a/x.txs\"}")
        assertNotEquals(
            "参数差一个字符 ⇒ 不触发（判据原文）",
            RunRules.repeatCallKey("read_file", a),
            RunRules.repeatCallKey("read_file", b),
        )
    }

    @Test
    fun `工具名不同_key必不同`() {
        val args = JSONObject("{\"path\":\"/a/x.txt\"}")
        assertNotEquals(RunRules.repeatCallKey("read_file", args), RunRules.repeatCallKey("list_dir", args))
    }

    @Test
    fun `规范化_嵌套与数组也按键排序`() {
        val a = JSONObject("{\"outer\":{\"b\":1,\"a\":2},\"arr\":[3,1]}")
        val b = JSONObject("{\"arr\":[3,1],\"outer\":{\"a\":2,\"b\":1}}")
        assertEquals(RunRules.canonicalArgs(a), RunRules.canonicalArgs(b))
        // 数字口径：Integer 与 Double 形式的同一个值算同一次
        val int1 = JSONObject("{\"n\":1}")
        val dbl1 = JSONObject("{\"n\":1.0}")
        assertEquals(RunRules.canonicalArgs(int1), RunRules.canonicalArgs(dbl1))
    }

    @Test
    fun `参数摘要_超长截断并标注`() {
        val big = JSONObject()
        big.put("content", "字".repeat(500))
        val brief = RunRules.argsBrief(big)
        assertTrue("超 200 必须截", brief.length <= RunRules.REPEAT_ARGS_BRIEF_MAX + 8)
        assertTrue("截断要标注（不静默）", brief.contains("已截断"))
        val small = JSONObject("{\"path\":\"/a\"}")
        assertEquals(small.toString(), RunRules.argsBrief(small))
    }

    @Test
    fun `重复熔断文案_四要素齐全`() {
        val msg = RunRules.repeatCallNotice(3, "read_file", "{\"path\":\"/a/x.txt\"}")
        assertTrue("调了几次", msg.contains("3 次"))
        assertTrue("工具名", msg.contains("read_file"))
        assertTrue("参数摘要", msg.contains("{\"path\":\"/a/x.txt\"}"))
        assertTrue("换路径建议", msg.contains("换条路径"))
        assertTrue("换工具建议", msg.contains("换个工具"))
        assertTrue("拆小建议", msg.contains("把任务拆小"))
    }

    // ── §1.2 分段：段号与复盘行 ──────────────────────────────────────────

    @Test
    fun `段号_0步在第1段_100进第2段_200进第3段`() {
        assertEquals(1, RunRules.segmentOf(0))
        assertEquals(1, RunRules.segmentOf(99))
        assertEquals(2, RunRules.segmentOf(100))
        assertEquals(2, RunRules.segmentOf(199))
        assertEquals(3, RunRules.segmentOf(200))
        assertEquals(3, RunRules.segmentOf(299))
        // 300 步整是步数刹车的地盘：不再有第 4 段（复盘日志必须恰 3 行）
        assertEquals(3, RunRules.segmentOf(300))
    }

    @Test
    fun `复盘行_读数齐全且单行`() {
        val line = RunRules.segmentCheckpointLine(2, 100, 37, 12345L, "查热点写微头条")
        assertFalse("复盘日志必须一行（结构化）", line.contains('\n'))
        assertTrue("段号", line.contains("第 2/3 段"))
        assertTrue("已用步数", line.contains("已用 100/300 步"))
        assertTrue("已调工具数", line.contains("已调工具 37 次"))
        assertTrue("token", line.contains("token 12345"))
        assertTrue("当前目标", line.contains("当前目标：查热点写微头条"))
    }

    @Test
    fun `段提示文案_说清不点也继续`() {
        val msg = RunRules.segmentCardNotice(2, 100)
        assertTrue(msg.contains("第 2/3 段"))
        assertTrue("非阻塞语义写进文案", msg.contains("不用回应"))
        assertTrue("指路停止键", msg.contains("点停止"))
    }

    // ── §1.3 无进展熔断：哈希与阈值 ──────────────────────────────────────

    @Test
    fun `哈希_同内容同哈希_异内容异哈希`() {
        val h1 = RunRules.stepResultHash(listOf("abc", "def"))
        val h2 = RunRules.stepResultHash(listOf("abc", "def"))
        val h3 = RunRules.stepResultHash(listOf("abc", "deg"))
        assertEquals(h1, h2)
        assertNotEquals("结果变一次 ⇒ 计数清零的前提：哈希必须能分出新旧", h1, h3)
        assertEquals("空步返空串（接线处按「无工具结果」计）", "", RunRules.stepResultHash(emptyList()))
        assertEquals(64, h1.length)
    }

    @Test
    fun `哈希_拼接顺序参与运算`() {
        assertNotEquals(
            RunRules.stepResultHash(listOf("a", "b")),
            RunRules.stepResultHash(listOf("b", "a")),
        )
    }

    @Test
    fun `无进展阈值_11不停_12停`() {
        assertFalse("11 步还不停", RunRules.noProgressExceeded(11))
        assertTrue("12 步必停", RunRules.noProgressExceeded(12))
        assertTrue(RunRules.noProgressExceeded(13))
    }

    @Test
    fun `无进展文案_三条出路齐全`() {
        val msg = RunRules.noProgressNotice(12)
        assertTrue("说清病因", msg.contains("连续 12 步"))
        assertTrue(msg.contains("没有新信息"))
        assertTrue("出路一：缩小目标", msg.contains("把目标缩小"))
        assertTrue("出路二：换工具", msg.contains("换个工具"))
        assertTrue("出路三：问人", msg.contains("问我"))
    }

    @Test
    fun `sha256_口径稳定`() {
        // sha256("abc") 的公认值；钉住算法没被换掉
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            RunRules.sha256Hex("abc"),
        )
    }

    @Test
    fun `阈值族真源在内核_本文件只转手`() {
        assertEquals(budget().REPEAT_CALL_LIMIT, RunRules.REPEAT_CALL_LIMIT)
        assertEquals(budget().NO_PROGRESS_STEPS, RunRules.NO_PROGRESS_STEPS)
        assertEquals(budget().STEP_SEGMENTS, RunRules.STEP_SEGMENTS)
        assertEquals(budget().STEPS_PER_SEGMENT, RunRules.STEPS_PER_SEGMENT)
        assertEquals(
            "总量＝段数×每段步数（改成不相等就是分段口径破了）",
            budget().STEP_SEGMENTS * budget().STEPS_PER_SEGMENT,
            budget().MAX_STEPS,
        )
    }

    @Test
    fun `JSONArray与null的规范化不崩`() {
        val args = JSONObject("{\"a\":null,\"b\":[{\"z\":1,\"y\":2}],\"c\":\"引\\\"号\"}")
        val again = JSONObject(args.toString())
        assertEquals(RunRules.canonicalArgs(args), RunRules.canonicalArgs(again))
        assertTrue(RunRules.canonicalArgs(args).contains("\"y\":2,\"z\":1"))
        // 数组递归路径：同一数组两种写法（空格差异）算同一次
        val arrA = JSONObject("{\"k\":[2, 1]}")
        val arrB = JSONObject("{\"k\":[2,1]}")
        assertEquals(RunRules.canonicalArgs(arrA), RunRules.canonicalArgs(arrB))
    }
}
