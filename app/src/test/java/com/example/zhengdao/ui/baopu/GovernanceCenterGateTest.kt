// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百二十二批（2026-11-08）· 治理中心的**源级门禁**（判据①⑤ ＋ "只走 527"）。
//
// 为什么这些判据只能按源码钉：Compose 树在 JVM 直测不了（本仓既有门禁的同一取舍）。
// 但"钉源码"不等于"钉字面量"——三条原则照本仓既有源级门禁（229 顺序门禁 / 536 回退门禁）抄：
//   1. **去注释再扫**：注释里提到 `DecisionTab` 不算违规（写清楚"我们不碰它"是好事）；
//   2. **锚点必须唯一且稳定**：入口顺序用 `R.string.*` 键名与函数调用名钉，
//      不用行号（行号一漂移就假红）；
//   3. **禁词按"业务口"列**：不是禁汉字（"治理"这两个字本屏到处都是），
//      而是禁"批/驳/搁置/回滚这一族动作的代码口"与"决策中心的数据口"。
package com.example.zhengdao.ui.baopu

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GovernanceCenterGateTest {

    private fun source(suffix: String): String {
        val candidates = listOf(
            "src/main/java/com/example/zhengdao/$suffix",
            "../app/src/main/java/com/example/zhengdao/$suffix",
            "app/src/main/java/com/example/zhengdao/$suffix",
        )
        val f = candidates.map(::File).firstOrNull(File::isFile)
            ?: throw AssertionError("找不到 $suffix（工作目录 ${File(".").absolutePath}）")
        return f.readText(Charsets.UTF_8)
    }

    /** 去行注释与块注释后的代码文本（结构性断言用；免得被注释绕过，也免得注释里的词误伤）。 */
    private fun codeOnly(src: String): String =
        src.split("\n").joinToString("\n") { it.substringBefore("//") }
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")

    private val drawer by lazy { codeOnly(source("ui/baopu/BaopuDrawer.kt")) }
    private val screen by lazy { codeOnly(source("ui/baopu/GovernanceCenterScreen.kt")) }
    private val rules by lazy { codeOnly(source("ui/baopu/GovernanceCenterRules.kt")) }

    // ── 判据①：入口在「定时任务」下面（硬编码下标断言）────────────────────────

    @Test
    fun `左侧栏顺序_新会话_定时任务_治理中心_工作区`() {
        // 用户定死两次（2026-11-07/08）：「治理中心放左侧栏，排在定时任务的下面」。
        // 锚点＝字符串资源键名与那一行的调用名（行号会漂，键名不会）。
        val atSessionGroup = drawer.indexOf("R.string.baopu_drawer_group_session")
        val atNewSession = drawer.indexOf("R.string.baopu_new_session")
        val atScheduleRow = drawer.indexOf("ScheduleDrawerRow(status = schedStatus")
        val atGovernance = drawer.indexOf("R.string.baopu_drawer_governance_center")
        val atWorkspaceGroup = drawer.indexOf("R.string.baopu_drawer_group_workspace")
        listOf(atSessionGroup, atNewSession, atScheduleRow, atGovernance, atWorkspaceGroup)
            .forEach { assertTrue("锚点丢了（抽屉结构变了要回来重新钉）：$it", it >= 0) }
        assertTrue(
            "顺序必须是 会话组 → 新会话 → 定时任务 → **治理中心** → 工作区组",
            atSessionGroup < atNewSession &&
                atNewSession < atScheduleRow &&
                atScheduleRow < atGovernance &&
                atGovernance < atWorkspaceGroup,
        )
    }

    @Test
    fun `治理中心只出现一处入口_且是转发不判数`() {
        assertEquals(
            "抽屉里「治理中心」只许一处入口",
            1,
            Regex("R\\.string\\.baopu_drawer_governance_center\\b").findAll(drawer).count(),
        )
        assertEquals(
            "onGovernanceCenter 只许一处调用（就是那一行）",
            1,
            Regex("onClick = onGovernanceCenter").findAll(drawer).count(),
        )
        // 抽屉层不取数、不读账：读数只走 527，且只在屏幕层调
        listOf("GovernanceStateTool", "BoardStore", "IdentityStore", "CredentialStore")
            .forEach { assertFalse("抽屉不许自己取数（$it 该在屏幕层）", drawer.contains(it)) }
    }

    // ── 判据⑤：零重叠（本屏没有"要你拍板"的入口与数据口）────────────────────

    @Test
    fun `本屏没有任何批驳搁置回滚入口_也没有决策中心的数据口`() {
        // 决策中心那一族的数据口与动作口：出现在本屏就是"两屏各干一半"
        val banned = listOf(
            // 数据口
            "DecisionTab", "royalRows", "RoyalQueueRules", "RoyalCardGate", "RoyalDecisionLedger",
            // 下面三个名字 536 回退后已不在树上 —— 留在黑名单里当"回退不许回来"的锚：
            // 谁再把 DecisionZone 那一族接回本屏，这条立刻红。
            "DecisionZoneRules", "DecisionZoneSnapshot", "decisionZoneSnapshot",
            // 动作口（批 / 驳 / 搁置 / 回滚）
            "onZonePendingAction", "ZonePendingAction", "onRoyalPick", "onDecisionRecordRollback",
            "DecisionRollbacks", "onDecisionRecordJudge", "keepPostAdoption", "rollbackPostAdoption",
        )
        banned.forEach { b ->
            assertFalse("本屏不许出现「$b」（判据⑤：办事在决策中心，这里只看）", screen.contains(b))
        }
        // 动作文案也不许出现（连"按钮叫什么"都不该有）
        listOf("批", "驳", "搁置", "回滚").forEach { word ->
            assertFalse("本屏代码里不许出现动作字「$word」——那是决策中心的词", screen.contains(word))
        }
        // 冲突数据源归 537（决策中心御案），本屏一个字节都不碰
        listOf("conflicts", "MutualLedger", "pending_replay").forEach { b ->
            assertFalse("本屏不许读冲突队列（$b）：它归 537", screen.contains(b))
        }
    }

    @Test
    fun `本屏只有一个小黑板清空是写口_别的一律只读`() {
        // 唯一允许的写（565 起共三处，都是**人**按的）：清空小黑板 / 清空今天干过什么 /
        // 确认删一条待办（`TodoStore.confirm`）。后两个在 539/565 各自的判据里另钉。
        assertTrue("清空要走 BoardStore.clear", screen.contains("BoardStore.clear"))
        // 账本/队列/记忆的写口一律不许出现
        listOf(
            "DecisionLedger", "PatrolLedger", "ConsequenceLedger", "MutualLedger",
            "MemoryStore", "MemoryTiers", "BaopuDb", "recordTo", "save(",
        ).forEach { b ->
            assertFalse("本屏不许有账本/记忆写口（$b）", screen.contains(b))
        }
    }

    // ── 判据②的源级那一半：读数只走 527，不另算 ────────────────────────────

    @Test
    fun `读数只走527出口_没有第二条取数路`() {
        assertTrue("必须调 527 的出口", screen.contains("GovernanceStateTool.run"))
        // 出口之外，不允许自己去读那几本账来"算一遍率"
        listOf(
            "ratioViolation", "ratioMisjudge", "ratioIntervention", "ratioSkip",
            "healthCounts", "healthLine", "currentCanaryBand", "CanaryCapability",
            "AskGovernance", "Metrics.decimals", "LogStep",
        ).forEach { b ->
            assertFalse("不许另算（$b）：读数只有 527 一条路", screen.contains(b))
        }
        // 屏内也不许出现百分比格式化（率的三态由 527 给，本层再加一把尺就是两套口径）
        assertFalse("不许自己拼 %", screen.contains("%"))
        assertFalse("不许自己四舍五入", screen.contains("roundToInt"))
    }

    @Test
    fun `判据层零算术_只抄不算`() {
        // 判据层（GovernanceCenterRules）里不许有换算：率、百分比、四舍五入都归 527
        listOf("* 100", "100.0", "toDouble", "roundToInt", "decimals", "/ d", "toFloat").forEach { b ->
            assertFalse("判据层不许算（$b）", rules.contains(b))
        }
        // 也不许认识账本：它只认识出口那把 JSON
        listOf("DecisionLedger", "MutualLedger", "PatrolLedger", "ConsequenceLedger", "AskGovernance")
            .forEach { b -> assertFalse("判据层不许摸账本（$b）", rules.contains(b)) }
    }

    // ── 挂载与返回键 ─────────────────────────────────────────────────────────

    @Test
    fun `屏幕挂在抱朴页上_有返回键且不新增导航栈`() {
        val host = codeOnly(source("ui/baopu/BaopuScreen.kt"))
        assertTrue("抽屉入口要开屏", host.contains("showGovernanceCenter = true"))
        assertTrue("要有渲染点", host.contains("GovernanceCenterScreen("))
        assertTrue("返回键要能收它", host.contains("BackHandler(enabled = showGovernanceCenter)"))
        // 不新增导航栈：不许出现 startActivity/Intent（那是另一套返回栈）
        assertFalse("本屏不另开 Activity（不走导航栈）", screen.contains("startActivity"))
        assertFalse("本屏不另开 Activity（不走导航栈）", screen.contains("Intent("))
    }

    @Test
    fun `读盘不在组合期_走IO`() {
        // 264 的口径：面板/抽屉首帧不许占主线程读盘
        assertTrue("读盘要在 withContext(IO) 里", screen.contains("withContext(Dispatchers.IO)"))
        assertTrue("读盘要由 LaunchedEffect 拉起", screen.contains("LaunchedEffect(tick)"))
    }
}
