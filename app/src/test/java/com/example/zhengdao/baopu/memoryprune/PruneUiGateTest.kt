// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.memoryprune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第七百五十八批 · 修剪界面的**源码门禁**（757 判据③④）：
 *   · 字段映射表：四写口各对应一键——写口块互不串线，交换任意两个 ⇒ 必红（变异负例）；
 *   · 第四写口：检索页 Switch 吃页面 state，includeRetired 不落盘、不被设置页串线；
 *   · 文案：`退役 ≠ 删除，仍可恢复` 整串全等（≠＝U+2260、两侧半角空格、后跟全角逗号），
 *     两处必显；752 既有四键原文一字不改；
 *   · 接收器走前置门（pruneOnScreenOffGated），频率门复用同一把天序号尺。
 * 752 的 PruneSourceGateTest **原样复跑**（十条源码断言不动）；本文件只加 758 的新门禁。
 */
class PruneUiGateTest {

    private companion object {
        val SECTION = "app/src/main/java/com/example/zhengdao/ui/baopu/BaopuPruneSettingsSection.kt"
        val SEARCH = "app/src/main/java/com/example/zhengdao/ui/baopu/BaopuPhoneSearchCard.kt"
        val APP = "app/src/main/java/com/example/zhengdao/ZhengdaoApp.kt"
        val SETTINGS = "app/src/main/java/com/example/zhengdao/baopu/memoryprune/PruneSettings.kt"
        val GATES = "app/src/main/java/com/example/zhengdao/baopu/memoryprune/PruneGates.kt"
        val STRINGS_CN = "app/src/main/res/values/strings_baopu_prune.xml"
        val STRINGS_EN = "app/src/main/res/values-en/strings_baopu_prune.xml"

        /** 752 原文（判据③：一字不改）。 */
        const val FOOTNOTE_CN = "退役＝只标状态不删文件：默认召回不再带它，显式检索仍可找到；撤销后条目与索引回到修剪前。"
        const val FOOTNOTE_EN =
            "Retire only marks status, never deletes files: default recall no longer includes it, " +
                "explicit retrieval still finds it; undo restores entries and the index to the pre-prune state."
        const val NOTICE_CN = "退役 ≠ 删除，仍可恢复"
        const val NOTICE_EN = "Retired ≠ deleted — it can still be restored"
    }

    /** 从测试工作目录回仓库根找文件（PruneSourceGateTest 同房型）。 */
    private fun repoFile(rel: String): File {
        for (f in listOf(File(rel), File("app/$rel"), File("../$rel"))) {
            if (f.isFile || f.isDirectory) return f
        }
        error("找不到源文件：$rel（模块搬家了？门禁跟着改）")
    }

