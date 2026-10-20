// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百批 §6 E1–E6：**源码门禁**。每条门禁都指向一条会出人命的红线：
// 自动回滚（文件被换掉）、L3 开关被打开（绕过人批）、后台定时器（偷偷干活）、
// 新样式（视觉漂移）。门禁本身必须能红——负例见交付报告。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PostAdoptionGateTest {

    private fun repoFile(rel: String): File = File(rel).let { if (it.isFile) it else File("app/$rel") }

    private fun raw(rel: String): String =
        repoFile(rel).readText(Charsets.UTF_8).replace("\r\n", "\n")

    private val mainRoot = "src/main/java/com/example/zhengdao"

    private val policy = raw("$mainRoot/baopu/EvolutionPolicy.kt")
    private val rollback = raw("$mainRoot/baopu/PostAdoptionRollback.kt")
    private val model = raw("$mainRoot/baopu/PostAdoption.kt")
    private val port = raw("$mainRoot/baopu/PostAdoptionPort.kt")
    private val log = raw("$mainRoot/baopu/PostAdoptionLog.kt")
    private val card = raw("$mainRoot/ui/baopu/BaopuPostAdoptionCard.kt")
    private val controller = raw("$mainRoot/ui/baopu/BaopuChatController.kt")

    /** 整个 main 源码树（门禁要扫全仓，不能只看新文件）。 */
    private val allMain: List<File> by lazy {
        repoFile(mainRoot).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun allMainTexts(): List<Pair<String, String>> = allMain.map {
        it.path.replace('\\', '/') to it.readText(Charsets.UTF_8)
    }

    @Test
    fun `E1_L3开关仍false且没有生产实例开它`() {
        assertTrue(
            "L3 低风险自动开关必须保持 false",
            policy.contains("const val L3_AUTO_LOW_RISK = false"),
        )
        val offenders = allMainTexts().filter { (_, text) ->
            text.contains("autoLowRiskEnabled = true") || text.contains("L3_AUTO_LOW_RISK = true")
        }.map { it.first }
        assertTrue("不许有生产代码打开 L3：$offenders", offenders.isEmpty())
    }

    @Test
    fun `E2_没有自动回滚与自动退役的开关`() {
        val words = listOf("autoRollback", "autoRetire", "autoKeep")
        val offenders = allMainTexts().filter { (_, text) -> words.any { text.contains(it) } }.map { it.first }
        assertTrue("不许出现自动回滚/退役的开关：$offenders", offenders.isEmpty())
    }

    @Test
    fun `E3_restore只出现在人批函数内`() {
        // ① 本批的模型/端口/账本文件一次都不许调 restore
        listOf(model, port, log).forEach {
            assertFalse("这些文件里不许出现 restore(", it.contains("SkillStore.restore("))
            assertFalse("这些文件里不许出现 restore(", it.contains("PersonaBackup.restore("))
        }
        // ② 唯一允许的地方：applyOnUserApproval 里，且只有学习卡通道一处 + 人设通道一处
        val gate = rollback.indexOf("fun applyOnUserApproval(")
        assertTrue("人批函数必须存在", gate >= 0)
        val hits = Regex("(SkillStore|PersonaBackup)\\.restore\\(").findAll(rollback).toList()
        assertEquals("本批只允许两处 restore（技能 + 人设）", 2, hits.size)
        hits.forEach { assertTrue("restore 必须在 applyOnUserApproval 之内", it.range.first > gate) }
        // ③ 人批标记必须非空才动文件
        assertTrue(rollback.contains("if (approvedBy.isBlank()) return PostAdoptionRollbackResult.Rejected"))
    }

    @Test
    fun `E4_没有后台定时器也没有新权限`() {
        val banned = listOf("WorkManager", "AlarmManager", "JobScheduler", "setRepeating", "PeriodicWorkRequest")
        (listOf(model, port, log, card) + controller).forEachIndexed { i, text ->
            banned.forEach { word ->
                assertFalse("第 $i 份文件里出现了 $word（本批不许偷偷定时跑）", text.contains(word))
            }
        }
    }

    @Test
    fun `E5_outcomesFor有生产调用点`() {
        assertTrue(
            "复测必须真的读 85 的效果聚合（不是自己再算一份）",
            controller.contains("SkillEffects.outcomesFor(db"),
        )
    }

    @Test
    fun `E6_新卡不引新视觉常量与动效`() {
        assertFalse("不许自造颜色", card.contains("Color("))
        assertFalse("不许加动效", card.contains("animate") || card.contains("tween("))
        assertFalse("不许加渐变/画笔", card.contains("Brush."))
        // 与学习卡同口径：同样的 Surface 高度与内距
        assertTrue(card.contains("tonalElevation = 3.dp"))
        assertTrue(card.contains("padding(horizontal = 12.dp, vertical = 10.dp)"))
    }

    @Test
    fun `E7_卡上的回滚按钮只在有回滚路时可用`() {
        assertTrue(
            "RETIRE（没备份）不许给可点的回滚按钮",
            card.contains("enabled = notice.verdict == PostAdoptionVerdict.ROLLBACK"),
        )
    }
}
