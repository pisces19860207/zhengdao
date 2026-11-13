// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui.browser

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第六百二十三批 · 玄览教学胶囊（浮层）门禁 —— 定稿 §5 的 ①–⑪ ＋ 230-A2 四条，逐条对。
 *
 * 为什么是源码级断言而不是 Compose 测试：本仓 JVM 单测起不来 Compose + WebView
 * （见 [BrowserFirstFrameEngineGateTest] 的同一句说明），`ui-test-junit4` 只在 `androidTest`
 * 里跑、要设备。所以这里的判据一律「剥注释后的真源码」+ 纯函数，**并配负例变异**：
 * 判据若恒真，变异必然不红，本文件就自己红给自己看。
 *
 * 纯函数那几条（三态判据、归一化夹紧、半屏高度上限、引用标记）是**真调用**，不是比字面量。
 */
class XuanlanCapsuleGateTest {

    private fun repoFile(rel: String): File =
        listOf(File(rel), File("../$rel"), File("app/$rel"))
            .firstOrNull { it.exists() }
            ?: throw AssertionError("找不到 $rel")

    /** 剥注释（先块、再整行）：判据只读代码，KDoc 里写着判据词不许被当成实现。 */
    private fun code(rel: String): String = stripComments(repoFile(rel).readText())

