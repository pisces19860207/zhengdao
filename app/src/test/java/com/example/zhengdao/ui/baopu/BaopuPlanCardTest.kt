// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第十七批追加一 §4 判据 J5/J6/J15–J18 —— **第一百零六批已把 J5 / J15 迁走**：
// - J5（展开态）从「卡级」迁到「组级」：`planGroupExpanded(group, running, userChoice)`
//   ⇒ 判据落在 `BaopuPlanAreaTest`（本文件不再测 J5）；
// - J15（显隐）从「与轮收起无关」升级为「胶囊显隐只看有没有计划」
//   ⇒ `planCapsuleState(messages) != null`，同样在 `BaopuPlanAreaTest`；
// - J16 升级为「正文里不许再出现计划卡渲染」，也在 `BaopuPlanAreaTest`；
//   本文件保留**数据层**那条：plan payload 不进文本通道、卡只认解析后的 `BaopuUiPlan`。
//
// 本文件留下的判据：计划取数（同轮取最后）、payload 容错、三态符号、完成计数、
// 条目截断阈值、J16 数据层。
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

    // ── 计划取数：同轮取最后一条 ─────────────────────────────────────────

    @Test
    fun `同轮多次update_plan_取最后一条`() {
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
    fun `没有计划的轮_planOrNull为null`() {
        val round = groupRounds(listOf(msg(1, "user", 1_000), msg(2, "assistant", 2_000, textPart("纯聊天")))).single()
        assertNull(round.planOrNull())
    }

    @Test
    fun `空计划的轮_planOrNull为null`() {
        assertEquals(0, plan().items.size)
        assertNotNull("空计划本身是个对象（解析器给的）", BaopuUiPlan(emptyList()))
        // 「没有条目 ⇒ 等于没有计划」的口径落在面板/胶囊取数上（BaopuPlanAreaTest）
        val round = groupRounds(roundWithPlan("""{"v":1,"items":[]}""")).single()
        assertNull("空 items 解析不出来 ⇒ 没有可显示的计划", round.planOrNull())
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
    fun `J6_重启后重新分组_计划还在历史轮上`() {
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
            "历史轮默认收起（第十六批口径不变）",
            roundCollapsed(round, running = false, pinnedUserIds = emptySet(), userExpandedIds = emptySet()),
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

    // ── 条目截断阈值（UI 第二批 §2 保留） ────────────────────────────────

    @Test
    fun `条目截断_只有可能装不下的才摆展开入口`() {
        assertEquals("阈值 60 字（保守值）", 60, PLAN_ITEM_COLLAPSED_CHARS)
        assertEquals("两行保底", 2, PLAN_ITEM_COLLAPSED_MAX_LINES)
        assertFalse("短条目不该挂入口", planItemNeedsExpand("读文件"))
        assertFalse("正好 60 字不挂", planItemNeedsExpand("甲".repeat(60)))
        assertTrue("61 字要挂", planItemNeedsExpand("甲".repeat(61)))
    }

    // ── J16：plan 不当正文（数据层） ─────────────────────────────────────

    @Test
    fun `J16_controller里的plan过滤行已删_解析落点在`() {
        // 第七十二批：装配判据从控制器搬到 BaopuMessageAssembler（纯函数，JVM 直测）；
        // 判据本体不变——plan 必须经 BaopuUiPlan.fromPayload 解析进 msg.plan，不许直显 payload。
        val src = source("BaopuMessageAssembler.kt")
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
