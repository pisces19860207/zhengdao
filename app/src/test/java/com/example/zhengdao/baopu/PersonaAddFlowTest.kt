// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单第二百八十五批 §1（用户加人设：入账 谁/何时/原文 → 生效）
// ＋ §2（出厂兜底／恢复出厂不备份）＋ 判据②③（一键恢复可用／重启仍在＋账上有记录）。
package com.example.zhengdao.baopu

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PersonaAddFlowTest {

    private lateinit var root: File
    private lateinit var workspace: File

    private val factory = "## 规矩\n- 出厂条文一\n"

    private fun files() = PersonaFiles(
        globalRoot = root,
        projectRoot = File(workspace, PersonaPackRules.DIR_ROOT),
        factoryProjectText = factory,
    )

    private fun patchOf(text: String, scope: PersonaPatchScope = PersonaPatchScope.C_PROJECT): PersonaPatch {
        val f = files()
        val wsId = PersonaPatchApply.workspaceIdOf(f)
        return PersonaPatch(
            scope = scope,
            workspaceId = if (scope == PersonaPatchScope.C_PROJECT) wsId else null,
            sections = listOf(PersonaSection(PersonaSectionKind.RULES, text)),
            reason = "设置页手写",
        )
    }

    @Before
    fun setUp() {
        root = Files.createTempDirectory("persona-add").toFile()
        workspace = Files.createTempDirectory("persona-ws").toFile()
        DecisionLedger.install(root)
        PersonaChangeLedger.resetForTest()
    }

    @After
    fun tearDown() {
        DecisionLedger.uninstallForTest()
        PersonaChangeLedger.resetForTest()
    }

    // ── 判据③：加一条 → 重启仍在 ＋ 账上有记录 ─────────────────────────

    @Test
    fun `判据_用户加一条就写进C层且立刻可用`() {
        val f = files()
        val r = PersonaPatchApply.apply(patchOf("改文件前先说改哪几个"), PersonaConsent.WORKSPACE, PersonaPatchApply.workspaceIdOf(f), f, source = "设置页手写")
        assertTrue("应当写入成功：$r", r is PersonaApplyResult.Applied)
        val text = f.read(PersonaLayer.C_PROJECT).orEmpty()
        assertTrue("原文必须在正文里：$text", text.contains("改文件前先说改哪几个"))
    }

    @Test
    fun `判据_重启之后那条人设还在`() {
        val first = files()
        PersonaPatchApply.apply(patchOf("干活前先说结论"), PersonaConsent.WORKSPACE, PersonaPatchApply.workspaceIdOf(first), first, source = "设置页手写")
        // 模拟重启：换一个全新的 PersonaFiles 实例（只认盘上的文件）
        val afterRestart = files()
        assertTrue(
            "重启后人设不许消失",
            afterRestart.read(PersonaLayer.C_PROJECT).orEmpty().contains("干活前先说结论"),
        )
    }

    @Test
    fun `判据_账上记了谁何时原文`() {
        val f = files()
        PersonaPatchApply.apply(patchOf("不许猜，不确定就问"), PersonaConsent.WORKSPACE, PersonaPatchApply.workspaceIdOf(f), f, source = "设置页手写")
        val rows = PersonaChangeLedger.replay()
        assertEquals(1, rows.size)
        val row = rows.first()
        assertEquals("谁加的", PersonaWho.USER, row.who)
        assertEquals("原文（不是摘要）", "不许猜，不确定就问", row.text)
        assertEquals(PersonaLayer.C_PROJECT, row.layer)
        assertTrue("何时", row.atMs > 0)
        assertTrue(row.applied)
    }

    @Test
    fun `判据_账本重启后仍读得到`() {
        val f = files()
        PersonaPatchApply.apply(patchOf("一条规矩"), PersonaConsent.WORKSPACE, PersonaPatchApply.workspaceIdOf(f), f, source = "设置页手写")
        PersonaChangeLedger.resetForTest() // 清内存＝模拟重启
        assertEquals(1, PersonaChangeLedger.replay().size)
    }

    @Test
    fun `负例_没同意也要在账上留一行`() {
        val f = files()
        val r = PersonaPatchApply.apply(patchOf("这条不该写进去"), PersonaConsent.DECLINED, PersonaPatchApply.workspaceIdOf(f), f, source = "设置页手写")
        assertTrue(r is PersonaApplyResult.Rejected)
        val rows = PersonaChangeLedger.replay()
        assertEquals(1, rows.size)
        assertFalse("没落盘", rows.first().applied)
        assertTrue("理由要写清", rows.first().reason.isNotBlank())
        assertFalse("正文里不许有它", f.read(PersonaLayer.C_PROJECT).orEmpty().contains("这条不该写进去"))
    }

    @Test
    fun `判据_C层补丁不能顺手升全局`() {
        val f = files()
        val r = PersonaPatchApply.apply(patchOf("只想改本项目"), PersonaConsent.GLOBAL, PersonaPatchApply.workspaceIdOf(f), f, source = "设置页手写")
        assertTrue("C 层补丁按全局写必须拒", r is PersonaApplyResult.Rejected)
    }

    @Test
    fun `判据_显式点全局才写B层`() {
        val f = files()
        val p = patchOf("全局也守这条", PersonaPatchScope.B_GLOBAL)
        val r = PersonaPatchApply.apply(p, PersonaConsent.GLOBAL, PersonaPatchApply.workspaceIdOf(f), f, source = "设置页手写")
        assertTrue("B 层补丁按全局写应当成功：$r", r is PersonaApplyResult.Applied)
        assertTrue(f.read(PersonaLayer.B_GLOBAL).orEmpty().contains("全局也守这条"))
        // 而且**没有**顺带写进 C 层
        assertFalse(f.read(PersonaLayer.C_PROJECT).orEmpty().contains("全局也守这条"))
    }

    // ── 判据②：出厂兜底 ＋ 一键恢复⑨不备份 ───────────────────────────────

    @Test
    fun `判据_没写过人设时用出厂兜底`() {
        val f = files()
        assertEquals("出厂兜底", factory, f.read(PersonaLayer.C_PROJECT))
        assertTrue(f.usingFactory())
    }

    @Test
    fun `判据_写过之后出厂层退役但保留`() {
        val f = files()
        PersonaPatchApply.apply(patchOf("我自己的规矩"), PersonaConsent.WORKSPACE, PersonaPatchApply.workspaceIdOf(f), f, source = "设置页手写")
        val text = f.read(PersonaLayer.C_PROJECT).orEmpty()
        assertTrue("用自己的那份", text.contains("我自己的规矩"))
        assertFalse("出厂层退役（不再生效）", f.usingFactory())
        assertNotNull("但出厂内容仍然保留着（随时可恢复）", f.factoryText())
    }

    @Test
    fun `判据_一键恢复出厂且不备份用户那份`() {
        val f = files()
        PersonaPatchApply.apply(patchOf("先写一条自己的"), PersonaConsent.WORKSPACE, PersonaPatchApply.workspaceIdOf(f), f, source = "设置页手写")
        val active = f.projectFile()
        assertTrue(active.isFile)

        val r = PersonaPatchApply.restoreFactory(f)
        assertTrue("恢复应当成功：$r", r is PersonaApplyResult.Applied)
        assertEquals("active 应当等于出厂内容", factory, active.readText(Charsets.UTF_8))
        // 用户明确不要备份：**整棵人设目录**（含 `备份/` 子目录）里都不许出现 .bak
        // （2026-10-29 变异实测：只看一级目录会漏——备份是落在 `备份/` 子目录里的）
        val baks = active.parentFile!!.walkTopDown()
            .filter { it.isFile && it.name.contains(".bak") }
            .toList()
        assertTrue("恢复出厂不许备份用户那份，实际出现：${baks.map { it.name }}", baks.isEmpty())
        // 恢复后直接可用（不需要重启）
        assertEquals(factory, files().read(PersonaLayer.C_PROJECT))
        // 而且状态必须**如实**变成"用着出厂那份"（2026-10-29 真机 bug：只看文件空不空 ⇒ 恢复后仍显示"你自己那份"）
        assertTrue("恢复出厂之后 usingFactory() 必须为 true", files().usingFactory())
    }

    @Test
    fun `判据_恢复出厂也记账`() {
        val f = files()
        PersonaPatchApply.apply(patchOf("一条"), PersonaConsent.WORKSPACE, PersonaPatchApply.workspaceIdOf(f), f, source = "设置页手写")
        PersonaPatchApply.restoreFactory(f)
        val rows = PersonaChangeLedger.replay()
        assertEquals(2, rows.size)
        assertTrue("最新一条是恢复出厂", rows.first().reason.contains("恢复出厂"))
    }

    @Test
    fun `负例_没有出厂层时恢复要拒且说清`() {
        val noFactory = PersonaFiles(globalRoot = root, projectRoot = File(workspace, PersonaPackRules.DIR_ROOT))
        val r = PersonaPatchApply.restoreFactory(noFactory)
        assertTrue(r is PersonaApplyResult.Rejected)
        assertTrue((r as PersonaApplyResult.Rejected).reason.contains("出厂"))
    }

    /**
     * 「恢复出厂不备份用户那份」的**源码级**判据。
     *
     * 为什么不能只靠运行时断言：备份可能因为目录不可写而"失败但不报错"，
     * 那样运行时就看不到 .bak，测试假绿（2026-10-29 变异实测正是如此：
     * 往 restoreFactory 里塞一句 backupBefore，运行时用例照样绿）。
     * 所以这里直接读 `PersonaFiles.restoreFactory` 的函数体，**不许出现备份调用**。
     */
    @Test
    fun `负例_恢复出厂这条路径里不许出现任何备份调用`() {
        val src = listOf(
            File("app/src/main/java/com/example/zhengdao/baopu/PersonaFiles.kt"),
            File("src/main/java/com/example/zhengdao/baopu/PersonaFiles.kt"),
        ).first { it.isFile }.readText(Charsets.UTF_8)
        val body = funBody(src, "restoreFactory")
        listOf("backupBefore", "PersonaBackup.").forEach {
            assertFalse(
                "恢复出厂里出现了备份调用（$it）——用户 2026-10-29 明确要求不备份用户那份",
                body.contains(it),
            )
        }
        assertTrue("恢复出厂必须写 active（projectFile）", body.contains("projectFile()"))
    }

    /** 取某个顶层函数的正文（第一个 `{` 到匹配的 `}`；同 ContainerTierGuardTest 的朴素配对）。 */
    private fun funBody(src: String, funName: String): String {
        val start = src.indexOf("fun $funName(")
        if (start < 0) throw AssertionError("找不到 fun $funName")
        val open = src.indexOf('{', start)
        if (open < 0) throw AssertionError("fun $funName 没有函数体")
        var depth = 0
        var i = open
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(open, i + 1)
                }
            }
            i++
        }
        throw AssertionError("fun $funName 的花括号不配对")
    }
}
