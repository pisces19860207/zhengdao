// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单 2026-10-21《第一百零五批 · 抱朴 UI P1：应用外壳》§1（测试＋负例）。
// 设计口径：`..\_zd-private\docs\抱朴UI设计方案-2026-10-21.md` v1.4 §3.8（抽屉）、§3.9（顶栏/跑马灯）、
// §3.10（设置页）、§4（人机工程）、§7 验收 #27–#32。
//
// 为什么这些判据只能写在源码＋纯函数层：本模块没有 UI 测试基建（无 Robolectric），
// Compose 的布局/触摸跑不起来。所以能算的**当场算**（宽度、窗口高度、减少动画降级），
// 算不了的（左/右、五件顺序、浮层 vs 内联）**钉源码结构**，并在断言里写清"变异体长什么样会红"。
package com.example.zhengdao.ui.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第一百零五批 · 外壳四件门禁（抽屉 / 顶栏 / 跑马灯 / 设置页）。
 *
 * 每条用例的第一行注释写「红＝什么被改坏了」，负例逐条对应派工单 §1 最后一段：
 * 抽屉从右侧开 / 跑马灯撑高顶栏 / 详情推正文 / ⊕ 热区不足 / 设置入口另加顶栏图标。
 */
class BaopuShellP1GateTest {

    // ── 读源码 ────────────────────────────────────────────────────────────

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    /** 剥注释后的源码（房型照 [BaopuCollapseAffordanceTest]／[BaopuTouchTargetGateTest]）。 */
    private fun code(rel: String): String = repoFile(rel).readText()
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private fun flat(s: String): String = s.replace(Regex("\\s+"), " ")

    private fun drawer(): String = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuDrawer.kt")

    private fun screen(): String = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuScreen.kt")

    private fun ticker(): String = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuNoticeTicker.kt")

    private fun settingsPage(): String =
        code("src/main/java/com/example/zhengdao/ui/baopu/BaopuSettingsScreen.kt")

    private fun strings(lang: String): String =
        code("src/main/res/$lang/strings.xml")

    /**
     * 顶栏那一段源码（五件注释 → 通知位注释）；五件顺序的判据只在这一段里查。
     *
     * ⚠️ 这里读**原文**（不剥注释）：定位锚点是注释里的「顶栏五件（v1.4 W9a）」，
     * 剥了注释就找不到块边界了（首跑就踩这个，写在里免得下一个人重踩）。
     */
    private fun topBarBlock(): String {
        val src = repoFile("src/main/java/com/example/zhengdao/ui/baopu/BaopuScreen.kt")
            .readText()
            .replace("\r\n", "\n")
        val start = src.indexOf("顶栏五件（v1.4 W9a）")
        assertTrue("顶栏注释块不见了（被重写？判据需要它定位）", start >= 0)
        val end = src.indexOf("// ── 第六十三批", start)
        assertTrue("顶栏结束位置找不到（通知位注释块被改了？）", end > start)
        return src.substring(start, end)
    }

    // ── ① 抽屉（v1.4 §3.8）──────────────────────────────────────────────

    /**
     * D1 七段顺序：会话 → 工作区 → 产物 → 回收站 → 资料与书 → 预览 → 设置。
     *
     * 红＝谁把某一段挪了位置、或把「设置」从底部挪走（设计里设置是低频位，必须在最后）。
     */
    @Test
    fun `D1_抽屉七段顺序固定_设置在最底部`() {
        val src = drawer()
        val order = listOf(
            "R.string.baopu_drawer_group_session",
            "R.string.baopu_drawer_group_workspace",
            "R.string.baopu_artifact_shelf_title",
            "R.string.trash_drawer_entry",
            "R.string.baopu_drawer_group_library",
            "R.string.baopu_drawer_group_preview",
            "R.string.baopu_drawer_group_settings",
        )
        val idx = order.map { src.indexOf(it) }
        idx.forEachIndexed { i, at ->
            assertTrue("第 ${i + 1} 段 ${order[i]} 不见了（七段缺一就是外壳残件）", at >= 0)
        }
        assertEquals("七段必须按设计顺序排（v1.4 §3.8），实得 $idx", idx.sorted(), idx)
        // 设置组里的两项（换通道 / 抱朴设置）必须排在「设置」组标题之后——即全抽屉最底部
        val settingsGroup = src.indexOf("R.string.baopu_drawer_group_settings")
        assertTrue(
            "「抱朴设置」入口必须在抽屉底部设置组里（v1.4：不占顶栏图标位）",
            src.indexOf("R.string.baopu_drawer_settings)", settingsGroup) > settingsGroup,
        )
    }

