// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.ui.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第一百七十八批 · 勾选批量删的**接线门禁**（判据 J8–J11 ＋ 负例）。
 *
 * 与 36/38 批同族：行为层在 `ArtifactDeleteRulesTest`（真临时目录），这里只钉「接线上不许走样」：
 * - J8 只复用既有出口：`moveArtifactToTrash`（移回收站）＋ `ArtifactDeleteRules`（台账）；
 * - J9 红线：本批不许出现任何真删（`.delete()` / `deleteRecursively` / 工具层删除）；
 * - J10 一句确认：闸在纯函数里，界面把 `confirming` 传进去；没勾选时大按钮不可点；
 * - J11 文案中英对称、每条资源都有人用。
 *
 * 每条「不许出现 X」都配判别器自证，防门禁空跑（38 批同款纪律）。
 */
class BaopuArtifactPickerGateTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    private fun strip(path: String): String = repoFile(path).readText(Charsets.UTF_8)
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private val pickerRel = "src/main/java/com/example/zhengdao/ui/baopu/BaopuArtifactPicker.kt"
    private val shelfRel = "src/main/java/com/example/zhengdao/ui/baopu/BaopuArtifactShelf.kt"
    private val zhRel = "src/main/res/values/strings.xml"
    private val enRel = "src/main/res/values-en/strings.xml"

    private fun picker() = strip(pickerRel)
    private fun shelf() = strip(shelfRel)
    private fun zh() = repoFile(zhRel).readText(Charsets.UTF_8)
    private fun en() = repoFile(enRel).readText(Charsets.UTF_8)

    private fun keys(text: String): Map<String, String> =
        Regex("""<string name="(baopu_artifact_picker_[a-z0-9_]+)"[^>]*>(.*?)</string>""")
            .findAll(text)
            .associate { it.groupValues[1] to it.groupValues[2] }

    private fun placeholders(text: String): List<String> =
        Regex("%\\d\\$[a-zA-Z]").findAll(text).map { it.value }.toList()

    // ── J8：只复用既有出口 ────────────────────────────────────────────────

    @Test
    fun `J8_移文件走38批的moveArtifactToTrash_台账走178的Rules`() {
        val src = picker()
        assertTrue("移回收站必须复用 38 批那个函数", src.contains("moveArtifactToTrash("))
        assertTrue("台账标记走 ArtifactDeleteRules", src.contains("ArtifactDeleteRules.markDeletedInLedger"))
        assertTrue("落点/台账选择复用 179", src.contains("BaopuLongformStore.vaultRoot") && src.contains("BaopuLongformPolicy.pickLedger"))
        assertTrue("每个文件都先验它仍在产物目录这一层", src.contains("ArtifactDeleteRules.fileIn(productDir, name)"))
        assertFalse("回收站目录不许在这里另拼一次", src.contains("\"回收站\""))
    }

    // ── J9：红线（不真删）────────────────────────────────────────────────

    @Test
    fun `J9_红线_本批没有真删_也不调工具层`() {
        val src = picker()
        assertFalse("不许 .delete()", Regex("""\.delete\(\)""").containsMatchIn(src))
        assertFalse("不许删目录", src.contains("deleteRecursively"))
        assertFalse("不许出现工具层删除", src.contains("DeletePathTool"))
        assertFalse("不许 import 工具层包", src.contains("import com.example.zhengdao.baopu.agent"))
        assertFalse("原件区不在本批路径里", src.contains("原始"))
        // 判别器自证：上面两条抓得住真代码里的坏形状
        assertTrue("判别器：.delete()", Regex("""\.delete\(\)""").containsMatchIn("File(\"x\").delete()"))
        assertTrue("判别器：deleteRecursively", "tree.deleteRecursively()".contains("deleteRecursively"))
    }

    // ── J10：一句确认 ＋ 只删可见选中 ─────────────────────────────────────

    @Test
    fun `J10_确认闸在纯函数里_没勾选不可点_列表外的选中被剔掉`() {
        val src = picker()
        assertTrue("确认闸必须走 Rules", src.contains("ArtifactDeleteRules.batchGate("))
        assertTrue("把界面确认位传进闸", src.contains("confirmed = confirming"))
        assertTrue("没勾选时大按钮不可点", src.contains("enabled = selected.isNotEmpty() && !busy"))
        assertTrue("筛完只留仍在列表里的", src.contains("selected.filter { it in visibleNames }"))
        assertTrue("下次一定重新问", src.contains("confirming = false"))
        assertTrue("删完清空选中", src.contains("selected = emptySet()"))
    }

    @Test
    fun `J10_审计一行_失败也要说话`() {
        val src = picker()
        assertTrue("批次摘要写运行日志", src.contains("RunLog.log("))
        assertTrue("失败分支必须用资源说话", src.contains("R.string.baopu_artifact_picker_failed_partial"))
        assertTrue("台账没写成也要说", src.contains("R.string.baopu_artifact_picker_ledger_failed"))
        // 红字不直接拿 error 色当文字（3.55:1 不达 AA）
        assertTrue(src.contains("zhengdaoErrorText()"))
        assertFalse(src.contains("colorScheme.error"))
    }

    // ── 入口 ──────────────────────────────────────────────────────────────

    @Test
    fun `J11_入口在产物面板_只开这一层`() {
        val src = shelf()
        assertTrue("产物面板底部有入口", src.contains("R.string.baopu_artifact_picker_open"))
        assertTrue("调用的是 178 的弹窗", src.contains("ArtifactPickerDialog("))
        assertEquals("入口只有一处", 1, Regex("ArtifactPickerDialog\\(").findAll(src).count())
    }

    // ── J11：中英文案对称 ─────────────────────────────────────────────────

    @Test
    fun `J11_中英资源键与占位符对称_且每条都有人用`() {
        val zhKeys = keys(zh())
        val enKeys = keys(en())
        assertTrue("至少十条", zhKeys.size >= 10)
        assertEquals("中英键集合必须一致：${zhKeys.keys - enKeys.keys}", zhKeys.keys, enKeys.keys)
        zhKeys.forEach { (k, v) ->
            assertEquals("占位符不一致：$k", placeholders(v), placeholders(enKeys.getValue(k)))
        }
        val src = picker() + "\n" + shelf()
        zhKeys.keys.forEach { k ->
            assertTrue("资源没人用：$k", src.contains("R.string.$k"))
        }
    }
}