    /** 读源码并剥掉注释（只看代码；PruneSourceGateTest 同房型）。 */
    private fun code(rel: String): String = repoFile(rel).readText(Charsets.UTF_8)
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \t]*//.*$"), "")

    /** 两个函数名之间的源码块（块序钉死在本文件里，函数挪位要改门禁——同 752 口径）。 */
    private fun block(src: String, from: String, to: String): String {
        val start = src.indexOf("fun $from(")
        val end = src.indexOf("fun $to(")
        assertTrue("找不到块起点 fun $from(", start >= 0)
        assertTrue("找不到块终点 fun $to(", end > start)
        return src.substring(start, end)
    }

    private fun resourceMap(rel: String): Map<String, String> = Regex("<string name=\"([^\"]+)\">(.*?)</string>")
        .findAll(repoFile(rel).readText(Charsets.UTF_8))
        .associate { it.groupValues[1] to it.groupValues[2] }

    // ── 判据③：文案整串全等 ＋ 两处必显 ＋ 752 原文不动 ────────────────────────

    @Test
    fun 判据3_通知句整串全等_符号细节逐位钉死() {
        val cn = resourceMap(STRINGS_CN)["baopu_prune_retired_notice"]
        assertEquals("整串全等（含 ≠、两侧空格、全角逗号）", NOTICE_CN, cn)
        cn!!.let { v ->
            assertEquals("两侧是半角空格（U+0020）", ' ', v[2])
            assertEquals("≠ 是 U+2260", '≠', v[3])
            assertEquals(' ', v[4])
            assertEquals("后跟全角逗号（第 7 位：退役≠删除之后）", '，', v[7])
            assertTrue("含 U+2260", v.contains("\u2260"))
            assertFalse("不许用半角逗号", v.contains(","))
        }
        assertEquals("英文整串全等", NOTICE_EN, resourceMap(STRINGS_EN)["baopu_prune_retired_notice"])
        // 变异负例：漏空格/换符号/半角逗号——任何一种都不得与正串全等（判据能被咬住）。
        listOf("退役≠删除，仍可恢复", "退役 = 删除，仍可恢复", "退役 ≠ 删除,仍可恢复", "退役就是删除，仍可恢复")
            .forEach { mutated -> assertNotEquals(NOTICE_CN, mutated) }
    }

    @Test
    fun 判据3_通知句两处必显_设置页分区与列表页各一处() {
        val section = code(SECTION)
        assertTrue(
            "① 设置页分区渲染",
            block(section, "BaopuPruneSettingsSection", "PruneEnabledRow").contains("baopu_prune_retired_notice"),
        )
        assertTrue(
            "② 已退役列表页顶部渲染",
            block(section, "BaopuRetiredListDialog", "RetiredRowLine").contains("baopu_prune_retired_notice"),
        )
    }

    @Test
    fun 判据3_752既有四键原文一字不改() {
        val cn = resourceMap(STRINGS_CN)
        val en = resourceMap(STRINGS_EN)
        assertEquals(FOOTNOTE_CN, cn["baopu_prune_card_footnote"])
        assertEquals(FOOTNOTE_EN, en["baopu_prune_card_footnote"])
        assertEquals("记忆修剪", cn["baopu_prune_card_title"])
        assertEquals("撤销本次修剪", cn["baopu_prune_undo"])
        assertEquals("知道了", cn["baopu_prune_dismiss"])
        assertEquals("中英键一一对应（lint MissingTranslation 的可跑版本）", cn.keys, en.keys)
    }

    // ── 判据④：字段映射表——四写口各对应一键，交换即红 ─────────────────────────

    @Test
    fun 判据4_PruneSettings写口各写各键_互不串线() {
        val src = code(SETTINGS)
        assertEquals("prune_enabled 字面量只在键常量声明处（注释已剥）", 1, Regex.escape("prune_enabled").let { Regex(it).findAll(src).count() })
        val enabledBlock = block(src, "setEnabled", "setSchedule")
        val scheduleBlock = block(src, "setSchedule", "setRetireDays")
        val daysBlock = src.substring(src.indexOf("fun setRetireDays("))
        assertTrue("写口1 写 KEY_ENABLED", enabledBlock.contains("KEY_ENABLED") && enabledBlock.contains("putBoolean"))
        assertFalse("写口1 不碰周期键", enabledBlock.contains("KEY_SCHEDULE"))
        assertFalse("写口1 不碰阈值键", enabledBlock.contains("KEY_RETIRE_DAYS"))
        assertTrue("写口2 写 KEY_SCHEDULE", scheduleBlock.contains("KEY_SCHEDULE") && scheduleBlock.contains("putString"))
        assertFalse("写口2 不碰总开关键", scheduleBlock.contains("KEY_ENABLED"))
        assertFalse("写口2 不碰阈值键", scheduleBlock.contains("KEY_RETIRE_DAYS"))
        assertTrue("写口3 写 KEY_RETIRE_DAYS", daysBlock.contains("KEY_RETIRE_DAYS") && daysBlock.contains("putInt"))
        assertFalse("写口3 不碰总开关键", daysBlock.contains("KEY_ENABLED"))
        assertFalse("写口3 不碰周期键", daysBlock.contains("KEY_SCHEDULE"))
        // 变异负例：把写口1接到阈值键 ⇒ 红的逻辑必须真红（交换即红）。
        val swapped = enabledBlock + "\n        PruneSettings.setRetireDays(ctx, 90)\n"
        assertTrue(
            "负例：交换后的块不再满足「只写自己的键」（写口1 块里长出了写口3 的调用）",
            swapped.contains("KEY_ENABLED") && swapped.contains("setRetireDays("),
        )
    }

    @Test
    fun 判据4_设置页三控件各接各的写口_行块互不串线() {
        val section = code(SECTION)
        val enabledRow = block(section, "PruneEnabledRow", "PruneScheduleRow")
        val scheduleRow = block(section, "PruneScheduleRow", "PruneRetireDaysRow")
        val daysRow = block(section, "PruneRetireDaysRow", "BaopuRetiredListDialog")
        assertTrue("总开关行接 setEnabled", enabledRow.contains("PruneSettings.setEnabled("))
        assertTrue("周期行接 setSchedule", scheduleRow.contains("PruneSettings.setSchedule("))
        assertTrue("阈值行接 setRetireDays", daysRow.contains("PruneSettings.setRetireDays("))
        assertFalse("总开关行不接周期写口", enabledRow.contains("setSchedule("))
        assertFalse("总开关行不接阈值写口", enabledRow.contains("setRetireDays("))
        assertFalse("周期行不接总开关写口", scheduleRow.contains("setEnabled("))
        assertFalse("周期行不接阈值写口", scheduleRow.contains("setRetireDays("))
        assertFalse("阈值行不接总开关写口", daysRow.contains("setEnabled("))
        assertFalse("阈值行不接周期写口", daysRow.contains("setSchedule("))
        assertEquals(
            "总开关写口全文件恰一处（出现第二处＝旁路）",
            1,
            Regex("PruneSettings\\.setEnabled\\(").findAll(section).count(),
        )
        assertEquals("阈值写口全文件恰一处", 1, Regex("PruneSettings\\.setRetireDays\\(").findAll(section).count())
        // 负例：把周期写口接进阈值行 ⇒ 行块判据必红。
        assertTrue(daysRow.contains("setRetireDays(") && !daysRow.contains("setSchedule("))
        val swappedDaysRow = daysRow.replace("PruneSettings.setRetireDays(ctx, days)", "PruneSettings.setSchedule(ctx, \"weekly\")")
        assertFalse("交换后阈值行判据红", swappedDaysRow.contains("setRetireDays(") && !swappedDaysRow.contains("setSchedule("))
    }

    @Test
    fun 判据4_周期与阈值档位来自闭集_UI不造第二份值域() {
        val scheduleRow = block(code(SECTION), "PruneScheduleRow", "PruneRetireDaysRow")
        val daysRow = block(code(SECTION), "PruneRetireDaysRow", "BaopuRetiredListDialog")
        assertTrue("周期三档闭集（关/每天/每周）", scheduleRow.contains("SCHEDULE_OFF") && scheduleRow.contains("SCHEDULE_DAILY") && scheduleRow.contains("SCHEDULE_WEEKLY"))
        assertTrue("阈值四档只从 RETIRE_DAYS_CHOICES 出", daysRow.contains("PruneSettings.RETIRE_DAYS_CHOICES"))
    }

    @Test
    fun 判据4_第四写口_检索页Switch吃页面state_不落盘不串线() {
        val search = code(SEARCH)
        assertTrue(
            "开关是页面本地 state（remember，不落盘；757 §三 行4）",
            search.contains("var includeRetired by remember { mutableStateOf(false) }"),
        )
        assertEquals(
            "recall 恰一处消费页面 state（includeRetired = includeRetired）",
            1,
            Regex("includeRetired = includeRetired").findAll(search).count(),
        )
        assertEquals("不许硬编码 true", 0, Regex("includeRetired = true").findAll(search).count())
        assertEquals("不许硬编码 false", 0, Regex("includeRetired = false").findAll(search).count())
        assertFalse("SharedPreferences 不得进这张卡（页面 state 不落盘）", search.contains("getSharedPreferences"))
        val section = code(SECTION)
        assertEquals("设置页分区零 includeRetired（总开关不许串到第四写口）", 0, Regex("includeRetired").findAll(section).count())
        assertEquals("设置读写对象零 includeRetired", 0, Regex("includeRetired").findAll(code(SETTINGS)).count())
    }

    @Test
    fun 判据4_接收器走前置门_频率门复用同一把天序号尺() {
        val app = code(APP)
        assertTrue("接收器调用带门的装配壳", app.contains("pruneOnScreenOffGated(app)"))
        assertFalse("752 直通口从接收器摘除", app.contains("pruneOnScreenOff(app)"))
        val gates = code(GATES)
        assertTrue("前置门在本包装配壳内（总开关关 ⇒ 直接拦）", gates.contains("总开关已关"))
        assertTrue("周期＝off 同拦", gates.contains("周期＝off"))
        assertTrue("频率门复用 PatrolScheduler.dayBucket（同一把天序号尺，不另立）", gates.contains("PatrolScheduler.dayBucket("))
        assertTrue("last 读 PruneDayGate（不另立第二把状态）", gates.contains("PruneDayGate.lastRunDayKey("))
        assertTrue("阈值经 settings.retireAfterDays 喂 runPruneRound", gates.contains("settings.retireAfterDays"))
        assertTrue("豁免清单在装配层消费", gates.contains("PruneKeepList.ids("))
        assertTrue("频率门判定本体是纯函数（JVM 直测口）", gates.contains("fun pruneScheduleDue("))
    }
}
