// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第十七批追加一 §4 判据 J5/J6/J15–J18。判据分两层：
// - **纯函数层**（JVM 直测）：planCardExpanded / shouldShowPlanCard / planDoneCount /
//   planStatusSymbol / BaopuUiPlan.fromPayload——与第十六批第一件同房型；
// - **源码级门禁**（J15/J16）：轮收起分支也挂计划卡、plan payload 不进文本通道——
//   Compose 渲染不可 JVM 直测（本仓无 Robolectric），照 BaopuPresetGuardTest 的
//   范式用源码断言钉死。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.baopu.PlanRules
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BaopuPlanCardTest {

    // ── 造数小工具 ────────────────────────────────────────────────────────

    private fun plan(vararg items: Pair<String, String>) =
        BaopuUiPlan(items.map { (t, s) -> BaopuPlanItem(t, s) })

    private fun msg(id: Long, role: String, at: Long, vararg parts: BaopuUiPart, plan: BaopuUiPlan? = null) =
        BaopuUiMessage(id = id, role = role, parts = parts.toList(), createdAt = at, plan = plan)

    private fun textPart(t: String) = BaopuUiPart(kind = "text", text = t)
    private fun toolPart(t: String = "update_plan") =
        BaopuUiPart(kind = "tool", text = "结果：$t", tool = t)

    /** 一轮带计划的消息流：user 开轮 → update_plan 工具卡 → plan-only 消息 → 最终回答。 */
    private fun roundWithPlan(planPayload: String): List<BaopuUiMessage> = listOf(
        msg(1, "user", 1_000),
        msg(2, "assistant", 2_000, toolPart()),
        msg(3, "assistant", 2_100, plan = BaopuUiPlan.fromPayload(planPayload)),
        msg(4, "assistant", 9_000, textPart("干完了")),
    )

    private fun source(name: String): String {
        val roots = listOf(
            File("app/src/main/java/com/example/zhengdao/ui/baopu"),
            File("src/main/java/com/example/zhengdao/ui/baopu"),
        )
        val dir = roots.firstOrNull { it.isDirectory }
            ?: error("找不到 ui/baopu 目录（cwd=${File("").absolutePath}）")
        val f = File(dir, name)
        assertTrue("找不到 $name：${f.path}", f.isFile)
        return f.readText()
    }

    // ── J15：不被轮收纳吃掉 ─────────────────────────────────────────────

    @Test
    fun `J15_轮收起也显示计划卡`() {
        val payload = PlanRules.toJson(
            listOf(PlanRules.PlanItem("读文件", PlanRules.STATUS_DONE), PlanRules.PlanItem("写文件", PlanRules.STATUS_TODO)),
        )
        val round = groupRounds(roundWithPlan(payload)).single()
        val plan = round.planOrNull()
        assertNotNull("轮里应解析出计划", plan)
        // 收起/展开两个态都返回 true——「与轮收起无关」正是本判据
        assertTrue(shouldShowPlanCard(round, plan, collapsed = true))
        assertTrue(shouldShowPlanCard(round, plan, collapsed = false))
    }

    @Test
    fun `J15_没有计划的轮不显示卡片`() {
        val round = groupRounds(listOf(msg(1, "user", 1_000), msg(2, "assistant", 2_000, textPart("纯聊天")))).single()
        assertNull(round.planOrNull())
        assertFalse(shouldShowPlanCard(round, null, collapsed = false))
        assertFalse(shouldShowPlanCard(round, null, collapsed = true))
    }

    @Test
    fun `J15_同一轮多次update_plan_取最后一条`() {
        val first = PlanRules.toJson(listOf(PlanRules.PlanItem("旧计划", PlanRules.STATUS_TODO)))
        val last = PlanRules.toJson(
            listOf(
                PlanRules.PlanItem("新一", PlanRules.STATUS_DONE),
                PlanRules.PlanItem("新二", PlanRules.STATUS_DOING),
            ),
        )
        val messages = listOf(
            msg(1, "user", 1_000),
            msg(2, "assistant", 2_000, toolPart()),
            msg(3, "assistant", 2_100, plan = BaopuUiPlan.fromPayload(first)),
            msg(4, "assistant", 5_000, toolPart()),
            msg(5, "assistant", 5_100, plan = BaopuUiPlan.fromPayload(last)),
        )
        val plan = groupRounds(messages).single().planOrNull()!!
        assertEquals("后一版覆盖前一版", listOf("新一", "新二"), plan.items.map { it.text })
    }

    @Test
    fun `J15_收起与展开两个分支都挂计划卡_源码级`() {
        val src = source("BaopuMessageList.kt")
        val collapsedBlock = src.substringAfter("if (collapsed) {").substringBefore("} else {")
        assertTrue(
            "收起的轮也必须渲染计划卡（被轮收纳吃掉就红）",
            collapsedBlock.contains("BaopuPlanCard("),
        )
        val expandedBlock = src.substringAfter("} else {")
            .substringBefore("private fun BaopuRound.finalAnswerMessage")
        assertTrue("展开的轮也要渲染计划卡", expandedBlock.contains("BaopuPlanCard("))
    }

    // ── J5：收起/展开 ───────────────────────────────────────────────────

    @Test
    fun `J5_正在跑_计划卡自动展开`() {
        val payload = PlanRules.toJson(listOf(PlanRules.PlanItem("读文件", PlanRules.STATUS_DOING)))
        val round = groupRounds(roundWithPlan(payload)).single()
        assertTrue("跑着 ⇒ 展开", planCardExpanded(round, running = true, userPlanExpandedIds = emptySet()))
    }

    @Test
    fun `J5_跑完_计划卡自动收起`() {
        val payload = PlanRules.toJson(listOf(PlanRules.PlanItem("读文件", PlanRules.STATUS_DONE)))
        val round = groupRounds(roundWithPlan(payload)).single()
        assertFalse("跑完 ⇒ 收起", planCardExpanded(round, running = false, userPlanExpandedIds = emptySet()))
    }

    @Test
    fun `J5_点一次翻转一次`() {
        val payload = PlanRules.toJson(listOf(PlanRules.PlanItem("读文件", PlanRules.STATUS_DONE)))
        val round = groupRounds(roundWithPlan(payload)).single()
        val key = round.userMessageId
        assertFalse("初始收起", planCardExpanded(round, running = false, userPlanExpandedIds = emptySet()))
        assertTrue("点一次 ⇒ 展开", planCardExpanded(round, running = false, userPlanExpandedIds = setOf(key)))
        assertFalse(
            "再点一次 ⇒ 收起（翻转由组合层换集合实现，这里钉集合→状态）",
            planCardExpanded(round, running = false, userPlanExpandedIds = emptySet()),
        )
    }

    @Test
    fun `J5_跑着的是当前轮_历史轮不跟跑`() {
        val payload = PlanRules.toJson(listOf(PlanRules.PlanItem("旧任务", PlanRules.STATUS_DONE)))
        val messages = roundWithPlan(payload) + msg(10, "user", 20_000) + msg(11, "assistant", 21_000, textPart("新轮"))
        val rounds = groupRounds(messages)
        val historical = rounds.first()
        assertFalse("历史轮不因在跑而展开", planCardExpanded(historical, running = true, userPlanExpandedIds = emptySet()))
        assertTrue("当前轮在跑 ⇒ 展开", planCardExpanded(rounds.last(), running = true, userPlanExpandedIds = emptySet()))
    }

    // ── J6：重启恢复 ────────────────────────────────────────────────────

    @Test
    fun `J6_payload读回_条目数文本状态逐字一致`() {
        val items = listOf(
            PlanRules.PlanItem("读文件", PlanRules.STATUS_DONE),
            PlanRules.PlanItem("甲".repeat(60), PlanRules.STATUS_DOING),
            PlanRules.PlanItem("收尾", PlanRules.STATUS_TODO),
        )
        val parsed = BaopuUiPlan.fromPayload(PlanRules.toJson(items))!!
        assertEquals(3, parsed.items.size)
        items.forEachIndexed { i, it ->
            assertEquals("第 $i 条 text 逐字一致", it.text, parsed.items[i].text)
            assertEquals("第 $i 条 status 逐字一致", it.status, parsed.items[i].status)
        }
    }

    @Test
    fun `J6_坏payload_当没有计划_不崩`() {
        assertNull(BaopuUiPlan.fromPayload("not json"))
        assertNull(BaopuUiPlan.fromPayload(""))
        assertNull(BaopuUiPlan.fromPayload("""{"v":1,"items":[]}"""))
        assertNull(BaopuUiPlan.fromPayload("""{"v":1,"items":[{"text":"x","status":"finish"}]}"""))
        // 好坏掺杂 ⇒ 只要好的（PlanRules.fromJson 的容错口径）
        val mixed = BaopuUiPlan.fromPayload("""{"v":1,"items":[{"text":"好的","status":"done"},"坏"]}""")
        assertNotNull(mixed)
        assertEquals(1, mixed!!.items.size)
    }

    @Test
    fun `J6_重启后重新分组_计划还在历史轮上且默认收起`() {
        val payload = PlanRules.toJson(
            listOf(
                PlanRules.PlanItem("一", PlanRules.STATUS_DONE),
                PlanRules.PlanItem("二", PlanRules.STATUS_DONE),
            ),
        )
        val messages = roundWithPlan(payload)
        // 「重启」= 进程重建后从库重读（同一份消息数据重新分组）
        val round = groupRounds(messages).single()
        assertNotNull("重启后计划仍在", round.planOrNull())
        assertTrue(
            "历史轮默认收起（J6）",
            roundCollapsed(round, running = false, pinnedUserIds = emptySet(), userExpandedIds = emptySet()),
        )
        assertTrue(
            "但收起也要露计划那一行（J15）",
            shouldShowPlanCard(round, round.planOrNull(), collapsed = true),
        )
    }

    // ── J17：三态映射 ───────────────────────────────────────────────────

    @Test
    fun `J17_三态符号映射`() {
        assertEquals("✓", planStatusSymbol(PlanRules.STATUS_DONE))
        assertEquals("●", planStatusSymbol(PlanRules.STATUS_DOING))
        assertEquals("○", planStatusSymbol(PlanRules.STATUS_TODO))
    }

    @Test
    fun `J17_未知状态按todo画_不崩`() {
        assertEquals("未知状态保守当未开始", "○", planStatusSymbol("finish"))
        assertEquals("空状态同样不崩", "○", planStatusSymbol(""))
    }

    // ── J18：计数口径 ───────────────────────────────────────────────────

    @Test
    fun `J18_分子是done数_分母是总数`() {
        val items = plan(
            "一" to PlanRules.STATUS_DONE,
            "二" to PlanRules.STATUS_DONE,
            "三" to PlanRules.STATUS_DOING,
            "四" to PlanRules.STATUS_TODO,
            "五" to PlanRules.STATUS_TODO,
        ).items
        assertEquals("分子只数 done（doing/todo 不算）", 2, planDoneCount(items))
        assertEquals("分母是全部条目", 5, items.size)
    }

    @Test
    fun `J18_空计划整卡不渲染`() {
        assertTrue(plan().items.isEmpty())
        assertFalse(shouldShowPlanCard(groupRounds(listOf(msg(1, "user", 1_000))).single(), plan(), collapsed = false))
    }

    // ── J16：plan 不当正文（源码级） ─────────────────────────────────────

    @Test
    fun `J16_controller里的plan过滤行已删_解析落点在`() {
        val src = source("BaopuChatController.kt")
        assertFalse(
            "「过滤行」（if (kind == \"plan\") continue 形态）必须消失——C 段摘掉临时过滤",
            src.contains("if (kind == \"plan\") continue"),
        )
        assertTrue(
            "plan 必须经 BaopuUiPlan.fromPayload 解析进 msg.plan（不是直显 payload）",
            src.contains("BaopuUiPlan.fromPayload"),
        )
    }

    @Test
    fun `J16_plan不进parts文本通道`() {
        val list = source("BaopuMessageList.kt")
        assertFalse(
            "part.kind 的 when 不该有 plan 分支（plan 走 msg.plan，不当文本画）",
            list.contains("\"plan\" ->"),
        )
        val card = source("BaopuPlanCard.kt")
        assertFalse(
            "计划卡只认解析后的 BaopuUiPlan，不许碰 BaopuUiPart",
            card.contains("BaopuUiPart"),
        )
    }
}
