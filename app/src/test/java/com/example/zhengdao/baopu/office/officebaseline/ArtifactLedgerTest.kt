// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百四十五批 · 判据④（产物台账接线，复用 685 那张表）。
//
// 深测产出物本身也要能进台账、能用其他应用打开；本单不新增第二份映射，
// 直接断言 docx/xlsx 走 685 的 ArtifactKinds.TABLE ⇒ Doc 族 + OOXML MIME + Ready。
package com.example.zhengdao.baopu.office.officebaseline

import com.example.zhengdao.baopu.ArtifactFamily
import com.example.zhengdao.baopu.ArtifactKinds
import com.example.zhengdao.baopu.FileOpLog
import com.example.zhengdao.baopu.agent.ToolOutcome
import com.example.zhengdao.baopu.agent.DocxWriteTool
import com.example.zhengdao.baopu.agent.XlsxWriteTool
import com.example.zhengdao.ui.baopu.ArtifactOpen
import com.example.zhengdao.ui.baopu.ArtifactOpenPlan
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ArtifactLedgerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var ws: File

    @Before
    fun setUp() {
        ws = tmp.newFolder("ws")
        FileOpLog.resetForTest()
        FileOpLog.enterSession(745L)
    }

    @After
    fun tearDown() {
        FileOpLog.resetForTest()
    }

    private fun produce(): List<File> {
        assertTrue("docx 生成成功", DocxWriteTool.run(docxArgs(), ws) is ToolOutcome.Ok)
        assertTrue("xlsx 生成成功", XlsxWriteTool.run(xlsxArgs(), ws) is ToolOutcome.Ok)
        return File(ws, "产物").listFiles().orEmpty().filter { it.name.endsWith(".docx") || it.name.endsWith(".xlsx") }
    }

    @Test
    fun `判据4_深测产物进台账且能用其他应用打开`() {
        val products = produce()
        assertEquals("两份产物", 2, products.size)

        for (file in products) {
            val ext = file.name.substringAfterLast('.')
            // 复用 685 表：准入 + Doc 族
            assertTrue("$ext 应被台账准入", ArtifactKinds.isAdmitted(ext))
            assertEquals("$ext 属于 Doc 族", ArtifactFamily.Doc, ArtifactKinds.familyOf(ext))

            // 「用其他应用打开」拿得到 OOXML MIME
            val mime = ArtifactOpen.mimeOf(file.name)
            val expectedMime = when (ext) {
                "docx" -> ArtifactKinds.MIME_DOCX
                "xlsx" -> ArtifactKinds.MIME_XLSX
                else -> error("意外的产物类型 $ext")
            }
            assertEquals("$ext 的打开 MIME", expectedMime, mime)
            assertTrue("MIME 是 OOXML", mime!!.startsWith("application/vnd.openxmlformats"))

            // 盘上有文件 ⇒ 打开计划是 Ready（不是 Unsupported）
            val plan = ArtifactOpen.planOf(file.name, onDisk = true)
            assertTrue("$ext 应可打开：$plan", plan is ArtifactOpenPlan.Ready)
            assertEquals("Intent 携带正确 MIME", expectedMime, (plan as ArtifactOpenPlan.Ready).spec.mime)
        }
    }

    @Test
    fun `判据4_表里没有的格式不进台账也不发打开Intent`() {
        // 负例：685 的硬规则「表里没有 mime ⇒ 不登记」
        for (ext in listOf("doc", "xls", "pdf", "exe")) {
            assertTrue(".$ext 不该被准入", !ArtifactKinds.isAdmitted(ext))
            assertEquals(".$ext 不该有打开 MIME", null, ArtifactOpen.mimeOf("file.$ext"))
            assertTrue(".$ext 打开计划应是 Unsupported", ArtifactOpen.planOf("file.$ext", true) is ArtifactOpenPlan.Unsupported)
        }
    }
}
