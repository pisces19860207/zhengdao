// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 依据的公开接口：Android ContextWrapper（android.content）、JUnit 4（TemporaryFolder）。
package com.example.zhengdao.ui

import android.content.Context
import android.content.ContextWrapper
import com.example.zhengdao.ui.home.HOME_AGENT_ENTRY_IDS
import com.example.zhengdao.ui.home.homeEntryVisible
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 第一百四十二批 · 首页 Agent 入口恢复四件的门禁（2026-10-22）。
 *
 * 派工单：《派工单-2026-10-22-第一百四十二批-Agent入口恢复》＋设计约束汇总 §2.2「136A」；
 * 设计：《抱朴设置重构方案-2026-10-22.md》§10（用户 09:22「都恢复」，覆盖 10-18 第十八批
 * B 段「只保留 Hermes」旧定稿）。
 *
 * 判据表（一条一断言；负例按派工单「各红」逐条钉）：
 *
 * | # | 判据 | 期望 |
 * |---|------|------|
 * | 1 | 白名单四件（claude-code / hermes / antigravity / codex） | 全部可见；只回 hermes ⇒ 红 |
 * | 2 | 未知 id（manifest 追加）与历史 id（opencode） | 一律不进首页入口 |
 * | 3 | 注册表四件 | 安装命令、启动命令俱全（复用现有注册表，能装能启） |
 * | 4 | 四件描述说人话 | 各自的登录方式写在明处（官方账号/官方登录或 Key/本项目通道/Google 账号） |
 * | 5 | 体积提示 | 只有 AGY 有（约 201MB，实测值），其余三件没有——不编数字 |
 * | 6 | 负例·混写模型来源 | 四件描述不出现抱朴/OpenRouter/免费（§2/§4 的文案不混进终端 agent 卡） |
 * | 7 | 源码门禁·两行封顶同高 | 描述区 minLines = maxLines = 2；13sp（bodySmall）；无 12.sp、无 Color(0x |
 * | 8 | 负例·重造安装器 | HomeScreen 仍走 AgentInstaller.prepareInstall；无安装命令字符串、无 AgentInfo( 构造 |
 * | 9 | 源码门禁·间距与容器 | 12dp 间距、16dp 圆角＋0.5dp 发丝现有容器原样；不引消息流四档容器 |
 * |10 | 检测区口径未被顺手改 | visibleRows 仍只认 hermes（J4 延续，本单范围只含入口） |
 *
 * 为什么能 JVM 直测：白名单与注册表都是纯数据；源码级判据读剥注释后的文本
 * （本仓无 UI 测试基建，与 HomeTitleCollapseGuardTest / LargeFontWrapGuardTest 同惯例）。
 */
class HomeAgentEntryGuardTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── 源码读取（判据一律读剥掉整行注释后的文本，防注释里的反例词误伤） ──

    /** 单测的工作目录可能是模块目录（app/）或仓库根，两处都找。 */
    private fun repoFile(rel: String): File {
        val cands = listOf(File(rel), File("../$rel"))
        return cands.firstOrNull { it.isFile }
            ?: throw AssertionError("找不到 $rel（试过：${cands.joinToString { it.absolutePath }}）")
    }

    private val homeRaw by lazy {
        repoFile("app/src/main/java/com/example/zhengdao/ui/HomeScreen.kt").readText()
    }

    /** 剥掉整行 `//` 注释后的源码（行尾注释不动，避免误伤字符串里的 `//`）。 */
    private val home by lazy { homeRaw.replace(Regex("(?m)^[ \\t]*//.*$"), "") }

    /** 造一个 filesDir 可写的假 Context（AppState.agents 只用到 filesDir 与 SharedPreferences）。 */
    private class FakeFilesContext(private val dir: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = dir
        override fun getApplicationContext(): Context = this
    }

    private fun registry(): List<AppState.AgentInfo> {
        val files = tmp.newFolder()
        File(files, "rootfs").mkdirs()
        File(files, "rootfs/.zhengdao-rootfs-ok").writeText("installed-by=zhengdao")
        return AppState.agents(FakeFilesContext(files))
    }

    private fun agentOf(id: String): AppState.AgentInfo =
        registry().first { it.id == id }

    // ── ①② 白名单：四件全可见，未知/历史 id 不进首页 ──────────────────

    @Test
    fun `判据1_白名单四件全部可见_只回hermes会红`() {
        // 派工单负例第一条：「只回 hermes ⇒ 红」——这条就是它的对偶：
        // 谁把 filter 改回 hermes-only，其余三个断言当场红。
        for (id in listOf("claude-code", "hermes", "antigravity", "codex")) {
            assertTrue("白名单四件之一必须可见：$id", homeEntryVisible(id))
        }
        assertEquals("白名单恰好四件，不多不少", 4, HOME_AGENT_ENTRY_IDS.size)
        assertEquals(
            "白名单内容与派工单一致（顺序＝注册表出厂序）",
            listOf("claude-code", "hermes", "antigravity", "codex"),
            HOME_AGENT_ENTRY_IDS,
        )
    }

    @Test
    fun `判据2_未知id与历史id不进首页入口`() {
        // manifest 可以追加未知 id（注册表照收），但首页入口只放白名单四件；
        // opencode 是历史 id（已内置为抱朴），新旧定稿都不展示。
        for (id in listOf("opencode", "unknown-agent", "", "hermesx", "claude")) {
            assertFalse("非白名单 id 不许进首页入口：$id", homeEntryVisible(id))
        }
    }

    // ── ③ 注册表四件可安装可启动（复用现有注册表） ────────────────────

    @Test
    fun `判据3_注册表四件_安装与启动命令俱全`() {
        val agents = registry()
        for (id in HOME_AGENT_ENTRY_IDS) {
            val a = agents.firstOrNull { it.id == id }
            assertNotNull("白名单 id 必须在注册表里：$id", a)
            assertTrue("$id 启动命令不能空", a!!.launchCmd.isNotBlank())
            assertTrue("$id 安装命令不能空（能一键安装）", !a.installCmd.isNullOrBlank())
        }
    }

    // ── ④ 描述说人话 ────────────────────────────────────────────────

    @Test
    fun `判据4_四件描述各说各的登录方式`() {
        // 设计文档 §10：每张卡的使用门槛写在明处，说人话。
        assertTrue("Claude Code＝官方账号登录", agentOf("claude-code").desc.contains("官方账号"))
        assertTrue("Codex CLI＝官方登录或换自己的 Key", agentOf("codex").desc.contains("Key"))
        assertTrue("Hermes＝本项目通道", agentOf("hermes").desc.contains("本项目"))
        assertTrue("AGY＝Google 账号", agentOf("antigravity").desc.contains("Google"))
        for (id in HOME_AGENT_ENTRY_IDS) {
            assertTrue("$id 描述不能空", agentOf(id).desc.isNotBlank())
        }
    }

    // ── ⑤ 体积提示：只有 AGY 有实测值 ────────────────────────────────

    @Test
    fun `判据5_体积提示只有AGY有且带201MB_其余三件没有`() {
        val hint = agentOf("antigravity").sizeHint
        assertNotNull("AGY 必须带体积提示（派工单：agy 实测 201MB）", hint)
        assertTrue("体积提示要带实测值：$hint", hint!!.contains("201MB"))
        for (id in listOf("claude-code", "hermes", "codex")) {
            assertEquals("$id 没有实测值就不给提示——不编数字", null, agentOf(id).sizeHint)
        }
    }

    // ── ⑥ 负例：不混写抱朴模型来源 ──────────────────────────────────

    @Test
    fun `判据6_负例_agent卡描述不混写抱朴模型来源`() {
        // §10：终端 agent 用各自的官方登录/Key，与抱朴通道（§2 选模型抽屉 / §4 免费档）
        // 是两套并存的模型来源，UI 上不混写。DeepSeek 是设计给 Codex 明文许可的
        // （「换基底（DeepSeek 可）」），不在禁列表。
        for (id in HOME_AGENT_ENTRY_IDS) {
            val desc = agentOf(id).desc
            for (banned in listOf("抱朴", "OpenRouter", "免费")) {
                assertFalse("$id 描述不许混写抱朴模型来源文案（$banned）：$desc", desc.contains(banned))
            }
        }
    }

    // ── ⑦ 源码门禁：两行封顶、同高、13sp ────────────────────────────

    @Test
    fun `判据7_描述区两行封顶且同高_13sp无12sp无硬编码色`() {
        // 设计约束：描述 ≤2 行 13sp；四卡同高（同构卡不同高＝纯视觉噪音）⇒
        // minLines = maxLines = 2（恒占两行，超长省略号收口）。
        assertTrue("描述区必须两行封顶", home.contains("maxLines = 2"))
        assertTrue("描述区必须恒占两行（同高判据）", home.contains("minLines = 2"))
        assertTrue("提示收在同一段末尾（不新开一行）⇒ 富文本拼接", home.contains("buildAnnotatedString"))
        assertTrue("提示用次墨槽位（onSurfaceVariant＝#57544C，深色自动换）", home.contains("onSurfaceVariant"))
        assertTrue("描述 13sp（bodySmall）", home.contains("MaterialTheme.typography.bodySmall"))
        assertTrue("超行要有省略号收口", home.contains("TextOverflow.Ellipsis"))
        assertFalse("新增代码不许出现 12.sp（字号只许 15/13/10）", home.contains("12.sp"))
        assertFalse("新增代码不许硬编码 Color(0x（一律走主题槽位）", home.contains("Color(0x"))
    }

    // ── ⑧ 负例：没重造安装器 ────────────────────────────────────────

    @Test
    fun `判据8_负例_复用现有安装器_没有重造`() {
        // 红线「复用现有注册表/安装/卸载/轮询；不重造安装器」的源码级对偶：
        // 安装命令字符串归 AppState 注册表，UI 层只负责调用。
        assertTrue("仍走现成的一键安装入口", home.contains("AgentInstaller.prepareInstall"))
        assertTrue("状态轮询原样（2 秒 LaunchedEffect）", home.contains("LaunchedEffect(agents, stateTick)"))
        assertFalse("UI 层不许出现安装命令字符串（curl 脚本）", home.contains("curl -fsSL"))
        assertFalse("UI 层不许出现安装命令字符串（npm）", home.contains("npm i -g"))
        assertFalse("UI 层不许新建 AgentInfo 注册条目", home.contains("AgentInfo("))
        // 入口 filter 必须走白名单判据，而不是在 UI 层另写一份 id 列表
        assertTrue("filter 走白名单判据", home.contains("agents.filter { homeEntryVisible(it.id) }"))
        assertFalse(
            "旧的 hermes-only filter 字面量必须已经不在（只回 hermes ⇒ 红）",
            home.contains("""agents.filter { it.id == "hermes" }"""),
        )
    }

    // ── ⑨ 源码门禁：间距与容器没新造 ────────────────────────────────

    @Test
    fun `判据9_12dp间距与现有入口卡容器原样_不引消息流四档`() {
        // 设计约束：复用现有入口卡容器（不新造卡）；四卡纵向间距 12dp；
        // 入口卡属首页/设置页体系，不受消息流四档约束。
        assertTrue("纵向间距 12dp（LazyColumn 原有 arrangement）", home.contains("Arrangement.spacedBy(12.dp)"))
        assertTrue("入口卡容器：16dp 圆角原样", home.contains("shape = RoundedCornerShape(16.dp)"))
        assertTrue("入口卡容器：0.5dp 发丝描边原样", home.contains("border = BorderStroke(0.5.dp,"))
        assertFalse(
            "不许把消息流四档容器（BaopuContainers）搬进首页入口卡",
            home.contains("BaopuContainers"),
        )
    }

    // ── ⑩ 检测区口径未被顺手改（J4 延续） ───────────────────────────

    @Test
    fun `判据10_检测区仍只认Hermes_本单没有顺手改它`() {
        // 检测瘦身（第十八批 B 段，J4 钉死）是独立决策，不在 142「只动首页入口」范围内：
        // 它的 hermes-only 口径必须原样留在 visibleRows 里。
        assertTrue(
            "visibleRows 的检测面口径必须原样（只认 hermes）",
            home.contains("""agents.filter { it.id == "hermes" && !it.installed }"""),
        )
    }
}
