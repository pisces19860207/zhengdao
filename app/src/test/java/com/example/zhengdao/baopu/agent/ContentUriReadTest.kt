// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百二十八批 · `content://` 读取门禁（613 的边界延后项）。
//
// 为什么用「内存测试 ContentProvider」而不是真 android.content.ContentProvider：
// 本仓 JVM 单测是 `unitTests.isReturnDefaultValues = true`（无 Robolectric），Android
// ContentResolver 在测试 JVM 里是 stub。项目既有房型（BaopuRuntime §9「抽 Context-free
// 内核 + 闭包注入」）就是：装配层注入真实现，单测注入假件，两端打同一份读判据。
// 下面的 [TestContentProvider] 实现 [ContentOpener]，按 URI 返回开流结果 —— 等价于
// ContentProvider.openFile 那一层，语义/错误形态（null、抛 SecurityException）一致。
// 真机 SAF 端到端取一条未跑，列为残留（见交付报告 §七）。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.ContentOpener
import com.example.zhengdao.baopu.ContentRead
import com.example.zhengdao.baopu.ContentStream
import com.example.zhengdao.baopu.ContentUri
import com.example.zhengdao.baopu.ContentUriSource
import com.example.zhengdao.testkit.TempSandboxPool
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ContentUriReadTest {

    /** 一条测试 provider 里的记录。 */
    private data class Entry(
        val bytes: ByteArray,
        val displayName: String? = null,
        val declaredSize: Long? = null,
        val denied: Boolean = false,
    )

    /** 内存「测试 ContentProvider」：URI → 开流结果；未登记 / denied ⇒ 无权限或失效。 */
    private class TestContentProvider : ContentOpener {
        val entries = mutableMapOf<String, Entry>()
        var openCount = 0

        override fun open(uri: String): ContentStream? {
            openCount++
            val e = entries[uri] ?: throw FileNotFoundException("URI 失效：$uri")
            if (e.denied) throw SecurityException("无 URI 持久权限：$uri")
            return ContentStream(e.displayName, e.declaredSize, ByteArrayInputStream(e.bytes))
        }
    }

    private lateinit var provider: TestContentProvider
    private lateinit var outDir: File

    @Before
    fun setUp() {
        provider = TestContentProvider()
        ContentUriSource.opener = provider
        outDir = TempSandboxPool.create("zd628-artifacts")
    }

    @After
    fun tearDown() {
        ContentUriSource.opener = null // 全局注入点必须复位，不许污染全量其它用例
        TempSandboxPool.clean()
    }

    // ── ① 夹具：表格 / 文本各能读出内容 ─────────────────────────────────

    @Test
    fun `判据一_表格_csv_uri读出内容`() {
        val uri = "content://com.example.zd628/table/score.csv"
        provider.entries[uri] = Entry("姓名,分数\n张三,90\n李四,85\n".toByteArray(), "score.csv")

        val out = SheetReadTool.run(JSONObject().put("path", uri), outDir)

        assertTrue("表格 URI 应读出内容，实际：$out", out is ToolOutcome.Ok)
        val text = (out as ToolOutcome.Ok).text
        assertTrue("要读到单元格内容", text.contains("张三") && text.contains("90"))
        assertTrue("要读到第二行", text.contains("李四") && text.contains("85"))
        assertFalse("不许报不存在", text.contains("不存在"))
    }

    @Test
    fun `判据一_表格_xlsx_uri读出内容`() {
        val uri = "content://com.example.zd628/table/book.xlsx"
        provider.entries[uri] = Entry(xlsxBytes("单元格-628"), "book.xlsx")

        val out = SheetReadTool.run(JSONObject().put("path", uri), outDir)

        assertTrue("xlsx URI 应读出内容，实际：$out", out is ToolOutcome.Ok)
        assertTrue((out as ToolOutcome.Ok).text.contains("单元格-628"))
    }

    @Test
    fun `判据一_文本_uri读出内容_带行号`() {
        val uri = "content://com.example.zd628/text/note.txt"
        provider.entries[uri] = Entry("第一行-甲\n第二行-乙\n第三行-丙\n".toByteArray(), "note.txt")

        val out = ReadFileTool.readContent(uri, 0, 200)

        assertTrue("文本 URI 应读出内容，实际：$out", out is ToolOutcome.Ok)
        val text = (out as ToolOutcome.Ok).text
        assertTrue(text.contains("0\t第一行-甲"))
        assertTrue(text.contains("2\t第三行-丙"))
        assertTrue(text.contains("共 3 行"))
    }

    @Test
    fun `判据一_大小写与空白也认得出`() {
        val uri = "  CONTENT://com.example.zd628/text/note.txt  "
        provider.entries[uri.trim()] = Entry("内容-628\n".toByteArray(), "note.txt")

        val out = ReadFileTool.spec().executor(JSONObject().put("path", uri))

        assertTrue("带空白/大写的 content:// 也要认得出，实际：$out", out is ToolOutcome.Ok)
        assertTrue((out as ToolOutcome.Ok).text.contains("内容-628"))
    }

    // ── ② 负例：无权限 / 失效 ⇒ 不许报「不存在」 ───────────────────────

    @Test
    fun `判据二_无权限_uri报无权限或已失效_不报不存在`() {
        val uri = "content://com.example.zd628/denied/secret.csv"
        provider.entries[uri] = Entry("机密-不该被读出".toByteArray(), "secret.csv", denied = true)

        val out = SheetReadTool.run(JSONObject().put("path", uri), outDir)

        assertTrue(out is ToolOutcome.Failed)
        val reason = (out as ToolOutcome.Failed).error
        assertTrue("要带 URI 原文：$reason", reason.contains(uri))
        assertTrue("要说「无权限或已失效」：$reason", reason.contains("URI 无权限或已失效"))
        assertFalse("负例：报「不存在」即红 —— $reason", reason.contains("不存在"))
        assertFalse("不许泄漏内容", reason.contains("机密"))
    }

    @Test
    fun `判据二_uri失效_报无权限或已失效_不报不存在`() {
        val uri = "content://com.example.zd628/gone/old.csv"
        // 未登记 ⇒ provider 抛 FileNotFoundException（URI 已失效）

        val out = ReadFileTool.readContent(uri, 0, 200)

        assertTrue(out is ToolOutcome.Failed)
        val reason = (out as ToolOutcome.Failed).error
        assertTrue(reason.contains("URI 无权限或已失效"))
        assertFalse("报「不存在」即红：$reason", reason.contains("不存在"))
    }

    @Test
    fun `判据二_端口未装配_也说无权限或已失效_不报不存在`() {
        ContentUriSource.opener = null // 模拟进程还没装 ContentResolver

        val out = SheetReadTool.run(
            JSONObject().put("path", "content://com.example.zd628/x.csv"),
            outDir,
        )

        assertTrue(out is ToolOutcome.Failed)
        val reason = (out as ToolOutcome.Failed).error
        assertTrue(reason.contains("URI 无权限或已失效"))
        assertFalse(reason.contains("不存在"))
    }

    // ── ③ 源码断言：content:// 不进 File(root, rel) 分支 ────────────────

    /** 读源码（去注释），免得注释里的字面量把断言喂成恒真。 */
    private fun code(rel: String): String =
        File(rel).let { if (it.isFile) it else File("app/$rel") }.readText()
            .replace("\r\n", "\n")
            .replace(Regex("(?s)/\\*.*?\\*/"), "")
            .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private fun toolsSrc(rel: String): String = code("src/main/java/com/example/zhengdao/baopu/agent/tools/$rel")

    @Test
    fun `判据三_sheet_read_先分流再解析_顺序钉死`() {
        // 613 合入后「拼根」已从本文件消失（改由 `UserPath.resolve` 一处解析）；
        // 断言随之收紧成：content:// 必须早于 **613 的外域 scheme 拒** 与 **613 的解析口**。
        val runBody = bodyOf(toolsSrc("SheetReadTool.kt"), "fun run(args: JSONObject, root: File)")
        val atCheck = runBody.indexOf("ContentUri.isContent(raw)")
        val atForeign = runBody.indexOf("UserPath.foreignScheme(raw)")
        val atResolve = runBody.indexOf("UserPath.resolve(root, raw)")

        assertTrue("run 里必须有 content:// 的早返回分流", atCheck >= 0)
        assertTrue("613 的外域 scheme 拒应在 run 里", atForeign >= 0)
        assertTrue("613 的解析口应在 run 里", atResolve >= 0)
        assertTrue(
            "content:// 必须**早于** 613 的外域 scheme 拒（否则读工具会被 613 拒掉）：" +
                "check=$atCheck foreign=$atForeign",
            atCheck < atForeign,
        )
        assertTrue(
            "content:// 必须**早于** UserPath 解析（否则会被折成本地路径）：check=$atCheck resolve=$atResolve",
            atCheck < atResolve,
        )
        assertFalse("run 里不许再出现「拼工作区根」那一支", runBody.contains("File(root,"))
        assertTrue("分流必须是 return（直接转走），不是判一下继续走", runBody.contains("return readContent(args, raw)"))
    }

    @Test
    fun `判据三_read_file_先分流再解析_顺序钉死`() {
        val runBody = bodyOf(toolsSrc("ReadFileTool.kt"), "private fun run(args: org.json.JSONObject)")
        val atCheck = runBody.indexOf("ContentUri.isContent(path)")
        val atForeign = runBody.indexOf("UserPath.foreignScheme(path)")
        val atFile = runBody.indexOf("File(UserPath.normalize(path))")

        assertTrue("run 里必须有 content:// 的早返回分流", atCheck >= 0)
        assertTrue("613 的外域 scheme 拒应在 run 里", atForeign >= 0)
        assertTrue("613 的归一后造 File 应在 run 里", atFile >= 0)
        assertTrue(
            "content:// 必须**早于** 613 的外域 scheme 拒：check=$atCheck foreign=$atForeign",
            atCheck < atForeign,
        )
        assertTrue(
            "content:// 必须**早于** File(...)：check=$atCheck file=$atFile",
            atCheck < atFile,
        )
        assertTrue(runBody.contains("return readContent(path, offset, limit)"))
    }

    @Test
    fun `判据三_content分支内部不许出现File_root拼接`() {
        val sheet = bodyOf(toolsSrc("SheetReadTool.kt"), "private fun readContent(")
        val read = bodyOf(toolsSrc("ReadFileTool.kt"), "fun readContent(")
        assertFalse("sheet_read 的 content 分支不许拼根", sheet.contains("File(root,"))
        assertFalse("read_file 的 content 分支不许造 File", read.contains("File("))
        assertTrue("content 分支必须走 ContentUri.read", sheet.contains("ContentUri.read("))
        assertTrue("content 分支必须走 ContentUri.read", read.contains("ContentUri.read("))
    }

    @Test
    fun `判据三_管线在外域分流先于解析_且读工具白名单是闭集`() {
        val src = code("src/main/java/com/example/zhengdao/baopu/agent/GuardPipeline.kt")
        val atGuard = src.indexOf("contentUriGuard(call, toolName, args)")
        val atResolve = src.indexOf("val target = resolveStrict(raw)")
        // 613 合入后 ② 段多了一道 `foreignSchemeReason(raw)` 的外域拒 —— 628 的分流必须早于它，
        // 否则 content:// 会在到达工具层之前就被 613 拒掉（两个「另立单」撞车）。
        val atForeign = src.indexOf("foreignSchemeReason(raw)?.let { return Guarded.No(it) }")
        assertTrue("② 段必须有 content:// 分流", atGuard >= 0)
        assertTrue("分流必须**早于**任何路径解析：guard=$atGuard resolve=$atResolve", atGuard in 1 until atResolve)
        assertTrue("613 的外域拒应在 ② 段", atForeign >= 0)
        assertTrue(
            "分流必须**早于** 613 的外域 scheme 拒：guard=$atGuard foreign=$atForeign",
            atGuard in 1 until atForeign,
        )
        assertTrue(
            "白名单只许表格/文本两个读工具",
            src.contains("setOf(\"sheet_read\", \"read_file\")"),
        )
    }

    /** 某个函数体（从声明到下一个 private fun / 文件尾；与既有源码断言同一房型）。 */
    private fun bodyOf(src: String, decl: String): String {
        val at = src.indexOf(decl)
        if (at < 0) return ""
        val rest = src.substring(at + decl.length)
        val end = rest.indexOf("private fun ").takeIf { it > 0 } ?: rest.length
        return rest.substring(0, end)
    }

    // ── ④ 上限：超额报错不截断 ──────────────────────────────────────────

    @Test
    fun `判据四_声明大小超上限_整条失败_不打开流读取`() {
        val uri = "content://com.example.zd628/big/huge.csv"
        provider.entries[uri] = Entry(
            "前段哨兵".toByteArray(),
            "huge.csv",
            declaredSize = SheetLimits.MAX_FILE_BYTES + 1,
        )

        val out = SheetReadTool.run(JSONObject().put("path", uri), outDir)

        assertTrue("超限必须 Failed，实际：$out", out is ToolOutcome.Failed)
        val reason = (out as ToolOutcome.Failed).error
        assertTrue("要说「文件过大」：$reason", reason.contains("文件过大"))
        assertTrue("要说清上限 MB：$reason", reason.contains("上限"))
        assertTrue("要说明不截断：$reason", reason.contains("不截断"))
        assertFalse("不许返回前段内容（截断读取）", reason.contains("前段哨兵"))
    }

    @Test
    fun `判据四_边读边数超限_整条失败_不返回部分字节`() {
        val uri = "content://com.example.zd628/big/nosize.csv"
        // provider 不报 SIZE，只能边读边数：用小上限验证 readBounded 那一支
        provider.entries[uri] = Entry("ABCDEFGHIJKLMNOP".toByteArray(), "nosize.csv", declaredSize = null)

        val result = ContentUri.read(uri, 8L)

        assertTrue("超限必须整条 TooBig，实际：$result", result is ContentRead.TooBig)
        assertEquals("报出的字节数应是读到的下界", 9L, (result as ContentRead.TooBig).bytes)
        val msg = ContentUri.tooBigMessage(result.bytes, 8L, uri)
        assertTrue(msg.contains("文件过大"))
        assertTrue(msg.contains("上限"))
        assertFalse(msg.contains("ABCDEFGH"))
    }

    @Test
    fun `判据四_工具层超限文案_表格与文本各一条`() {
        val sheetUri = "content://com.example.zd628/big/t.csv"
        provider.entries[sheetUri] = Entry("哨兵".toByteArray(), "t.csv", declaredSize = SheetLimits.MAX_FILE_BYTES + 1)
        val textUri = "content://com.example.zd628/big/t.txt"
        provider.entries[textUri] = Entry("哨兵".toByteArray(), "t.txt", declaredSize = ReadFileTool.MAX_FILE_BYTES + 1)

        val sheetOut = SheetReadTool.run(JSONObject().put("path", sheetUri), outDir) as ToolOutcome.Failed
        val textOut = ReadFileTool.readContent(textUri, 0, 200) as ToolOutcome.Failed

        listOf(sheetOut.error, textOut.error).forEach { reason ->
            assertTrue("文件过大：$reason", reason.contains("文件过大"))
            assertTrue("上限：$reason", reason.contains("上限"))
            assertFalse("不截断、不泄漏前段：$reason", reason.contains("哨兵"))
        }
    }

    // ── ⑤ 不落产物：读完后产物目录无新增文件 ────────────────────────────

    private fun snapshot(dir: File): Set<String> =
        dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).path }.toSet()

    @Test
    fun `判据五_表格与文本各读一遍_产物目录零新增`() {
        val csv = "content://com.example.zd628/t/a.csv"
        val txt = "content://com.example.zd628/t/a.txt"
        provider.entries[csv] = Entry("甲,乙\n1,2\n".toByteArray(), "a.csv")
        provider.entries[txt] = Entry("文本-628\n".toByteArray(), "a.txt")
        val before = snapshot(outDir)

        val a = SheetReadTool.run(JSONObject().put("path", csv), outDir)
        val b = ReadFileTool.readContent(txt, 0, 200)

        assertTrue("表格应读成功：$a", a is ToolOutcome.Ok)
        assertTrue("文本应读成功：$b", b is ToolOutcome.Ok)
        assertEquals("读完产物目录不许新增任何文件", before, snapshot(outDir))
    }

    @Test
    fun `判据五_失败路径也不落盘`() {
        val denied = "content://com.example.zd628/t/denied.csv"
        provider.entries[denied] = Entry("机密".toByteArray(), "denied.csv", denied = true)
        val before = snapshot(outDir)

        val out = SheetReadTool.run(JSONObject().put("path", denied), outDir)

        assertTrue(out is ToolOutcome.Failed)
        assertEquals("失败也不许落一个字节", before, snapshot(outDir))
    }

    @Test
    fun `判据五_content分支源码里没有写盘调用`() {
        val src = code("src/main/java/com/example/zhengdao/baopu/ContentUri.kt")
        assertFalse("端口层不许写盘", src.contains("writeBytes") || src.contains("outputStream()"))
        assertFalse("端口层不许造 File", src.contains("File("))
        val sheet = bodyOf(toolsSrc("SheetReadTool.kt"), "private fun readContent(")
        assertFalse("sheet_read 的 content 分支不许写盘", sheet.contains("writeBytes") || sheet.contains("outputStream"))
        val read = bodyOf(toolsSrc("ReadFileTool.kt"), "fun readContent(")
        assertFalse("read_file 的 content 分支不许写盘", read.contains("writeBytes") || read.contains("outputStream"))
    }

    // ── 附加：不写 content://（派工单 §一④）+ File 路径零回归 ───────────

    @Test
    fun `链路_管线端到端_content_uri原样透传且读出内容`() {
        // 613 的真机教训：②段一旦把 content:// 改写成 <根>/content:/…，工具层就再也
        // 认不出来，只能回报「不存在」。这条从 execute 打到底，钉住整条链路。
        val uri = "content://com.example.zd628/e2e/note.txt"
        provider.entries[uri] = Entry("端到端-628\n".toByteArray(), "note.txt")
        val registry = ToolRegistry().apply { register(ReadFileTool.spec()) }
        val pipeline = GuardPipeline(
            registry, { outDir }, { null },
            ToolRunner(java.util.concurrent.Executors.newSingleThreadExecutor()), {},
        )

        val r = pipeline.execute(
            ToolCall("c-628-e2e", "read_file", JSONObject().put("path", uri)),
            { _, _ -> null },
        )

        assertTrue("content:// 应原样走到底并读出来，实际：$r", r is GuardPipeline.Result.Done)
        assertTrue(
            "回灌里要有读到的内容（不许被改成「不存在」）",
            (r as GuardPipeline.Result.Done).wrapped.contains("端到端-628"),
        )
        assertEquals("整条链路不许落盘", emptySet<String>(), snapshot(outDir))
    }

    @Test
    fun `边界_写类工具收content_uri被管线明确拒`() {
        val registry = ToolRegistry().apply {
            register(WriteFileTool.spec { outDir })
            register(SheetReadTool.spec { outDir })
        }
        val pipeline = GuardPipeline(
            registry, { outDir }, { null }, ToolRunner(java.util.concurrent.Executors.newSingleThreadExecutor()), {},
        )
        val uri = "content://com.example.zd628/write/out.txt"

        val r = pipeline.execute(
            ToolCall("c-628", "write_file", JSONObject().put("path", uri).put("content", "x")),
            { _, _ -> null },
        )

        assertTrue("写类工具不许接 content://：$r", r is GuardPipeline.Result.Rejected)
        assertTrue(
            (r as GuardPipeline.Result.Rejected).reason.contains("content://"),
        )
        assertEquals("拒了就不许落盘", emptySet<String>(), snapshot(outDir))
    }

    @Test
    fun `零回归_普通相对路径仍按老口径读`() {
        val f = File(outDir, "a.md").apply { writeText("甲\n乙\n") }
        val before = snapshot(outDir)

        val out = ReadFileTool.read(f, 0, 200)

        assertTrue(out is ToolOutcome.Ok)
        assertTrue((out as ToolOutcome.Ok).text.contains("0\t甲"))
        assertEquals(before, snapshot(outDir))
    }

    @Test
    fun `零回归_普通相对路径表格仍按老口径读`() {
        val f = File(outDir, "a.csv").apply { writeText("甲,乙\n1,2\n") }

        val out = SheetReadTool.run(JSONObject().put("path", "a.csv"), outDir)

        assertTrue("相对路径表格行为不变：$out", out is ToolOutcome.Ok)
        assertTrue((out as ToolOutcome.Ok).text.contains("1"))
    }

    @Test
    fun `边界_普通路径不存在仍报不存在_content路径不许`() {
        val missing = ReadFileTool.read(File(outDir, "nope.md"), 0, 200) as ToolOutcome.Failed
        assertTrue("File 路径的老话术不许被改", missing.error.contains("不存在"))

        val gone = "content://com.example.zd628/gone/x.txt"
        val out = ReadFileTool.readContent(gone, 0, 200) as ToolOutcome.Failed
        assertFalse("content:// 一律不许报不存在：${out.error}", out.error.contains("不存在"))
    }

    // ── 造夹具：最小 xlsx（真 zip，与 613/ToolsV1 同一配方，写进内存不落盘） ──

    private fun xlsxBytes(cell: String): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            fun put(path: String, content: String) {
                z.putNextEntry(ZipEntry(path))
                z.write(content.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
            put(
                "[Content_Types].xml",
                "<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>",
            )
            put(
                "xl/workbook.xml",
                "<?xml version=\"1.0\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"" +
                    " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
                    "<sheets><sheet name=\"表1\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>",
            )
            put(
                "xl/_rels/workbook.xml.rels",
                "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rId1\" Target=\"worksheets/sheet1.xml\"/></Relationships>",
            )
            put(
                "xl/sharedStrings.xml",
                "<?xml version=\"1.0\"?><sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
                    "<si><t>$cell</t></si></sst>",
            )
            put(
                "xl/worksheets/sheet1.xml",
                "<?xml version=\"1.0\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
                    "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c></row></worksheet>",
            )
        }
        return bos.toByteArray()
    }
}