    /**
     * D2 抽屉在**左侧**、宽 ＝ min(屏宽×0.8, 300dp)。
     *
     * 红＝① 有人把宽度写死成 300.dp（小屏会顶出屏外，HardcodedSizeGuardTest 的同款病）；
     * ② 有人在 sheet 上加了 CenterEnd/end 对齐（抽屉就跑到右边去了）。
     */
    @Test
    fun `D2_抽屉在左侧_宽min屏宽08与300dp`() {
        assertEquals("360dp 基准下抽屉 288dp（右侧留 72dp）", 288f, drawerWidthDp(360), 0.01f)
        assertEquals("大屏封顶 300dp", 300f, drawerWidthDp(400), 0.01f)
        assertEquals("320dp 小屏按 80% 走", 256f, drawerWidthDp(320), 0.01f)

        val src = screen()
        assertTrue(
            "抽屉宽度必须由 drawerWidthDp 现算，不许写死（v1.4 §3.8 证据②）",
            flat(src).contains("val drawerWidth = drawerWidthDp(screenWidthDp).dp"),
        )
        assertTrue(
            "sheet 宽度必须吃 drawerWidth",
            flat(src).contains("ModalDrawerSheet(modifier = Modifier.width(drawerWidth))"),
        )
        // ModalNavigationDrawer 的 drawerContent 就是 start 侧抽屉；一旦有人用 End 对齐，
        // 这段里必然出现 CenterEnd/End —— 那就是「抽屉从右侧开」的变异体。
        val drawerBlock = src.substringAfter("ModalNavigationDrawer(").substringBefore(") {")
        assertFalse("抽屉必须贴左侧（start 侧），不许改成 End 对齐", drawerBlock.contains("CenterEnd"))
        assertFalse("抽屉宽度不许写死成 300.dp", flat(src).contains("ModalDrawerSheet(modifier = Modifier.width(300.dp))"))
    }

    /**
     * D3 三条关闭路径：点遮罩 / 返回键 / 点抽屉项（先关再跳）。
     *
     * 红＝有人图省事把 close 删了（点条目后抽屉还盖在页面上），或删了 BackHandler（返回键直接退出抱朴页）。
     */
    @Test
    fun `D3_抽屉三条关闭路径都在_点项先关再跳`() {
        val src = screen()
        assertTrue(
            "返回键要能关抽屉",
            flat(src).contains("BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }"),
        )
        assertTrue(
            "遮罩由 ModalNavigationDrawer 提供（它有 drawerState 才会画 scrim 并可点关）",
            src.contains("ModalNavigationDrawer(") && src.contains("drawerState = drawerState"),
        )
        // 「点项先关再跳」：抽屉里每个入口的回调都必须先 close 再干活，共 13 个入口。
        val closers = Regex("scope\\.launch \\{ drawerState\\.close\\(\\) \\}").findAll(flat(src)).count()
        assertTrue("点抽屉项必须先关抽屉再跳（实得 $closers 处，至少 13 个入口）", closers >= 13)
    }

    /**
     * D4 抽屉条目行高 ≥48dp、⊕ 视觉 24 / 命中盒 48（与 H1/H2 同源常量）。
     *
     * 红＝有人把行高常量调到 48 以下，或把条目从 heightIn(min) 改成固定高度（大字体就裁字）。
     */
    @Test
    fun `D4_条目行高与加号热区都达标`() {
        assertTrue("条目行高必须 ≥48dp（v1.4 §4）", BAO_ROW_MIN_HEIGHT_DP >= 48)
        assertEquals("⊕ 视觉圆点仍是 24dp", 24, RUN_HERE_GLYPH_DP)
        assertEquals("⊕ 命中盒仍是 48dp", 48, RUN_HERE_TOUCH_DP)
        val src = flat(drawer())
        assertTrue(
            "条目用最小行高（heightIn），不是固定高度——大字体下要能长高",
            src.contains(".heightIn(min = BAO_ROW_MIN_HEIGHT_DP.dp)"),
        )
        assertFalse("条目不许写死高度（写死＝大字体裁字）", src.contains(".height(BAO_ROW_MIN_HEIGHT_DP.dp)"))
    }

