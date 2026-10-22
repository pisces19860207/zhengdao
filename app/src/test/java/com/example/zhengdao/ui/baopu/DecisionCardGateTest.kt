// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百零四批：决策卡家族/单卡槽/决策中心/工具组设置的**源码门禁**。
// 靶子全是派工单 §2 的负例（出现即红）：
//   N1 出现 A 内核入口   —— 决策 UI 不许碰内核（Guard/权限/核心循环）
//   N2 99/100 合并      —— 建议与效果是两张独立的卡，类型签区分
//   N3 终端组           —— 抱朴无终端/rootfs，UI 零出现
//   N4 逐工具弹卡       —— 授权卡一张批整组（按 ToolGroup，不按工具名）
//   N5 重启才刷新       —— 授权/开关同屏刷新（103 已修，本批接着钉）
// 另加两条正门禁：按钮 ≥48dp、组行 ≥64dp（真机 uiautomator 另验，见验收 #1/#25）。
package com.example.zhengdao.ui.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DecisionCardGateTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    /** 剥注释后的源码（房型照 BaopuTouchTargetGateTest）。 */
    private fun code(rel: String): String = repoFile(rel).readText()
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private val decisionCard = code("src/main/java/com/example/zhengdao/ui/baopu/DecisionCard.kt")
    private val center = code("src/main/java/com/example/zhengdao/ui/baopu/DecisionCenterScreen.kt")
    private val gate = code("src/main/java/com/example/zhengdao/baopu/agent/ToolGroupGate.kt")
    private val settings = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuSettingsScreen.kt")
    private val zh = code("src/main/res/values/strings.xml")
    private val en = code("src/main/res/values-en/strings.xml")

    // ── N1：决策 UI 不许出现 A 内核入口 ──────────────────────────────────────

    @Test
    fun `N1_决策UI不碰A内核`() {
        val banned = listOf(
            "GuardPipeline", "BaopuKernel", "RunRules", "ToolPermission",
            "com.example.zhengdao.baopu.kernel",
        )
        listOf("DecisionCard.kt" to decisionCard, "DecisionCenterScreen.kt" to center).forEach { (name, text) ->
            banned.forEach { word ->
                assertFalse("$name 里出现了 $word（决策 UI 不许碰 A 内核）", text.contains(word))
            }
        }
    }

    // ── N2：99 与 100 不合并 ────────────────────────────────────────────────

    @Test
    fun `N2_建议与效果是两张独立的卡_类型签区分`() {
        // ① 变体枚举里四个都在（合并成一个就连类型签都没处挂）
        val rules = repoFile("src/main/java/com/example/zhengdao/ui/baopu/DecisionCardRules.kt").readText()
        listOf("AUTHORIZATION", "POST_ADOPTION", "PERSONA", "SUGGESTION").forEach { v ->
            assertTrue("DecisionVariant 里要有 $v", rules.contains("    $v,") || rules.contains("$v,"))
        }
        // ② 建议页签的行构建处同时用两个类型签（只用一个＝并串）——行构建在控制器
        val controller = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt")
        assertTrue(
            "建议页签必须同时带「建议/效果」两个类型签",
            controller.contains("R.string.decision_type_suggestion") &&
                controller.contains("R.string.decision_type_effect"),
        )
        // ③ 两个类型签的中英文案都在（走查 grep 的机器版）
        listOf("decision_type_suggestion", "decision_type_effect").forEach { key ->
            assertTrue("中文缺 $key", zh.contains("name=\"$key\""))
            assertTrue("英文缺 $key", en.contains("name=\"$key\""))
        }
    }

    // ── N3：终端组零出现 ────────────────────────────────────────────────────

    @Test
    fun `N3_终端组零出现`() {
        listOf("DecisionCard.kt" to decisionCard, "DecisionCenterScreen.kt" to center).forEach { (name, text) ->
            assertFalse("$name 里出现了「终端」（抱朴无终端/rootfs）", text.contains("终端"))
        }
        // 八组用途一行也不许有终端（授权卡副标题走这批素材）
        val lineKeys = listOf(
            "baopu_group_line_core", "baopu_group_line_edit", "baopu_group_line_browser",
            "baopu_group_line_files", "baopu_group_line_library", "baopu_group_line_card",
            "baopu_group_line_skill", "baopu_group_line_preview",
        )
        lineKeys.forEach { key ->
            assertTrue("中文缺 $key", zh.contains("name=\"$key\""))
            assertTrue("英文缺 $key", en.contains("name=\"$key\""))
            assertFalse("$key 的文案里出现了「终端」", stringValue(zh, key).contains("终端"))
        }
    }

    /** 从 strings.xml 文本里取一个键的值（门禁专用；取不到返空串）。 */
    private fun stringValue(xml: String, key: String): String =
        Regex("<string name=\"$key\">([^<]*)</string>").find(xml)?.groupValues?.get(1).orEmpty()

    // ── N4：授权卡一张批整组，不逐工具弹 ─────────────────────────────────────

    @Test
    fun `N4_授权卡按组不按工具`() {
        assertTrue("授权卡必须按 ToolGroup 构造", gate.contains("fun request(group: ToolGroup)"))
        // 构造体内不许出现按工具名循环（逐个弹＝违反「一张卡批整组」）
        val body = gate.substringAfter("fun request(group: ToolGroup)").take(800)
        assertFalse("授权卡构造里不许按工具名循环", body.contains("namesOf(") || body.contains("forEach"))
    }

    // ── N5：同屏刷新（103 已修；本批钉住不许退回重启） ───────────────────────

    @Test
    fun `N5_组头订阅同一个刷新源`() {
        // 103 批修法：组头 remember(toolTick) 与展开后的成员行同一个 tick
        assertTrue(
            "组头必须挂 toolTick（否则同屏不刷新，要退出重进）",
            settings.contains("remember(toolTick)"),
        )
    }

    // ── 正门禁：触控目标 ────────────────────────────────────────────────────

    @Test
    fun `P1_决策卡按钮行不低于48dp`() {
        // 第一百零五批：卡 20 / 钮 14 / 标题 48 的**值**收进 BaopuSkin.kt（卡与外壳共用一份），
        // 本判据跟着改从"文件里有个 48.dp"变成"引用共享常量且常量 ≥48"——判的是同一个事实。
        assertTrue(
            "按钮必须 heightIn(min = BAO_BUTTON_MIN_HEIGHT_DP.dp) 且该常量 ≥48",
            decisionCard.contains("heightIn(min = BAO_BUTTON_MIN_HEIGHT_DP.dp)") &&
                BAO_BUTTON_MIN_HEIGHT_DP >= 48,
        )
    }

    @Test
    fun `P2_设置页摘要行整行可点且组行不低于64dp`() {
        // 141 批：组行不再"整行可点展开成员"（成员名单不进 UI，行内展开已砍），
        // 整行可点的角色交给**摘要行**（点它展开/收起要你处理的组）；组行只留开关。
        // 触控目标的两条底线都没动：整行可点仍在，组行仍 ≥64dp（派工单④）。
        assertTrue(
            "摘要行必须整行可点（展开/收起）",
            settings.contains(".clickable { toolRowsOpen = !toolRowsOpen }"),
        )
        assertTrue("组行必须 ≥64dp（派工单④）", settings.contains("heightIn(min = 64.dp)"))
        assertFalse(
            "成员行内展开不许回潮（141 批已砍）",
            settings.contains(".clickable { expanded.value = !expanded.value }"),
        )
    }

    @Test
    fun `P3_八组显示名与映射表一一对应`() {
        // 组名键中英都在；浏览器/卡片两组名与设计稿 v1.4 映射表对齐
        listOf(
            "baopu_tools_group_core", "baopu_tools_group_edit", "baopu_tools_group_browser",
            "baopu_tools_group_files", "baopu_tools_group_library", "baopu_tools_group_card",
            "baopu_tools_group_skill", "baopu_tools_group_preview",
        ).forEach { key ->
            assertTrue("中文缺 $key", zh.contains("name=\"$key\""))
            assertTrue("英文缺 $key", en.contains("name=\"$key\""))
        }
        assertTrue("BROWSER 显示名要叫「网页操作」", zh.contains("<string name=\"baopu_tools_group_browser\">网页操作</string>"))
        assertTrue("CARD 显示名要叫「卡片格式」", zh.contains("<string name=\"baopu_tools_group_card\">卡片格式</string>"))
        // COMPAT 不列设置页（59/61 批口径，本批接着钉）
        assertTrue("设置页取组必须滤掉 COMPAT", settings.contains("baopuSwitchableGroups"))
    }

    @Test
    fun `P4_决策卡文案中英双份齐全`() {
        val keys = listOf(
            "decision_card_desc", "decision_button_desc",
            "decision_badge_high", "decision_badge_medium", "decision_badge_low", "decision_badge_effect",
            "decision_rec_adopt", "decision_rec_reject", "decision_rec_defer",
            "decision_suggest_reject", "decision_suggest_defer", "decision_suggest_adopt",
            "decision_authorize_session", "decision_authorize_always", "decision_authorize_cancel",
            "decision_persona_workspace", "decision_persona_global", "decision_persona_cancel",
            "decision_center_title", "decision_tab_authorize", "decision_tab_suggestions",
            "decision_tab_proposals", "decision_tab_history",
            "decision_empty_authorize", "decision_empty_suggestions",
            "decision_empty_proposals", "decision_empty_history",
            "decision_undo", "decision_undone", "decision_shield_desc",
            "decision_adopt_confirm_title", "decision_adopt_confirm_ok",
        )
        keys.forEach { key ->
            assertTrue("中文缺 $key", zh.contains("name=\"$key\""))
            assertTrue("英文缺 $key", en.contains("name=\"$key\""))
        }
    }

    @Test
    fun `P5_锁组显示已锁加原因不隐藏`() {
        // 97 批四态：已锁那一档的文案要写清原因（默认不发给模型＋要用时会申请）
        assertTrue("中文锁态文案要在", zh.contains("name=\"baopu_tools_group_default_off\""))
        assertTrue("英文锁态文案要在", en.contains("name=\"baopu_tools_group_default_off\""))
        assertTrue("四态归一判据要在", settings.contains("baopuGroupState("))
    }
}
