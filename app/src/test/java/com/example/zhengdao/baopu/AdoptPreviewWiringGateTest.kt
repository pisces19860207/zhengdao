// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百一十八批门禁：**确认层的「前／后」不许再各算一份**（缺陷 539-(5) 的复发闸）。
//
// 六条真值都从 `src/main` 现读：
//   G1 弹窗渲染取 `pendingAdoptDiff`，且那一段里不再出现候选 payload；
//   G2 打开确认层时两侧都走 `AdoptPreviewRules`（人设走 personaAppend、技能走 skillBody）；
//   G3 写人设的那条通道也走 `AdoptPreviewRules.personaAppend`（写盘与预览同一个函数）——
//      并且不许退回「自己拼 `\n\n`」的旧写法；
//   G4 技能「后」与 `writeLearnedSkill` 是同一个表达式 `SkillRefine.skillBodyFor(`；
//   G5 展示层只认 `before`／`after` 两个字段（不许有人再从 payload 现算一份塞进弹窗）。
//
// 为什么用源码门禁而不是真跑 UI：确认层要 Context＋协程＋盘上人设，JVM 里跑不起来；
// 而这次要钉的恰恰是「取值来源」这一行——源码位置就是判据本身。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AdoptPreviewWiringGateTest {

    private val src = File("src/main/java/com/example/zhengdao")

    private fun text(rel: String): String {
        val f = File(src, rel)
        assertTrue("文件不见了：${f.absolutePath}（门禁按它找接线点）", f.isFile)
        return f.readText(Charsets.UTF_8)
    }

    /**
     * 取 `start` 之后、`end` 之前的正文（两个锚都必须找得到，找不到直接红——
     * 锚点改名意味着门禁失效，不许静默通过）。
     */
    private fun region(body: String, start: String, end: String): String {
        val a = body.indexOf(start)
        assertTrue("锚点不见了（门禁失效）：$start", a >= 0)
        val b = body.indexOf(end, a + start.length)
        assertTrue("收尾锚点不见了（门禁失效）：$end", b > a)
        return body.substring(a, b)
    }

    private val controller = "ui/baopu/BaopuChatController.kt"
    private val screen = "ui/baopu/BaopuScreen.kt"

    @Test
    fun `G1_弹窗渲染只认pendingAdoptDiff_不再渲染候选payload`() {
        val dialog = region(
            text(screen),
            "controller.pendingAdoptConfirm?.let",
            "if (showSessionLibrary)",
        )
        assertTrue("弹窗没取 pendingAdoptDiff?.before", dialog.contains("pendingAdoptDiff?.before"))
        assertTrue("弹窗没取 pendingAdoptDiff?.after", dialog.contains("pendingAdoptDiff?.after"))
        assertFalse("弹窗又拿候选的 payload 当「采纳后」了（539-(5) 复发）", dialog.contains("payload"))
        // 两个标签仍在（这次只换取值来源，不动显示格式）
        assertTrue(dialog.contains("decision_adopt_confirm_before"))
        assertTrue(dialog.contains("decision_adopt_confirm_after"))
    }

    @Test
    fun `G2_打开确认层两侧都走共享算法`() {
        val open = region(text(controller), "private fun openAdoptConfirm(", "fun decideSuggestion(")
        assertTrue("人设类没走 AdoptPreviewRules.personaAppend", open.contains("AdoptPreviewRules.personaAppend("))
        assertTrue("技能类没走 AdoptPreviewRules.skillBody", open.contains("AdoptPreviewRules.skillBody("))
        assertTrue("技能的「前」没按落点文件读", open.contains("AdoptPreviewRules.skillTargetFile("))
        assertFalse("确认层又在自己拼分隔符了", open.contains("\"\\n\\n\""))
    }

    @Test
    fun `G3_写人设与预览同一个函数_且没退回手工拼接`() {
        val append = region(text(controller), "private fun appendSuggestionPersona(", "建议卡动作审计")
        assertTrue("写盘没走 AdoptPreviewRules.personaAppend（又会分叉）", append.contains("AdoptPreviewRules.personaAppend("))
        assertFalse("写盘又自己拼 \"\\n\\n\" 了（这是 539-(5) 的原始写法）", append.contains("\"\\n\\n\""))
        assertFalse("写盘又自己 trimEnd 拼串了", append.contains("trimEnd() + \"\\n"))
    }

    @Test
    fun `G4_技能采纳后与写盘是同一个表达式`() {
        val open = region(text(controller), "private fun openAdoptConfirm(", "fun decideSuggestion(")
        val write = region(text(controller), "private fun writeLearnedSkill(", "private fun ")
        assertTrue("确认层没算包装后的正文", open.contains("SkillRefine.skillBodyFor("))
        assertTrue("写盘口没算包装后的正文", write.contains("SkillRefine.skillBodyFor("))
    }

    @Test
    fun `G5_两处都从共享算法取_且全main没有第二份追加拼接`() {
        val whole = text("baopu/AdoptPreviewRules.kt")
        assertTrue("追加算法不在 AdoptPreviewRules 里", whole.contains("fun appended("))
        // 计数闸：预览（openAdoptConfirm）与写盘（appendSuggestionPersona）各一次。
        // 少一处 ⇒ 说明有人又自己算了一份（「只接了一边」是这次缺陷的原形）。
        val calls = text(controller).split("AdoptPreviewRules.personaAppend(").size - 1
        assertEquals("personaAppend 在控制器里应恰好两处（确认层 + 写盘），实际 $calls 处", 2, calls)
        // 人设落点文件里，`原正文 ＋ 空行 ＋ 新增` 这种手工拼接一份都不许有（共享算法在 appended 里）。
        // 只扫「会造 PersonaProposal 的文件」：输入框草稿合并那类同样字形但不同语义的拼接不在范围内。
        val offenders = File(src, ".")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { f ->
                val t = f.readText(Charsets.UTF_8)
                t.contains("PersonaProposal(") && t.contains("trimEnd() + \"\\n\\n\"")
            }
            .map { it.relativeTo(src).path }
            .toList()
        assertTrue("这些文件里还有第二份「原正文＋空行＋新增」拼接：$offenders", offenders.isEmpty())
    }
}