    /**
     * D5 抽屉文案零「终端」：抱朴没有终端组/终端能力，这个词只属于证道的另一个产品线。
     *
     * 红＝文案回潮（「（在『终端与资料』页）」这类旧句子最容易被复制回来）。
     */
    @Test
    fun `D5_抽屉里不出现终端旧词`() {
        assertFalse("抽屉源码不许出现「终端」", drawer().contains("终端"))
        for (lang in listOf("values", "values-en")) {
            val xml = strings(lang)
            val offenders = Regex("<string name=\"baopu_drawer_[a-z_]+\">([^<]*)</string>")
                .findAll(xml)
                .filter { it.groupValues[1].contains("终端") || it.groupValues[1].contains("Terminal") }
                .map { it.groupValues[1] }
                .toList()
            assertTrue("$lang 的抽屉文案里还有终端旧词：$offenders", offenders.isEmpty())
        }
    }

    // ── ② 顶栏五件（v1.4 §3.9 / §4）─────────────────────────────────────

    /**
     * T1 五件顺序：☰ → 状态行 → 跑马灯 → 🛡 → ＋。
     *
     * 红＝有人把盾牌挪到跑马灯前面、或把 ＋ 挤到状态行旁边（真机五件的左右手分工就乱了）。
     */
    @Test
    fun `T1_顶栏五件顺序固定`() {
        val bar = flat(topBarBlock())
        val order = listOf(
            "Text(text = \"☰\"",
            "statusLineWithStep(",
            "BaopuNoticeTicker(",
            "Text(text = \"🛡\"",
            "Text(text = \"＋\"",
        )
        val idx = order.map { bar.indexOf(it) }
        idx.forEachIndexed { i, at ->
            assertTrue("顶栏第 ${i + 1} 件 ${order[i]} 不见了（五件缺一即不合格）", at >= 0)
        }
        assertEquals("顶栏五件必须按 ☰·状态·跑马灯·🛡·＋ 排（v1.4 W9a），实得 $idx", idx.sorted(), idx)
    }

    /**
     * T2 三图标位各 48dp，360dp 下不缩不裁。
     *
     * 红＝有人把 IconButton 的 size 改成 40/44（触控红线），或改走 MinWidth（大字体下会被文字挤小）。
     */
    @Test
    fun `T2_三图标位各48dp`() {
        assertEquals("图标位常量必须是 48dp（v1.4 §4）", 48, BAO_ICON_SLOT_DP)
        val bar = flat(topBarBlock())
        val slots = Regex("size\\(BAO_ICON_SLOT_DP\\.dp\\)").findAll(bar).count()
        assertEquals("☰／🛡／＋ 三处都要 48dp 图标位（实得 $slots 处）", 3, slots)
        assertFalse("图标位不许残留写死的 40/44dp", bar.contains("size(40.dp)") || bar.contains("size(44.dp)"))
    }

    /**
     * T3 设置入口**不在**顶栏（v1.4 §0 决策 #5：守「刀 3 瘦身」）。
     *
     * 红＝有人给顶栏加第四颗图标（设置）——负例「设置入口另加顶栏图标」。
     */
    @Test
    fun `T3_顶栏不加设置图标_入口只在抽屉`() {
        val bar = topBarBlock()
        assertFalse("顶栏不许出现设置入口（showSettings）", bar.contains("showSettings"))
        assertFalse("顶栏不许出现「抱朴设置」文案", bar.contains("baopu_drawer_settings"))
        // 反证：入口确实在抽屉里（不是被删了）
        assertTrue("「抱朴设置」入口必须在抽屉里", drawer().contains("R.string.baopu_drawer_settings"))
        assertTrue("抽屉要把 onSettings 接上", screen().contains("onSettings = {"))
    }

