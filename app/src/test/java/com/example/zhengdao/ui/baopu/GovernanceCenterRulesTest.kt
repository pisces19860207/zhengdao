// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百二十二批（2026-11-08）· 判据②：**读数与 527 出口逐项对拍相等**。
//
// 怎么算"相等"：测试同时拿两样东西——
//   · **出口**：`GovernanceStateTool.run()` 返回的那把 JSON（真跑，不是手搓的理想形状）；
//   · **显示**：`GovernanceCenterRules` 把同一把 JSON 抄成的那几行。
// 逐格断言 `显示的字 == 出口的字`（`rate` 逐字节相等、`n/d` 相等、`cn` 相等、三数相等）。
//
// 第二条牙是"不许编"：出口说读不到（`unknown`）时，显示必须是"读不到"＋**原样的原因**，
// 而不是 0、不是 0%、不是"暂无数据"。分母 0 的 `no_sample` 也要原样显示——
// 把它渲染成 0% 就是全仓最贵的那种假读数（`docs/metrics-registry.md` 〇节）。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.baopu.agent.GovernanceStateTool
import com.example.zhengdao.baopu.consequence.IdentityRow
import com.example.zhengdao.baopu.consequence.IdentityState
import java.time.ZoneId
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GovernanceCenterRulesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 真跑 527 出口（临时工作区、固定时刻）。 */
    private fun realExit(nowMs: Long = 1_760_000_000_000L): JSONObject {
        val ws = tmp.newFolder("ws")
        val out = GovernanceStateTool.run(ws, nowMs)
        val ok = out as? com.example.zhengdao.baopu.agent.ToolOutcome.Ok
            ?: throw AssertionError("527 出口必须成功（拿到的：$out）")
        val parsed = GovernanceCenterRules.parse(ok.text)
        return parsed ?: throw AssertionError("527 出口必须是 JSON 对象")
    }

    // ── 逐项对拍（判据②）─────────────────────────────────────────────────────

    @Test
    fun `四率显示的字与出口逐格相等_读不到就写读不到`() {
        val exit = realExit()
        val shown = GovernanceCenterRules.ratios(exit)
        assertEquals("四率四格，顺序固定", GovernanceCenterRules.RATIO_KEYS, shown.map { it.key })
        shown.forEach { row ->
            val cell = exit.getJSONObject("ratios").getJSONObject(row.key)
            val why = cell.optString("unknown")
            if (why.isNotEmpty()) {
                assertEquals("${row.key}：出口说读不到，显示也必须是读不到", GovernanceCenterRules.UNKNOWN, row.value)
                assertEquals("${row.key}：原因要原样带出来", why, row.detail)
            } else {
                assertEquals("${row.key}：率的字必须与出口逐字节相等", cell.getString("rate"), row.value)
                assertEquals(
                    "${row.key}：n/d 也必须相等",
                    "${cell.getInt("n")}/${cell.getInt("d")}",
                    row.detail,
                )
            }
        }
    }

    @Test
    fun `弹卡三数与出口相等`() {
        val exit = realExit()
        val cards = exit.optJSONObject("cards")
        val shown = GovernanceCenterRules.cards(exit)
        assertEquals(GovernanceCenterRules.CARD_KEYS, shown.map { it.key })
        val why = cards?.optString("unknown").orEmpty()
        shown.forEach { row ->
            if (why.isNotEmpty()) {
                assertEquals(GovernanceCenterRules.UNKNOWN, row.value)
                assertEquals(why, row.detail)
            } else {
                assertEquals("${row.key} 必须与出口同一个数", cards!!.getInt(row.key).toString(), row.value)
            }
        }
    }

    @Test
    fun `灰度逐能力一格_键集与出口全等_值用出口的cn`() {
        val exit = realExit()
        val gray = exit.optJSONObject("gray")
        val shown = GovernanceCenterRules.grays(exit)
        val why = gray?.optString("unknown").orEmpty()
        if (why.isNotEmpty()) {
            assertEquals(1, shown.size)
            assertEquals(GovernanceCenterRules.UNKNOWN, shown.first().value)
            assertEquals(why, shown.first().detail)
            return
        }
        val exitKeys = gray!!.keys().asSequence().toSet()
        assertEquals("灰度键集必须与出口全等（多一格少一格都是编）", exitKeys, shown.map { it.key }.toSet())
        shown.forEach { row ->
            val cell = gray.getJSONObject(row.key)
            assertEquals("${row.key} 的档中文名要与出口相等", cell.getString("cn"), row.value)
            assertEquals("${row.key} 的线值进小字", cell.getString("band"), row.detail)
        }
    }

    @Test
    fun `当日原样取自出口`() {
        val exit = realExit()
        assertEquals("day 段必须原样显示", exit.getString("day"), GovernanceCenterRules.dayOf(exit))
    }

    // ── 不编数：手搓的三种坏形状 ─────────────────────────────────────────────

    @Test
    fun `分母0的no_sample原样显示_绝不渲染成0%`() {
        val exit = JSONObject().put(
            "ratios",
            JSONObject().put(
                "violation",
                JSONObject().put("n", 0).put("d", 0).put("rate", "no_sample"),
            ),
        )
        val row = GovernanceCenterRules.ratios(exit).first { it.key == "violation" }
        assertEquals("no_sample 必须原样", "no_sample", row.value)
        assertTrue("不许出现 %（那就是编了一个 0%）", !row.value.contains("%"))
    }

    @Test
    fun `段缺失时说读不到_不补0`() {
        val empty = JSONObject()
        val ratios = GovernanceCenterRules.ratios(empty)
        assertEquals(4, ratios.size)
        assertTrue("缺段不许补 0：$ratios", ratios.all { it.value == GovernanceCenterRules.UNKNOWN })
        assertTrue(
            "弹卡三数缺段同理",
            GovernanceCenterRules.cards(empty).all { it.value == GovernanceCenterRules.UNKNOWN },
        )
    }

    @Test
    fun `格存在但键缺失也不许补0`() {
        val exit = JSONObject().put("cards", JSONObject().put("pass", 3))
        val shown = GovernanceCenterRules.cards(exit)
        assertEquals("3", shown.first { it.key == "pass" }.value)
        val cardRow = shown.first { it.key == "card" }
        assertEquals("缺的格必须是读不到", GovernanceCenterRules.UNKNOWN, cardRow.value)
        assertTrue("缺格的小字要说清是缺了", cardRow.detail.isNotEmpty())
    }

    @Test
    fun `不是JSON的出口_解析失败不抛`() {
        assertNull("坏 JSON 只回 null", GovernanceCenterRules.parse("not a json"))
        // 连带着三块都要能画（不许抛）
        assertEquals(4, GovernanceCenterRules.ratios(null).size)
        assertEquals(3, GovernanceCenterRules.cards(null).size)
        assertEquals(1, GovernanceCenterRules.grays(null).size)
    }

    // ── 执行体三格 ───────────────────────────────────────────────────────────

    @Test
    fun `执行体_id取前8_凭据数照传_撤销取最近的已撤销行`() {
        val exit = JSONObject().put(
            "identity",
            JSONObject().put("id", "abcdef12-3456-7890").put("state", "active"),
        )
        val rows = listOf(
            IdentityRow("old00001-x", IdentityState.ROTATED_OUT, 100L, "", 0L),
            IdentityRow("rev00001-x", IdentityState.REVOKED, 200L, "", 1_700_000_000_000L),
            IdentityRow("rev00002-x", IdentityState.REVOKED, 300L, "", 1_710_000_000_000L),
            IdentityRow("abcdef12-3456-7890", IdentityState.ACTIVE, 400L),
        )
        val zone = ZoneId.of("Asia/Shanghai")
        val exec = GovernanceCenterRules.executor(exit, credentialCount = 3, revokedAtMs = GovernanceCenterRules.lastRevokeAtMs(rows), zone = zone)
        assertEquals("id 只显示前 8 位", "abcdef12", exec.id8)
        assertEquals("状态原样", "active", exec.state)
        assertEquals("凭据条数照传", 3, exec.credentialCount)
        assertEquals(
            "最近撤销＝最后一条 REVOKED 的时刻（已轮换不算撤销）",
            GovernanceCenterRules.clock(1_710_000_000_000L, zone),
            exec.lastRevokeClock,
        )
    }

    @Test
    fun `没撤销过就是空串_不写'从未'也不写0`() {
        val rows = listOf(IdentityRow("abcdef12-3456", IdentityState.ACTIVE, 400L))
        assertEquals(0L, GovernanceCenterRules.lastRevokeAtMs(rows))
        val exec = GovernanceCenterRules.executor(JSONObject(), 0, 0L)
        assertEquals("没撤销⇒空串（界面翻成'没有'）", "", exec.lastRevokeClock)
        assertEquals("id 读不到⇒空串（界面翻成'未生成'，不假装有主）", "", exec.id8)
    }

    @Test
    fun `时刻形状是月日时分_本地时区`() {
        // 2026-11-08 10:30（Asia/Shanghai＝UTC+8）＝ 2026-11-08T02:30Z
        val ms = java.time.Instant.parse("2026-11-08T02:30:00Z").toEpochMilli()
        assertEquals("11-08 10:30", GovernanceCenterRules.clock(ms, ZoneId.of("Asia/Shanghai")))
        assertEquals("11-08 02:30", GovernanceCenterRules.clock(ms, ZoneId.of("UTC")))
    }

    @Test
    fun `真跑出口_显示与出口逐格一致_不另算`() {
        // 这条不预设"空工作区一定读不到"（账本根是进程级单例，别的用例可能装过）——
        // 只钉一件：**出口说什么，屏幕就显示什么**。这才是判据②的原话。
        val exit = realExit()
        val ratios = exit.getJSONObject("ratios")
        GovernanceCenterRules.ratios(exit).forEach { row ->
            val cell = ratios.getJSONObject(row.key)
            val why = cell.optString("unknown")
            val expect = if (why.isNotEmpty()) GovernanceCenterRules.UNKNOWN else cell.getString("rate")
            assertEquals("${row.key}：显示必须等于出口", expect, row.value)
        }
        // 灰度同理：出口几个键，屏幕就几行
        val gray = exit.optJSONObject("gray")
        val grayKeys = if (gray == null || gray.has("unknown")) 1 else gray.length()
        assertEquals("灰度行数必须等于出口键数", grayKeys, GovernanceCenterRules.grays(exit).size)
    }

    // ── 542：小黑板四格（判据②「空板占位／最近更新／清空三样齐」的纯层那一半）──────

    @Test
    fun `黑板_没写过_空且没有时刻且清空不可点`() {
        val view = GovernanceCenterRules.boardView(text = "", updatedAtMs = 0L)
        assertTrue("没写过应当是空板（显示占位）", view.empty)
        assertTrue("空板不许被说成读不出", !view.unreadable)
        assertEquals("没有这件文件就没有时刻（不编一个 00:00）", "", view.updatedClock)
        assertTrue("空板按不动清空", !view.clearEnabled)
    }

    @Test
    fun `黑板_有字_带HHmm时刻且清空可按`() {
        // 2026-11-08 21:07 UTC+8 ⇒ 21:07（固定时区，不受跑测机器时区影响）
        val at = java.time.ZonedDateTime.of(2026, 11, 8, 21, 7, 0, 0, ZoneId.of("Asia/Shanghai"))
            .toInstant().toEpochMilli()
        val view = GovernanceCenterRules.boardView(
            text = "待办：先看 BoardTools",
            updatedAtMs = at,
            zone = ZoneId.of("Asia/Shanghai"),
        )
        assertTrue("有字就不是空板", !view.empty)
        assertEquals("时刻只到分", "21:07", view.updatedClock)
        assertTrue("有字才按得动清空", view.clearEnabled)
    }

    @Test
    fun `黑板_读不出与空是两件事`() {
        val view = GovernanceCenterRules.boardView(text = null, updatedAtMs = 1_760_000_000_000L)
        assertTrue("读不出要如实标", view.unreadable)
        assertTrue("读不出不许被当成空板（那样会说'还没写过'，是假话）", !view.empty)
        assertTrue("读不出时清空也不该乱按", !view.clearEnabled)
        assertTrue("盘上有这件文件就有时刻（读不出内容不等于没写过）", view.updatedClock.isNotEmpty())
    }
}
