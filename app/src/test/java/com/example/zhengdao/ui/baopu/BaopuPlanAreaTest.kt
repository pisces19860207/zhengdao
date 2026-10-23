// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单第一百四十批 §测试（胶囊计数 / 只列未完成 / 当前步骤高亮 / 无计划隐藏）
// ＋ §设计约束（11:50：120dp / 28dp / 48dp / rPill / rSheet / 320dp / 8 条折叠 / 黛青高亮）。
//
// 两层判据（与 106 版同房型）：
// - **纯函数层**（JVM 直测）：`planAreaPlans` / `planCapsuleState` / `planPanelSlice` /
//   `planSummaryExpanded` / `planGroupStale` / `planGroupTitle` / `planTimeLabel` /
//   `planPalette`——本仓无 Robolectric，Compose 渲染跑不起来，判据全压在纯函数与源码断言；
// - **源码级门禁**：胶囊/面板尺寸、当前步骤只染黛青不整行加粗、无计划零占位、
//   计划不进正文、挂载位置、可滚/可收回。
//
// 负例靶心（派工单 §测试）：
// ① 已完成计划仍占位 ⇒ `只列未完成` 与 `胶囊计数` 红；
// ② 无计划也占位 ⇒ `无计划_胶囊态为null` 与 `源码_无计划整块不渲染` 红；
// ③ 硬绑一步一勾 ⇒ `BaopuPlanCard` 的高亮若改成整行加粗/整行着色，`只染当前行` 红。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.baopu.PlanRules
import java.io.File
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BaopuPlanAreaTest {

    // ── 造数 ──────────────────────────────────────────────────────────────

    private fun msg(
        id: Long,
        role: String,
        at: Long,
        vararg parts: BaopuUiPart,
        plan: BaopuUiPlan? = null,
    ) = BaopuUiMessage(id = id, role = role, parts = parts.toList(), createdAt = at, plan = plan)

    private fun textPart(t: String) = BaopuUiPart(kind = "text", text = t)
    private fun toolPart(t: String = "update_plan") = BaopuUiPart(kind = "tool", text = "结果：$t", tool = t)

    private fun payload(vararg items: Pair<String, String>) =
        PlanRules.toJson(items.map { (t, s) -> PlanRules.PlanItem(t, s) })

    /** 一轮带计划的消息流（user 开轮 → 工具卡 → plan-only 消息 → 回答）。 */
    private fun round(
        userId: Long,
        at: Long,
        planPayload: String?,
        answer: String = "干完了",
    ): List<BaopuUiMessage> = buildList {
        add(msg(userId, "user", at, textPart("做事")))
        add(msg(userId + 1, "assistant", at + 100, toolPart()))
        if (planPayload != null) {
            add(msg(userId + 2, "assistant", at + 200, plan = BaopuUiPlan.fromPayload(planPayload)))
        }
        add(msg(userId + 3, "assistant", at + 900, textPart(answer)))
    }

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

    /** 剥注释后的源码（房型照 `BaopuCollapseAffordanceTest`）。 */
    private fun code(name: String): String = source(name)
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private fun res(name: String): String {
        val roots = listOf(File("app/src/main/res/$name"), File("src/main/res/$name"))
        val f = roots.firstOrNull { it.isFile } ?: error("找不到 res/$name")
        return f.readText()
    }

    // ── 胶囊：无计划零占位 ────────────────────────────────────────────────

    @Test
    fun `无计划_胶囊态为null_零占位`() {
        assertNull("空会话不占位", planCapsuleState(emptyList(), running = false))
        val chatOnly = listOf(msg(1, "user", 1_000, textPart("你好")), msg(2, "assistant", 2_000, textPart("在")))
        assertNull("纯聊天轮不占位", planCapsuleState(chatOnly, running = false))
        assertTrue(planAreaPlans(chatOnly, running = false).isEmpty())
    }

    @Test
    fun `源码_无计划整块不渲染`() {
        val src = code("BaopuPlanArea.kt")
        assertTrue(
            "无计划必须整块不渲染（零占位），不是渲染一个空胶囊",
            src.contains("if (capsule == null || plans.isEmpty()) return"),
        )
    }

    // ── 胶囊：计数＝未完成的计划数（不是步数） ────────────────────────────

    @Test
    fun `胶囊计数_只数未完成计划`() {
        val messages = round(1, 1_000, payload("做完一" to PlanRules.STATUS_DONE)) +
            round(20, 10_000, payload("一" to PlanRules.STATUS_DONE, "二" to PlanRules.STATUS_DOING)) +
            round(40, 20_000, payload("三" to PlanRules.STATUS_TODO, "四" to PlanRules.STATUS_TODO))
        val capsule = planCapsuleState(messages, running = true)
        assertNotNull(capsule)
        assertEquals("两份计划有未完成项（done 那份不占位）", 2, capsule!!.activeCount)
        assertEquals("列表也只列两份", 2, planAreaPlans(messages, running = true).size)
    }

    @Test
    fun `只列未完成_全done计划不进列表_也不给胶囊留入口`() {
        val messages = round(1, 1_000, payload("一" to PlanRules.STATUS_DONE, "二" to PlanRules.STATUS_DONE))
        assertTrue("已完成计划必须过滤掉（负例：留着 ⇒ 本断言红）", planAreaPlans(messages, running = false).isEmpty())
        assertNull("只剩已完成 ⇒ 胶囊也不显示", planCapsuleState(messages, running = false))
    }

    @Test
    fun `当前步骤_取第一个doing_没有doing取第一个未完成`() {
        val withDoing = planAreaPlans(
            round(
                1, 1_000,
                payload(
                    "一" to PlanRules.STATUS_DONE,
                    "二" to PlanRules.STATUS_DONE,
                    "三" to PlanRules.STATUS_DOING,
                    "四" to PlanRules.STATUS_TODO,
                ),
            ),
            running = true,
        ).single()
        assertEquals("当前步 = 第一个 doing（1 起）", 3, withDoing.currentStepIndex)
        assertEquals("三", withDoing.currentText)
        assertEquals("分子只数 done", 2, withDoing.done)
        assertEquals("分母是全部条目", 4, withDoing.total)

        val noDoing = planAreaPlans(
            round(
                1, 1_000,
                payload(
                    "一" to PlanRules.STATUS_DONE,
                    "二" to PlanRules.STATUS_TODO,
                    "三" to PlanRules.STATUS_TODO,
                ),
            ),
            running = true,
        ).single()
        assertEquals("没有 doing 就取第一个未完成", 2, noDoing.currentStepIndex)
        assertEquals("二", noDoing.currentText)
    }

    @Test
    fun `当前步骤高亮_只染当前行_不整行加粗`() {
        val area = code("BaopuPlanArea.kt")
        assertTrue(
            "面板必须把当前步 index 交给卡片（转成 0 起）",
            area.contains("highlightIndex = summary.currentStepIndex?.minus(1)"),
        )
        assertTrue("当前步用黛青（palette.accent）", area.contains("accentColor = accent"))
        val card = code("BaopuPlanCard.kt")
        assertTrue("卡片只对当前行取前景色", card.contains("highlightIndex == index"))
        assertTrue("只换颜色", card.contains("color = foreground"))
        assertFalse(
            "当前步骤不许靠整行加粗（11:50 设计约束）",
            card.contains("FontWeight.Bold"),
        )
    }

    // ── 聚合：同轮取最后一条、最新置顶、无计划轮不占组 ────────────────────

    @Test
    fun `聚合_三份未完成计划全部列出_最新置顶`() {
        val messages = round(1, 1_000, payload("一" to PlanRules.STATUS_TODO)) +
            round(20, 10_000, payload("二" to PlanRules.STATUS_DOING)) +
            round(40, 20_000, payload("三" to PlanRules.STATUS_TODO))
        val plans = planAreaPlans(messages, running = false)
        assertEquals("一个会话所有未完成计划集中在这里", 3, plans.size)
        assertEquals("最新轮置顶", "三", plans[0].plan.items.single().text)
        assertEquals("历史倒序", "二", plans[1].plan.items.single().text)
        assertEquals("最早的在最后", "一", plans[2].plan.items.single().text)
        assertTrue("最新的那轮就是当前轮", plans[0].isCurrent)
        assertEquals(0, plans[0].ageIndex)
        assertEquals(2, plans[2].ageIndex)
    }

    @Test
    fun `聚合_没有计划的轮不占组`() {
        val messages = round(1, 1_000, payload("一" to PlanRules.STATUS_TODO)) +
            round(20, 10_000, planPayload = null)
        val plans = planAreaPlans(messages, running = true)
        assertEquals("纯聊天轮不进面板", 1, plans.size)
        assertFalse("那一份不是当前轮的计划", plans[0].isCurrent)
    }

    @Test
    fun `聚合_同轮多次update_plan取最后一条`() {
        val messages = listOf(
            msg(1, "user", 1_000, textPart("做事")),
            msg(2, "assistant", 2_000, toolPart()),
            msg(3, "assistant", 2_100, plan = BaopuUiPlan.fromPayload(payload("旧" to PlanRules.STATUS_TODO))),
            msg(4, "assistant", 5_000, toolPart()),
            msg(5, "assistant", 5_100, plan = BaopuUiPlan.fromPayload(payload("新" to PlanRules.STATUS_DOING))),
        )
        val plans = planAreaPlans(messages, running = false)
        assertEquals(1, plans.size)
        assertEquals("后一版覆盖前一版", "新", plans[0].plan.items.single().text)
    }

    // ── 展开列表：超过 8 条收成「还有 N 个」 ──────────────────────────────

    private fun manyPlans(n: Int): List<PlanSummary> {
        var messages = emptyList<BaopuUiMessage>()
        for (i in 1..n) {
            messages = messages + round(i * 10L, i * 1_000L, payload("计划$i" to PlanRules.STATUS_TODO))
        }
        return planAreaPlans(messages, running = false)
    }

    @Test
    fun `展开列表_不超8条_全部显示_没有更多`() {
        val slice = planPanelSlice(manyPlans(8), showAll = false)
        assertEquals(8, slice.visible.size)
        assertEquals(0, slice.hiddenCount)
    }

    @Test
    fun `展开列表_超过8条_先收8条_还有N个`() {
        val slice = planPanelSlice(manyPlans(11), showAll = false)
        assertEquals(8, slice.visible.size)
        assertEquals(3, slice.hiddenCount)
        assertEquals("最新 8 条在前", "计划11", slice.visible.first().plan.items.single().text)
    }

    @Test
    fun `展开列表_点还有N个_一次看全`() {
        val slice = planPanelSlice(manyPlans(11), showAll = true)
        assertEquals(11, slice.visible.size)
        assertEquals(0, slice.hiddenCount)
    }

    // ── J5 迁移：计划级展开 ──────────────────────────────────────────────

    @Test
    fun `J5计划级_运行中当前计划自动展开`() {
        val plans = planAreaPlans(round(1, 1_000, payload("一" to PlanRules.STATUS_DOING)), running = true)
        assertTrue(
            "跑着 ⇒ 当前计划展开，打开面板即见每条 ✓/●/○",
            planSummaryExpanded(plans.single(), running = true, userChoice = emptyMap()),
        )
    }

    @Test
    fun `J5计划级_跑完自动收起`() {
        val plans = planAreaPlans(round(1, 1_000, payload("一" to PlanRules.STATUS_DOING)), running = false)
        assertFalse(
            "跑完 ⇒ 收起（胶囊入口保留）",
            planSummaryExpanded(plans.single(), running = false, userChoice = emptyMap()),
        )
    }

    @Test
    fun `J5计划级_用户选择优先`() {
        val plans = planAreaPlans(round(1, 1_000, payload("一" to PlanRules.STATUS_DOING)), running = false)
        val key = plans.single().roundKey
        assertTrue(
            "用户点开过 ⇒ 保持用户选择",
            planSummaryExpanded(plans.single(), running = false, userChoice = mapOf(key to true)),
        )
        assertFalse(
            "用户收起过 ⇒ 跑着也照用户的",
            planSummaryExpanded(plans.single(), running = true, userChoice = mapOf(key to false)),
        )
    }

    // ── 漏打勾（W11c，106 版口径原样保留） ────────────────────────────────

    @Test
    fun `漏打勾_已结束的轮还有未完成项才算`() {
        val round = groupRounds(round(1, 1_000, payload("一" to PlanRules.STATUS_DONE))).single()
        val items = BaopuUiPlan.fromPayload(
            payload("一" to PlanRules.STATUS_DONE, "二" to PlanRules.STATUS_TODO),
        )!!.items
        assertTrue("跑完了还有 todo ⇒ 漏打勾", planGroupStale(round, running = false, items = items))
        assertFalse(
            "在跑的当前轮不算漏",
            planGroupStale(round, running = true, items = items),
        )
    }

    @Test
    fun `漏打勾_面板里带上stale标记_但不替它补勾`() {
        val messages = round(1, 1_000, payload("一" to PlanRules.STATUS_DONE, "二" to PlanRules.STATUS_TODO))
        val plans = planAreaPlans(messages, running = false)
        assertTrue("结束轮有未完成项 ⇒ 计划行挂 chip", plans.single().stale)
        assertEquals("面板里也不替它补勾", 2, plans.single().plan.items.size)
        assertEquals(1, plans.single().done)
    }

    // ── 计划头：本轮 / 上一轮 / 第 N 轮 ＋ 时间 ────────────────────────────

    @Test
    fun `计划头_当前轮本轮_最新历史轮上一轮_更早第N轮`() {
        val messages = round(1, 1_000, payload("一" to PlanRules.STATUS_TODO)) +
            round(20, 10_000, planPayload = null) +
            round(40, 20_000, payload("三" to PlanRules.STATUS_TODO))
        val plans = planAreaPlans(messages, running = false)
        assertEquals(PlanGroupTitleKind.CURRENT, planGroupTitle(plans[0]).kind)
        assertEquals(
            "第 2 轮没有计划，所以最新历史计划是第 1 轮",
            PlanGroupTitleKind.NTH,
            planGroupTitle(plans[1]).kind,
        )
        assertEquals("第 N 轮取全轮序号", 1, planGroupTitle(plans[1]).nth)
    }

    @Test
    fun `计划头_当前轮没计划_最新有计划的叫上一轮`() {
        val messages = round(1, 1_000, payload("一" to PlanRules.STATUS_TODO)) +
            round(20, 10_000, planPayload = null)
        val plans = planAreaPlans(messages, running = true)
        assertEquals(
            "当前轮还没报计划 ⇒ 最新有计划的叫上一轮",
            PlanGroupTitleKind.PREV,
            planGroupTitle(plans.single()).kind,
        )
    }

    @Test
    fun `时间标签_今天昨天更早三态`() {
        val zone = TimeZone.getDefault()
        fun at(y: Int, m: Int, d: Int, h: Int, min: Int): Long =
            Calendar.getInstance(zone).apply {
                clear()
                set(y, m, d, h, min, 0)
            }.timeInMillis
        val now = at(2026, Calendar.OCTOBER, 21, 18, 0)

        val today = planTimeLabel(at(2026, Calendar.OCTOBER, 21, 10, 24), now)
        assertEquals(PlanTimeKind.TODAY, today.kind)
        assertEquals("10:24", today.clock)

        val yesterday = planTimeLabel(at(2026, Calendar.OCTOBER, 20, 22, 10), now)
        assertEquals(PlanTimeKind.YESTERDAY, yesterday.kind)
        assertEquals("22:10", yesterday.clock)

        val older = planTimeLabel(at(2026, Calendar.OCTOBER, 18, 9, 5), now)
        assertEquals(PlanTimeKind.DATE, older.kind)
        assertEquals("10-18", older.date)
    }

    // ── 11:50 设计约束：尺寸、颜色、触控 ──────────────────────────────────

    private fun luminance(argb: Long): Double {
        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    private fun contrast(a: Long, b: Long): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    @Test
    fun `胶囊规格_28dp视觉_48dp热区_120dp宽_rPill`() {
        val src = code("BaopuPlanArea.kt")
        assertTrue("视觉高 28dp", src.contains("PLAN_CAPSULE_VISUAL_DP = 28"))
        assertTrue("命中热区 ≥48dp", src.contains("PLAN_CAPSULE_TOUCH_DP = 48"))
        assertTrue("宽 ≤120dp（现状 268dp 必须收）", src.contains("PLAN_CAPSULE_MAX_WIDTH_DP = 120"))
        assertTrue("圆角 rPill", src.contains("RoundedCornerShape(rPill)"))
        assertTrue("热区真撑到 48dp", src.contains("defaultMinSize(minHeight = PLAN_CAPSULE_TOUCH_DP.dp)"))
        assertTrue("视觉真收到 28dp", src.contains("height(PLAN_CAPSULE_VISUAL_DP.dp)"))
    }

    @Test
    fun `面板规格_rSheet_320限高_行48dp_内部可滚`() {
        val src = code("BaopuPlanArea.kt")
        assertTrue("限高 320dp", src.contains("PLAN_PANEL_MAX_HEIGHT_DP = 320"))
        assertTrue("圆角 rSheet", src.contains("RoundedCornerShape(rSheet)"))
        assertTrue("每行命中 ≥48dp", src.contains("PLAN_ROW_MIN_DP = 48"))
        assertTrue("内部可滚（不撑破上限）", src.contains("verticalScroll(rememberScrollState())"))
        assertTrue("≥8 条折叠阈值", src.contains("PLAN_PANEL_VISIBLE_MAX = 8"))
    }

    @Test
    fun `胶囊配色_浅色6点11比_深色同构换色`() {
        val light = planPalette(dark = false)
        assertEquals("浅色底 #E3EDE7", 0xFFE3EDE7, light.capsuleBg)
        assertEquals("浅色字 #2C5F54", 0xFF2C5F54, light.capsuleFg)
        assertTrue(
            "浅色胶囊文字对比度 ≥ 4.5（设计稿给 6.11）",
            contrast(light.capsuleBg, light.capsuleFg) >= 4.5,
        )
        assertTrue(
            "浅色当前步骤黛青在纸面（#F7F4ED）上 ≥ 4.5",
            contrast(0xFFF7F4ED, light.accent) >= 4.5,
        )

        val dark = planPalette(dark = true)
        assertTrue("深色只是换色、结构不变", dark.capsuleBg != light.capsuleBg)
        assertTrue(
            "深色胶囊文字对比度 ≥ 4.5",
            contrast(dark.capsuleBg, dark.capsuleFg) >= 4.5,
        )
        assertTrue(
            "深色当前步骤（青提亮）在墨底上 ≥ 4.5",
            contrast(0xFF1C1B18, dark.accent) >= 4.5,
        )
    }

    @Test
    fun `胶囊文案_N个进行中_中英都有`() {
        val zh = res("values/strings.xml")
        val en = res("values-en/strings.xml")
        assertTrue("中文文案模板", zh.contains("baopu_plan_capsule_active\">%1\$d 个进行中"))
        assertTrue("英文文案模板", en.contains("baopu_plan_capsule_active\">%1\$d in progress"))
        // 134 批：折叠行改 <plurals>（lint PluralsCandidate），文案本身逐字不变。
        assertTrue("超过 8 条的折叠行", zh.contains("name=\"baopu_plan_more\"") && zh.contains("还有 %1\$d 个"))
        assertTrue("英文折叠行", en.contains("name=\"baopu_plan_more\"") && en.contains("%1\$d more"))
    }

    // ── 源码门禁：计划不进正文、挂载位置、可收回 ──────────────────────────

    @Test
    fun `正文零计划卡_计划只在面板`() {
        val list = code("BaopuMessageList.kt")
        assertFalse(
            "计划必须从消息正文全面撤出（回正文 ⇒ 本判据红）",
            list.contains("BaopuPlanCard("),
        )
        assertTrue(
            "面板里必须复用计划卡做组渲染（不是另写一套条目）",
            code("BaopuPlanArea.kt").contains("BaopuPlanCard("),
        )
        assertTrue(
            "计划数据只从消息里的已解析 plan 取（不新开查询）",
            code("BaopuPlanArea.kt").contains("planOrNull()"),
        )
    }

    @Test
    fun `计划区挂在消息区内_在底部卡区之前`() {
        val src = code("BaopuScreen.kt")
        val boxIdx = src.indexOf("Box(Modifier.weight(1f))")
        val planIdx = src.indexOf("BaopuPlanArea(")
        val composerIdx = src.indexOf("BaopuComposer(")
        assertTrue(
            "消息区 Box / 计划区 / 输入框三处都要在（索引失效会让本判据静默通过）",
            boxIdx > 0 && planIdx > 0 && composerIdx > 0,
        )
        assertTrue("计划区在消息区 Box 之内（物理上到不了输入框）", planIdx > boxIdx)
        assertTrue("计划区必须在输入框之前（不许跑到底部固定层）", planIdx < composerIdx)
        assertFalse(
            "面板不是 Dialog（30 批的门钉着整文件只有 1 处 Dialog(，那是图片全屏层）",
            code("BaopuPlanArea.kt").contains("Dialog("),
        )
    }

    @Test
    fun `面板点外面与返回键都能收回`() {
        val src = code("BaopuPlanArea.kt")
        assertTrue("遮罩点外面收回", src.contains("clickable { open = false }"))
        assertTrue("返回键收回", src.contains("BackHandler(enabled = open)"))
    }
}
