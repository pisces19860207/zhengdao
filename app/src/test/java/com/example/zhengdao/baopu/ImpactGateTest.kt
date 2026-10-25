// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百二十批 · 源码级门禁（派工单 §2.5／§4 的负例，凡是"运行期测不出来"的，
// 都在这里从**源码形态**上钉死）。
//
// 为什么要有这一层：本批的红线大多是否定式（"不写盘""不多弹卡""不加第二行"），
// 而否定式判据用行为测试写不干净——`write_file` 压根不会被调用时，测试永远是绿的。
// 所以这几条从 main 源码里读原文断言（**只扫 src/main，不扫本文件自己**）。
package com.example.zhengdao.baopu

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
            val text = src(f)
            val hits = forbidden.filter { it in text }
            assertTrue("$f 里出现了不该有的写盘/网络/线程字面：$hits", hits.isEmpty())
        }
    }

    /** 自检：扫描器不是恒真（喂一段真写盘代码，必须抓出来）。 */
    @Test
    fun `负例1自检_扫描器能咬住写盘与线程`() {
        val bad = "file.writeText(\"x\")\nval t = Thread({ work() }, \"zd-x\")\n"
        val forbidden = listOf("writeText(", "Thread(")
        val hits = forbidden.filter { it in bad }
        assertEquals("扫描器对真违规必须报红", 2, hits.size)
    }

    // ── 负例「无卡场景 0 显示」──────────────────────────────────────────
    //
    // 显示通路只有一条：`BaopuScreen` 的 CONFIRM 分支。别的地方出现
    // `confirmImpactLine` 就等于"在别的地方也显示了"。

    @Test
    fun `判据_显示通路只有确认卡一处`() {
        val screen = src("ui/baopu/BaopuScreen.kt")
        assertEquals("confirmImpactLine 只许出现一次（确认卡那一条）", 1, countOf(screen, "confirmImpactLine"))
    }

    @Test
    fun `判据_卡面只多一行_不新增任何卡片`() {
        val card = src("ui/baopu/BaopuConfirmCard.kt")
        assertEquals("impactLine 只许被一个 Text 渲染", 1, countOf(card, "text = impactLine"))
        // 本批不许在卡里加第二个容器/对话框（卡片本身就是容器；再加就是"多一张卡"）
        assertEquals("不许新增 Dialog", 0, countOf(card, "Dialog("))
        assertEquals("不许新增 AlertDialog", 0, countOf(card, "AlertDialog("))
    }

    // ── 负例「还没卡就先弹提示」────────────────────────────────────────
    //
    // 预览的**唯一**入口在 `ConfirmRequest` 那个分支里（先 `confirmCard = ev`，再算）。
    // 入口多一个，就意味着有别的时机会出现"没卡却先算/先显示"。

    @Test
    fun `负例_预览只从出卡那一个分支进来`() {
        val ctl = src("ui/baopu/BaopuChatController.kt")
        assertEquals("queueImpactPreview 只许一处声明＋一处调用", 2, countOf(ctl, "queueImpactPreview("))
        assertEquals(
            "impactLineFor 只许这几处（声明/读取/置空/写回/注释里的指代）",
            5,
            countOf(ctl, "impactLineFor"),
        )
    }

    // ── 负例「关掉开关仍有提示」─────────────────────────────────────────
    //
    // 两处都要看：控制器入口必须早退（关掉＝连算都不算），设置页必须写同一个键。

    @Test
    fun `负例_关掉开关必须连算都不算`() {
        val ctl = src("ui/baopu/BaopuChatController.kt")
        assertTrue(
            "queueImpactPreview 第一行必须是开关早退",
            ctl.contains("if (!com.example.zhengdao.baopu.ImpactPrefs.get(appCtx)) return"),
        )
        val prefs = src("baopu/ImpactWiring.kt")
        assertTrue("默认值必须是 true", prefs.contains("getBoolean(KEY_SHOW, true)"))
    }

    @Test
    fun `判据_设置页开关写的是同一个键`() {
        val settings = src("ui/baopu/BaopuSettingsScreen.kt")
        assertTrue("设置页必须走 ImpactPrefs.set", settings.contains("ImpactPrefs.set(ctx, want)"))
        assertTrue("中英文案必须成对存在", res("values/strings_baopu_impact.xml").isFile)
        assertTrue("中英文案必须成对存在", res("values-en/strings_baopu_impact.xml").isFile)
    }

    // ── 负例 3：R0 只读不注入（且入队点只有一个）────────────────────────

    @Test
    fun `负例3_注入队列只有一处入口`() {
        val ctl = src("ui/baopu/BaopuChatController.kt")
        assertEquals("ImpactMailbox.offer 只许在预览那条路径里出现一次", 1, countOf(ctl, "ImpactMailbox.offer("))
        val engine = src("baopu/agent/RunEngine.kt")
        assertEquals("引擎侧只许一个取走点（取走即清，不许第二处）", 1, countOf(engine, "ImpactMailbox.drain()"))
    }

    // ── 负例 4：原件区判据复用 PathGuard（不许自己再写一遍路径归一）─────

    @Test
    fun `负例4_原件区复用PathGuard`() {
        val text = src("baopu/ImpactPreview.kt")
        assertTrue("必须调 PathGuard.isInside", text.contains("PathGuard.isInside("))
        assertTrue("不许自己 canonicalize 另立一套", !text.contains("canonicalFile"))
    }

    // ── 负例 7：预览失败不许拖累动作（控制器里必须 runCatching）──────────

    @Test
    fun `负例7_预览异常不影响动作`() {
        val ctl = src("ui/baopu/BaopuChatController.kt")
        assertTrue("预览必须包在 runCatching 里", ctl.contains("com.example.zhengdao.baopu.ImpactPreview.preview(call, facts)"))
        assertTrue("算预览之前先有 runCatching{", ctl.contains("val impact = runCatching {"))
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
