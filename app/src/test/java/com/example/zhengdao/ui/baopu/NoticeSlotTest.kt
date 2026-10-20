// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单 2026-10-20 第六十三批 · UI 第四批（抱朴首屏通知位合并）§3，
//      ＋ 蓝总管同日定稿（一次只显示一条：错误 > 行动 > 提示；其余折成「+N」，
//        点开 bottomSheet 看全量，每条都可操作；通知位空态整条不渲染）。
//
// 读法沿用本仓 UI 批次的两型：
// - A 组＝纯函数，零 Android，JVM 直接问（优先级／rank／稳定序／去重／+N）；
// - B 组＝源码门禁：注释剥掉、字符串留着（要钉的是文案与代码混在一起的那些行）。
//
// 三条负例（派工单 §3）改哪儿会红，写在每条用例自己的注释里。
package com.example.zhengdao.ui.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NoticeSlotTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    private fun code(rel: String): String = repoFile(rel).readText()
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private fun screen(): String = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuScreen.kt")

    private fun slot(): String = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuNoticeSlot.kt")

    private fun entry(src: BaopuNoticeSource, text: String) = BaopuNoticeEntry(src, text)

    // ── A 组：排序与计数（纯函数） ────────────────────────────────────────

    /**
     * 负例 1 的靶心：把 [BaopuNoticeLevel] 的声明顺序改反（Hint 排到第一）⇒ 这条与 A2 立刻红。
     * 优先级只由「声明顺序」这一处表达，所以没有第二条路可以悄悄改回去。
     */
    @Test
    fun `A1_一次只显一条_错误压行动压提示`() {
        assertEquals(
            "声明顺序就是优先级：错误 > 行动 > 提示",
            listOf("Error", "Action", "Hint"),
            BaopuNoticeLevel.values().map { it.name },
        )
        val all = listOf(
            entry(BaopuNoticeSource.EngineNotice, "提示"),
            entry(BaopuNoticeSource.MemoryWriteError, "错误"),
            entry(BaopuNoticeSource.Resume, "行动"),
        )
        assertEquals(BaopuNoticeLevel.Error, baopuNoticeOrder(all).first().level)
        assertEquals(
            "没有错误时行动上条",
            BaopuNoticeLevel.Action,
            baopuNoticeOrder(listOf(all[0], all[2])).first().level,
        )
        assertEquals(
            "只剩提示时才轮到提示",
            BaopuNoticeLevel.Hint,
            baopuNoticeOrder(listOf(all[0])).first().level,
        )
    }

    /** 定稿那张「同优先级谁先上」的表。故意用**倒着传**的顺序，证明赢的是 rank 不是插入先后。 */
    @Test
    fun `A2_同档谁先上_按定稿那张表`() {
        assertEquals(
            "行动：续跑 > 导出",
            BaopuNoticeSource.Resume,
            baopuNoticeOrder(
                listOf(entry(BaopuNoticeSource.Export, "导出"), entry(BaopuNoticeSource.Resume, "续跑")),
            ).first().source,
        )
        assertEquals(
            "错误：记忆写失败 > 资料库错 > 技能错",
            listOf(
                BaopuNoticeSource.MemoryWriteError,
                BaopuNoticeSource.LibraryError,
                BaopuNoticeSource.SkillError,
            ),
            baopuNoticeOrder(
                listOf(
                    entry(BaopuNoticeSource.SkillError, "丙"),
                    entry(BaopuNoticeSource.LibraryError, "乙"),
                    entry(BaopuNoticeSource.MemoryWriteError, "甲"),
                ),
            ).map { it.source },
        )
        assertEquals(
            "提示：引擎人话 > Info 便签",
            BaopuNoticeSource.EngineNotice,
            baopuNoticeOrder(
                listOf(entry(BaopuNoticeSource.InfoNote, "进度"), entry(BaopuNoticeSource.EngineNotice, "人话")),
            ).first().source,
        )
    }

    /** 同档同 rank 不许互相插队（`sortedWith` 是稳定排序；换成不稳定的写法这条红）。 */
    @Test
    fun `A3_同档同rank_保持传入顺序`() {
        val ordered = baopuNoticeOrder(
            listOf(
                entry(BaopuNoticeSource.InfoNote, "记下来中…"),
                entry(BaopuNoticeSource.InfoNote, "收料处理中…"),
                entry(BaopuNoticeSource.InfoNote, "导出中…"),
            ),
        )
        assertEquals(listOf("记下来中…", "收料处理中…", "导出中…"), ordered.map { it.text })
    }

    /**
     * 去重口径钉死：
     * - **同档同文案** ⇒ 只留先来那条（技能与资料库同报一句「没有可导出的原件」时第二条是噪音）；
     * - **跨档同文案** ⇒ 都留着（错误与提示同时喊同一句话是两件事，吞掉一条就是少一项）。
     */
    @Test
    fun `A4_同档同文案去重_跨档不去重`() {
        assertEquals(
            1,
            baopuNoticeOrder(
                listOf(
                    entry(BaopuNoticeSource.LibraryError, "没有可导出的原件"),
                    entry(BaopuNoticeSource.SkillError, "没有可导出的原件"),
                ),
            ).size,
        )
        assertEquals(
            "错误与提示同文案不算重复",
            2,
            baopuNoticeOrder(
                listOf(
                    entry(BaopuNoticeSource.MemoryWriteError, "网络不通"),
                    entry(BaopuNoticeSource.EngineNotice, "网络不通"),
                ),
            ).size,
        )
        assertEquals("去重留的是先来那条", BaopuNoticeSource.LibraryError, baopuNoticeOrder(
            listOf(
                entry(BaopuNoticeSource.LibraryError, "同一句"),
                entry(BaopuNoticeSource.SkillError, "同一句"),
            ),
        ).first().source)
    }

    /** 空态与「+N」。空态那条同时钉着 B3 的源码形态——判据只有一处：条数。 */
    @Test
    fun `A5_空态不占位_N等于剩下的条数`() {
        assertTrue(baopuNoticeOrder(emptyList()).isEmpty())
        assertEquals(0, baopuNoticeMoreCount(baopuNoticeOrder(emptyList())))
        assertEquals(
            "只有一条时不该出现 +N（没东西可折）",
            0,
            baopuNoticeMoreCount(listOf(entry(BaopuNoticeSource.Resume, "续跑"))),
        )
        val four = listOf(
            entry(BaopuNoticeSource.MemoryWriteError, "甲"),
            entry(BaopuNoticeSource.Resume, "乙"),
            entry(BaopuNoticeSource.Export, "丙"),
            entry(BaopuNoticeSource.EngineNotice, "丁"),
        )
        assertEquals("条上显 1 条，折 3 条", 3, baopuNoticeMoreCount(baopuNoticeOrder(four)))
    }

    /** 排序与去重不许把功能吞了：六个来源全进得去（少一项＝打回判据第 4 条）。 */
    @Test
    fun `A6_六个来源一个不丢`() {
        val all = BaopuNoticeSource.values().map { entry(it, "文$it") }
        val ordered = baopuNoticeOrder(all)
        assertEquals(BaopuNoticeSource.values().size, ordered.size)
        assertEquals(all.map { it.source }.toSet(), ordered.map { it.source }.toSet())
        assertEquals(
            "档位就是来源带的，UI 不再判一遍",
            all.map { it.source.level }.toSet(),
            ordered.map { it.level }.toSet(),
        )
    }

    // ── B 组：源码门禁 ────────────────────────────────────────────────────

    /**
     * 旧首屏**八处**渲染点各有新家（派工单 §1 那张账的可执行版）：
     * 三条错误便签／两条进度便签（技能与资料库各两条分支）／中断＋续跑／防丢＋一键导出／引擎人话。
     * 通知位在屏上只有**一个出口**。
     */
    @Test
    fun `B1_八处旧渲染点各有新家_屏里只剩一个出口`() {
        val src = screen()
        assertEquals("BaopuScreen 里只许有一个通知位出口", 1, Regex("BaopuNoticeSlot\\(").findAll(src).count())
        assertEquals("八条条目构造＝旧八处各有新家，少一项就是打回", 8, Regex("BaopuNoticeEntry\\(").findAll(src).count())
        listOf(
            "controller.pendingNote?.let { note ->",
            "controller.skillNote?.let { note ->",
            "libraryNote?.let { note ->",
            "controller.interruptedStep >= 0",
            "!engineNotice.isNullOrBlank()",
            "BackupRules.isStale(stats.lastExportAt, nowMs, stats.total)",
            "controller.exportOriginals(false)",
            "controller.resume()",
        ).forEach { assertTrue("旧条目的判据／入口没了：$it", src.contains(it)) }
    }

    /**
     * 消息流上方不得再出现「防丢横幅／SessionBar／三个便签」**各自独立的常驻渲染块**（派工单 §3）。
     * 切片取 `val stats` 到 `BaopuNoticeSlot(noticeEntries)` 之间：那儿只剩数据，不该再有第二个 Surface。
     */
    @Test
    fun `B2_旧的五条独立渲染块确实不在了`() {
        val src = screen()
        val i = src.indexOf("val stats = controller.backupStats")
        val j = src.indexOf("BaopuNoticeSlot(noticeEntries)")
        assertTrue("通知位的接线段还在", i in 0 until j)
        val slice = src.substring(i, j)
        listOf("Surface(", "TextButton(", "errorContainer", "Text(", "Row(").forEach {
            assertFalse("旧渲染块的形态又回来了：$it", slice.contains(it))
        }
        // 整文件：五条旧渲染点全部零命中（它们只该活在通知位里）
        assertEquals(0, Regex("BaopuSessionBar\\(").findAll(src).count())
        assertEquals(0, Regex("BaopuIntrospectionNote\\(").findAll(src).count())
        assertFalse(src.contains("MaterialTheme.colorScheme.errorContainer"))
    }

    /**
     * 空态必须在 `if` 内（派工单 §3 负例 3：又回退成常驻空条 ⇒ 红）。
     * 形态与 49 批 `SessionBarEmptyStateGateTest` 同一条判据：守卫在前，Surface 在后。
     */
    @Test
    fun `B3_空态整条不渲染_守卫在Surface之前`() {
        val src = slot()
        val guard = src.indexOf("if (ordered.isEmpty()) return")
        val surface = src.indexOf("Surface(")
        assertTrue("通知位里必须有空态守卫", guard >= 0)
        assertTrue("Surface( 必须在空态守卫之后（否则空态又撑出一条灰条）", surface > guard)
        // 而且不许在屏里再判一遍空（两处判据＝下一次改漏的源头）
        assertFalse(screen().contains("noticeEntries.isEmpty()"))
    }

    /**
     * 保命线 3：导出／续跑这类**行动入口必须直接长在通知条上**。
     * 负例 2 的靶心：把条上那颗按钮删掉、只留详情卡里那份 ⇒ 这条红。
     */
    @Test
    fun `B4_行动入口长在条上_不是只藏在加N里`() {
        val src = slot()
        val row = src.substringAfter("Surface(").substringBefore("ModalBottomSheet(")
        assertTrue("条上必须渲染 top.action 那颗按钮", row.contains("top.action?.let"))
        assertTrue("按钮真的接上 run", src.contains("TextButton(onClick = a.run, enabled = a.enabled)"))
        assertTrue("「+N」也在条上（点开才谈全量）", row.contains("more > 0"))
        assertEquals("屏里两个行动入口：导出／续跑", 2, Regex("BaopuNoticeAction\\(").findAll(screen()).count())
    }

    /** 点掉错误便签走的还是那三个既有 clear*（61 批 B3 的原话，跟着渲染点搬进通知位）。 */
    @Test
    fun `B5_清理通道没换_也不许新开`() {
        val src = screen()
        listOf(
            "controller.clearSkillNote()",
            "controller.clearLibraryNote()",
            "controller.clearPendingNote()",
        ).forEach { clearer ->
            assertEquals(
                "$clearer 只许挂在 onDismiss 上，且只这一处",
                1,
                Regex("onDismiss = \\{ ${Regex.escape(clearer)} \\}").findAll(src).count(),
            )
        }
        // 引擎人话那条不许被顺手改成「UI 自己清」（那是第二条清的状态，而且 agent 里没有那个口子）
        assertFalse(src.contains("clearEngineNotice"))
        assertFalse(slot().contains("clearEngineNotice"))
    }

    /**
     * 保命线 1、2：CopyBox／底部卡区／顶条的渲染点**零改动**。
     * 判据取「每个都还是只有一处、且还在原来的相对位置」——搬进来的通知位必须排在它们之前。
     */
    @Test
    fun `B6_CopyBox_底部卡区_顶条各就各位`() {
        val src = screen()
        listOf(
            "CopyBox(", "BaopuArtifactTopBar(", "BaopuNoticeTicker(", "BaopuConfirmCard(",
            "BaopuAskUserCard(", "BaopuPendingMemoryCard(", "BaopuMemoryCard(", "BaopuSkillProposalCard(",
        ).forEach { assertEquals("$it 的渲染点该只有一处", 1, Regex(Regex.escape(it)).findAll(src).count()) }
        assertTrue(
            "卡片区仍旧只在 cardsView.hasAny 时出（本批没动底部卡区的判据）",
            src.contains("if (cardsView.hasAny) {"),
        )
        val noticeIdx = src.indexOf("BaopuNoticeSlot(noticeEntries)")
        val copyIdx = src.indexOf("CopyBox(")
        val listIdx = src.indexOf("BaopuMessageList(")
        assertTrue("通知位在消息流上方", noticeIdx in 0 until listIdx)
        assertTrue("通知位排在顶栏那一条（CopyBox／产物）之前", noticeIdx < copyIdx)
        assertTrue("CopyBox 与产物条的位置没换（还是顶栏那段）", copyIdx < listIdx)
        // 反向钉：通知位不许把这三样收进自己肚子里（保命线 1、2——收进来就是换了人家的位置与形态）
        listOf("CopyBox(", "BaopuNoticeTicker(", "bottomCards(", "BaopuConfirmCard(").forEach {
            assertFalse("这三样不归通知位管：$it", slot().contains(it))
        }
    }

    /** 详情卡复用 49 批那个现成便签（错误档那一型），不另造第二套样式。 */
    @Test
    fun `B7_详情卡复用现成便签_颜色全走既有令牌`() {
        val src = slot()
        assertTrue("详情卡里错误档仍用 BaopuIntrospectionNote(", src.contains("BaopuIntrospectionNote("))
        assertTrue("错误档仍走 isError = true 那一型", src.contains("isError = true"))
        listOf("errorContainer", "secondaryContainer", "surfaceVariant").forEach {
            assertTrue("底色该是既有令牌 $it", src.contains("MaterialTheme.colorScheme.$it"))
        }
        // 49 批那条错误色正文的口径不能又被写回来（P2-11：红字 3.55:1 不达 AA）
        assertFalse(src.contains("color = MaterialTheme.colorScheme.error,"))
        assertTrue("详情卡用 ModalBottomSheet（仿通道表）", src.contains("ModalBottomSheet(onDismissRequest = "))
    }

    /** 「还有 N 条」与详情卡标题中英都有；且没把「回到最新」那两句的命中面扩大（J12 口径）。 */
    @Test
    fun `B8_新增两个键中英都有`() {
        for (rel in listOf("src/main/res/values/strings.xml", "src/main/res/values-en/strings.xml")) {
            val text = repoFile(rel).readText()
            listOf("baopu_notice_more", "baopu_notice_sheet_title").forEach { k ->
                assertTrue("$rel 缺 $k", text.contains("name=\"$k\""))
            }
        }
        listOf("baopu_notice_more", "baopu_notice_sheet_title").forEach { k ->
            assertTrue("屏里/组件里得真用它：$k", screen().contains("R.string.$k") || slot().contains("R.string.$k"))
        }
    }
}
