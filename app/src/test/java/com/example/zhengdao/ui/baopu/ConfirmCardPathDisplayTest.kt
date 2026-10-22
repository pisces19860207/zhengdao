// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百四十九批 组5：`relativizePath` 接确认卡显示（**显示行为变更**，交付报告已写）。
//
// 依据：派工单 §3.4（确认卡一行标题「要动文件：<路径>」＋一行摘要）＋ §3.3（在线出卡与
// 重启重建共用 RunRules.confirmSummary）。
//
// 这一批是回程：`9cda8e31` 删过 relativizePath（当时生产零调用），删账里就写着
// 「确认卡实际显示绝对路径（接线审计 2026-10-18 已点名）」。洞的真实形状：
// 管线第②段把守卫放过的工作区内路径**改写成绝对路径**（GuardPipeline.prepare），
// 第③段才出卡 ⇒ 卡面拿到的是 `D:\工作区\资料库\x.txt`，又长又把目录结构印在聊天界面上。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.baopu.agent.ConfirmRequest
import com.example.zhengdao.baopu.agent.RunRules
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 确认卡路径显示的判据 ＋ 六个接线点的源码门禁。 */
class ConfirmCardPathDisplayTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.exists()) it else File("app/$rel") }

    private fun read(rel: String): String = repoFile(rel).readText()

    private fun codeOnly(src: String): String =
        src.split("\n").joinToString("\n") { it.substringBefore("//") }
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")

    private val root = File(System.getProperty("java.io.tmpdir") ?: ".", "zd-149-ws")

    private fun abs(rel: String): String = File(root, rel).absolutePath

    private fun request(tool: String, argsJson: String) =
        ConfirmRequest(callId = "c-1", tool = tool, summary = "-", argsJson = argsJson)

    // ── 判据本体 ─────────────────────────────────────────────────────────

    @Test
    fun `工作区内剥根_工作区外原样`() {
        // 平台无关写法（Windows 上 File("/tmp/ws") 的 absolutePath 带反斜杠，
        // 期望值用 File(...).path 归一，不手写斜杠）
        assertEquals(File("a/b.txt").path, RunRules.relativizePath(abs("a/b.txt"), root))
        val outside = File(root.parentFile ?: root, "elsewhere/passwd").absolutePath
        assertEquals("不在工作区 ⇒ 原样", outside, RunRules.relativizePath(outside, root))
    }

    @Test
    fun `根带尾分隔符也剥得掉_否则留下一个前导斜杠`() {
        val rootWithSlash = File(root.absolutePath + File.separator)
        assertEquals(File("a/b.txt").path, RunRules.relativizePath(abs("a/b.txt"), rootWithSlash))
    }

    @Test
    fun `路径不等于根时不误剥成空串`() {
        assertEquals("原样", abs(""), RunRules.relativizePath(root.absolutePath, root))
    }

    // ── 卡面两处显示（标题＋摘要）────────────────────────────────────────

    @Test
    fun `标题把绝对路径剥成相对_不给根时逐字节原样`() {
        val req = request("write_file", JSONObject().put("path", abs("资料库/x.txt")).toString())
        assertEquals(File("资料库/x.txt").path, confirmCardTitlePath(req, root))
        assertEquals("没给根 ⇒ 保持接线前的样子", abs("资料库/x.txt"), confirmCardTitlePath(req, null))
    }

    @Test
    fun `工作区外的路径原样印_不许编一个短路径出来`() {
        val outside = File(root.parentFile ?: root, "outside.txt").absolutePath
        val req = request("edit_file", JSONObject().put("path", outside).toString())
        assertEquals(outside, confirmCardTitlePath(req, root))
    }

    @Test
    fun `坏参数JSON标题取不到路径_照旧退化成工具名不抛`() {
        assertNull(confirmCardTitlePath(request("write_file", "{半截"), root))
        assertNull(confirmCardTitlePath(request("browser_open", JSONObject().put("url", "https://x").toString()), root))
    }

    @Test
    fun `标题与摘要用同一条剥法_同一张卡不许两个样子`() {
        val args = JSONObject().put("path", abs("资料库/x.txt")).put("content", "甲\n乙").put("overwrite", true)
        val title = confirmCardTitlePath(request("write_file", args.toString()), root)
        val summary = RunRules.confirmSummary("write_file", args, root)
        assertTrue("摘要里的路径与标题不是同一个：标题=$title 摘要=$summary", summary.contains(title!!))
        assertFalse("摘要漏了绝对前缀：$summary", summary.contains(root.absolutePath))
    }

    @Test
    fun `move_path的两端都剥_只剥一端会写出半截真话`() {
        val args = JSONObject().put("from", abs("a.txt")).put("to", abs("b/c.txt"))
        val shown = RunRules.confirmSummary("move_path", args, root)
        val expect = "移动 ${File("a.txt").path} → ${File("b/c.txt").path}"
        assertEquals(expect, shown)
    }

    @Test
    fun `不给根时三句话术与接线前逐字节相同`() {
        // 这一条是「旧调用点零改动」的锁：默认参数 ⇒ 老行为原样。
        val abs = abs("a.txt")
        assertEquals("新建 $abs（1 行）", RunRules.confirmSummary("write_file", JSONObject().put("path", abs).put("content", "甲")))
        assertEquals("编辑 $abs", RunRules.confirmSummary("edit_file", JSONObject().put("path", abs)))
        assertEquals("移动 ? → ?", RunRules.confirmSummary("move_path", JSONObject()))
    }

    // ── 接线门禁（接上前这几条红）────────────────────────────────────────

    @Test
    fun `六个接线点都拿到了同一个根`() {
        val flow = codeOnly(read("src/main/java/com/example/zhengdao/baopu/agent/ConfirmFlow.kt"))
        assertTrue("ConfirmFlow 出卡时没把根喂给摘要",
            flow.contains("RunRules.confirmSummary(call.name, call.arguments, projectRoot())"))
        val engine = codeOnly(read("src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt"))
        assertTrue("引擎没把 spillRoot 注入确认内核 ⇒ 根与②段改写路径用的不是同一个目录",
            engine.contains("projectRoot = { spillRoot() }"))
        val restore = codeOnly(read("src/main/java/com/example/zhengdao/ui/baopu/BaopuConfirmRestore.kt"))
        assertTrue("重启重建没传根 ⇒ 同一张卡重启前后两个样子",
            restore.contains("RunRules.confirmSummary(row.tool, args, projectRoot)"))
        val controller = codeOnly(read("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt"))
        assertTrue("控制器重建卡没传根", controller.contains("BaopuConfirmRestore.card(row, workspaceRoot)"))
        assertTrue("控制器的根必须是引擎那一个（BaopuRuntime.workspaceRoot），不许另拼一个目录",
            controller.contains("BaopuRuntime.workspaceRoot(appCtx)"))
        val screen = codeOnly(read("src/main/java/com/example/zhengdao/ui/baopu/BaopuScreen.kt"))
        assertTrue("卡面没接根", screen.contains("projectRoot = controller.workspaceRoot"))
        val card = codeOnly(read("src/main/java/com/example/zhengdao/ui/baopu/BaopuConfirmCard.kt"))
        assertTrue("标题没走剥路径那条口", card.contains("RunRules.relativizePath(raw, projectRoot)"))
    }

    @Test
    fun `剥根这件事全仓只写一次`() {
        // relativizePath 的前缀算法一旦在别处再写一遍（removePrefix/trimEnd），
        // 就有了第二把尺子——标题与摘要会漂。
        val owners = File(repoFile("src/main/java"), ".").walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .filter { codeOnly(it.readText()).contains("trimEnd('/', '\\\\')") }
            .map { it.name }
            .toList()
        assertEquals("剥根的前缀写法出现在多个生产文件里：$owners", listOf("RunRules.kt"), owners)
        assertTrue("RunRules 里 relativizePath 又被删了",
            codeOnly(read("src/main/java/com/example/zhengdao/baopu/agent/RunRules.kt")).contains("fun relativizePath("))
    }
}
