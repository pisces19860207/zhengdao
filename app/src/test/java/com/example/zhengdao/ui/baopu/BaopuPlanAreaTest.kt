// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第一百零六批 §3（测试 · 计划区）＋ 设计 v1.5 §3.11。
//
// 两层判据（与 `BaopuPlanCardTest` 同房型）：
// - **纯函数层**（JVM 直测）：`planAreaGroups` / `planCapsuleState` / `planGroupExpanded` /
//   `planGroupStale` / `planGroupTitle` / `planTimeLabel`——本仓无 Robolectric，Compose
//   渲染跑不起来，所以判据全压在纯函数与源码断言上；
// - **源码级门禁**：计划不进正文（J16 升级）、胶囊显隐只看有没有计划（J15 迁移）、
//   触控/尺寸常量、挂载位置（消息区内、卡区之前）。
//
// 负例靶心（派工单 §3「负例」）：
// ① 计划卡回正文 ⇒ `正文零计划卡` 红；
// ② 胶囊无计划也占位 ⇒ `无计划_胶囊态为null` 与 `胶囊显隐只看有没有计划` 红。
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

    private fun planOf(vararg items: Pair<String, String>) =
        BaopuUiPlan(items.map { (t, s) -> BaopuPlanItem(t, s) })

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

    // ── 胶囊：无计划零占位 ────────────────────────────────────────────────

    @Test
    fun `无计划_胶囊态为null_零占位`() {
        assertNull("空会话不占位", planCapsuleState(emptyList(), running = false))
        val chatOnly = listOf(msg(1, "user", 1_000, textPart("你好")), msg(2, "assistant", 2_000, textPart("在")))
        assertNull("纯聊天轮不占位", planCapsuleState(chatOnly, running = false))
    }

    @Test
    fun `胶囊显隐只看有没有计划_源码级`() {
        val src = code("BaopuPlanArea.kt")
        assertTrue(
            "无计划必须整块不渲染（零占位），不是渲染一个空胶囊",
            src.contains("if (capsule == null || groups.isEmpty()) return"),
        )
    }

    // ── 胶囊：计数与当前步 ────────────────────────────────────────────────

    @Test
    fun `胶囊_第n步取第一个doing`() {
        val messages = round(
            1, 1_000,
            payload(
                "一" to PlanRules.STATUS_DONE,
                "二" to PlanRules.STATUS_DONE,
                "三" to PlanRules.STATUS_DOING,
                "四" to PlanRules.STATUS_TODO,
            ),
        )
        val capsule = planCapsuleState(messages, running = true)!!
        assertEquals("分子只数 done", 2, capsule.done)
        assertEquals("分母是全部条目", 4, capsule.total)
        assertEquals("当前步 = 第一个 doing 的序号（1 起）", 3, capsule.currentStepIndex)
        assertEquals("当前步短句", "三", capsule.currentText)
        assertFalse(capsule.allDone)
    }

    @Test
    fun `胶囊_没有doing取第一个todo`() {
        val messages = round(
            1, 1_000,
            payload(
                "一" to PlanRules.STATUS_DONE,
                "二" to PlanRules.STATUS_TODO,
                "三" to PlanRules.STATUS_TODO,
            ),
        )
        val capsule = planCapsuleState(messages, running = true)!!
        assertEquals(2, capsule.currentStepIndex)
        assertEquals("二", capsule.currentText)
    }

    @Test
    fun `胶囊_全完成_allDone且无当前步`() {
        val messages = round(
            1, 1_000,
            payload("一" to PlanRules.STATUS_DONE, "二" to PlanRules.STATUS_DONE),
        )
        val capsule = planCapsuleState(messages, running = false)!!
        assertTrue(capsule.allDone)
        assertNull(capsule.currentStepIndex)
        assertFalse("全完成不是漏打勾", capsule.stale)
    }

    @Test
    fun `胶囊_当前轮没报计划_取最后一个有计划的轮`() {
        val messages = round(1, 1_000, payload("旧一" to PlanRules.STATUS_DONE, "旧二" to PlanRules.STATUS_DOING)) +
            round(10, 20_000, planPayload = null, answer = "纯聊天")
        val capsule = planCapsuleState(messages, running = true)!!
        assertEquals("取的是上一轮那份计划", 2, capsule.total)
        assertEquals("旧二", capsule.currentText)
    }

    // ── 分组：全部计划、最新置顶、历史倒序 ───────────────────────────────

    @Test
    fun `分组_三份计划全部列出_最新置顶`() {
        val messages = round(1, 1_000, payload("一" to PlanRules.STATUS_DONE)) +
            round(20, 10_000, payload("二" to PlanRules.STATUS_DOING)) +
            round(40, 20_000, payload("三" to PlanRules.STATUS_TODO))
        val groups = planAreaGroups(messages, running = false)
        assertEquals("按轮列出本会话所有计划", 3, groups.size)
        assertEquals("最新轮置顶", "三", groups[0].plan.items.single().text)
        assertEquals("历史倒序", "二", groups[1].plan.items.single().text)
        assertEquals("最早的在最后", "一", groups[2].plan.items.single().text)
        assertTrue("最新的那轮就是当前轮", groups[0].isCurrent)
    }

    @Test
    fun `分组_没有计划的轮不占组`() {
        val messages = round(1, 1_000, payload("一" to PlanRules.STATUS_DONE)) +
            round(20, 10_000, planPayload = null)
        val groups = planAreaGroups(messages, running = true)
        assertEquals("纯聊天轮不进面板", 1, groups.size)
        assertFalse("那一组不是当前轮", groups[0].isCurrent)
    }

    @Test
    fun `分组_同轮多次update_plan取最后一条`() {
        val messages = listOf(
            msg(1, "user", 1_000, textPart("做事")),
            msg(2, "assistant", 2_000, toolPart()),
            msg(3, "assistant", 2_100, plan = BaopuUiPlan.fromPayload(payload("旧" to PlanRules.STATUS_TODO))),
            msg(4, "assistant", 5_000, toolPart()),
            msg(5, "assistant", 5_100, plan = BaopuUiPlan.fromPayload(payload("新" to PlanRules.STATUS_DONE))),
        )
        val groups = planAreaGroups(messages, running = false)
        assertEquals(1, groups.size)
        assertEquals("后一版覆盖前一版", "新", groups[0].plan.items.single().text)
    }

    // ── J5 迁移：组级展开 ────────────────────────────────────────────────

    @Test
    fun `J5组级_运行中当前组自动展开`() {
        val groups = planAreaGroups(round(1, 1_000, payload("一" to PlanRules.STATUS_DOING)), running = true)
        assertTrue(
            "跑着 ⇒ 当前组展开，打开面板即见每条 ✓/●/○",
            planGroupExpanded(groups.single(), running = true, userChoice = emptyMap()),
        )
    }

    @Test
    fun `J5组级_跑完当前组自动收起`() {
        val groups = planAreaGroups(round(1, 1_000, payload("一" to PlanRules.STATUS_DONE)), running = false)
        assertFalse(
            "跑完 ⇒ 收起（胶囊入口保留）",
            planGroupExpanded(groups.single(), running = false, userChoice = emptyMap()),
        )
    }

    @Test
    fun `J5组级_用户选择优先_跑完也不收回用户刚点的展开`() {
        val groups = planAreaGroups(round(1, 1_000, payload("一" to PlanRules.STATUS_DONE)), running = false)
        val key = groups.single().roundKey
        assertTrue(
            "用户点开过 ⇒ 保持用户选择",
            planGroupExpanded(groups.single(), running = false, userChoice = mapOf(key to true)),
        )
        assertFalse(
            "用户收起过 ⇒ 跑着也照用户的",
            planGroupExpanded(groups.single(), running = true, userChoice = mapOf(key to false)),
        )
    }

    @Test
    fun `J5组级_历史组不因在跑而展开`() {
        val messages = round(1, 1_000, payload("旧" to PlanRules.STATUS_DONE)) +
            round(20, 10_000, payload("新" to PlanRules.STATUS_DOING))
        val groups = planAreaGroups(messages, running = true)
        assertTrue("当前组展开", planGroupExpanded(groups[0], running = true, userChoice = emptyMap()))
        assertFalse(
            "历史组不跟跑",
            planGroupExpanded(groups[1], running = true, userChoice = emptyMap()),
        )
    }

    // ── 漏打勾（W11c） ──────────────────────────────────────────────────

    @Test
    fun `漏打勾_已结束的轮还有未完成项才算`() {
        val items = planOf(
            "一" to PlanRules.STATUS_DONE,
            "二" to PlanRules.STATUS_TODO,
        ).items
        val round = groupRounds(round(1, 1_000, payload("一" to PlanRules.STATUS_DONE))).single()
        assertTrue("跑完了还有 todo ⇒ 漏打勾", planGroupStale(round, running = false, items = items))
    }

    @Test
    fun `漏打勾_在跑的当前轮不算漏`() {
        val items = planOf("一" to PlanRules.STATUS_DOING).items
        val round = groupRounds(round(1, 1_000, payload("一" to PlanRules.STATUS_DOING))).single()
        assertFalse("在跑是正常中间态", planGroupStale(round, running = true, items = items))
        assertFalse(
            "全 done 更不算漏",
            planGroupStale(round, running = false, items = planOf("一" to PlanRules.STATUS_DONE).items),
        )
    }

    @Test
    fun `漏打勾_分组里带上stale标记`() {
        val messages = round(1, 1_000, payload("一" to PlanRules.STATUS_DONE, "二" to PlanRules.STATUS_TODO))
        val groups = planAreaGroups(messages, running = false)
        assertTrue("结束轮有未完成项 ⇒ 组头挂 chip", groups.single().stale)
        assertEquals(
            "面板里也不替它补勾",
            2,
            groups.single().plan.items.size,
        )
    }

    // ── 组头：本轮 / 上一轮 / 第 N 轮 ＋ 时间 ────────────────────────────

    @Test
    fun `组头_当前轮本轮_最新历史组上一轮_更早第N轮`() {
        val messages = round(1, 1_000, payload("一" to PlanRules.STATUS_DONE)) +
            round(20, 10_000, planPayload = null) +
            round(40, 20_000, payload("三" to PlanRules.STATUS_DONE))
        val groups = planAreaGroups(messages, running = false)
        assertEquals(PlanGroupTitleKind.CURRENT, planGroupTitle(groups[0]).kind)
        // 第 2 轮没有计划，所以最新的历史组是第 1 轮（它前面只有它自己）
        assertEquals(PlanGroupTitleKind.NTH, planGroupTitle(groups[1]).kind)
        assertEquals("第 N 轮取全轮序号", 1, planGroupTitle(groups[1]).nth)
    }

    @Test
    fun `组头_当前轮没计划_最新有计划的组叫上一轮`() {
        val messages = round(1, 1_000, payload("一" to PlanRules.STATUS_DONE)) +
            round(20, 10_000, planPayload = null)
        val groups = planAreaGroups(messages, running = true)
        assertEquals(
            "当前轮还没报计划 ⇒ 最新有计划的组是上一轮",
            PlanGroupTitleKind.PREV,
            planGroupTitle(groups.single()).kind,
        )
    }

    @Test
    fun `时间标签_今天昨天更早三态`() {
        // 时刻与判定都在系统默认时区里造（生产路径走 TimeFormats，唯一真源），
        // 所以在任何时区的机器上这条都自洽。
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

    // ── 源码门禁：计划不进正文、规格常量、挂载位置 ──────────────────────

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
    }

    @Test
    fun `胶囊与行的触控规格`() {
        val src = code("BaopuPlanArea.kt")
        assertTrue("视觉高 32dp", src.contains("PLAN_CAPSULE_VISUAL_DP = 32"))
        assertTrue("热区 ≥48dp", src.contains("PLAN_CAPSULE_TOUCH_DP = 48"))
        assertTrue("组头/条目行 ≥48dp", src.contains("PLAN_ROW_MIN_DP = 48"))
    }

    @Test
    fun `面板尺寸上限_宽340高六成`() {
        val src = code("BaopuPlanArea.kt")
        assertTrue("宽上限 340dp", src.contains("PLAN_PANEL_MAX_WIDTH_DP = 340"))
        assertTrue("左右让位 32dp（屏宽 360 ⇒ 328）", src.contains("PLAN_PANEL_MARGIN_DP = 32"))
        assertTrue("高上限 = 屏高 60%", src.contains("PLAN_PANEL_HEIGHT_RATIO = 0.6f"))
        assertTrue("内部可滚（不撑破上限）", src.contains("verticalScroll("))
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
