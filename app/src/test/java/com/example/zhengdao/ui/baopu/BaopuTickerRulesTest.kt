// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单 2026-10-22《第一百三十八批 · 跑马灯两态 ＋ 状态单一出口》§测试。
// 设计口径：`..\_zd-private\docs\抱朴设置重构方案-2026-10-22.md` §5「两态判定图」＋ 派工单
// 「设计约束」11:50 补（记录条档 / 40dp / 13sp / 待你确认琥珀 / 两池零交集）。
//
// 为什么全在纯函数＋源码层：本模块没有 Compose UI 测试基建（无 Robolectric），布局与
// 计时跑不起来。所以**能算的当场算**（态判定、节拍、暂停、排序、池交集），算不了的
// （"展开 3 秒后还停在那条"这类时序）落到纯函数的**多次调用**上，并在注释里写清
// 「变异体长什么样会红」。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.R
import com.example.zhengdao.baopu.agent.RunState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse

import org.junit.Assert.assertTrue
import org.junit.Test

class BaopuTickerRulesTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    private fun read(rel: String): String = repoFile(rel).readText(Charsets.UTF_8).replace("\r\n", "\n")

    private fun code(rel: String): String = read(rel)
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private fun item(label: String, kind: NoticeKind = NoticeKind.INFO) =
        NoticeItem(label = label, body = "$label 的详情", kind = kind)

    // ── 判据 1／2：态判定（两态互斥且穷尽）────────────────────────────────

    /**
     * 判据 1：空闲三态 ⇒ ② 轮播。
     *
     * 红＝有人把 DONE / ABORTED 也当成"还在跑"（回答结束后该滚的不滚，用户以为卡住了）。
     */
    @Test
    fun `J1_空闲三态都是轮播态`() {
        listOf(RunState.IDLE, RunState.DONE, RunState.ABORTED).forEach { s ->
            assertEquals("$s 必须是②空闲轮播", TickerMode.IDLE, tickerModeOf(s))
        }
    }

    /**
     * 判据 2：运行四态 ⇒ ① 锁定。**AWAIT_CONFIRM 属运行中**（最需要被看见的一条）。
     *
     * 红＝有人把 AWAIT_CONFIRM 归到空闲（「待你确认」会在 3 秒后被滚走）。
     */
    @Test
    fun `J2_运行四态都是锁定态`() {
        listOf(
            RunState.THINKING,
            RunState.ACTING,
            RunState.COMPRESSING,
            RunState.AWAIT_CONFIRM,
        ).forEach { s ->
            assertEquals("$s 必须是①锁定", TickerMode.LOCKED, tickerModeOf(s))
        }
    }

    /** 两态穷尽：所有 RunState 都落进且只落进一态（集合无交集、并集＝全体）。 */
    @Test
    fun `J3_两态互斥且穷尽`() {
        assertTrue("两集合不许有交集", (TICKER_LOCKED_STATES intersect TICKER_IDLE_STATES).isEmpty())
        assertEquals(
            "两集合之并必须覆盖所有 RunState（新加状态不许漏判）",
            RunState.entries.toSet(),
            TICKER_LOCKED_STATES + TICKER_IDLE_STATES,
        )
    }

    // ── 判据 3：锁定四词 ───────────────────────────────────────────────────

    /**
     * 判据 3：锁定文案逐条（深度思考中 / 调用中 / 压缩中 / 待你确认）。
     *
     * 判据取**资源 id**而非中文字面量：文案改措辞不该让测试红，改"哪个态说哪句话"才该红。
     */
    @Test
    fun `J4_锁定四词逐条对齐`() {
        assertEquals(R.string.baopu_state_thinking, tickerLockedLabel(RunState.THINKING))
        assertEquals(R.string.baopu_state_acting, tickerLockedLabel(RunState.ACTING))
        assertEquals(R.string.baopu_state_compressing, tickerLockedLabel(RunState.COMPRESSING))
        assertEquals(R.string.baopu_state_await_confirm, tickerLockedLabel(RunState.AWAIT_CONFIRM))
        val src = read("src/main/res/values/strings.xml")
        listOf(
            "baopu_state_thinking\">深度思考中<",
            "baopu_state_acting\">调用中<",
            "baopu_state_compressing\">压缩中<",
            "baopu_state_await_confirm\">待你确认<",
        ).forEach { needle ->
            assertTrue("strings.xml 缺这条锁定文案（或值被改回旧口径）：$needle", src.contains(needle))
        }
    }

    // ── 判据 4：空态恒定占位（真修点）────────────────────────────────────

    /**
     * 判据 4：②且无条目 ⇒ 渲染**空白占位**，不许 return、不许零高。
     *
     * 旧实现的真修点：`if (items.isEmpty()) return` ⇒ 事件到来时组件突然出现，顶栏被撑跳。
     * 现在：孔位高度只由 fontScale 决定，空态与运行态**必然同高**。
     */
    @Test
    fun `J5_空态不塌_孔位与态无关`() {
        assertEquals("空列表没有当前条，索引为 -1", -1, tickerEntryIndex(0, 0, reduceMotion = false))
        assertEquals("孔位 1.0× 恒 40dp", 40f, tickerSlotDp(1.0f), 0.01f)
        // 三态同高的代码形态：高度函数不吃 RunState / items（吃了就能按态变高）
        val rules = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuTickerRules.kt")
        val body = rules.substringAfter("fun tickerSlotDp(").substringBefore("}")
        assertFalse("tickerSlotDp 不许吃 RunState", body.contains("RunState"))
        assertFalse("tickerSlotDp 不许吃 items", body.contains("items"))
        // 组件里不许再出现「空就直接 return 整个组件」（那就是顶栏跳的根因）
        val tk = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuNoticeTicker.kt")
        assertFalse("空态不许 return 掉整个组件", tk.contains("if (items.isEmpty()) return"))
    }

    // ── 判据 5：轮播节奏 3 秒／条 ─────────────────────────────────────────

    /**
     * 判据 5：每条停留 3000ms 后切换；环形前进。
     *
     * 红＝有人把节拍改回「每帧滚 0.3px」那种连续滚动（字一直在爬，读不完），或改成 1 秒。
     */
    @Test
    fun `J6_三条轮播_每条3秒_环形前进`() {
        assertEquals("每条停 3 秒（§5 定稿）", 3_000L, TICKER_CYCLE_MS)
        assertEquals(1, nextTickerIndex(current = 0, size = 3, expanded = false))
        assertEquals(2, nextTickerIndex(current = 1, size = 3, expanded = false))
        assertEquals("末条回到首条（环形）", 0, nextTickerIndex(current = 2, size = 3, expanded = false))
        assertEquals("只有一条时不切（恒 0）", 0, nextTickerIndex(current = 0, size = 1, expanded = false))
        assertEquals("空池不越界", 0, nextTickerIndex(current = 5, size = 0, expanded = false))
    }

    // ── 判据 6：展开态**持续**暂停（无障碍硬判据）────────────────────────

    /**
     * 判据 6：`expanded == true` ⇒ 轮播停止，且**跨多个周期仍停在那条**。
     *
     * 这条是本批的无障碍硬判据：读屏用户伸手点某条时，轮播若继续换，点到的就变成别的条目。
     * 「点击只停一下又继续」＝**红**。这里用"连续推 10 个周期"模拟时间流逝：
     * 每推一次都必须是原地不动。
     */
    @Test
    fun `J7_展开态持续暂停_跨十个周期不换条`() {
        var cursor = 1
        repeat(10) { round ->
            cursor = nextTickerIndex(current = cursor, size = 4, expanded = true)
            assertEquals("第 ${round + 1} 个周期仍在第 1 条（展开态持续暂停）", 1, cursor)
        }
        // 收起之后立刻恢复轮播（不是"永久停"）
        assertEquals("收起后才前进一条", 2, nextTickerIndex(cursor, size = 4, expanded = false))
    }

    /** 判据 6 的前置：展开态／运行态／减少动画都不许起轮播协程。 */
    @Test
    fun `J8_四种情况都不起轮播`() {
        assertTrue("正常空闲 ⇒ 轮播", tickerShouldCycle(TickerMode.IDLE, 3, expanded = false, reduceMotion = false))
        assertFalse("展开态 ⇒ 停", tickerShouldCycle(TickerMode.IDLE, 3, expanded = true, reduceMotion = false))
        assertFalse("锁定态 ⇒ 停", tickerShouldCycle(TickerMode.LOCKED, 3, expanded = false, reduceMotion = false))
        assertFalse("减少动画 ⇒ 停", tickerShouldCycle(TickerMode.IDLE, 3, expanded = false, reduceMotion = true))
        assertFalse("单条 ⇒ 没有轮播", tickerShouldCycle(TickerMode.IDLE, 1, expanded = false, reduceMotion = false))
    }

    // ── 判据 7：排序 ──────────────────────────────────────────────────────

    /**
     * 判据 7：待用户拍板 ＞ 待知晓 ＞ informational；**同档保持原顺序**（稳定排序）。
     *
     * 红＝有人改成"按字符串排序"或不分档（最要紧的一条可能排到最后，3 秒轮播根本轮不到它）。
     */
    @Test
    fun `J9_三类混入按紧急度排序_同档不失序`() {
        val model = item("型号", NoticeKind.INFO)
        val learned = item("这次学到", NoticeKind.NEEDS_KNOWING)
        val compressed = item("已压缩", NoticeKind.NEEDS_KNOWING)
        val scratch = item("待办", NoticeKind.NEEDS_YOU)
        val sorted = sortedForTicker(listOf(model, learned, compressed, scratch))
        assertEquals(
            listOf("待办", "这次学到", "已压缩", "型号"),
            sorted.map { it.label },
        )
        // 同档稳定：两个 NEEDS_KNOWING 保持传入顺序
        assertEquals(
            listOf("这次学到", "已压缩"),
            sorted.filter { it.kind == NoticeKind.NEEDS_KNOWING }.map { it.label },
        )
    }

    // ── 判据 8／9：两池不重叠 ＋ 没有「生成回答中」────────────────────────

    /**
     * 判据 9：① 的文案池与 ② 的条目池**零交集**（杜绝同一时刻两处说两种话）。
     *
     * 取资源 id 而不是中文字面量：这样断言不受译文影响，又能抓住「把某个 state 文案
     * 挪去当空闲条目」这类真错。
     */
    @Test
    fun `J10_两池文案零交集`() {
        val overlap = TICKER_LOCKED_LABELS intersect TICKER_IDLE_LABELS
        assertTrue("运行态与空闲态的文案资源不许重合，重合了：$overlap", overlap.isEmpty())
        assertEquals("① 恰好四条锁定文案", 4, TICKER_LOCKED_LABELS.size)
    }

    /**
     * 判据 8：文案池里**永不出现**「生成回答中」（引擎没有这个状态）。
     *
     * 红＝有人把「生成回答中」加回锁定额外第五项（用户所列四项之外的自造态）。
     */
    @Test
    fun `J11_没有生成回答中这个态`() {
        listOf("src/main/res/values/strings.xml", "src/main/res/values-en/strings.xml").forEach { rel ->
            assertFalse("$rel 不许出现「生成回答中」", read(rel).contains("生成回答中"))
        }
        val tk = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuNoticeTicker.kt")
        val rules = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuTickerRules.kt")
        assertFalse("跑马灯源码不许出现「生成回答中」", tk.contains("生成回答中") || rules.contains("生成回答中"))
    }

    // ── 派工单：会话头的中断提示 ＋ 一键续跑不许丢（49 批功能）────────────

    /**
     * SessionBar 的**状态行**撤出，但**中断提示 ＋ 一键续跑**必须还在。
     *
     * 红＝有人把整条 BaopuSessionBar 一起删掉（49 批的续跑入口就没了，这是功能回退）。
     */
    @Test
    fun `J12_会话头只撤状态行_中断与续跑仍在`() {
        val bar = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuSessionBar.kt")
        assertTrue("中断提示文案必须在", bar.contains("R.string.baopu_interrupted_at"))
        assertTrue("续跑按钮必须在", bar.contains("R.string.baopu_resume"))
        assertTrue("续跑回调必须接上", bar.contains("onClick = onResume"))
    }

    // ── 术语（派工单 §范围末条）────────────────────────────────────────────

    /**
     * 术语：待命中 → 空闲、缓存命中 → 已用缓存。
     *
     * 红＝有人把旧词写回来（用户 09:49 点名的两个词）。
     */
    @Test
    fun `J13_术语两处已换新词`() {
        val zh = read("src/main/res/values/strings.xml")
        assertTrue("空闲", zh.contains("baopu_state_idle\">空闲<"))
        assertTrue("已用缓存", zh.contains("baopu_usage_cache_hit\">已用缓存<"))
        assertFalse("旧词「待命中」不许回来", zh.contains("待命中\">"))
        assertFalse("旧词「缓存命中」不许回来", zh.contains(">缓存命中<"))
    }

    // ── 房型（记录条档 ＋ 13sp ＋ 待你确认琥珀）────────────────────────────

    /**
     * 设计约束（派工单 §设计约束 → 第四百三十四批改为**朱系胶囊档**）。
     *
     * 第一百三十八批原口径＝记录条档（无底、无圆角、下边一条发丝线）。第四百三十四批派工单
     * 逐字给了新形态：「朱系胶囊 高44 / r10 / 底 #F7E5E0 / 描边 #A8402B 1.5dp」⇒
     * 这条判据的**形状方向整个反过来**（胶囊必须有底、必须有圆角、不许再自己画发丝线——
     * 分隔线由顶栏那一条 [androidx.compose.material3.HorizontalDivider] 承担，全栏只画一次）。
     * 颜色仍不许新增：#F7E5E0 走 `errorContainer` 槽、#A8402B 走 `error`、白字走 `onError`。
     *
     * 红＝有人把胶囊改回裸记录条（底/圆角没了）、写死了色值或半径、把字号改成 10/15sp，
     * 或在顶栏里再画一条只有跑马灯用的发丝线。
     */
    @Test
    fun `J14_房型_朱系胶囊档_13sp_琥珀只在待你确认`() {
        val tk = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuNoticeTicker.kt")
        assertEquals("字号 13sp（§3 脚注档）", 13, TICKER_TEXT_SP)
        assertEquals("「待你确认」描边 1dp", 1, TICKER_CONFIRM_STROKE_DP)
        // ── 434 逐字尺寸：高 44 / 描边 1.5dp / 半径 rPill(10) ──
        assertEquals("胶囊容器高 44dp（434 与模型名区同高）", 44, BAO_TOPBAR_PILL_HEIGHT_DP)
        assertEquals("朱砂描边 1.5dp（434 逐字）", 1.5f, BAO_TOPBAR_PILL_STROKE_DP, 0.0001f)
        // rPill=10dp / rBadge=6dp 由 `ui/theme/Radius.kt` 自己钉着（本仓口径：数值在令牌文件里，
        // 用处只认令牌名，所以这里断的是"用处不许写死数字"，见下面两条 assertFalse）。
        assertTrue("容器底走 errorContainer 槽（#F7E5E0 不写死）", tk.contains("colorScheme.errorContainer"))
        assertTrue("描边走 error 槽（#A8402B 不写死）", tk.contains("MaterialTheme.colorScheme.error,"))
        assertTrue("圆角只许走 rPill 令牌", tk.contains("RoundedCornerShape(rPill)"))
        assertTrue("类目标那颗只许走 rBadge 令牌", tk.contains("RoundedCornerShape(rBadge)"))
        assertFalse("半径不许写死数字（圆角一律走令牌）",
            Regex("RoundedCornerShape\\(\\s*\\d+(\\.\\d+)?\\.dp\\s*\\)").containsMatchIn(tk))
        assertFalse("色值不许写死（一律走 colorScheme 槽）",
            Regex("Color\\s*\\(\\s*0[xX][0-9a-fA-F]+\\s*\\)").containsMatchIn(tk))
        // 记录条档的三样遗留：本批起**不许**再出现在跑马灯里。
        assertFalse("跑马灯不再自己画发丝线（顶栏那条 HorizontalDivider 承担）", tk.contains("drawLine("))
        assertTrue("顶栏仍要有一条分隔线（只是不在跑马灯文件里画）",
            code("src/main/java/com/example/zhengdao/ui/baopu/BaopuScreen.kt").contains("HorizontalDivider("))
        assertFalse("记录条档不许有圆角这件事已作废：现在必须是胶囊（不许 RectangleShape）",
            tk.contains("RectangleShape"))
        assertFalse("不许再用 Surface 起容器（胶囊是 Modifier 三层修饰）", tk.contains("Surface("))
        assertTrue("琥珀底只在 confirm 分支", tk.contains("if (confirm)"))
        assertTrue("待你确认用 surfaceVariant 加深一档", tk.contains("surfaceVariant"))
        assertTrue("描边取主题琥珀槽 tertiary", tk.contains("colorScheme.tertiary"))
        assertTrue("字重 500 只在待你确认", tk.contains("FontWeight.Medium"))
        // 两态文字颜色不同：锁定态用墨；443 详情改跑马灯同色底 ⇒ 文字配 onErrorContainer。
        assertTrue(
            "锁定态文字取 onSurface（用词边界防止误中 onSurfaceVariant）",
            Regex("colorScheme\\.onSurface\\b").containsMatchIn(tk),
        )
        assertTrue("443 详情文字取 onErrorContainer（配 errorContainer 底）",
            tk.contains("colorScheme.onErrorContainer"))
    }

    /**
     * 第四百三十四批判据⑤：「型号」退出轮播池。
     *
     * 顶栏第二件变成 `[徽标] 模型名` 之后，跑马灯再滚一遍型号＝同一时刻两处说同一件事
     * （正是第一百三十八批判据 2 要防的）。红＝有人把那条 NoticeItem 加回空闲池。
     * 资源键 `baopu_ticker_model_label` 本批**不删**（删资源是另一张单），所以这里查的是**池子**，
     * 不是键的存在性。
     */
    @Test
    fun `J15_型号已退出轮播池_两处不说同一件事`() {
        assertFalse("型号不许再进空闲池", TICKER_IDLE_LABELS.contains(R.string.baopu_ticker_model_label))
        assertFalse("型号也不许进锁定池", TICKER_LOCKED_LABELS.contains(R.string.baopu_ticker_model_label))
        assertFalse(
            "跑马灯文件里不许再画型号那颗",
            code("src/main/java/com/example/zhengdao/ui/baopu/BaopuNoticeTicker.kt")
                .contains("baopu_ticker_model_label"),
        )
        // 两池零交集这条老判据（J12）在删了一项之后仍然成立——这里重断一次，防"删错行"。
        assertEquals("两池交集必须为空", emptySet<Int>(), TICKER_LOCKED_LABELS intersect TICKER_IDLE_LABELS)
    }

    /**
     * 判据③逐字：「跑马灯：没学到时**不显示**该段；有学时显示，且不挤占身份区」。
     *
     * 为什么现在补：写负例清单时逐条回看判据，发现这条**原先一条测试都没有**——
     * `marqueePillVisible` 全仓只有实现、没有一处断言（J5 钉的是「空态不塌、孔位照占」＝高度，
     * 不是「容器画不画」）。红＝有人把算式改成恒 `true`（没学到也飘一只空胶囊在顶栏），
     * 或改成 `mode == IDLE && hasEntries`（运行态那颗状态字被吃掉＝138 批的锁定孔位作废）。
     */
    @Test
    fun `J16_没学到整段不显示_运行态锁定字照旧画`() {
        assertFalse("空闲＋条目池空 ⇒ 容器不画（判据③那一半）",
            marqueePillVisible(TickerMode.IDLE, hasEntries = false))
        assertTrue("空闲＋有条目 ⇒ 画", marqueePillVisible(TickerMode.IDLE, hasEntries = true))
        assertTrue("运行态：一条都没学到也画（那是状态字那颗，不是「学到」那颗）",
            marqueePillVisible(TickerMode.LOCKED, hasEntries = false))
        // 界面不许自己另算一套可见性：只许调这条纯函数，入参就是「条目池非空」。
        val ticker = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuNoticeTicker.kt")
        assertTrue("那颗可见性走纯函数", ticker.contains("marqueePillVisible(mode, ordered.isNotEmpty())"))
        assertEquals("全界面只此一处调用（别处再算一份就是两份口径）",
            1, Regex("marqueePillVisible\\(").findAll(ticker).count())
    }
}
