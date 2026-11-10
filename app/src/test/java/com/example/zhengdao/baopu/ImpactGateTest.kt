// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百二十批 · 源码级门禁（派工单 §2.5／§4 的负例，凡是"运行期测不出来"的，
// 都在这里从**源码形态**上钉死）。
//
// 为什么要有这一层：本批的红线大多是否定式（"不写盘""不多弹卡""不加第二行"），
// 而否定式判据用行为测试写不干净——`write_file` 压根不会被调用时，测试永远是绿的。
// 所以这几条从 main 源码里读原文断言（**只扫 src/main，不扫本文件自己**）。
//
// 第五百五十七批（⑤去脆化）：字面整句／标识符计数 → 结构断言，保护意图不变——
// · 显示通路：影响行只许喂给唯一一处 BaopuConfirmCard 调用（调用点唯一性是稳定 API 计数，保留）；
// · 状态字段：不钉名字，从「写回 (callId, 行)」的形状 capture，再查声明/按卡读取/置空三触点；
// · 开关早退：queueImpactPreview 函数体内，开关 return 必须先于 launch（关掉＝连算都不算）；
// · 失败降级：preview 调用必须落在某个 runCatching{…} 的括号体内（ containment，不钉整句）。
// 扫描统一走 CodeText（剥注释/字符串），合法重构（改名/抽函数/换参序）不再误伤。
package com.example.zhengdao.baopu

import com.example.zhengdao.CodeText
import com.example.zhengdao.GateBodies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets

class ImpactGateTest {

    /**
     * 读一份 main 源码。
     *
     * 两种工作目录都试（`app/` 与仓库根）：AGP 跑 JVM 单测时 cwd 是模块目录，但
     * `DeadCode27GuardTest` 那种从根跑的用法在本仓也存在——两条都支持，免得门禁
     * 因为"在哪儿跑"而假绿。
     */
    private fun src(rel: String): String {
        val candidates = listOf(File("src/main/java/com/example/zhengdao/$rel"), File("app/src/main/java/com/example/zhengdao/$rel"))
        val f = candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("找不到 $rel（试过：${candidates.joinToString()}）；门禁失效比报红更糟")
        return f.readText(StandardCharsets.UTF_8)
    }

    private fun res(rel: String): File {
        val candidates = listOf(File("src/main/res/$rel"), File("app/src/main/res/$rel"))
        return candidates.firstOrNull { it.isFile } ?: File(candidates.first().path)
    }

    /** 代码面（注释/字符串已清空）：本文件的断言全是代码形状，不需要字符串内容。 */
    private fun code(rel: String): String = CodeText.codeOnly(src(rel))

    /** 预览入口的函数体（括号配对定位；改可见性/抽中间层都不影响切片）。 */
    private fun previewBody(): String {
        val body = GateBodies.functionBody(src("ui/baopu/BaopuChatController.kt"), "fun queueImpactPreview(")
        assertTrue("预览入口函数不在了？", body != null)
        return body!!
    }

    // ── 负例 1：预览产生任何写盘 / 弹卡 / 网络 ──────────────────────────
    //
    // 白名单只有"读"：stat/list/query/execJs。写口、删口、网络口一个都不许出现。

    @Test
    fun `负例1_预览三件套只读_没有写盘删除网络与裸线程`() {
        val files = listOf(
            "baopu/ImpactModel.kt", "baopu/ImpactPreview.kt", "baopu/ImpactGovernance.kt",
            "baopu/ImpactLine.kt", "baopu/ImpactWiring.kt", "baopu/ImpactFacts.kt",
        )
        val forbidden = listOf(
            "writeText(", "appendText(", "writeBytes(", "mkdirs(", "createNewFile(",
            "deleteRecursively(", ".delete(", "renameTo(", "setLastModified(",
            "FileOutputStream", "RandomAccessFile", "openConnection(", "HttpURLConnection",
            "Thread(", "thread {", "java.net.Socket",
        )
        for (f in files) {
            val text = code(f)
            val hits = forbidden.filter { it in text }
            assertTrue("$f 里出现了不该有的写盘/网络/线程字面：$hits", hits.isEmpty())
        }
    }

