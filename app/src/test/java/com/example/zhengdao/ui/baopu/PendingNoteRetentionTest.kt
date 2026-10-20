// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.ui.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第四十九批 · 任务 B 的源码门禁：pendingNote 留痕（接上现成组件）。
 *
 * 现场（基线 `1d709f32`）：
 * - `BaopuScreen.kt` 里 `pendingNote` 的 `LaunchedEffect` 等 **8 秒**就 `clearPendingNote()`；
 * - 显示处是裸 `Text(labelSmall, zhengdaoErrorText())`，**不可点、不可关**；
 * - 而 `pendingNote` 装的是**错误原因**（`BaopuChatController.kt:984` = `r.reason`，记忆写盘失败／不合格）
 *   ⇒ 8 秒后用户什么都看不到，且没有任何地方能再调出来。
 * - 仓库里 `BaopuMemoryCard.kt:155` 的 `BaopuIntrospectionNote(text, onDismiss)` **早就写好**（Surface ＋ 标题 ＋ `×`），
 *   全仓零调用 —— 本批就是把它接上。
 *
 * 五组判据（源码文本级，先剥注释再断言）：
 * ① `pendingNote` 不许再自动清（`LaunchedEffect(pendingNote)` 不许存在，全文件 `delay(8_000)` 只许剩 2 处）；
 * ② 显示处必须换成 `BaopuIntrospectionNote(`，且 `onDismiss` 里调 `clearPendingNote()`；
 * ③ `libraryNote` / `skillNote` 的 8 秒自清仍在、清理入口没换（**2026-10-20 第六十一批 §2 修订**：
 *    原句「一行不动」的前提是那两类还没分级，§2 来分级了 ⇒ 这一条改成钉「进度仍自清、
 *    且必须先问级别」，见那个用例的注释；①④⑤ 三条一字未动）；
 * ④ 组件本体确实认 `onDismiss`（不传就不显示 `×`、不可点）；
 * ⑤ 全文件只许有**一处** `clearPendingNote()` 调用（点 `×` 那一条路，不许别处偷清）。
 */
class PendingNoteRetentionTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    private fun code(rel: String): String = repoFile(rel).readText()
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private fun screen() = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuScreen.kt")

    private fun card() = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuMemoryCard.kt")

    /** pendingNote 的显示块（到 skillNote 那块之前为止）。 */
    private fun pendingBlock(src: String): String {
        val i = src.indexOf("controller.pendingNote?.let { note ->")
        assertTrue("pendingNote 的显示块还在（本批只换组件，不该整块删掉）", i >= 0)
        val j = src.indexOf("controller.skillNote?.let", i)
        assertTrue("skillNote 的显示块还在（不许顺手改）", j > i)
        return src.substring(i, j)
    }

    @Test
    fun `错误原因不再八秒自清`() {
        val src = screen()
        assertFalse(
            "pendingNote 的 LaunchedEffect 没删干净 —— 错误还是会自己消失",
            src.contains("LaunchedEffect(pendingNote)"),
        )
        assertEquals(
            "delay(8_000) 只该剩 libraryNote／skillNote 两处",
            2,
            Regex("delay\\(8_000\\)").findAll(src).count(),
        )
        assertFalse("clearPendingNote 不许再由自动通道调用（只许挂在 onDismiss 上）",
            Regex("LaunchedEffect\\([^)]*\\)[^{]*\\{[^}]*clearPendingNote").findAll(src).count() > 0)
    }

    @Test
    fun `换成现成的可关便签组件`() {
        val block = pendingBlock(screen())
        assertTrue("显示处必须换成 BaopuIntrospectionNote(", block.contains("BaopuIntrospectionNote("))
        assertTrue(
            "onDismiss 必须接 controller.clearPendingNote()（点 × 立即消失）",
            block.contains("onDismiss = { controller.clearPendingNote() }"),
        )
        assertFalse("裸 Text 不许留着（不可点不可关那一型）", block.contains("Text("))
        assertEquals("本批只有一处 clearPendingNote 调用", 1, Regex("clearPendingNote\\(\\)").findAll(screen()).count())
    }

    @Test
    fun `library与skill的八秒自清只剩进度那一档`() {
        // 第四十九批当时钉的是「这两个块一行不动」——那句的前提是**还没分类**
        // （那批的派工单 §3 原话：没分类之前去掉自清会把进度钉死）。
        // 第六十一批 §2 正是来分类的：Info 仍走这同一个 8 秒定时器，Error 不挂定时器、
        // 只走 `×`。所以这里保留 49 批真正关心的两件事（8 秒还在、清理入口没换），
        // 并把「必须先问级别」加进来——那是 §2 新判据，不是把 49 批的判据放宽。
        val src = screen()
        for ((key, clearer) in listOf("libraryNote" to "clearLibraryNote()", "skillNote" to "clearSkillNote()")) {
            val i = src.indexOf("LaunchedEffect($key)")
            assertTrue("$key 的 LaunchedEffect 必须还在", i >= 0)
            val block = src.substring(i, minOf(src.length, i + 200))
            assertTrue("$key 仍走 8 秒自清（进度类一句话，不许钉在屏上）", block.contains("delay(8_000)"))
            assertTrue("$key 的清理入口没换", block.contains("controller.$clearer"))
            assertTrue(
                "$key 的 8 秒自清必须先问级别（第六十一批 §2：错误不许自己走）",
                block.contains("$key.autoClears"),
            )
        }
    }

    @Test
    fun `组件本体认onDismiss`() {
        val src = card()
        assertTrue("组件还在", src.contains("internal fun BaopuIntrospectionNote("))
        assertTrue("onDismiss 是可空回调", src.contains("onDismiss: (() -> Unit)? = null"))
        assertTrue("传了 onDismiss 才让整条可点掉", src.contains("Modifier.clickable { onDismiss() }"))
        assertTrue("标题用现成键（本批不新增字符串）", src.contains("R.string.baopu_introspection_label"))
    }

    @Test
    fun `中英文都已有那个标题键`() {
        for (rel in listOf("src/main/res/values/strings.xml", "src/main/res/values-en/strings.xml")) {
            val text = repoFile(rel).readText()
            assertTrue("$rel 缺 baopu_introspection_label", text.contains("name=\"baopu_introspection_label\""))
        }
    }
}