    private fun stripComments(src: String): String = src.replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private fun block(src: String, header: String): String? {
        val at = src.indexOf(header)
        if (at < 0) return null
        val open = src.indexOf('{', at)
        if (open < 0) return null
        var depth = 0
        for (i in open until src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(open, i + 1)
                }
            }
        }
        return null
    }

    private val capsuleRel = "app/src/main/java/com/example/zhengdao/ui/browser/XuanlanCapsule.kt"
    private val browserRel = "app/src/main/java/com/example/zhengdao/ui/browser/BrowserScreen.kt"
    private val baopuRel = "app/src/main/java/com/example/zhengdao/ui/baopu/BaopuScreen.kt"
    private val mainRel = "app/src/main/java/com/example/zhengdao/MainActivity.kt"

    // ── 判据①②⑨⑪：组合树与 insets（三条最容易翻车的，逐个带负例） ────────────

    /** 判据②：`imePadding()` 全文件**恰好一处**。 */
    private fun imePaddingCount(src: String): Int =
        Regex("imePadding\\(\\)").findAll(src).count()

    /** 判据②：玄览根一个都不加（否则底栏被顶飞＝第二条避让链）。 */
    private fun browserRootHasNoIme(src: String): Boolean = !src.contains("imePadding")

    /** 判据⑨：浮层只在 `if (teaching)` 块内被组合（非教学不进树，不是 GONE）。 */
    private fun capsuleOnlyUnderTeaching(browser: String): Boolean {
        val body = block(browser, "if (teaching)")
        return body != null && body.contains("XuanlanCapsuleLayer(") && !browser.contains("visibility")
    }

    /** 判据⑪：教学结束（recording=false 且 draft=null）⇒ 展开态复位，胶囊与半屏一起消失。 */
    private fun endsWithTeaching(browser: String): Boolean {
        val body = block(browser, "LaunchedEffect(teaching)")
        return body != null && body.contains("if (!teaching) capsuleExpanded = false")
    }

    @Test
    fun `判据2_imePadding只在半屏根加一次`() {
        assertEquals("半屏根只能有一份 imePadding（双份＝底栏被顶飞）", 1, imePaddingCount(code(capsuleRel)))
        assertTrue("玄览根不许加 imePadding", browserRootHasNoIme(code(browserRel)))
        // 判据②的另一半：页面区那颗 AndroidView 仍是**唯一**一颗（浮层不重建 WebView ⇒
        // 收起后网页滚动位置天然不变）。
        assertEquals(
            "页面区只许挂一颗 AndroidView（浮层不许另挂）",
            1,
            Regex("AndroidView\\(").findAll(code(browserRel)).count(),
        )
        // 半屏根＝胶囊文件里那唯一一处；它必须挂在 CapsuleSheet 的根 Box 上
        val sheet = block(code(capsuleRel), "private fun CapsuleSheet(")
        assertNotNull(sheet)
        assertTrue("imePadding 必须在半屏根", sheet!!.contains("imePadding()"))
    }

    @Test
    fun `判据2负例_再加一份imePadding必红`() {
        val src = code(capsuleRel)
        assertEquals(1, imePaddingCount(src))
        val mutated = src.replaceFirst("imePadding()", "imePadding()\n            .imePadding()")
        assertEquals("第二份必须被抓住", 2, imePaddingCount(mutated))
    }

    @Test
    fun `判据2负例_玄览根加imePadding必红`() {
        val src = code(browserRel)
        assertTrue(browserRootHasNoIme(src))
        assertFalse(browserRootHasNoIme(src.replaceFirst("Box(modifier = modifier.fillMaxSize())", "Box(modifier = modifier.fillMaxSize()).imePadding()")))
    }

    @Test
    fun `判据9_非教学模式不进组合树`() {
        assertTrue("浮层必须在 if (teaching) 块内", capsuleOnlyUnderTeaching(code(browserRel)))
        assertFalse(
            "教学门禁被抹掉必须红",
            capsuleOnlyUnderTeaching(code(browserRel).replace("if (teaching) {", "if (true) {")),
        )
    }

    @Test
    fun `判据2_返回键先收半屏_三条enabled互斥`() {
        val src = code(browserRel)
        val capsule = src.indexOf("BackHandler(enabled = teaching && capsuleExpanded && !addressEditing)")
        val edit = src.indexOf("BackHandler(enabled = addressEditing)")
        val history = src.indexOf("BackHandler(enabled = !addressEditing && !(teaching && capsuleExpanded)")
        assertTrue("半屏那条必须存在", capsule >= 0)
        assertTrue("编辑态那条必须存在", edit >= 0)
        assertTrue("历史栈那条必须存在", history >= 0)
        assertTrue("半屏那条登记在另两条之前（定稿 §3.3）", capsule < edit && edit < history)
        assertTrue("半屏那条必须排掉编辑态（互斥）", src.substring(capsule, edit).contains("!addressEditing"))
    }

    @Test
    fun `判据11_结束教学浮层一起消失`() {
        assertTrue("teaching 的合成口径必须只有 recording / draft", endsWithTeaching(code(browserRel)))
        assertTrue(
            "teaching 的定义必须同时看录制中与待确认",
            code(browserRel).contains("val teaching = teachRecording || teachDraft != null"),
        )
        assertFalse(
            "复位那一句被删必须红",
            endsWithTeaching(code(browserRel).replace("if (!teaching) capsuleExpanded = false", "Unit")),
        )
    }

    // ── 判据③⑦：同一 controller、不建第二份存储 ──────────────────────────────

    /** 全仓 `BaopuChatController(` 构造点（排除类声明那一行）。 */
    private fun controllerSites(): List<String> {
        val root = repoFile("app/src/main/java/com/example/zhengdao")
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { f ->
                stripComments(f.readText()).lines()
                    .filter { it.contains("BaopuChatController(") && !it.contains("internal class") }
                    .map { "${f.name}: ${it.trim()}" }
            }
            .toList()
    }

    @Test
    fun `判据3_全仓只有一个controller构造点_且两页吃同一个变量`() {
        val sites = controllerSites()
        assertEquals("构造点只剩宿主一处，否则就是两份 messages/busy：\n${sites.joinToString("\n")}", 1, sites.size)
        assertTrue("那一处必须在 MainActivity", sites.first().startsWith("MainActivity.kt"))
        assertTrue("玄览必须接参，不许自己 new", code(browserRel).contains("controller: BaopuChatController"))
        assertTrue("抱朴必须接参，不许自己 new", code(baopuRel).contains("controller: BaopuChatController"))
        val main = code(mainRel)
        assertTrue(main.contains("BrowserScreen(\n                    controller = chatController"))
        assertTrue(main.contains("BaopuScreen(\n                    controller = chatController"))
    }

    @Test
    fun `判据3负例_玄览自己new控制器必红`() {
        assertEquals(1, controllerSites().size)
        val fake = "app/src/main/java/com/example/zhengdao/ui/browser/XuanlanCapsule.kt"
        // 只做内存变异：把「自己 new」写进被扫的行里，构造点数必须变成 2
        val lines = stripComments(repoFile(fake).readText()).lines() +
            "val c = BaopuChatController(context)"
        val count = lines.count { it.contains("BaopuChatController(") && !it.contains("internal class") }
        assertEquals("自造控制器必须被数出来", 1, count)
    }

    @Test
    fun `判据7_无新DB表_无新jsonl_只有一个位置SP`() {
        val src = code(capsuleRel)
        listOf("BaopuDb(", "SQLiteOpenHelper(", ".jsonl", "File(", "openOrCreateDatabase").forEach {
            assertFalse("浮层不许碰存储：$it", src.contains(it))
        }
        assertEquals(
            "只允许一个 SP 文件",
            1,
            Regex("getSharedPreferences\\(").findAll(src).count(),
        )
        assertTrue(src.contains("const val FILE = \"xuanlan_capsule_pos\""))
        // 2 float + 1 bool，一个不多
        assertEquals(2, Regex("putFloat\\(").findAll(src).count())
        assertEquals(1, Regex("putBoolean\\(").findAll(src).count())
        assertEquals(2, Regex("getFloat\\(").findAll(src).count())
        assertEquals(1, Regex("getBoolean\\(").findAll(src).count())
    }

    @Test
    fun `判据7负例_浮层里建第二份存储必红`() {
        val src = code(capsuleRel)
        val mutated = src + "\nprivate fun leaky() = BaopuDb(context)\n"
        assertTrue("加一行建库必须被抓住", mutated.contains("BaopuDb("))
        assertFalse("真源码必须干净", src.contains("BaopuDb("))
    }

    // ── 判据⑥⑩：三态与红点 ────────────────────────────────────────────────

    @Test
    fun `判据10_三态判据是闭集_优先级待确认高于正在想`() {
        assertEquals(XuanlanCapsuleState.IDLE, capsuleStateOf(busy = false, draftPending = false))
        assertEquals(XuanlanCapsuleState.THINKING, capsuleStateOf(busy = true, draftPending = false))
        assertEquals(XuanlanCapsuleState.NEEDS_DECISION, capsuleStateOf(busy = false, draftPending = true))
        assertEquals(
            "同时有待确认与正在想 ⇒ 待确认优先",
            XuanlanCapsuleState.NEEDS_DECISION,
            capsuleStateOf(busy = true, draftPending = true),
        )
        assertEquals("闭集只有三态", 3, XuanlanCapsuleState.entries.size)
    }

    @Test
    fun `判据6_红点不读不改decisionBadge_三态只看busy与draft`() {
        val src = code(capsuleRel)
        val stateFn = src.substringAfter("internal fun capsuleStateOf(").substringBefore("\n}")
        assertFalse("三态判据里不许出现 decisionBadge", stateFn.contains("decisionBadge"))
        assertTrue("三态判据只看 busy", stateFn.contains("busy"))
        assertTrue("三态判据只看 draftPending", stateFn.contains("draftPending"))
        // 整个文件里 decisionBadge 只允许出现在那行出口的**唯一调用点**（DecisionExitRow(badge = ...)）
        val hits = src.lines().filter { it.contains("decisionBadge") }
        assertEquals("decisionBadge 只许一处（那行可选出口的实参）：$hits", 1, hits.size)
        assertTrue("那一处必须是 DecisionExitRow 的实参", hits.first().contains("badge = controller.decisionBadge"))
        // 红点是点不是数：8dp 圆点、不带数字
        assertTrue(src.contains("val DOT = 8f"))
        assertTrue(src.contains("MaterialTheme.colorScheme.error, CircleShape"))
        assertFalse("红点不许带数字", src.contains("?N"))
    }

    // ── 判据①④⑤：几何、引用、拖动持久化（纯函数真跑） ─────────────────────────

    @Test
    fun `判据1_三档分辨率下胶囊都夹在安全区内`() {
        val cases = listOf(
            // widthDp, heightDp（内容区，已扣系统栏）
            360f to 640f,
            411f to 780f,
            780f to 360f,
        )
        for ((w, h) in cases) {
            for (x in listOf(-999f, 0f, w / 2f, w * 2f)) {
                val left = CapsuleGeometry.clampLeft(x, w)
                assertTrue("左不越 ${CapsuleGeometry.MARGIN}", left >= CapsuleGeometry.MARGIN)
                assertTrue("右不越 ${CapsuleGeometry.MARGIN}", left + CapsuleGeometry.CAPSULE <= w - CapsuleGeometry.MARGIN + 0.01f)
            }
            for (y in listOf(-999f, 0f, h / 2f, h * 2f)) {
                val top = CapsuleGeometry.clampTop(y, h)
                assertTrue("上不越标签条 ${CapsuleGeometry.TOP_SAFE}", top >= CapsuleGeometry.TOP_SAFE)
                val bottom = top + CapsuleGeometry.CAPSULE
                assertTrue("下不入侵底栏 56＋8：$w x $h", bottom <= h - CapsuleGeometry.BAR - CapsuleGeometry.BAR_GAP + 0.01f)
            }
        }
    }

    @Test
    fun `判据5_归一化往返不越界_拖动后重启读回仍在安全区`() {
        val cases = listOf(1280f / 3.5f to 2848f / 3.5f, 1080f / 2.75f to 2400f / 2.75f, 780f to 360f)
        for ((w, h) in cases) {
            val left = CapsuleGeometry.defaultLeft(w)
            val top = CapsuleGeometry.defaultTop(h)
            val nx = CapsuleGeometry.normalize(left, w)
            val ny = CapsuleGeometry.normalize(top, h)
            assertTrue("归一化落在 [0,1]：$nx", nx in 0f..1f)
            assertTrue("归一化落在 [0,1]：$ny", ny in 0f..1f)
            val backLeft = CapsuleGeometry.clampLeft(CapsuleGeometry.denormalize(nx, w), w)
            val backTop = CapsuleGeometry.clampTop(CapsuleGeometry.denormalize(ny, h), h)
            assertEquals("往返误差必须可忽略", left, backLeft, 0.01f)
            assertEquals("往返误差必须可忽略", top, backTop, 0.01f)
        }
        // 越界写入的脏值也必须被夹回（重启读回不越安全区）
        assertEquals(CapsuleGeometry.MARGIN, CapsuleGeometry.clampLeft(CapsuleGeometry.denormalize(-5f, 360f), 360f), 0.01f)
        assertEquals(1f, CapsuleGeometry.normalize(9999f, 360f), 0.001f)
    }

    @Test
    fun `判据2_半屏高度上限是40%与可视高减8取小`() {
        // 竖屏无键盘：可用 760 ⇒ 40% = 304
        assertEquals(304f, CapsuleGeometry.panelCap(760f, 760f, 64f), 0.01f)
        // 键盘弹起：可视 460 ⇒ 460-8 = 452 仍大于 304 ⇒ 取 304
        assertEquals(304f, CapsuleGeometry.panelCap(760f, 460f, 8f), 0.01f)
        // 极小窗：可视 180 ⇒ 180-8 = 172 < 40% ⇒ 取 172（随后因 <168 触发自动收起）
        assertEquals(172f, CapsuleGeometry.panelCap(760f, 180f, 8f), 0.01f)
        assertTrue("可视高 <168 的条件必须落在 SHEET_MIN_LANDSCAPE", 180f - 8f - 8f < CapsuleGeometry.SHEET_MIN_LANDSCAPE)
    }

    @Test
    fun `判据4_引用标记只在开着且有页时带上_关掉不带`() {
        assertEquals("[当前页: https://a.example/x]", citeTagOf("https://a.example/x"))
        assertNull("关掉引用 ⇒ 不带标记", citeTagFor(page = null, citeOn = true))
        val page = BrowserTabState().apply { url = "https://a.example/x" }
        assertEquals("[当前页: https://a.example/x]", citeTagFor(page = page, citeOn = true))
        assertNull("关掉引用 ⇒ 不带标记", citeTagFor(page = page, citeOn = false))
        val blank = BrowserTabState()
        assertNull("没有地址就不伪造", citeTagFor(page = blank, citeOn = true))
        // 发送路径：拼在前缀后面，走同一个 send（不另开通道）
        assertEquals(
            "[当前页: https://a.example/x]\n聊一句",
            outgoingTextOf("  聊一句  ", page, citeOn = true),
        )
        assertEquals("聊一句", outgoingTextOf("聊一句", page, citeOn = false))
        assertNull("空话不发", outgoingTextOf("   ", page, citeOn = true))
        assertEquals("没页面就不带标记，原样发", "话", outgoingTextOf("话", null, citeOn = true))
        val src = code(capsuleRel)
        assertTrue("发送只走 controller.send", src.contains("controller.send(outgoing)"))
    }

    // ── 形态与红线（230-A2 四条 ＋ 判据⑫） ───────────────────────────────────

    @Test
    fun `判据2_叠层要真压住页面_点遮罩才收得起来`() {
        // 真机实证（2026-11-13）：Compose 的浮层压不住 AndroidView 的手势 ——
        // 点遮罩会穿透到网页（页面里的视频当场开播，半屏没收起）。所以展开期间必须
        // 把活跃 WebView 的触摸关掉，收起/离开组合时原样还原。
        val src = code(capsuleRel)
        assertTrue("展开时关掉活跃 WebView 的触摸", src.contains("webView?.isEnabled = false"))
        assertTrue("收起/离开组合必须还原", src.contains("onDispose { if (expanded) webView?.isEnabled = true }"))
        assertTrue("遮罩点击收起仍在", src.contains("detectTapGestures { onExpandedChange(false) }"))
    }

    @Test
    fun `230A2_半屏是叠层不是Dialog_圆角走令牌`() {
        val src = code(capsuleRel)
        assertFalse("禁止 Dialog（会改 inset 行为）", src.contains("Dialog("))
        assertFalse("圆角不许写裸值 24.dp（check-radius-tokens 会红）", src.contains("RoundedCornerShape(24.dp)"))
        assertTrue("圆角走 rSheet", src.contains("RoundedCornerShape(topStart = rSheet, topEnd = rSheet)"))
        assertTrue("胶囊是正圆", src.contains("shape = CircleShape"))
        assertFalse("不新增第五档容器", src.contains("BaopuInfoContainer(") || src.contains("BaopuRecordContainer("))
        assertTrue("教学确认卡复用既有行动卡", src.contains("BaopuActionContainer("))
    }

    @Test
    fun `判据12_回放步号与橙点不实现`() {
        val src = code(capsuleRel)
        assertFalse("不许出现步号读数（当前无回放状态源）", src.contains("teachStepCount"))
        assertFalse("不许出现回放播放符", src.contains("▶"))
        assertFalse("橙点 ?N 归 624", src.contains("colorScheme.tertiary"))
        // 红点态只有一颗胶囊：同时刻不出现第二颗（只有一个 CapsuleButton 调用点）
        assertEquals(
            1,
            src.lines().count { it.trimStart().startsWith("CapsuleButton(") },
        )
    }

    @Test
    fun `230A2_遮罩不盖底栏_底栏仍可点`() {
        val src = code(capsuleRel)
        assertTrue("遮罩要留出底栏 56dp", src.contains(".padding(bottom = CapsuleGeometry.BAR.dp)"))
        assertTrue(src.contains("Color(0x52000000)"))
        assertTrue("点遮罩收起", src.contains("detectTapGestures { onExpandedChange(false) }"))
    }

    @Test
    fun `判据2_键盘判断不写死dp也不取WindowInsets_ime`() {
        val src = code(capsuleRel)
        assertFalse("不许取 Compose 的 WindowInsets.ime 扩展", src.contains("WindowInsets.ime"))
        assertFalse("不许写死键盘高度", src.contains("300.dp"))
        assertTrue("靠可视高变化判断键盘", src.contains("val keyboardUp = visibleH < availableH - 1f"))
        assertTrue("太低自动收起", src.contains("CapsuleGeometry.SHEET_MIN_LANDSCAPE"))
    }
}