    /** 自检：扫描器不是恒真（喂一段真写盘代码，过同一条 CodeText 管线，必须抓出来）。 */
    @Test
    fun `负例1自检_扫描器能咬住写盘与线程`() {
        val bad = "file.writeText(\"x\")\nval t = Thread({ work() }, \"zd-x\")\n"
        val forbidden = listOf("writeText(", "Thread(")
        val hits = forbidden.filter { it in CodeText.codeOnly(bad) }
        assertEquals("扫描器对真违规必须报红", 2, hits.size)
    }

    // ── 负例「无卡场景 0 显示」──────────────────────────────────────────
    //
    // 显示通路只有一条：`BaopuScreen` 的 CONFIRM 分支。别的地方出现
    // `confirmImpactLine` 就等于"在别的地方也显示了"。

    @Test
    fun `判据_显示通路只有确认卡一处`() {
        val screen = code("ui/baopu/BaopuScreen.kt")
        // 稳定 API 调用点唯一性（保留计数）：屏幕上只许一处 BaopuConfirmCard 调用。
        val marker = "BaopuConfirmCard("
        val regions = mutableListOf<IntRange>()
        var from = 0
        while (true) {
            val at = screen.indexOf(marker, from)
            if (at < 0) break
            val end = GateBodies.balancedEnd(screen, at + marker.length - 1)
            regions += at until end
            from = end
        }
        assertEquals("确认卡调用只许一处", 1, regions.size)
        // 影响行的每一次读取都必须落在确认卡调用的实参段里（第二条显示通路 ⇒ 红）。
        val uses = Regex("""\.confirmImpactLine\b""").findAll(screen).map { it.range.first }.toList()
        assertTrue("确认卡必须拿到影响行（显示通路断了）", uses.isNotEmpty())
        assertTrue("影响行只许喂给确认卡", uses.all { at -> regions.any { at in it } })
    }

    @Test
    fun `判据_卡面只多一行_不新增任何卡片`() {
        val card = src("ui/baopu/BaopuConfirmCard.kt")
        // 不钉 named-arg 形状：Text 实参段里含 impactLine 的恰一处（改名/改 positional 不误伤）。
        val renders = GateBodies.callArgs(card, "Text(").filter { it.contains("impactLine") }
        assertEquals("impactLine 只许被一个 Text 渲染", 1, renders.size)
        // 本批不许在卡里加第二个容器/对话框（卡片本身就是容器；再加就是"多一张卡"）
        assertEquals("不许新增 Dialog", 0, countOf(code("ui/baopu/BaopuConfirmCard.kt"), "Dialog("))
        assertEquals("不许新增 AlertDialog", 0, countOf(code("ui/baopu/BaopuConfirmCard.kt"), "AlertDialog("))
    }

    // ── 负例「还没卡就先弹提示」────────────────────────────────────────
    //
    // 预览的**唯一**入口在 `ConfirmRequest` 那个分支里（先 `confirmCard = ev`，再算）。
    // 入口多一个，就意味着有别的时机会出现"没卡却先算/先显示"。

    @Test
    fun `负例_预览只从出卡那一个分支进来`() {
        val ctl = code("ui/baopu/BaopuChatController.kt")
        assertEquals("queueImpactPreview 只许一处声明", 1, Regex("""fun\s+queueImpactPreview\s*\(""").findAll(ctl).count())
        assertEquals(
            "queueImpactPreview 只许一处调用（入口多一个＝别的时机也会先算）",
            1,
            Regex("""(?<!fun )queueImpactPreview\s*\(""").findAll(ctl).count(),
        )
        // 状态字段不钉名字：从写回行「X = req.callId to line」的形状 capture，
        // 再查它的另外三个触点（mutableStateOf 声明／按卡 callId 读取／置空）都在。
        val write = Regex("""(?m)^\s*(\w+)\s*=\s*\w+\.callId\s+to\s+\w+\s*$""").find(CodeText.codeOnly(previewBody()))
        assertTrue("预览算完没有写回（卡Id, 行）状态？", write != null)
        val prop = Regex.escape(write!!.groupValues[1])
        assertTrue("状态字段必须是可观察的 mutableStateOf", Regex("""var\s+$prop\s+by\s+mutableStateOf""").containsMatchIn(ctl))
        assertTrue("读取必须按当前卡 callId 对齐（takeIf first）", Regex("""$prop\?\.takeIf\s*\{[^}]*\.first\s*==""").containsMatchIn(ctl))
        assertTrue("卡清空时状态必须置空", Regex("""$prop\s*=\s*null""").containsMatchIn(ctl))
    }

