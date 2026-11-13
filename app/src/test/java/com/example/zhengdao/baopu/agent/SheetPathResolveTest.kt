// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百一十三批 · 判据（派工单 §一）：
// ① 绝对路径三条（根／子目录／含空格）能读；② 相对路径不回归；
// ③ 越界拒；④ 报错含**解析后**的绝对路径；⑤ 同目录 .md/.txt/.xlsx 三件都能看见并读到。
//
// 背景（569 真机 §五-3）：`sheet_read` 把管线已改写好的绝对路径又 `File(root, rel)` 拼了一次
// ⇒ 真目标变成 `<工作区>/storage/…`，同目录的 .xlsx 一律「不存在」；报错回的却是那条
// **看起来对**的绝对路径，模型只能原地重试直到打转熔断。
// 本文件前两组用例钉**解析器本身**（纯函数、跨宿主语义一致），后面几组钉**四个读工具的行为**。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.UserPath
import com.example.zhengdao.testkit.TempSandbox
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SheetPathResolveTest {

    // ── ① 解析器（纯函数） ──────────────────────────────────────────────────

    @Test
    fun `解析器_file三种写法与百分号解码归一`() {
        assertEquals(
            "file:/// 三斜杠（模型最常打的那种）",
            "/storage/emulated/0/证道/成绩表.xlsx",
            UserPath.normalize("file:///storage/emulated/0/证道/成绩表.xlsx"),
        )
        assertEquals(
            "file://localhost/ 也不许把 localhost 当目录",
            "/storage/emulated/0/a.xlsx",
            UserPath.normalize("file://localhost/storage/emulated/0/a.xlsx"),
        )
        assertEquals(
            "File.toURI() 的单斜杠写法",
            "/storage/emulated/0/a.xlsx",
            UserPath.normalize("file:/storage/emulated/0/a.xlsx"),
        )
        assertEquals(
            "%20 是空格（真机上附件目录名常带空格）",
            "/storage/emulated/0/my docs/a.xlsx",
            UserPath.normalize("file:///storage/emulated/0/my%20docs/a.xlsx"),
        )
        assertEquals(
            "「+」是文件名的合法字符，不许被当成空格",
            "/storage/emulated/0/1+1.xlsx",
            UserPath.normalize("file:///storage/emulated/0/1+1.xlsx"),
        )
        assertEquals("普通相对路径一个字符都不许动", "资料库/成绩表.xlsx", UserPath.normalize(" 资料库/成绩表.xlsx "))
    }

    @Test
    fun `解析器_外域scheme认得出且不当路径`() {
        assertEquals("content", UserPath.foreignScheme("content://com.android.providers/1"))
        assertEquals("http", UserPath.foreignScheme("http://example.com/a.xlsx"))
        assertNull("file:// 是同一个文件路径的另一种写法", UserPath.foreignScheme("file:///sdcard/a.xlsx"))
        assertNull("纯路径", UserPath.foreignScheme("/sdcard/a.xlsx"))
        assertNull("Windows 盘符不是 scheme", UserPath.foreignScheme("C:/a.xlsx"))
    }

    @Test
    fun `解析器_绝对路径幂等_不再拼根`() {
        TempSandbox.use("zd613-idem") { root ->
            val abs = File(root, "投递/569-fmt.xlsx")
            // 管线把参数改写成绝对路径后，工具再解析一次必须还是它（569 就是这里拼了第二次）
            assertEquals(abs.absolutePath, UserPath.resolve(root, abs.absolutePath).absolutePath)
            assertEquals(abs.absolutePath, UserPath.resolve(root, "file://" + abs.absolutePath).absolutePath)
            assertEquals(
                "相对路径仍然落根下",
                File(root, "投递/569-fmt.xlsx").absolutePath,
                UserPath.resolve(root, "投递/569-fmt.xlsx").absolutePath,
            )
        }
    }

    // ── ② sheet_read：绝对路径三条 ──────────────────────────────────────────

    @Test
    fun `sheet_read_绝对路径三条都能读_根_子目录_含空格`() {
        TempSandbox.use("zd613-abs") { ws ->
            val sub = File(ws, "投递").apply { mkdirs() }
            val spaced = File(ws, "投递/带 空格 的目录").apply { mkdirs() }
            val cases = listOf(
                Triple(ws, "根表.xlsx", "根上的值"),
                Triple(sub, "子表.xlsx", "子目录的值"),
                Triple(spaced, "空 格表.xlsx", "含空格的值"),
            )
            cases.forEach { (dir, name, cell) ->
                val f = writeXlsx(dir, name, cell)
                val out = SheetReadTool.run(JSONObject().put("path", f.absolutePath), ws)
                assertTrue("绝对路径必须能读：${f.absolutePath} ⇒ $out", out is ToolOutcome.Ok)
                assertTrue(
                    "读到的必须是这个文件的内容（$cell）：${(out as ToolOutcome.Ok).text}",
                    out.text.contains(cell),
                )
            }
        }
    }

    @Test
    fun `sheet_read_相对路径不回归`() {
        TempSandbox.use("zd613-rel") { ws ->
            val sub = File(ws, "资料库").apply { mkdirs() }
            writeXlsx(sub, "成绩表.xlsx", "张三")
            val out = SheetReadTool.run(JSONObject().put("path", "资料库/成绩表.xlsx"), ws)
            assertTrue("相对路径照旧：$out", out is ToolOutcome.Ok)
            assertTrue((out as ToolOutcome.Ok).text.contains("张三"))
        }
    }

    @Test
    fun `sheet_read_file前缀与报错自证`() {
        TempSandbox.use("zd613-file-scheme") { ws ->
            val f = writeXlsx(ws, "前缀.xlsx", "前缀也能读")
            val out = SheetReadTool.run(JSONObject().put("path", "file://" + f.absolutePath), ws)
            assertTrue("file:// 前缀要认：$out", out is ToolOutcome.Ok)

            val ghost = File(ws, "根本没有.xlsx")
            val bad = SheetReadTool.run(JSONObject().put("path", ghost.absolutePath), ws)
            assertTrue(bad is ToolOutcome.Failed)
            val err = (bad as ToolOutcome.Failed).error
            assertTrue("报错要回解析后的绝对路径：$err", err.contains(ghost.absolutePath))
            assertFalse(
                "不许把绝对路径再拼一次根（569 的形态）：$err",
                err.contains(File(ws, ghost.absolutePath).absolutePath),
            )
            assertTrue("要指路 list_dir", err.contains("list_dir"))

            // 回输入串与回解析后路径**不相等**的那一形态：`file://` 前缀进来、报错里只剩路径本体。
            // （只测绝对路径那一条证明不了「自证」——那时输入与解析结果恰好长得一样。）
            val viaScheme = SheetReadTool.run(JSONObject().put("path", "file://" + ghost.absolutePath), ws)
            assertTrue(viaScheme is ToolOutcome.Failed)
            val err2 = (viaScheme as ToolOutcome.Failed).error
            assertTrue("报错要是**解析后**的路径：$err2", err2.contains(ghost.absolutePath))
            assertFalse("不许把 file:// 原样当路径回给模型：$err2", err2.contains("file://"))
        }
    }

    // ── ③ 越界 ──────────────────────────────────────────────────────────────

    @Test
    fun `越界_系统与凭据目录一律拒`() {
        TempSandbox.use("zd613-deny") { ws ->
            listOf("/system/etc/hosts.xlsx", "/data/data/com.other/files/a.xlsx").forEach { raw ->
                val out = SheetReadTool.run(JSONObject().put("path", raw), ws)
                assertTrue("必须拒：$raw ⇒ $out", out is ToolOutcome.Rejected)
                assertTrue(
                    "拒绝要说是哪条红线：$raw ⇒ ${(out as ToolOutcome.Rejected).reason}",
                    out.reason.contains("抱朴不碰"),
                )
            }
            // 凭据段：相对路径里的 .ssh 也要拦（判据按路径段，不按绝对/相对）
            val cred = SheetReadTool.run(JSONObject().put("path", ".ssh/keys.xlsx"), ws)
            assertTrue("凭据目录必须拒：$cred", cred is ToolOutcome.Rejected)
        }
    }

    @Test
    fun `外域scheme_http仍拒并说另立单_content已由628转读`() {
        TempSandbox.use("zd613-scheme") { ws ->
            // 628（2026-11-13）起 `content://` 就是 613 说的那张「另立单」：sheet_read 不再
            // 按外域 scheme 拒，而是转去 ContentUri 开流。JVM 测试没装配 opener ⇒ 结局是
            // 「URI 无权限或已失效」，**不再**说「另立单」，更不许说「不存在」。
            val content = SheetReadTool.run(JSONObject().put("path", "content://media/external/1"), ws)
            assertTrue("content:// 应走 628 的读取分支：$content", content is ToolOutcome.Failed)
            val contentErr = (content as ToolOutcome.Failed).error
            assertTrue("要带 URI 原文：$contentErr", contentErr.contains("content://media/external/1"))
            assertTrue("未装配 opener ⇒ 无权限或已失效：$contentErr", contentErr.contains("URI 无权限或已失效"))
            assertFalse("628 起不许再说「另立单」：$contentErr", contentErr.contains("另立单"))
            assertFalse("更不许报「不存在」：$contentErr", contentErr.contains("不存在"))

            // 628 只放 `content://` 这一种；`http(s)://` 仍是外域 scheme，照旧拒并说另立单。
            val http = SheetReadTool.run(JSONObject().put("path", "http://example.com/a.xlsx"), ws)
            assertTrue("http:// 仍应拒：$http", http is ToolOutcome.Failed)
            val httpErr = (http as ToolOutcome.Failed).error
            assertTrue("要点名是哪一种地址：$httpErr", httpErr.contains("http://"))
            assertTrue("要说清为什么不认：$httpErr", httpErr.contains("另立单"))
        }
    }

    /**
     * 管线级：② 段会把路径参数**改写**成绝对路径，所以 `file://` 必须在改写之前归一，
     * 外域 scheme 也必须在改写之前说清（改完就只剩 `<根>/content:/…`，工具再也认不出来）。
     */
    @Test
    fun `管线级_file前缀归一_外域scheme先于改写拒`() {
        TempSandbox.use("zd613-pipe") { root ->
            val f = writeXlsx(root, "管线表.xlsx", "管线里的值")
            val reg = ToolRegistry().apply {
                register(SheetReadTool.spec { root })
                currentSessionId = 1L
                unlockStore().unlock(1L, ToolGroup.entries.toList())
            }
            val pipe = GuardPipeline(reg, { root }, { null })

            val ok = pipe.execute(
                ToolCall("c1", "sheet_read", JSONObject().put("path", "file://" + f.absolutePath)),
            ) { _, _ -> null }
            assertTrue("管线要认 file://：$ok", ok is GuardPipeline.Result.Done)
            assertTrue(
                "要真读到这个文件：${(ok as GuardPipeline.Result.Done).wrapped}",
                ok.wrapped.contains("管线里的值"),
            )

            // 628：`content://` 由 ② 段的 contentUriGuard 先分流 —— 对 sheet_read **原样透传**
            // （不在这里拒、也不改写），到工具层才按读取端口处理；未装配 opener ⇒ 工具层
            // 给「URI 无权限或已失效」，管线把它记成 Failed（**不是** Rejected：② 段没拦）。
            val content = pipe.execute(
                ToolCall("c2", "sheet_read", JSONObject().put("path", "content://media/external/1")),
            ) { _, _ -> null }
            assertTrue(
                "628 起 content:// 不在 ② 段被拒（应为工具层失败）：$content",
                content is GuardPipeline.Result.Failed,
            )
            val contentErr = (content as GuardPipeline.Result.Failed).error
            assertTrue(
                "原样透传到工具层 ⇒ 未装配 opener 时应给「无权限或已失效」：$contentErr",
                contentErr.contains("URI 无权限或已失效"),
            )

            // `http://` 仍在 ② 段被拒（628 只放 content://），且拒因不是越界。
            val bad = pipe.execute(
                ToolCall("c3", "sheet_read", JSONObject().put("path", "http://example.com/a.xlsx")),
            ) { _, _ -> null }
            assertTrue("管线要拒 http://：$bad", bad is GuardPipeline.Result.Rejected)
            val rejected = bad as GuardPipeline.Result.Rejected
            assertTrue("拒绝原文要点名 scheme：${rejected.reason}", rejected.reason.contains("http://"))
            assertFalse("这不是越界（没打算碰禁区），不许记成越界：$rejected", rejected.scopeEscape)
        }
    }

    // ── ④ 同目录三件（569 的原场景） ────────────────────────────────────────

    @Test
    fun `同目录md_txt_xlsx三件都看得见读得到`() {
        TempSandbox.use("zd613-three") { ws ->
            val dir = File(ws, "投递/20261110").apply { mkdirs() }
            val md = File(dir, "笔记.md").apply { writeText("# 笔记\n内容", Charsets.UTF_8) }
            val txt = File(dir, "正文.txt").apply { writeText("第一行\n第二行", Charsets.UTF_8) }
            val xlsx = writeXlsx(dir, "569-fmt.xlsx", "表里的值")

            val mdOut = ReadFileTool.read(md, 0, 10)
            assertTrue(".md 要读得到：$mdOut", mdOut is ToolOutcome.Ok)
            val txtOut = ReadFileTool.read(txt, 0, 10)
            assertTrue(".txt 要读得到：$txtOut", txtOut is ToolOutcome.Ok)
            val xlsxOut = SheetReadTool.run(JSONObject().put("path", xlsx.absolutePath), ws)
            assertTrue("同一目录的 .xlsx 必须一样能读：$xlsxOut", xlsxOut is ToolOutcome.Ok)

            val outline = BookOutlineTool.run(
                JSONObject().put("path", txt.absolutePath),
                ws,
            )
            assertTrue("book_outline 也走同一处解析：$outline", outline is ToolOutcome.Ok)
        }
    }

    // ── ⑤ 同类工具：book_outline / read_file / table_query ──────────────────

    @Test
    fun `book_outline绝对路径能列_报错回绝对路径`() {
        TempSandbox.use("zd613-outline") { ws ->
            val book = File(File(ws, "书库").apply { mkdirs() }, "第一章.txt")
            book.writeText("第一章 开端\n内容一\n第二章 转折\n内容二\n", Charsets.UTF_8)
            val ok = BookOutlineTool.run(JSONObject().put("path", book.absolutePath), ws)
            assertTrue("绝对路径要能列目录：$ok", ok is ToolOutcome.Ok)

            val ghost = File(ws, "没有这本.txt")
            val bad = BookOutlineTool.run(JSONObject().put("path", ghost.absolutePath), ws)
            assertTrue(bad is ToolOutcome.Failed)
            val err = (bad as ToolOutcome.Failed).error
            assertTrue("报错含解析后的绝对路径：$err", err.contains(ghost.absolutePath))
        }
    }

    @Test
    fun `read_file报错回绝对路径`() {
        TempSandbox.use("zd613-readfile") { ws ->
            val ghost = File(ws, "没有这一篇.md")
            val out = ReadFileTool.read(ghost, 0, 10)
            assertTrue(out is ToolOutcome.Failed)
            assertTrue(
                "报错要能自证找了哪：${(out as ToolOutcome.Failed).error}",
                out.error.contains(ghost.absolutePath),
            )
        }
    }

    @Test
    fun `table_query绝对路径在工作区内认_工作区外明确拒`() {
        TempSandbox.use("zd613-table") { ws ->
            val dir = File(ws, "资料库").apply { mkdirs() }
            File(dir, "成绩表.csv").writeText("姓名,分数\n张三,90\n李四,80\n", Charsets.UTF_8)
            TableQueryTool.resetForTest()
            val inside = TableQueryTool.run(
                JSONObject().put("op", "count").put("table", File(dir, "成绩表.csv").absolutePath),
                ws,
            )
            assertTrue("工作区内的绝对路径要认：$inside", inside is ToolOutcome.Ok)
            val body = JSONObject((inside as ToolOutcome.Ok).text)
            assertTrue("要真答上数：$body", body.getBoolean("ok"))
            assertEquals("出处仍按工作区相对路径写", "资料库/成绩表.csv", body.getJSONObject("evidence").getString("file"))
            assertFalse(
                "不许再回「只接受相对路径」：$body",
                inside.text.contains("表名只接受工作区里的相对路径"),
            )

            // 工作区外：如实说「不在工作区」并指路 sheet_read（不许硬读、也不许拿去瞎匹配）
            val outsideFile = File(ws.absolutePath + File.separator + "..", "外部表.csv")
            val outside = TableQueryTool.run(
                JSONObject().put("op", "count").put("table", outsideFile.absolutePath),
                ws,
            )
            assertTrue("工作区外要拒：$outside", outside is ToolOutcome.Rejected)
            val reason = (outside as ToolOutcome.Rejected).reason
            assertTrue("要说清不在工作区：$reason", reason.contains("不在工作区"))
            assertTrue("要指路 sheet_read：$reason", reason.contains("sheet_read"))

            // 既有判据不回归：`..` 仍然拒
            val dotdot = TableQueryTool.run(JSONObject().put("op", "count").put("table", "../别的表.xlsx"), ws)
            assertTrue("`..` 仍然拒：$dotdot", dotdot is ToolOutcome.Rejected)
        }
    }

    // ── 造夹具：最小 xlsx（真 zip + 四块 XML，与 ToolsV1Test 同一配方） ─────

    private fun writeXlsx(dir: File, name: String, cell: String): File {
        val f = File(dir, name)
        ZipOutputStream(f.outputStream().buffered()).use { z ->
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
        return f
    }
}
