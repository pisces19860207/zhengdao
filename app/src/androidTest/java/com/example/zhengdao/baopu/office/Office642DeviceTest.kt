// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百四十二批（2026-11-14）**真机验机 V51**：轻办公二期的四件事在真手机上跑一遍。
//
// ## 为什么这几条必须在设备上
// - **HEIC**：解码是平台件（`BitmapFactory` 里的 HEIF 解码器，API 28+）。JVM 单测里
//   它是桩，只能证明"分流对"，证明不了"这台手机真能解出 PNG"；
// - **页脚页码**：域的 XML 在 JVM 里能断言形状，但"手机上产出的文件拿去 Word 里能不能算对"
//   要一份真机产出的文件；
// - **.doc 抽取 / 模板替换**：都是纯逻辑，但要证明"落盘位置（产物/）在真机文件系统上也对"。
//
// ## 怎么跑（本仓规矩：`install` ＋ `am instrument`，**不用** `connectedDebugAndroidTest`）
// ```
// ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
// adb install -r app/build/outputs/apk/debug/app-debug.apk
// adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
// adb shell am instrument -w -e class com.example.zhengdao.baopu.office.Office642DeviceTest \
//   com.example.zhengdao.test/androidx.test.runner.AndroidJUnitRunner
// ```
// 输入件（example.heic / real.doc / 模板-*.docx）放
// `/sdcard/Android/data/com.example.zhengdao/files/zd642-in/`（App 自己的外部目录，无需授权）；
// 产出与读数写 `.../files/zd642-out/`，跑完 `adb pull` 回来复核。
package com.example.zhengdao.baopu.office

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.zhengdao.baopu.agent.DocxTemplateTool
import com.example.zhengdao.baopu.agent.DocxWriteTool
import com.example.zhengdao.baopu.agent.ReadFileTool
import com.example.zhengdao.baopu.agent.ToolOutcome
import java.io.File
import java.util.zip.ZipInputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Office642DeviceTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val inbox: File = File(ctx.getExternalFilesDir(null), "zd642-in")
    private val outRoot: File = File(ctx.getExternalFilesDir(null), "zd642-out")
    private val report = StringBuilder()

    @Test
    fun v51_轻办公二期_四件在真机上跑通() {
        outRoot.mkdirs()
        inbox.mkdirs()
        report.appendLine("V51|sdk=${android.os.Build.VERSION.SDK_INT}|device=${android.os.Build.MODEL}")
        report.appendLine("V51|inbox=${inbox.absolutePath}|exists=${inbox.isDirectory}")

        footerPageNumbers()
        dotDocRead()
        heicToPng()
        templateFill()

        File(outRoot, "V51-report.txt").writeText(report.toString())
        assertTrue("报告要落盘", File(outRoot, "V51-report.txt").isFile)
    }

    // ── ① 页脚页码：手机上产出的 docx，页脚里有 PAGE/NUMPAGES 两个域 ────────────

    private fun footerPageNumbers() {
        val blocks = JSONArray().put(
            JSONObject().put("type", "heading").put("level", 1).put("text", "V51 页码"),
        )
        for (i in 1..40) {
            blocks.put(JSONObject().put("type", "paragraph").put("text", "第 $i 段：真机上写的一页页内容。"))
        }
        val args = JSONObject()
            .put("name", "V51-页码")
            .put("header", "真机内部资料")
            .put("footer", "青灯科技")
            .put("footer_pages", true)
            .put("blocks", blocks)
        val out = DocxWriteTool.run(args, outRoot)
        report.appendLine("V51|footer=$out")
        assertTrue("页脚页码要写成功：$out", out is ToolOutcome.Ok)
        val docx = products().last { it.name.startsWith("V51-页码") }
        val footer = entryText(docx, "word/footer1.xml")
        val instr = Regex("<w:instrText[^>]*>([^<]*)</w:instrText>")
            .findAll(footer).map { it.groupValues[1].trim() }.toList()
        report.appendLine("V51|footer_instr=$instr")
        report.appendLine("V51|footer_has_CN=" + footer.contains("第 ") + "|docx=${docx.name}|${docx.length()}B")
        assertTrue("页脚要带 PAGE/NUMPAGES：$instr", instr == listOf("PAGE", "NUMPAGES"))
    }

    // ── ② .doc 只读抽取 ────────────────────────────────────────────────────

    private fun dotDocRead() {
        val doc = File(inbox, "real.doc")
        if (!doc.isFile) {
            report.appendLine("V51|doc_skip=没有 real.doc")
            return
        }
        val out = ReadFileTool.read(doc, 0, 5)
        report.appendLine("V51|doc=$out")
        assertTrue("真 .doc 要能读：$out", out is ToolOutcome.Ok)

        val fake = File(inbox, "fake.doc").apply { writeText("我其实是个 txt") }
        val bad = ReadFileTool.read(fake, 0, 5)
        report.appendLine("V51|doc_fake=$bad")
        assertTrue("假 .doc 要明确报只支持 .docx", bad is ToolOutcome.Failed && "只支持 .docx" in bad.error)
    }

    // ── ③ HEIC：平台解码器真解，进包的必须是 PNG ─────────────────────────────

    private fun heicToPng() {
        val heic = File(inbox, "example.heic")
        if (!heic.isFile) {
            report.appendLine("V51|heic_skip=没有 example.heic")
            return
        }
        val args = JSONObject()
            .put("name", "V51-heic")
            .put(
                "blocks",
                JSONArray()
                    .put(JSONObject().put("type", "paragraph").put("text", "下面这张原图是 HEIC："))
                    .put(JSONObject().put("type", "image").put("path", heic.absolutePath)),
            )
        // 不注入解码器 ⇒ 走生产默认的 AndroidHeicDecode（真·平台解码）
        val out = DocxWriteTool.run(args, outRoot)
        report.appendLine("V51|heic=$out")
        assertTrue("HEIC 要能解成 PNG 再嵌：$out", out is ToolOutcome.Ok)
        val docx = products().last { it.name.startsWith("V51-heic") }
        val media = entryNames(docx).filter { it.startsWith("word/media/") }
        val info = media.firstOrNull()?.let { ImageProbe.probe(entryBytes(docx, it)) }
        report.appendLine("V51|heic_media=$media|info=$info")
        assertTrue("包里必须只有一张 .png：$media", media.size == 1 && media[0].endsWith(".png"))
        assertTrue("嵌进去的像素要是 PNG 头：$info", info != null && info.ext == "png" && info.width > 0)
    }

    // ── ④ 模板替换：填对了换、缺字段报错 ─────────────────────────────────────

    private fun templateFill() {
        val tpl = inbox.listFiles().orEmpty().firstOrNull { it.name.startsWith("模板") && it.name.endsWith(".docx") }
        if (tpl == null) {
            report.appendLine("V51|template_skip=没有模板 *.docx")
            return
        }
        val ok = DocxTemplateTool.run(
            JSONObject().put("template", tpl.absolutePath)
                .put("name", "V51-填好")
                .put("fields", JSONObject().put("姓名", "张三").put("日期", "2026-11-14")
                    .put("地点", "三号会议室").put("单位", "青灯科技")),
            outRoot,
        )
        report.appendLine("V51|template_ok=$ok")
        assertTrue("模板要能填：$ok", ok is ToolOutcome.Ok)

        val missing = DocxTemplateTool.run(
            JSONObject().put("template", tpl.absolutePath)
                .put("fields", JSONObject().put("姓名", "张三")),
            outRoot,
        )
        val reason = (missing as? ToolOutcome.Failed)?.error ?: ""
        report.appendLine("V51|template_missing=$reason")
        assertTrue("缺字段要点名：$reason", reason.contains("缺少字段") && reason.contains("日期"))
    }

    // ── 小工具 ────────────────────────────────────────────────────────────

    private fun products(): List<File> = File(outRoot, "产物").listFiles().orEmpty().filter { it.isFile }

    private fun entryNames(file: File): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                names += e.name
            }
        }
        return names
    }

    private fun entryText(file: File, name: String): String =
        entryBytes(file, name).toString(Charsets.UTF_8)

    private fun entryBytes(file: File, name: String): ByteArray {
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.name == name) return zip.readBytes()
            }
        }
        throw AssertionError("包里没有 $name")
    }
}
