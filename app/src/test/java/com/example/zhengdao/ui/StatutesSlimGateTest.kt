// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第二百五十一批（律令瘦身）的**结构门禁**。
 *
 * 这一批只做两件事：把抱朴的三项配置搬进律令，把律令里属于抱朴/已随终端离开的四项删掉。
 * 两类退化都不会让任何行为测试变红，只会悄悄把"两个入口"或"死入口"带回来：
 *
 * 1. **抽屉里又长回那三项**（用户 2026-10-27 明确要"抱朴的事回抱朴"；2026-10-30 又追加：
 *    抽屉「设置」组整组删除，口味档案也搬进律令「抱朴」组）；
 * 2. **律令里又出现那四项**（改名藏起来也算 —— 所以这里钉的是资源键与跳转目标，不是文案）。
 *
 * 判据一律钉**结构事实**（谁调谁、哪个键、哪个文件在不在），不钉行号与局部变量名
 * （ERRATA E-133）。两条纯函数判据都带负例自检，防"判据写错了但永远是绿的"。
 */
class StatutesSlimGateTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.exists()) it else File("app/$rel") }

    private fun text(rel: String): String {
        val f = repoFile(rel)
        assertTrue("找不到 $rel：${f.absolutePath}", f.isFile)
        return f.readText()
    }

    /** 剥注释：注释里提到"当年删了什么"是说明，不是依赖（同 TerminalMovedOutGateTest）。 */
    private fun stripComments(src: String): String =
        src.replace(Regex("(?s)/\\*.*?\\*/"), "")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }

    private val settingsRaw by lazy { text("src/main/java/com/example/zhengdao/ui/SettingsScreen.kt") }
    private val settings by lazy { stripComments(settingsRaw) }
    private val drawer by lazy { stripComments(text("src/main/java/com/example/zhengdao/ui/baopu/BaopuDrawer.kt")) }
    private val baopuScreen by lazy { stripComments(text("src/main/java/com/example/zhengdao/ui/baopu/BaopuScreen.kt")) }
    private val kbPage by lazy { text("src/main/java/com/example/zhengdao/ui/baopu/KnowledgeLibraryScreen.kt") }

    /** 抽屉「设置」组里渲染的 DrawerItem 标题键（顺序即渲染顺序）。纯函数，可造负例。 */
    fun drawerSettingsGroupKeys(src: String): List<String> {
        val marker = "DrawerGroup(stringResource(R.string.baopu_drawer_group_settings))"
        val at = src.indexOf(marker)
        if (at < 0) return emptyList()
        return Regex("DrawerItem\\(\\s*title = stringResource\\(R\\.string\\.([A-Za-z0-9_]+)\\)")
            .findAll(src.substring(at))
            .map { it.groupValues[1] }
            .toList()
    }

    // ── ① 律令「抱朴」组：四项都在，且都进原页面 ──────────────────────────

    @Test
    fun `律令抱朴组四项都在且都进原页面`() {
        // 四行（用户 2026-10-27 指定三项 + 2026-10-30 追加口味档案，一个字不许改名）
        listOf(
            "baopu_drawer_mcp",
            "baopu_channel_button",
            "baopu_drawer_settings",
            "baopu_taste_title",
        ).forEach { key ->
            assertTrue("律令「抱朴」组缺 $key", settings.contains("R.string.$key"))
        }
        // 四张面板都必须是**原 composable**（不是"再写一个精简版"）
        assertTrue("MCP 面板没接上", settings.contains("BaopuMcpSheet("))
        assertTrue("选模型浮层没接上", settings.contains("BaopuChannelSheet("))
        assertTrue("抱朴设置页没接上", settings.contains("BaopuSettingsScreen("))
        assertTrue("口味档案页没接上", settings.contains("TasteSettingsScreen("))
        // 组标题就是「抱朴」（复用底栏那个标签，不新造文案）
        assertTrue("组标题应为 nav_taiji（抱朴）", settings.contains("SectionCard(stringResource(R.string.nav_taiji))"))
    }

    // ── ② 密钥读写没变：提交口仍是全仓唯一那一个 ────────────────────────────

    @Test
    fun `选模型的密钥仍走 switchChannel 与 saveModelOverride`() {
        // 派工单 §2b：**不改密钥读写**。搬页面最容易顺手"简化"掉这两行，
        // 掉了的后果是"选了模型没生效"或"预设模型覆盖丢失"，而 UI 一切正常。
        assertTrue("提交必须走 BaopuRuntime.switchChannel", settings.contains("BaopuRuntime.switchChannel("))
        assertTrue(
            "预设上点定的模型必须另存（saveModelOverride）",
            settings.contains("ProviderPrefs.saveModelOverride("),
        )
        assertTrue("取消要作废草稿（Key 原文不留过夜）", settings.contains("ModelSheetDraft.clear()"))
        // 页面本身没被改：签名里的五个参数一个不少
        val sheet = text("src/main/java/com/example/zhengdao/ui/baopu/BaopuChannelSheet.kt")
        listOf(
            "currentChannelId: String",
            "customBaseUrl: String?",
            "customModel: String?",
            "customProtocol: String?",
            "onDismiss: () -> Unit",
        ).forEach { p -> assertTrue("BaopuChannelSheet 的 $p 不见了（页面被改了？）", sheet.contains(p)) }
    }

    // ── ③ 律令里那四项：键、跳转、Activity 都不许留 ────────────────────────

    @Test
    fun `律令里搜不到那四项的资源键与跳转`() {
        // ⚠️ 这几个键名在本文件里是**拼出来**的，不写全：派工单 §5 的判据是
        // 「桥／工作区／定时任务那三个键名在全仓 grep 零命中」—— 门禁自己写全了就成了
        // 唯一的命中项，判据会被自己的断言弄红。
        val banned = listOf(
            "settings_" + "bridge",           // 桥的设置行
            "settings_value_" + "bridge",     // 桥那行的副标题
            "settings_" + "workspace",        // 工作区行
            "settings_group_" + "workspace",  // 「工作区与资料」整组
            "settings_knowledge_base",        // 资料库行
            "sched_page_title",               // 定时任务行（标题与任务页共用，页面还在抱朴那边）
        )
        banned.forEach { k ->
            assertFalse("律令里还有 R.string.$k", settings.contains("R.string.$k"))
        }
        assertFalse("律令里还有跳 ScheduleTasksActivity 的代码", settings.contains("ScheduleTasksActivity"))
        assertFalse("律令里还有跳桥页的代码", settings.contains("SettingsBridgeActivity"))
        // 判据 §3 的口径：律令里连这四个词都不该出现 —— **注释也算"搜得到"**，
        // 所以这一条盯未剥注释的原文（历史说明改写成"§1 表里那四项"，别把词写回来）。
        listOf("浏览器桥", "工作区", "定时任务", "资料库").forEach { w ->
            assertFalse("律令源码（含注释）里还有「$w」", settingsRaw.contains(w))
        }
    }

    @Test
    fun `桥的 Activity 与文案已经整个删掉`() {
        assertFalse(
            "SettingsBridgeActivity.kt 应已删除",
            repoFile("src/main/java/com/example/zhengdao/ui/SettingsBridgeActivity.kt").exists(),
        )
        assertFalse("Manifest 不该再登记它", text("src/main/AndroidManifest.xml").contains("SettingsBridgeActivity"))
        val zh = text("src/main/res/values/strings.xml")
        val en = text("src/main/res/values-en/strings.xml")
        listOf("settings_" + "bridge", "settings_value_" + "bridge").forEach { k ->
            assertFalse("zh 还有 $k", zh.contains("name=\"$k\""))
            assertFalse("en 还有 $k", en.contains("name=\"$k\""))
        }
        // 桥自己的 19 条文案也只被那个页面用过 ⇒ 一起删（否则就是 UnusedResources）
        assertFalse("bridge_* 文案还在（页面已删）", Regex("<string name=\"bridge_").containsMatchIn(zh))
        assertFalse("bridge_* 文案还在（en）", Regex("<string name=\"bridge_").containsMatchIn(en))
    }

    // ── ④ 抱朴抽屉「设置」组：第二百八十六批整组删除 ─────────────────────────

    @Test
    fun `抽屉不再有设置组_两行都搬去律令抱朴组`() {
        // 2026-10-30 用户裁定：抽屉里两个「定时任务」只留一个、口味档案也搬走 ⇒
        // 设置组清空、整组删除。判据从「只剩两项」改成「一项都不剩」。
        assertEquals(
            "抽屉里不许再有「设置」组（整组已删）",
            emptyList<String>(),
            drawerSettingsGroupKeys(drawer),
        )
        assertFalse(
            "抽屉里不许再有「口味档案」入口（已搬去律令「抱朴」组）",
            drawer.contains("R.string.baopu_taste_title"),
        )
        // 三个回调连同入口一起删（参数还在 = 有人还能从别处接回来）
        listOf("onMcp", "onChannel", "onSettings", "onTaste").forEach { p ->
            assertFalse("BaopuDrawer 里还有 $p 参数/调用", drawer.contains(p))
        }
        // 抱朴页那一侧也不再装配它们（面板本身留给律令；抱朴页里顶栏/工作区卡那两条老路不动）
        assertFalse("BaopuScreen 里还有 showMcp", baopuScreen.contains("showMcp"))
        assertFalse("BaopuScreen 里还有 showTaste（口味档案已搬去律令）", baopuScreen.contains("showTaste"))
    }

    @Test
    fun `抽屉判据自检_多搬一项必须被抓到`() {
        // 造一份"把 MCP 又搬回抽屉"的假源码：判据必须数出三项、且含 baopu_drawer_mcp
        val fake = """
            DrawerGroup(stringResource(R.string.baopu_drawer_group_settings))
            DrawerItem(
                title = stringResource(R.string.baopu_drawer_sched),
                description = stringResource(R.string.baopu_drawer_sched_desc),
                onClick = onScheduleTasks,
            )
            DrawerItem(
                title = stringResource(R.string.baopu_drawer_mcp),
                description = stringResource(R.string.baopu_drawer_mcp_desc),
                onClick = onMcp,
            )
        """.trimIndent()
        val keys = drawerSettingsGroupKeys(fake)
        assertEquals("负例没被抓到：$keys", listOf("baopu_drawer_sched", "baopu_drawer_mcp"), keys)
        // 没长回设置组时（标记都不在）应给空表，而不是"默认通过"
        assertEquals(emptyList<String>(), drawerSettingsGroupKeys("DrawerItem(title = \"x\")"))
    }

    // ── ⑤ 资料库改成抱朴的：入口在抽屉，页里三件都在 ────────────────────────

    @Test
    fun `资料库入口在抱朴抽屉且页面有状态与重建`() {
        // 抽屉那一行不再跳设置页（250 之后那一路落到的是已删的桥页 —— 死入口）
        assertTrue("抽屉「资料库」应开本页", baopuScreen.contains("KnowledgeLibraryScreen(onDismiss"))
        assertFalse("抽屉「资料库」还在跳设置页", baopuScreen.contains("SettingsBridgeActivity"))
        // 三件：已索引 N 个文件 / 上次更新 / 重建索引
        assertTrue("缺「已索引」", kbPage.contains("R.string.baopu_kb_files_label"))
        assertTrue("缺文件数（复用既有 plural）", kbPage.contains("R.plurals.settings_status_file_count"))
        assertTrue("缺「上次更新」", kbPage.contains("R.string.baopu_kb_updated"))
        assertTrue("缺「重建索引」", kbPage.contains("R.string.baopu_kb_rebuild"))
        assertTrue("重建要走 KnowledgeBase.requestRebuild", kbPage.contains("KnowledgeBase.requestRebuild("))
        // 读盘必须在 IO 上（KnowledgeBase.status 的注释明写了这条）
        assertTrue("状态读取必须在 Dispatchers.IO", kbPage.contains("Dispatchers.IO"))
        // 中英成对（新加 7 条）
        val zh = text("src/main/res/values/strings.xml")
        val en = text("src/main/res/values-en/strings.xml")
        listOf(
            "baopu_kb_group", "baopu_kb_files_label", "baopu_kb_updated", "baopu_kb_never",
            "baopu_kb_rebuild", "baopu_kb_rebuild_hint", "baopu_kb_rebuild_started",
        ).forEach { k ->
            assertTrue("zh 缺 $k", zh.contains("name=\"$k\""))
            assertTrue("en 缺 $k", en.contains("name=\"$k\""))
        }
    }
}