    /**
     * T4 状态行锁单行（否则 1.3× 字体折行 ⇒ 顶栏被撑高，正好是负例「跑马灯撑高顶栏」的连带）。
     *
     * 红＝有人删掉 maxLines/overflow（状态文案一长就两行）。
     */
    @Test
    fun `T4_状态行单行省略`() {
        val bar = flat(topBarBlock())
        val status = bar.substringAfter("statusLineWithStep(").take(320)
        assertTrue("状态行必须 maxLines = 1（不许折行撑高顶栏）", status.contains("maxLines = 1"))
        assertTrue("状态行超宽要省略", status.contains("TextOverflow.Ellipsis"))
    }

    // ── ③ 跑马灯（v1.4 §3.9）────────────────────────────────────────────

    /**
     * K1 窗口高度：默认 16dp，大字体跟着长但**正好一行**。
     *
     * 红＝① 有人把默认值改成别的数（v1.4 与真机门禁 H5 都钉 16）；
     * ② 有人图省事写成"随字号无限长"——窗口比一行高就会露出下一条标签的半截（UI-D1 重影）。
     */
    @Test
    fun `K1_跑马灯窗口默认16_大字体等于一行行高`() {
        assertEquals("默认窗口仍是 16dp（v1.4 §3.9）", 16, TICKER_HEIGHT_DP)
        assertEquals("1.0× 字体＝默认 16dp", 16f, tickerWindowDp(1.0f), 0.01f)
        assertEquals("1.3× 字体＝一行行高 13×1.3＝16.9dp（旧版 16dp 会裁字）", 16.9f, tickerWindowDp(1.3f), 0.01f)
        assertEquals("2.0× 字体＝26dp", 26f, tickerWindowDp(2.0f), 0.01f)
        // 大字体下的唯一正确值＝「一行行高」：比它矮＝裁字，比它高＝看到第二条的半截。
        // （1.0× 例外：一行 13dp 比默认 16dp 还矮，此时窗口**保持 16dp 不回缩**——那是定稿值。）
        // 分界在 16/13 ≈ 1.231×：小于它时一行还比默认窗口矮，窗口保持 16dp 不动。
        assertEquals("1.15× 时一行 14.95dp 仍比默认矮 ⇒ 窗口保持 16dp", 16f, tickerWindowDp(1.15f), 0.01f)
        listOf(1.3f, 1.5f, 2.0f, 3.0f).forEach { scale ->
            val line = TICKER_LINE_DP * scale
            val win = tickerWindowDp(scale)
            assertEquals("fontScale=$scale 时窗口必须正好一行行高 $line", line, win, 0.01f)
            // 且顶栏高度仍由 48dp 图标位决定（零额外高度）
            assertTrue(
                "fontScale=$scale 时窗口 $win 不该顶到图标位 $BAO_ICON_SLOT_DP（否则就是撑高顶栏）",
                win < BAO_ICON_SLOT_DP,
            )
        }
        assertEquals("1.0× 时一行只有 13dp，窗口**不回缩**（默认 16dp 是定稿值）", 16f, tickerWindowDp(1.0f), 0.01f)
    }

    /**
     * K2 详情是**浮层**（DropdownMenu）、有尺寸上限，不推正文。
     *
     * 红＝有人把详情改回"在顶栏里展开一块高区域"——那就是负例「详情推正文」。
     */
    @Test
    fun `K2_跑马灯详情是浮层且限尺寸`() {
        val src = flat(ticker())
        assertTrue("详情必须走 DropdownMenu（浮层，不占布局高度）", src.contains("DropdownMenu("))
        assertTrue("详情宽度上限 320dp（v1.4 §3.9）", src.contains("widthIn(max = 320.dp)"))
        assertTrue("详情高度上限 280dp，内部可滚", src.contains("heightIn(max = 280.dp)"))
        assertTrue("详情内部可滚", src.contains("verticalScroll(rememberScrollState())"))
    }

