// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第二十九批 §4 判据 W1–W8 ＋ §5 负例清单 ＋ §2 A 段硬要求（A6／A7／口径 1／口径 3）。
package com.example.zhengdao.ui.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第二十九批 · 抽屉＝工作区树（W1–W8）。
 *
 * 两半分着钉（与 `BaopuPreviewTest` 同一口径）：
 * - **行为级**：分组、末级名、相对时间、高亮档全在 [BaopuSessionTree] 的纯函数里，JVM 直接喂值断言；
 * - **源码级**：抽屉那一侧"画的是不是算好的那个东西"、确认卡那三个出口、临时提示不常驻——
 *   读源码形状，每条 absence 断言尽量配一条**判别器自证**（喂一段坏形状必须报警）⇒「不红＝没真测」。
 *
 * 注释在断言前剥掉（仓库纪律：凡断言某字样**不出现**，先剥注释再断言——本批主源的 KDoc 里
 * 合法地写着「不许 split("/")」这类字样）。
 */
class BaopuSessionTreeTest {

    // ── 基础设施 ────────────────────────────────────────────────────────────

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    private fun raw(rel: String): String = repoFile(rel).readText().replace("\r\n", "\n")

    private fun stripComments(s: String): String = s
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    /** 从签名起按花括号配平取函数体（结构断言只认这一块，别整文件扫）。 */
    private fun bodyOf(src: String, signature: String): String {
        val start = src.indexOf(signature)
        assertTrue("源码里找不到签名：$signature", start >= 0)
        val open = src.indexOf('{', start)
        var depth = 0
        var i = open
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(open, i + 1)
                }
            }
            i++
        }
        error("$signature 的花括号没配平")
    }

    /** 取 `名字(` 到配平的 `)` 之间——data class 没有函数体，只能取参数表。 */
    private fun paramsOf(src: String, signature: String): String {
        val start = src.indexOf(signature)
        assertTrue("源码里找不到签名：$signature", start >= 0)
        val open = src.indexOf('(', start)
        var depth = 0
        var i = open
        while (i < src.length) {
            when (src[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return src.substring(open, i + 1)
                }
            }
            i++
        }
        error("$signature 的括号没配平")
    }

    private val treeRel = "src/main/java/com/example/zhengdao/ui/baopu/BaopuSessionTree.kt"
    private val drawerRel = "src/main/java/com/example/zhengdao/ui/baopu/BaopuDrawer.kt"
    private val screenRel = "src/main/java/com/example/zhengdao/ui/baopu/BaopuScreen.kt"
    private val controllerRel = "src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt"
    private val zhRel = "src/main/res/values/strings.xml"
    private val enRel = "src/main/res/values-en/strings.xml"

    private val treeCode: String by lazy { stripComments(raw(treeRel)) }
    private val drawerCode: String by lazy { stripComments(raw(drawerRel)) }
    private val screenCode: String by lazy { stripComments(raw(screenRel)) }
    private val controllerCode: String by lazy { stripComments(raw(controllerRel)) }

    private fun valueOf(rel: String, key: String): String =
        Regex("name=\"$key\">([^<]*)<").find(raw(rel))?.groupValues?.get(1)
            ?: throw AssertionError("找不到 $rel 里的 $key（键被删了？）")

    private fun keySet(rel: String): Set<String> = raw(rel)
        .split('\n')
        .mapNotNull { line -> Regex("name=\"(baopu_[a-z_0-9]+)\"").find(line)?.groupValues?.get(1) }
        .toSet()

    /** 相对时间模板（直接吃 strings.xml 的 zh 值——测的就是真文案）。 */
    private fun zhTemplates() = RelativeTemplates(
        justNow = valueOf(zhRel, "baopu_drawer_time_just_now"),
        minutes = valueOf(zhRel, "baopu_drawer_time_minutes"),
        hours = valueOf(zhRel, "baopu_drawer_time_hours"),
        days = valueOf(zhRel, "baopu_drawer_time_days"),
        months = valueOf(zhRel, "baopu_drawer_time_months"),
    )

    private fun entry(id: Long, title: String, updatedAt: Long, workspace: String?) =
        BaopuSessionEntry(
            id = id,
            title = title,
            rounds = 1,
            updatedAt = updatedAt,
            closedAt = null,
            workspace = workspace,
        )

    private val now = 1_700_000_000_000L
    private val minute = 60_000L
    private val hour = 3_600_000L
    private val day = 86_400_000L

    /** 公共调用桩：当前工作区 = /storage/emulated/0/Download/男性。 */
    private fun tree(
        entries: List<BaopuSessionEntry>,
        currentSessionId: Long? = null,
        currentState: WorkspaceState = WorkspaceState.NORMAL,
    ) = buildWorkspaceTree(
        entries = entries,
        currentHostPath = "/storage/emulated/0/Download/男性",
        currentLabel = workspaceFolderLabel(
            "/storage/emulated/0/Download/男性",
            currentState,
            "App 私有",
            "未授权",
        ),
        currentState = currentState,
        unknownLabel = "默认工作区",
        unknownPath = "/storage/emulated/0/Download/证道",
        currentSessionId = currentSessionId,
        now = now,
        templates = zhTemplates(),
        fallbackTitle = "未命名会话",
    )

    // ── W1：文件夹行只有末级名 ────────────────────────────────────────────

    @Test
    fun `W1_末级名只走File_name_深层目录取最后一段`() {
        assertEquals("男性", folderLeafName("/storage/emulated/0/Download/男性"))
        assertEquals("AndroidStudioProjects", folderLeafName("/storage/emulated/0/Download/AndroidStudioProjects"))
        // 尾斜杠不多出一个空段（File 自己归一）
        assertEquals("男性", folderLeafName("/storage/emulated/0/Download/男性/"))
        // 私有目录末段就是 workspace：按口径显示名字，不许为了好看另编说法
        assertEquals(
            "workspace",
            folderLeafName("/storage/emulated/0/Android/data/com.example.zhengdao/files/workspace"),
        )
    }

    @Test
    fun `W1_取名字不许手搓截串_主源里不许有split与substring`() {
        assertFalse("不许 split", treeCode.contains("split("))
        assertFalse("不许 substringAfterLast", treeCode.contains("substringAfterLast"))
        assertFalse("不许 substringBeforeLast", treeCode.contains("substringBeforeLast"))
        assertTrue("必须走 java.io.File 的 name", treeCode.contains("File(hostAbsolutePath).name"))
    }

    @Test
    fun `W1_树里label不含斜杠_完整路径只进fullPath那个字段`() {
        val nodes = tree(
            listOf(
                entry(1L, "你好呀", now - minute, "/storage/emulated/0/Download/男性"),
                entry(2L, "旧活儿", now - day, "/storage/emulated/0/Download/AndroidStudioProjects"),
            ),
        )
        // 默认工作区永远在（2026-10-18 用户口径）⇒ 两个真工作区 + 一个默认工作区
        assertEquals(3, nodes.size)
        nodes.forEach { node ->
            assertFalse("${node.label} 不许含斜杠（完整路径不上表）", node.label.contains("/"))
            assertTrue("完整路径留给点那一下：${node.fullPath}", node.fullPath.contains("/"))
        }
    }

    @Test
    fun `W1_接线面取的是hostDir_不许在页面里自己拼路径`() {
        assertTrue("当前工作区必须来自 Workspace.hostDir", screenCode.contains("Workspace.hostDir(context)"))
        assertFalse(
            "不许把绝对路径直接画进树那一行",
            Regex("text\\s*=\\s*host\\.absolutePath").containsMatchIn(screenCode),
        )
    }

    // ── W2：私有／未授权两行文案与符号 ────────────────────────────────────

    @Test
    fun `W2_未授权优先于私有_hostDir回落私有目录也不许说成App私有`() {
        // Workspace.hostDir 在无权限时**本来就返回私有目录**——顺序反了就把"没授权"说成"你选了私有"。
        assertEquals(
            WorkspaceState.UNAUTHORIZED,
            workspaceStateOf(storageGranted = false, privateMode = true),
        )
        assertEquals(WorkspaceState.PRIVATE, workspaceStateOf(storageGranted = true, privateMode = true))
        assertEquals(WorkspaceState.NORMAL, workspaceStateOf(storageGranted = true, privateMode = false))
    }

    @Test
    fun `W2_三种状态各有文案与符号`() {
        val host = "/storage/emulated/0/Android/data/com.example.zhengdao/files/workspace"
        assertEquals("App 私有", workspaceFolderLabel(host, WorkspaceState.PRIVATE, "App 私有", "未授权"))
        assertEquals("未授权", workspaceFolderLabel(host, WorkspaceState.UNAUTHORIZED, "App 私有", "未授权"))
        assertEquals("workspace", workspaceFolderLabel(host, WorkspaceState.NORMAL, "App 私有", "未授权"))
        assertEquals("🔒", folderIcon(WorkspaceState.PRIVATE))
        assertEquals("⚠️", folderIcon(WorkspaceState.UNAUTHORIZED))
        assertEquals("📁", folderIcon(WorkspaceState.NORMAL))
        assertEquals("📁", folderIcon(WorkspaceState.UNRECORDED))
    }

    @Test
    fun `W2_私有与未授权各自成一行`() {
        val entries = listOf(entry(1L, "你好呀", now - minute, "/storage/emulated/0/Download/男性"))
        assertEquals("App 私有", tree(entries, currentState = WorkspaceState.PRIVATE).first { it.isCurrent }.label)
        assertEquals("未授权", tree(entries, currentState = WorkspaceState.UNAUTHORIZED).first { it.isCurrent }.label)
    }

    @Test
    fun `W2_符号只有文字形状_抽屉不许引图标库`() {
        assertFalse("不许 import material-icons", drawerCode.contains("androidx.compose.material.icons"))
        assertFalse("不许用 Icons 对象", drawerCode.contains("Icons."))
        assertFalse("不许自绘 glyph", drawerCode.contains("drawPath"))
        assertTrue("符号由纯函数给出", drawerCode.contains("folderIcon(node.state)"))
        // 判别器自证：坏形状（引图标库）必须被同一条正则抓到
        assertTrue(
            "判别器抓得住图标库",
            Regex("androidx\\.compose\\.material\\.icons").containsMatchIn(
                "import androidx.compose.material.icons.Icons",
            ),
        )
    }

    @Test
    fun `W2_未授权那一行点了也去设置页_与文件夹行同一个落点`() {
        // 未授权行的"点它去设置页"不是另开一支：所有文件夹行都走 onWorkspaceTap，那一支落设置页。
        val tap = bodyOf(screenCode, "onWorkspaceTap = { path ->")
        assertTrue("点文件夹行必须开抱朴设置页（那张「工作区」卡在里面）", tap.contains("showSettings = true"))
        assertTrue("跳之前先给那句提示", tap.contains("showWorkspacePath(path)"))
    }

    // ── W3：点文件夹行＝临时提示＋落点，不许常驻 ─────────────────────────

    @Test
    fun `W3_临时提示走Toast_飘完就没不留常驻控件`() {
        val hint = bodyOf(screenCode, "val showWorkspacePath: (String) -> Unit = { path ->")
        assertTrue("提示就是一行 Toast（自动消失）", hint.contains("Toast.makeText(context, path"))
        assertTrue("LENGTH_SHORT，不自己养一个常驻计时器", hint.contains("Toast.LENGTH_SHORT"))
    }

    @Test
    fun `W3_提示不许常驻_抽屉里不许为它留一个状态位`() {
        // 「常驻」的形状＝把路径存成 UI 状态画在树上。本批的画法是：路径只进回调，不进 Text。
        assertFalse(
            "抽屉不许出现画完整路径的 Text",
            Regex("text\\s*=\\s*node\\.fullPath").containsMatchIn(drawerCode),
        )
        val persistent = Regex("var\\s+\\w*[Pp]ath\\w*\\s+by\\s+mutableStateOf")
        assertFalse(
            "抱朴页不许为那句提示另立一个 mutableStateOf（那才叫常驻）",
            persistent.containsMatchIn(screenCode),
        )
        // 判别器自证：喂一段"把路径存成状态"的坏形状，那条正则必须抓到
        assertTrue(
            "判别器得认得出常驻形状",
            persistent.containsMatchIn("var lastWorkspacePath by mutableStateOf<String?>(null)"),
        )
    }

    @Test
    fun `W3_长按只给提示不跳页_两条路各管各的`() {
        assertTrue(
            "长按只挂 showWorkspacePath，不碰设置页",
            screenCode.contains("onWorkspaceHold = showWorkspacePath,"),
        )
        val hold = bodyOf(screenCode, "val showWorkspacePath: (String) -> Unit = { path ->")
        assertFalse("提示函数自己不许顺手开页", hold.contains("showSettings = true"))
    }

    // ── W4：相对时间五档 ──────────────────────────────────────────────────

    @Test
    fun `W4_五十九秒算刚刚_六十秒起进分钟档`() {
        assertEquals(RelativeBucket.JUST_NOW, relativeAge(now - 59_000L, now).bucket)
        assertEquals(RelativeBucket.MINUTES, relativeAge(now - minute, now).bucket)
        assertEquals(1, relativeAge(now - minute, now).count)
        assertEquals(59, relativeAge(now - 59 * minute, now).count)
    }

    @Test
    fun `W4_六十分钟整点进小时档`() {
        assertEquals(RelativeBucket.HOURS, relativeAge(now - hour, now).bucket)
        assertEquals(1, relativeAge(now - hour, now).count)
        assertEquals(RelativeBucket.MINUTES, relativeAge(now - hour + minute, now).bucket)
        assertEquals(59, relativeAge(now - hour + minute, now).count)
    }

    @Test
    fun `W4_满一天进天档`() {
        assertEquals(RelativeBucket.DAYS, relativeAge(now - day, now).bucket)
        assertEquals(1, relativeAge(now - day, now).count)
        assertEquals(RelativeBucket.HOURS, relativeAge(now - day + hour, now).bucket)
        assertEquals(23, relativeAge(now - day + hour, now).count)
        assertEquals(RelativeBucket.DAYS, relativeAge(now - 29 * day, now).bucket)
        assertEquals(29, relativeAge(now - 29 * day, now).count)
    }

    @Test
    fun `W4_满三十天进月档_按月折算用三十天`() {
        assertEquals(RelativeBucket.MONTHS, relativeAge(now - 30 * day, now).bucket)
        assertEquals(1, relativeAge(now - 30 * day, now).count)
        assertEquals(2, relativeAge(now - 61 * day, now).count)
        assertEquals(6, relativeAge(now - 200 * day, now).count)
    }

    @Test
    fun `W4_五档成品文字都落得下来_直接读strings的模板`() {
        val t = zhTemplates()
        assertEquals("刚刚", relativeTimeText(now - 10_000L, now, t))
        assertEquals("20 分钟", relativeTimeText(now - 20 * minute, now, t))
        assertEquals("20 小时", relativeTimeText(now - 20 * hour, now, t))
        assertEquals("20 天", relativeTimeText(now - 20 * day, now, t))
        assertEquals("7 个月", relativeTimeText(now - 211 * day, now, t))
    }

    @Test
    fun `W4_时间戳缺失或跑向未来_一律刚刚不许出现负数`() {
        val t = zhTemplates()
        assertEquals("刚刚", relativeTimeText(0L, now, t))
        assertEquals("刚刚", relativeTimeText(-5L, now, t))
        // 设备时钟被往回调过（updated_at 比 now 新）：差值夹到 0
        val text = relativeTimeText(now + 7 * minute, now, t)
        assertEquals("刚刚", text)
        assertFalse("成品文字里不许有负号", text.contains("-"))
    }

    @Test
    fun `W4_会话行模型里就没有摘要这个字段`() {
        // A2 段：「不许把 preview 摘要塞进来 —— 只有标题 ＋ 时间」。
        val model = paramsOf(treeCode, "internal data class DrawerSessionRow")
        assertTrue("有标题", model.contains("val title: String"))
        assertTrue("有相对时间", model.contains("val timeText: String"))
        assertFalse("不许有 preview／摘要字段", model.contains("preview") || model.contains("summary"))
        assertFalse("不许有轮数（那是会话库浮层的列）", model.contains("rounds"))
        assertFalse("不许有工作区字段（分组键在节点上，不在行上）", model.contains("workspace"))
    }

    @Test
    fun `W4_会话行画的就是标题与时间两样`() {
        val row = bodyOf(drawerCode, "private fun SessionRow(")
        assertEquals(
            "两处 text 分别给标题与相对时间",
            2,
            Regex("text = row\\.(title|timeText)").findAll(row).count(),
        )
        assertTrue("标题只一行、超长省略", row.contains("overflow = TextOverflow.Ellipsis"))
    }

    // ── W5：分组口径 ──────────────────────────────────────────────────────

    @Test
    fun `W5_按workspace分组_组间按最新一条倒序`() {
        val nodes = tree(
            listOf(
                entry(1L, "最近的旧组", now - hour, "/storage/emulated/0/Download/AndroidStudioProjects"),
                entry(2L, "当前组第一条", now - 2 * day, "/storage/emulated/0/Download/男性"),
            ),
        )
        assertEquals(listOf("AndroidStudioProjects", "男性", "默认工作区"), nodes.map { it.label })
    }

    @Test
    fun `W5_NULL老会话归未记录组且排最后`() {
        val nodes = tree(
            listOf(
                entry(1L, "没记工作区的旧会话", now - 200 * day, null),
                entry(2L, "空白串也算没记", now - 3 * day, "  "),
                entry(3L, "今天的当前会话", now - minute, "/storage/emulated/0/Download/男性"),
            ),
            currentSessionId = 3L,
        )
        assertEquals(2, nodes.size)
        assertTrue("最后一组必须是未记录组", nodes.last().unknown)
        assertEquals("默认工作区", nodes.last().label)
        assertEquals(listOf(2L, 1L), nodes.last().rows.map { it.id })
        assertEquals("/storage/emulated/0/Download/证道", nodes.last().fullPath)
        assertFalse("未记录组不是当前工作区", nodes.last().isCurrent)
    }

    @Test
    fun `W5_当前工作区没有会话也一定出现`() {
        val nodes = tree(
            listOf(entry(1L, "别的文件夹里的会话", now - hour, "/storage/emulated/0/Download/AndroidStudioProjects")),
        )
        assertEquals(3, nodes.size)   // 别的文件夹 + 当前工作区 + 默认工作区
        val current = nodes.first { it.isCurrent }
        assertEquals("男性", current.label)
        assertTrue("它下面一条会话都没有，也照样占一行", current.rows.isEmpty())
        // 位置：有会话的组在前，空的当前组随后（不抢"最近在用"的位置，又看得见自己在哪）
        assertEquals("AndroidStudioProjects", nodes.first().label)
    }

    @Test
    fun `W5_没有会话又没有当前身份的文件夹不出现`() {
        val nodes = tree(listOf(entry(1L, "一条", now - hour, "/storage/emulated/0/Download/男性")))
        // 「没有会话又不是当前」的文件夹不出现；但默认工作区常显 ⇒ 2 行
        assertEquals(2, nodes.size)
        assertEquals(listOf("男性", "默认工作区"), nodes.map { it.label })
    }

    @Test
    fun `W5_库里只有NULL会话时当前工作区仍然在场`() {
        val nodes = tree(listOf(entry(9L, "老东西", now - day, null)), currentSessionId = 9L)
        assertEquals(2, nodes.size)
        assertTrue("当前工作区（男性）仍然要出现", nodes.first().isCurrent)
        assertTrue("未记录组仍然排最后", nodes.last().unknown)
    }

    @Test
    fun `W5_尾斜杠算同一个目录_不许拆成两组`() {
        val nodes = tree(
            listOf(
                entry(1L, "带斜杠", now - hour, "/storage/emulated/0/Download/男性/"),
                entry(2L, "不带斜杠", now - 2 * hour, "/storage/emulated/0/Download/男性"),
            ),
        )
        assertEquals(2, nodes.size)   // 男性一组 + 默认工作区常显
        assertEquals(2, nodes.first().rows.size)
    }

    @Test
    fun `W5_组内按updated_at倒序_当前会话那一行认得出来`() {
        val nodes = tree(
            listOf(
                entry(1L, "早的", now - 3 * hour, "/storage/emulated/0/Download/男性"),
                entry(2L, "晚的", now - hour, "/storage/emulated/0/Download/男性"),
            ),
            currentSessionId = 1L,
        )
        assertEquals(listOf(2L, 1L), nodes.first().rows.map { it.id })
        assertTrue("点着的那条会话是高亮档", nodes.first().rows.first { it.id == 1L }.isCurrent)
        assertFalse("另一条不是", nodes.first().rows.first { it.id == 2L }.isCurrent)
    }

    @Test
    fun `W5_标题兜底沿用会话库那一道`() {
        val nodes = tree(listOf(entry(1L, "   ", now - hour, "/storage/emulated/0/Download/男性")))
        assertEquals("未命名会话", nodes.first().rows.single().title)
        // 复用同一个 displayTitle（第二十批那道），不许在树里再造一条兜底
        assertTrue("树文件调用的是会话库那个兜底", treeCode.contains("displayTitle("))
    }

    // ── W6：两处高亮强度不同 ──────────────────────────────────────────────

    @Test
    fun `W6_当前工作区与当前会话是两个不同的高亮档`() {
        val nodes = tree(
            listOf(entry(1L, "你好呀", now - minute, "/storage/emulated/0/Download/男性")),
            currentSessionId = 1L,
        )
        val node = nodes.first()
        val row = node.rows.first()
        assertEquals(Highlight.WORKSPACE_CURRENT, highlightOf(node))
        assertEquals(Highlight.SESSION_CURRENT, highlightOf(row))
        assertNotEquals(highlightOf(node), highlightOf(row))
        val other = DrawerWorkspaceNode("k", "别处", "k", false, WorkspaceState.NORMAL, false, emptyList())
        assertEquals(Highlight.NONE, highlightOf(other))
    }

    @Test
    fun `W6_落地用两套不同语义_底色只给工作区行_主色只给会话行`() {
        val ws = bodyOf(drawerCode, "private fun WorkspaceRow(")
        val se = bodyOf(drawerCode, "private fun SessionRow(")
        assertTrue(
            "工作区行＝整行铺 secondaryContainer（最强那一档）",
            ws.contains("background(MaterialTheme.colorScheme.secondaryContainer)"),
        )
        assertFalse("会话行不许也铺底色（那就分不出强度了）", se.contains("background("))
        // 主色那一句在源码里是 if/else 两块写的，断言按形状拆成两半查（合起来才是"只有点着才主色"）
        assertTrue("会话行的颜色看的是 current 这个档位", se.contains("color = if (current)"))
        assertTrue("会话行点着＝主色", se.contains("MaterialTheme.colorScheme.primary"))
        assertTrue("会话行没点着＝普通前景色", se.contains("MaterialTheme.colorScheme.onSurface"))
        assertTrue("会话行确实加了字重", se.contains("fontWeight = if (current) FontWeight.Medium"))
        // 判别器自证：两行共用底色的坏形状必须被抓到
        val bad = "private fun SessionRow() {\n Modifier.background(MaterialTheme.colorScheme.secondaryContainer)\n}"
        assertTrue("判别器得认得出\"会话行也铺底色\"", bodyOf(bad, "private fun SessionRow()").contains("background("))
    }

    // ── W7：单活跃门撞车先问人 ────────────────────────────────────────────

    @Test
    fun `W7_点别人的会话先出确认卡_撞门那一支一处stop都不许有`() {
        val body = bodyOf(controllerCode, "fun requestOpenSession(sid: Long) {")
        assertTrue("门空着才直接切", body.contains("if (!busy && !runningNow())"))
        assertTrue("撞门这一支立确认卡", body.contains("sessionSwitchAsk = SessionSwitchAsk("))
        assertTrue("快速路径排在出卡之前", body.indexOf("openSession(sid)") < body.indexOf("sessionSwitchAsk = SessionSwitchAsk("))
        // 判据最硬的一条：撞门时不许自己停（负例 3 就是把这一行改成 stop()）
        assertTrue("出卡之后到函数结束之间不许出现 stop()", body.substring(body.indexOf("sessionSwitchAsk = SessionSwitchAsk(")).contains("stop()").not())
    }

    @Test
    fun `W7_取消只收卡_什么都不做`() {
        val body = bodyOf(controllerCode, "fun dismissSessionSwitch() {")
        assertEquals("只有一行收卡", "sessionSwitchAsk = null", body.trim().removePrefix("{").removeSuffix("}").trim())
        assertFalse("取消不许停", body.contains("stop()"))
        assertFalse("取消也不许留一个待切换的尾巴", body.contains("pendingSwitchTo"))
    }

    @Test
    fun `W7_停掉并切过来只调既有stop_不新造停止状态`() {
        val body = bodyOf(controllerCode, "fun acceptSessionSwitch() {")
        assertTrue("必须走既有的 stop()（= engine.stop()）", body.contains("stop()"))
        assertFalse("不许直接摸引擎的停止标志", body.contains("stopRequested"))
        assertFalse("不许自己放门", body.contains("ActiveSessionGate"))
        assertTrue("先收卡再停（用户点了才算数）", body.indexOf("sessionSwitchAsk = null") < body.indexOf("stop()"))
    }

    @Test
    fun `W7_切换只认Settled_门没放就不硬切`() {
        val body = bodyOf(controllerCode, "private fun consumePendingSwitch() {")
        assertTrue("门还挂着就不切", body.contains("runningNow()"))
        assertTrue("兑现前先把待切尾巴清掉", body.contains("pendingSwitchTo = null"))
        assertTrue("切走用的是既有的 openSession", body.contains("openSession(target)"))
        val settled = bodyOf(controllerCode, "private fun onEngineEvent(ev: EngineEvent) {")
        assertTrue(
            "Settled 那一支兑现切换",
            Regex("if \\(force\\) \\{[\\s\\S]*?consumePendingSwitch\\(\\)").containsMatchIn(settled),
        )
        assertFalse("不许另起一个轮询／延时去猜门什么时候放", controllerCode.contains("delay(100"))
    }

    @Test
    fun `W7_壳层对门只读不写_ActiveSessionGate只许出现current`() {
        val members = Regex("ActiveSessionGate\\s*\\.\\s*([A-Za-z]+)").findAll(controllerCode)
            .map { it.groupValues[1] }.toList()
        assertTrue("控制器读了门", members.isNotEmpty())
        members.forEach { assertEquals("只许读 current()，抓到的是 $it", "current", it) }
        assertFalse("不许出现 release", controllerCode.contains("ActiveSessionGate.release"))
        assertFalse("不许出现 tryAcquire", controllerCode.contains("ActiveSessionGate.tryAcquire"))
    }

    @Test
    fun `W7_确认卡三个出口只有确认按钮才停`() {
        val card = bodyOf(screenCode, "controller.sessionSwitchAsk?.let { ask ->")
        assertTrue("确认按钮走 accept", card.contains("onClick = { controller.acceptSessionSwitch() }"))
        assertTrue("取消按钮走 dismiss", card.contains("onClick = { controller.dismissSessionSwitch() }"))
        assertTrue("点外面关掉也走 dismiss", card.contains("onDismissRequest = { controller.dismissSessionSwitch() }"))
        assertEquals("accept 只此一处", 1, Regex("acceptSessionSwitch\\(\\)").findAll(card).count())
        assertFalse("卡上不许直接摸引擎", card.contains("engine."))
    }

    @Test
    fun `W7_那句话里带的是两个会话的名字不是内部id`() {
        val card = bodyOf(screenCode, "controller.sessionSwitchAsk?.let { ask ->")
        assertTrue("用 runningTitle", card.contains("ask.runningTitle"))
        assertTrue("用 targetTitle", card.contains("ask.targetTitle"))
        assertFalse("不许把内部 id 印给用户", card.contains("ask.targetSessionId"))
        assertEquals("「%1\$s」还在跑。要停掉它，并切到「%2\$s」继续吗？", valueOf(zhRel, "baopu_switch_desc"))
        assertEquals("停掉并切过来", valueOf(zhRel, "baopu_switch_confirm"))
        // 标题从库里取、取不到兜底成「未命名会话」，不许拿 null 直接画
        val title = bodyOf(controllerCode, "private fun sessionTitleOf(sid: Long?): String")
        assertTrue("兜底走会话库那个键", title.contains("baopu_session_unnamed"))
        assertTrue("兜底走同一个 displayTitle", title.contains("displayTitle("))
    }

    // ── W8：中英对称 ＋ A2 段文案 ─────────────────────────────────────────

    @Test
    fun `W8_29批新键中英都在`() {
        val keys = listOf(
            "baopu_drawer_group_workspace",
            "baopu_drawer_ws_private",
            "baopu_drawer_ws_unauthorized",
            "baopu_drawer_ws_unknown",
            "baopu_drawer_time_just_now",
            "baopu_drawer_time_minutes",
            "baopu_drawer_time_hours",
            "baopu_drawer_time_days",
            "baopu_drawer_time_months",
            "baopu_switch_title",
            "baopu_switch_desc",
            "baopu_switch_confirm",
        )
        val zh = keySet(zhRel)
        val en = keySet(enRel)
        keys.forEach {
            assertTrue("$it 必须在 zh", zh.contains(it))
            assertTrue("$it 必须在 en", en.contains(it))
            assertTrue("$it 的 en 值不许空", valueOf(enRel, it).isNotBlank())
        }
        // 占位符两边都得有，否则切英文环境直接崩
        assertTrue("分钟档两边都带 %1\$d", valueOf(enRel, "baopu_drawer_time_minutes").contains("%1\$d"))
        assertTrue(
            "切换那句话两边都是两个 %s 占位",
            Regex("%1\\\$s").containsMatchIn(valueOf(enRel, "baopu_switch_desc")) &&
                Regex("%2\\\$s").containsMatchIn(valueOf(enRel, "baopu_switch_desc")),
        )
    }

    @Test
    fun `A2_两行desc按派工单改实`() {
        assertEquals("在当前工作区开一个新会话，记忆不动", valueOf(zhRel, "baopu_drawer_new_session_desc"))
        assertEquals("全部会话，平铺看", valueOf(zhRel, "baopu_drawer_history_desc"))
        // 英文那边不能还留着旧口径（"Clear this context"）
        assertFalse(valueOf(enRel, "baopu_drawer_new_session_desc").contains("Clear this context"))
    }

    // ── A 段其余硬要求（A6／A7／口径 1／口径 3）──────────────────────────

    @Test
    fun `A7_图标只用文字符号_不新增依赖也不自绘`() {
        assertFalse("不许为图标加依赖", raw("build.gradle.kts").contains("material-icons"))
        assertFalse("不许 VectorPainter", drawerCode.contains("vectorResource"))
        assertFalse("不许新 drawable", drawerCode.contains("R.drawable"))
    }

    @Test
    fun `A6_树不许有展开收起动画也不许做搜索`() {
        listOf("AnimatedVisibility", "animateFloat", "animateDp", "updateTransition", "Crossfade").forEach {
            assertFalse("抽屉不许出现动画形状：$it", drawerCode.contains(it))
        }
        assertFalse("抽屉不许有输入框（本批不做搜索）", drawerCode.contains("TextField"))
    }

    @Test
    fun `A6_小节标题右侧留给入口_A7_只用文字符号`() {
        // 2026-10-18 用户口径（照 DSH 左侧栏）：「工作区」标题右侧要能选文件夹。
        // 29 批当初写的是「本批一个控件都不放」——现在放开，但仍然**只许文字符号 ＋**，
        // 不许引图标库、不许自绘 glyph（A7 不变）。
        val group = bodyOf(drawerCode, "private fun DrawerGroup(title: String, trailing: (@Composable () -> Unit)? = null) {")
        assertTrue("标题右侧要能挂入口", group.contains("trailing?.invoke()"))
        assertTrue("工作区组要真挂上 ＋", drawerCode.contains("onAddWorkspace"))
        assertFalse("不许改成 IconButton/图标库", group.contains("IconButton"))
        assertFalse("不许自绘 glyph", group.contains("Canvas("))
    }

    @Test
    fun `A8_文件夹行右侧的圆里加号_只给真文件夹`() {
        // DSH：文件夹行右边一个「圆里加号」＝在这个文件夹里干活。
        // 默认工作区（历史未记录）不是真文件夹 ⇒ 不给这个按钮。
        assertTrue("要有这个按钮", drawerCode.contains("onRunHere = onWorkspaceRunHere"))
        assertTrue("圆形描边＋文字加号，不自绘", drawerCode.contains(".border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)"))
        assertTrue("加号是文字", drawerCode.contains("text = \"＋\""))
        // 2026-10-18 用户口径：默认工作区就是 Download/证道 那个真文件夹 ⇒ 它也有这个＋。
        assertTrue("默认工作区也有", drawerCode.contains("run {"))
        // 页面：当前工作区直接开；别的文件夹**必须先问人**（切工作区是全局动作）
        val runHere = bodyOf(screenCode, "onWorkspaceRunHere = { node ->")
        assertTrue("当前工作区直接开新会话", runHere.contains("controller.newSession()"))
        assertTrue("别的文件夹先挂起问人", runHere.contains("runHereNode = node"))
        assertFalse("不许在这一支里直接改工作区", runHere.contains("Workspace.setCustom"))
    }

    @Test
    fun `A9_切工作区要确认_并且留痕`() {
        val confirm = bodyOf(screenCode, "runHereNode?.let { node ->")
        assertTrue("确认后才切", confirm.contains("Workspace.setCustom(context, node.fullPath)"))
        assertTrue("内核项变更要留痕（26-B）", confirm.contains("RunLog.log(KernelAudit.workspace(after, before))"))
        assertTrue("切完才开新会话", confirm.contains("controller.newSession()"))
        assertTrue("失败要说", confirm.contains("baopu_switch_ws_failed"))
        // 选文件夹那条路同样：切 ＋ 留痕 ＋ 开新会话
        val picker = bodyOf(screenCode, "if (addWorkspaceOpen) {")
        assertTrue("选择器要接上", picker.contains("WorkspaceFolderPicker("))
        assertTrue("选中后要留痕", picker.contains("RunLog.log(KernelAudit.workspace(after, before))"))
    }

    @Test
    fun `A10_切工作区与加工作区的键中英对称`() {
        val zh = repoFile("src/main/res/values/strings.xml").readText()
        val en = repoFile("src/main/res/values-en/strings.xml").readText()
        listOf(
            "baopu_switch_ws_title", "baopu_switch_ws_body", "baopu_switch_ws_confirm",
            "baopu_switch_ws_cancel", "baopu_switch_ws_failed",
        ).forEach { key ->
            assertTrue("中文缺 $key", zh.contains("name=\"$key\""))
            assertTrue("英文缺 $key", en.contains("name=\"$key\""))
        }
    }

    @Test
    fun `口径1_低频入口一个都不许少_历史会话已移出抽屉`() {
        // 2026-10-18 用户口径改了：会话都归在各工作区下面 ⇒ 抽屉里**不再有**「历史会话」行。
        // 但那一页（含删除）不能因此丢掉，挪成低频入口：抱朴设置 → 会话记录。
        listOf(
            "baopu_new_session",
            "baopu_drawer_library",
            "baopu_extract_title",
            "baopu_backup_title",
            "baopu_channel_button",
            "baopu_drawer_settings",
        ).forEach { key ->
            assertTrue("抽屉少了入口：$key", drawerCode.contains("R.string.$key"))
        }
        listOf(
            "baopu_drawer_group_session",
            "baopu_drawer_group_library",
            "baopu_drawer_group_settings",
        ).forEach { assertTrue("少了分组标题：$it", drawerCode.contains("R.string.$it")) }
        assertTrue("工作区那一组是新加的", drawerCode.contains("baopu_drawer_group_workspace"))
    }

    @Test
    fun `口径3_点项先关抽屉再跳_会话行与文件夹行也不例外`() {
        listOf(
            "onNewSession = {",
            "onOpenSession = { sid ->",
            "onWorkspaceTap = { path ->",
            "onLibrary = {",
            "onExtract = {",
            "onBackup = {",
            "onChannel = {",
            "onSettings = {",
        ).forEach { sig ->
            assertTrue("$sig 必须先关抽屉", bodyOf(screenCode, sig).contains("drawerState.close()"))
        }
    }

    @Test
    fun `树数据只在开抽屉那一刻读_没开过就一条SELECT都不跑`() {
        val build = bodyOf(screenCode, "val drawerNodes = remember(")
        assertTrue("drawerTick==0 ⇒ 空树", build.contains("if (drawerTick == 0)"))
        assertTrue("读库只走会话库那一个入口", build.contains("controller.loadSessionLibrary()"))
        assertTrue(
            "开抽屉那一刻才 +1",
            bodyOf(screenCode, "LaunchedEffect(drawerState.isOpen)").contains("drawerTick++"),
        )
    }

    @Test
    fun `本批不许提前加预览入口_抽屉里不许出现那两个函数`() {
        // §85「明确不在本批」：预览两行等刀 4-B 落地后另接（那条门禁本体在 BaopuPreviewTest R8，
        // 这里只钉"29 批没顺手加"——两边各管各的，不重复咬同一件事）。
        val screen = bodyOf(screenCode, "val drawerNodes = remember(")
        assertFalse(screen.contains("previewLastReply"))
        assertFalse(drawerCode.contains("previewLastReply"))
        assertFalse(drawerCode.contains("clearPreviewCache"))
    }
    @Test
    fun `口径4_抽屉里没有历史会话行_平铺列表挪进设置页`() {
        // 用户 2026-10-18：「历史会话不应该再出现了，而是归到各个工作区里」
        assertFalse(
            "抽屉里不许再有「历史会话」这一行（会话已归到各工作区下面）",
            drawerCode.contains("R.string.baopu_drawer_history"),
        )
        assertFalse("抽屉的形参也不许再收 onHistorySessions", drawerCode.contains("onHistorySessions"))
        // 2026-10-18 用户口径再改：设置页那张「会话记录」卡也删了（一条条删太累），
        val settings = repoFile("src/main/java/com/example/zhengdao/ui/baopu/BaopuSettingsScreen.kt").readText()
        // 那张卡已删：删除改到抽屉里长按会话行做（下一步），设置页不再重复列一遍
        assertFalse("设置页不该再有会话记录卡", settings.contains("R.string.baopu_session_manage_card"))
        assertTrue("设置页要能打开会话库", settings.contains("onOpenSessionLibrary"))
        val screen = screenCode
        assertTrue(
            "设置页的入口要真的把会话库打开（先关设置页，别两个 Dialog 叠着）",
            screen.contains("onOpenSessionLibrary = { showSettings = false; showSessionLibrary = true }"),
        )
    }

    @Test
    fun `口径5_默认工作区空着也要显示`() {
        // 照 DSH 左侧栏：默认工作区是历史会话的收容所，空着也占一行，别一会儿有一会儿没有
        val nodes = tree(listOf(entry(1L, "当前工作区里的一条", now - hour, "/storage/emulated/0/Download/男性")))
        assertTrue("最后一行必须是默认工作区", nodes.last().unknown)
        assertEquals("默认工作区", nodes.last().label)
        assertTrue("它下面可以一条会话都没有", nodes.last().rows.isEmpty())
    }
}
