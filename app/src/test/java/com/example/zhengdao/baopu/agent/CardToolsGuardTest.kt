// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 判据：F11（card_inspect 只读，源码级＋行为级）／F20-ii 的源码侧（导出与工具都不许覆盖）
//       ＋ 派工单 §1「工具描述必须说清只读／不覆盖」
// 口径沿用本仓老纪律：**先剥注释再断言**（注释里提一嘴不算过）。
package com.example.zhengdao.baopu.agent

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CardToolsGuardTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    private fun raw(rel: String): String = repoFile(rel).readText().replace("\r\n", "\n")

    private fun code(rel: String): String = raw(rel)
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private fun segment(text: String, from: String, to: String): String {
        val i = text.indexOf(from)
        if (i < 0) return ""
        val j = text.indexOf(to, i + from.length)
        return if (j < 0) text.substring(i) else text.substring(i, j)
    }

    private val writeTokens = listOf(
        "writeText", "writeBytes", "appendText", "delete(", "deleteRecursively",
        "createNewFile", "renameTo", "mkdir", "outputStream", "FileOutputStream",
        "Files.write", "Files.delete", "RandomAccessFile",
    )

    private val inspectChain = listOf(
        "src/main/java/com/example/zhengdao/baopu/agent/tools/CardTools.kt",
        "src/main/java/com/example/zhengdao/baopu/card/CardFormat.kt",
        "src/main/java/com/example/zhengdao/baopu/card/CardCheck.kt",
        "src/main/java/com/example/zhengdao/baopu/card/CardAssetParser.kt",
        "src/main/java/com/example/zhengdao/baopu/card/CardJson.kt",
        "src/main/java/com/example/zhengdao/baopu/card/CardModel.kt",
    )

    @Test
    fun `inspect_is_read_only_source_and_behavior`() {
        // ① 源码级：体检链上一个写盘调用都不许有
        for (f in inspectChain) {
            val src = code(f)
            assertTrue("找不到 $f", src.isNotBlank())
            for (t in writeTokens) {
                assertFalse("$f 里不许出现写盘调用「$t」（card_inspect 必须只读）", src.contains(t))
            }
        }

        // ② 行为级：跑一次 inspect，源文件 sha 不变
        val ws = tmp.newFolder("ws")
        val f = File(ws, "in.json").apply { writeText(CardFixtures.J4) }
        val before = sha(f)
        val spec = CardTools.inspectSpec()
        val out = spec.executor(jsonObjectOf("path" to f.absolutePath))
        assertTrue("inspect 应该成功：$out", out is ToolOutcome.Ok)
        assertEquals("inspect 改了文件", before, sha(f))
        assertTrue((out as ToolOutcome.Ok).text.contains("只读体检"))
        assertTrue(out.text.contains("酒馆原生世界书"))

        // ③ 权限与文案
        assertEquals(ToolPermission.FREE, spec.permission)
        assertTrue("描述要说清只读：${spec.description}", spec.description.contains("只读"))
        val export = CardTools.exportSpec { ws }
        assertEquals(ToolPermission.CONFIRM, export.permission)
        assertTrue("描述要说清不覆盖：${export.description}", export.description.contains("不覆盖"))
    }

    @Test
    fun `export_path_has_no_overwrite_and_no_parse`() {
        val png = code("src/main/java/com/example/zhengdao/baopu/card/CardPng.kt")
        for (t in listOf("REPLACE_EXISTING", "TRUNCATE_EXISTING", "deleteIfExists")) {
            assertFalse("导出链路不许出现 $t", png.contains(t))
        }
        // 导出函数体里不许 parse（parse→toString 会让键序/转义变，F8 立刻炸）
        val body = segment(png, "fun export(", "fun textChunk(")
        assertTrue("找不到 export 函数体", body.isNotBlank())
        for (t in listOf("JSONTokener", "JSONObject(", "toString()", "getMimeEncoder")) {
            assertFalse("export 函数体里不许出现 $t", body.contains(t))
        }
        assertTrue("必须用 CREATE_NEW 让文件系统当裁判", body.contains("CREATE_NEW"))
    }

    @Test
    fun `export_tool_refuses_existing_target_and_keeps_source`() {
        val ws = tmp.newFolder("ws2")
        val src = File(ws, "in.json").apply { writeText(CardFixtures.J4) }
        val out = File(ws, "out.png").apply { writeText("keep") }
        val srcBefore = sha(src)
        val outBefore = sha(out)

        val spec = CardTools.exportSpec { ws }
        val r = spec.executor(jsonObjectOf("path" to src.absolutePath, "out" to out.absolutePath))
        assertTrue("目标已存在必须失败：$r", r is ToolOutcome.Failed)
        assertTrue((r as ToolOutcome.Failed).error.contains("目标已存在，未覆盖："))
        assertEquals(srcBefore, sha(src))
        assertEquals(outBefore, sha(out))
    }

    @Test
    fun `export_tool_rejects_paths_outside_workspace`() {
        val ws = tmp.newFolder("ws3")
        val outside = tmp.newFolder("outside")
        val src = File(ws, "in.json").apply { writeText(CardFixtures.J4) }
        val spec = CardTools.exportSpec { ws }
        val r = spec.executor(jsonObjectOf("path" to src.absolutePath, "out" to File(outside, "x.png").absolutePath))
        assertTrue("越界必须被拒：$r", r is ToolOutcome.Rejected)
    }

    @Test
    fun `export_tool_writes_two_blocks_and_source_untouched`() {
        val ws = tmp.newFolder("ws4")
        val src = File(ws, "in.json").apply { writeText(CardFixtures.J4) }
        val out = File(ws, "ok.png")
        val srcBefore = sha(src)
        val spec = CardTools.exportSpec { ws }
        val r = spec.executor(jsonObjectOf("path" to src.absolutePath, "out" to out.absolutePath))
        assertTrue("导出失败：$r", r is ToolOutcome.Ok)
        assertTrue(out.isFile)
        val txt = out.readBytes().toString(Charsets.ISO_8859_1)
        assertTrue("要有 chara 块", txt.contains("chara"))
        assertTrue("要有 ccv3 块", txt.contains("ccv3"))
        assertEquals("源文件一个字节都不许动", srcBefore, sha(src))
    }

    private fun sha(f: File): String =
        MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
}