    /**
     * K3 减少动画（系统动画缩放＝0）时降级为「只显最新一条、不滚」。
     *
     * 红＝有人只判断了 items.size 却没看系统动画开关（无障碍用户的"关动画"就没生效）。
     */
    @Test
    fun `K3_减少动画降级为只显最新一条`() {
        assertTrue("动画缩放 0 ⇒ 减少动画", reduceMotionFrom(0f))
        assertFalse("动画缩放 1 ⇒ 正常滚", reduceMotionFrom(1f))
        assertFalse("动画缩放 0.5 ⇒ 仍是动画", reduceMotionFrom(0.5f))
        val src = flat(ticker())
        assertTrue("减少动画时只摆最新一条", src.contains("if (reduceMotion) listOf(items.last()) else tickerLoop(items)"))
        assertTrue("减少动画时滚动循环要提前返回", src.contains("if (reduceMotion) return@LaunchedEffect"))
    }

    // ── ④ 设置页五卡（v1.4 §3.10）────────────────────────────────────────

    /**
     * S1 五卡齐全且顺序固定：抱朴工具 → 模型参数 → 思考深度 → 人设 → 技能索引。
     *
     * 红＝有人把技能索引挪到人设前面（"设置页越用越乱"就是这么开始的）。
     */
    @Test
    fun `S1_设置页五卡齐全且顺序固定`() {
        val src = settingsPage()
        val cards = listOf(
            "R.string.baopu_tools_card",
            "R.string.baopu_params_card",
            "R.string.baopu_effort_card",
            "R.string.persona_rollback_card",
            "R.string.skill_review_card",
        )
        val idx = cards.map { src.indexOf("SectionCard(stringResource($it)") }
        idx.forEachIndexed { i, at ->
            assertTrue("设置页第 ${i + 1} 张卡 ${cards[i]} 不见了", at >= 0)
        }
        assertEquals("五卡顺序必须与 v1.4 W10a 一致，实得 $idx", idx.sorted(), idx)
    }

    /**
     * S2 模型参数没声明就写「未声明」，**不许**编 0 或旧的 128000。
     *
     * 红＝有人把 `?: ` 兜底写成 0 或把作废的回退值抄回来（J12 原话级口径）。
     */
    @Test
    fun `S2_模型参数未声明不许编数`() {
        val src = settingsPage()
        val undeclared = Regex("R\\.string\\.baopu_params_undeclared").findAll(src).count()
        assertEquals("窗口与最大输出两行都要兜「未声明」", 2, undeclared)
        assertFalse("不许出现作废的旧回退值 128000", src.contains("128000"))
        assertFalse("不许用 0 假装声明过", src.contains("?: 0 )") || src.contains("?:0)"))
    }

    /**
     * S3 人设回滚：没有备份时按钮**灰掉但这一行不消失**（L6 末句）。
     *
     * 红＝有人把按钮包进 `if (latest != null)`（没备份就整颗消失＝用户以为功能没了）。
     */
    @Test
    fun `S3_人设无备份时按钮灰掉不消失`() {
        val src = flat(settingsPage())
        assertTrue("按钮必须以 enabled 表达灰态", src.contains("enabled = latest != null"))
        assertFalse("不许用 if 把按钮整颗藏掉", src.contains("if (latest != null) { TextButton"))
        assertTrue("没有备份的提示文案要占位", src.contains("R.string.persona_rollback_none"))
    }

    /**
     * S4 技能索引三条口径：删索引＝归档（唯一写盘口）、转正只在够次数时出现、待重验只红字提示。
     *
     * 红＝有人把「删索引」写成直接删文件（绕开归档），或把转正做成恒显示（次数不够也能点）。
     */
    @Test
    fun `S4_技能索引归档转正与待重验口径`() {
        val src = settingsPage()
        assertTrue("删索引必须走存档（SkillStore.archive），不硬删文件", src.contains("SkillStore.archive("))
        assertTrue("转正只在 canPromote 为真时出现", src.contains("if (row.canPromote)"))
        assertTrue("待重验用红字徽标只提示", src.contains("R.string.skill_recheck_badge"))
        assertTrue("分母是数据层的 PROMOTE_USES，UI 不自己数", src.contains("SkillStore.PROMOTE_USES"))
    }
}