    // ── 负例「关掉开关仍有提示」─────────────────────────────────────────
    //
    // 两处都要看：控制器入口必须早退（关掉＝连算都不算），设置页必须写同一个键。

    @Test
    fun `负例_关掉开关必须连算都不算`() {
        val body = CodeText.codeOnly(previewBody())
        val gate = Regex("""if\s*\(\s*!\s*(?:[\w.]+\.)?ImpactPrefs\.get\s*\([^)]*\)\s*\)\s*return""").find(body)
        assertTrue("预览入口必须先过开关早退", gate != null)
        val launch = Regex("""\blaunch\s*\{""").find(body)
        assertTrue("预览的后台 launch 不在了？", launch != null)
        assertTrue("开关早退必须先于 launch（关掉＝连算都不算）", gate!!.range.first < launch!!.range.first)
        val prefs = code("baopu/ImpactWiring.kt")
        assertTrue("默认值必须是 true", Regex("""getBoolean\(\s*KEY_SHOW\s*,\s*true\s*,?\s*\)""").containsMatchIn(prefs))
    }

    @Test
    fun `判据_设置页开关写的是同一个键`() {
        val settings = code("ui/baopu/BaopuSettingsScreen.kt")
        assertTrue("设置页必须走 ImpactPrefs.set", Regex("""ImpactPrefs\.set\s*\(""").containsMatchIn(settings))
        assertTrue("中英文案必须成对存在", res("values/strings_baopu_impact.xml").isFile)
        assertTrue("中英文案必须成对存在", res("values-en/strings_baopu_impact.xml").isFile)
    }

    // ── 负例 3：R0 只读不注入（且入队点只有一个）────────────────────────

    @Test
    fun `负例3_注入队列只有一处入口`() {
        // 稳定 API 调用点唯一性（保留计数，只剥注释/字符串）。
        assertEquals("ImpactMailbox.offer 只许在预览那条路径里出现一次", 1, countOf(code("ui/baopu/BaopuChatController.kt"), "ImpactMailbox.offer("))
        assertEquals("引擎侧只许一个取走点（取走即清，不许第二处）", 1, countOf(code("baopu/agent/RunEngine.kt"), "ImpactMailbox.drain()"))
    }

    // ── 负例 4：原件区判据复用 PathGuard（不许自己再写一遍路径归一）─────

    @Test
    fun `负例4_原件区复用PathGuard`() {
        val text = code("baopu/ImpactPreview.kt")
        assertTrue("必须调 PathGuard.isInside", text.contains("PathGuard.isInside("))
        assertTrue("不许自己 canonicalize 另立一套", !text.contains("canonicalFile"))
    }

    // ── 负例 7：预览失败不许拖累动作（控制器里必须 runCatching）──────────

    @Test
    fun `负例7_预览异常不影响动作`() {
        val body = CodeText.codeOnly(previewBody())
        val previewAt = body.indexOf("ImpactPreview.preview(")
        assertTrue("预览调用不在了？", previewAt >= 0)
        // containment：preview 调用必须落在某个 runCatching{…} 的括号体内（不钉整句/变量名）。
        val guarded = Regex("""runCatching\s*\{""").findAll(body).any { m ->
            previewAt in (m.range.last + 1) until GateBodies.balancedEnd(body, m.range.last)
        }
        assertTrue("预览必须包在 runCatching 里（崩了不许拖累动作）", guarded)
    }

    /** 子串出现次数（别用正则：这些字面里全是点和括号）。 */
    private fun countOf(text: String, needle: String): Int {
        var i = 0
        var n = 0
        while (true) {
            val at = text.indexOf(needle, i)
            if (at < 0) return n
            n++
            i = at + needle.length
        }
    }
}
