// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百零六批 · 逐工具接影子（开工令 §二）：每接一个工具三件事齐一条——
//   ①副本里怎么试（沙盒用**真工具**在副本根上复刻一遍）；②成功判据（路径集合＋字节 hash＋
//   真盘不动）；③一条"真实工具 ⇒ CONSISTENT"的用例。顺序照开工令：move_path → delete_path →
//   convert_encoding → phone_dial/notify（无文件副作用，判 NOT_APPLICABLE）。
//
// 判据「每接一个，UNSUPPORTED 计数 −1」落在 `306三件离开UNSUPPORTED桶`：
// 三件此前必报 UNSUPPORTED（296 的窄口径），接完一个都不许再落那个桶——
// 数字不动＝没接。负例（各红）：搬动假称与实测不符 ⇒ MISMATCH；
// NOT_APPLICABLE 与 UNSUPPORTED 混为一谈 ⇒ 红（两码必须分得开）。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.baopu.RiskClass
import com.example.zhengdao.baopu.agent.FileShadowSandbox
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShadowToolsCoverageTest {

    private val gb18030: Charset = Charset.forName("GB18030")

    private fun workspace(): File = Files.createTempDirectory("zd-306-ws-").toFile()

    private fun ledgerRoot(): File = Files.createTempDirectory("zd-306-ledger-").toFile()

    private fun input(
        ws: File,
        tool: String,
        declared: List<String>,
        opId: String = "op-306",
        permanent: Boolean = false,
        ledger: File? = null,
        roundId: Int = 4,
        step: Int = 2,
    ) = ShadowInput(
        opId = opId,
        tool = tool,
        workspaceRoot = ws.absolutePath,
        declaredPaths = declared,
        permanent = permanent,
        risk = RiskClass.R2,
        reversible = false,
        sessionId = 7L,
        roundId = roundId,
        step = step,
        wallMs = 1_793_289_600_000L, // 2026-10-30 00:00 +08:00（写死，账上时刻不随跑漂）
        ledgerRoot = ledger?.absolutePath.orEmpty(),
    )

    private fun copyRootOf(ws: File, opId: String = "op-306"): String =
        File(File(ws, FileShadowSandbox.SHADOW_DIR_NAME), opId).absolutePath

    // ── B1 move_path：副本里真搬——源消失、目标出现且字节一致、真盘一字不动 ────

    @Test
    fun `move_path_副本里真搬一遍_一致`() {
        val ws = workspace()
        val led = ledgerRoot()
        val src = File(ws, "a/x.txt").apply { parentFile.mkdirs() }
        src.writeText("搬动的原稿\n", Charsets.UTF_8)
        val realBefore = src.readBytes()
        val sandbox = FileShadowSandbox()
        val in1 = input(ws, "move_path", listOf("a/x.txt", "b/y.txt"), ledger = led)

        // 沙盒那一半：实测五样事实逐个看（真搬 = 源没了、目标带着同样的字节在）
        val run = sandbox.rehearse(in1)
        assertTrue("副本没建起来：${run.note}", run.built)
        assertTrue("工具回执要 Ok：${run.trialNote}", run.trialOk)
        assertTrue("搬没落盘（源还在副本里＝没搬成）", run.landed)
        assertEquals(true, run.realUntouched)
        assertFalse("源要从副本里消失：${run.observedPaths}", File(run.copyRoot, "a/x.txt").exists())
        assertEquals("搬过去的要还是原来那些字节", "搬动的原稿\n", File(run.copyRoot, "b/y.txt").readText(Charsets.UTF_8))
        assertEquals(
            "实测路径集合＝两端（源消失＋目标出现），不多不少：${run.observedPaths}",
            listOf(copyRootOf(ws, "op-306") + "/a/x.txt", copyRootOf(ws, "op-306") + "/b/y.txt"),
            run.observedPaths.sorted(),
        )
        assertEquals("源那份要报删除且带字节", 1, run.observed?.deleteCount)
        assertEquals(realBefore.size.toLong(), run.observed?.deleteBytes)

        // 真盘一个字节没动、目标没有提前出现在真盘（§1④ 的第三条是实测不是声称）
        assertArrayEquals(realBefore, src.readBytes())
        assertFalse(File(ws, "b/y.txt").exists())

        // 适配器那一半：判定 ＋ 落账（(roundId, step) 进账行）
        val r = ShadowAdapter.rehearse(in1, override = sandbox)
        assertEquals("真搬一遍要绿：${r.outcome.items.map { it.cn }}", ShadowVerdict.CONSISTENT, r.outcome.verdict)
        assertTrue(r.accounted)
        val entry = ShadowLedger.read(led).single()
        assertEquals(ShadowVerdict.CONSISTENT, entry.verdict)
        assertEquals("账行要带轮号（306 的联键）", 4, entry.roundId)
        assertEquals(2, entry.step)
        assertEquals("两侧路径集合进账", entry.claimed.touchedPaths, entry.observed.touchedPaths)
        assertEquals(1, entry.observed.deleteCount)
    }

    // ── B2 delete_path：副本里真删——声明对象消失，工具自己的回收站不算多碰 ────

    @Test
    fun `delete_path_副本里真删一遍_回收站不算多碰`() {
        val ws = workspace()
        val led = ledgerRoot()
        val victim = File(ws, "notes/del.md").apply { parentFile.mkdirs() }
        victim.writeText("要删的稿子\n", Charsets.UTF_8)
        val realBefore = victim.readBytes()
        val sandbox = FileShadowSandbox()

        // 回收站档（默认）：副本里源消失，进的是副本的 回收站/（工具的退路，不算影响面）
        val inTrash = input(ws, "delete_path", listOf("notes/del.md"), opId = "op-del-trash", ledger = led)
        val run = sandbox.rehearse(inTrash)
        assertTrue("副本没建起来：${run.note}", run.built)
        assertTrue("工具回执要 Ok：${run.trialNote}", run.trialOk)
        assertTrue("删没落盘（声明对象还躺在副本里＝没删成）", run.landed)
        assertEquals(true, run.realUntouched)
        assertFalse(File(run.copyRoot, "notes/del.md").exists())
        assertTrue("工具要真的把源挪进自己的退路目录（回执作证之外再看一眼现场）", run.trialNote.contains("回收站"))
        assertEquals(
            "实测只报声明的那一份：${run.observedPaths}",
            listOf(copyRootOf(ws, "op-del-trash") + "/notes/del.md"),
            run.observedPaths,
        )
        assertEquals(1, run.observed?.deleteCount)
        assertEquals(realBefore.size.toLong(), run.observed?.deleteBytes)
        assertArrayEquals("真盘一个字节没动", realBefore, victim.readBytes())
        val r1 = ShadowAdapter.rehearse(inTrash, override = sandbox)
        assertEquals("回收站档要绿：${r1.outcome.items.map { it.detail }}", ShadowVerdict.CONSISTENT, r1.outcome.verdict)

        // 真删档（permanent=true）：副本里同样只剩"声明对象消失"这一个实测
        val inPerm = input(ws, "delete_path", listOf("notes/del.md"), opId = "op-del-perm", permanent = true)
        val r2 = ShadowAdapter.rehearse(inPerm, override = sandbox)
        assertEquals("真删档也要绿：${r2.outcome.items.map { it.cn }}", ShadowVerdict.CONSISTENT, r2.outcome.verdict)
        val entry = ShadowLedger.read(led).first { it.id == "sh:op-del-trash" }
        assertEquals("账行要带轮号", 4, entry.roundId)
    }

    // ── B3 convert_encoding：GBK 落盘转 UTF-8——副本里真转，真盘原字节不动 ─────

    @Test
    fun `convert_encoding_副本里真转一遍_一致`() {
        val ws = workspace()
        val led = ledgerRoot()
        val body = "第一章 起步\n正文一行\n"
        val target = File(ws, "docs/gbk.txt").apply { parentFile.mkdirs() }
        target.writeBytes(body.toByteArray(gb18030))
        val realBefore = target.readBytes()
        val sandbox = FileShadowSandbox()
        val in1 = input(ws, "convert_encoding", listOf("docs/gbk.txt"), ledger = led)

        val run = sandbox.rehearse(in1)
        assertTrue("副本没建起来：${run.note}", run.built)
        assertTrue("工具回执要 Ok：${run.trialNote}", run.trialOk)
        assertTrue("转没落盘（字节没变＝没转成）", run.landed)
        assertEquals(true, run.realUntouched)
        assertEquals("副本里那份要变成 UTF-8 且内容一字不变", body, File(run.copyRoot, "docs/gbk.txt").readText(Charsets.UTF_8))
        assertEquals(
            "实测只报声明的那一份：${run.observedPaths}",
            listOf(copyRootOf(ws, "op-306") + "/docs/gbk.txt"),
            run.observedPaths,
        )
        assertEquals("覆盖 1 份", 1, run.observed?.overwriteTotal)
        assertArrayEquals("真盘原字节不动（转的是副本）", realBefore, target.readBytes())

        val r = ShadowAdapter.rehearse(in1, override = sandbox)
        assertEquals("真转一遍要绿：${r.outcome.items.map { it.cn }}", ShadowVerdict.CONSISTENT, r.outcome.verdict)
        assertEquals("账行要带轮号", 4, ShadowLedger.read(led).single().roundId)
    }

    // ── B4 phone_dial/notify：无文件副作用 ⇒ NOT_APPLICABLE，一次沙盒都不碰 ────

    @Test
    fun `phone_dial与notify_判不适用_与还没接分得开`() {
        val ws = workspace()
        val led = ledgerRoot()
        var sandboxCalls = 0
        val counting = object : ShadowSandbox {
            override fun rehearse(input: ShadowInput): ShadowRun {
                sandboxCalls++
                return ShadowRun(built = true)
            }

            override fun discard(copyRoot: String) = Unit
        }
        listOf("phone_dial", "notify").forEach { tool ->
            val r = ShadowAdapter.rehearse(
                input(ws, tool, declared = emptyList(), opId = "op-$tool", ledger = led),
                override = counting,
            )
            assertEquals("$tool 要判 UNVERIFIABLE（没有可放行的结论）", ShadowVerdict.UNVERIFIABLE, r.outcome.verdict)
            assertEquals(
                "$tool 没有文件副作用：原因码是 NOT_APPLICABLE，不是「还没接」",
                ShadowUnverifiable.NOT_APPLICABLE,
                r.reason,
            )
            assertFalse(r.outcome.allow)
        }
        assertEquals("不适用也是「没跑」：一次沙盒都不许碰", 0, sandboxCalls)
        listOf("op-phone_dial", "op-notify").forEach { op ->
            val entry = ShadowLedger.read(led).first { it.id == "sh:$op" }
            assertTrue("账行要写明是哪一种跑不了：${entry.items}", entry.items.single().detail.contains("reason=NOT_APPLICABLE"))
        }
        // 负例（开工令点名格）：NOT_APPLICABLE 与 UNSUPPORTED 混为一谈 ＝ 红
        val unsup = ShadowAdapter.rehearse(input(ws, "bash", declared = listOf("a.md")), override = counting)
        assertEquals("真没接的还是 UNSUPPORTED", ShadowUnverifiable.UNSUPPORTED, unsup.reason)
        assertNotEquals(ShadowUnverifiable.NOT_APPLICABLE, ShadowUnverifiable.UNSUPPORTED)
    }

    // ── 「每接一个，UNSUPPORTED 计数 −1」：三件不许再落 UNSUPPORTED 桶 ─────────

    @Test
    fun `306三件离开UNSUPPORTED桶`() {
        val ws = workspace()
        listOf("move_path", "delete_path", "convert_encoding").forEachIndexed { i, tool ->
            val r = ShadowAdapter.rehearse(
                input(ws, tool, declared = emptyList(), opId = "op-bucket-$i"),
                override = FileShadowSandbox(),
            )
            assertNotEquals(
                "$tool 已接影子（数字不动＝没接）：不许再报 UNSUPPORTED",
                ShadowUnverifiable.UNSUPPORTED,
                r.reason,
            )
            assertEquals("说不出声明对象照旧报 NO_TARGET（原因码各归各位）", ShadowUnverifiable.NO_TARGET, r.reason)
        }
    }

    // ── 负例：搬动的假称与实测不符 ⇒ MISMATCH（副本留着当证据）────────────────

    @Test
    fun `负例_搬动只认目标没认源_照样红`() {
        val ws = workspace()
        val copy = copyRootOf(ws)
        var discarded = 0
        val fake = object : ShadowSandbox {
            override fun rehearse(input: ShadowInput) = ShadowRun(
                built = true,
                copyRoot = copy,
                existedBefore = listOf("a/x.txt"),
                observedPaths = listOf("$copy/b/y.txt"),
                observed = ImpactShape(newFiles = 1, touchedPaths = listOf("$copy/b/y.txt")),
                rollback = RollbackPoint("$copy/.rollback/x.bak", exists = true, bytes = 12L, originalBytes = 12L),
                realUntouched = true,
                landed = true,
                trialOk = true,
                trialNote = "只搬出了一半",
            )

            override fun discard(copyRoot: String) {
                discarded++
            }
        }
        val r = ShadowAdapter.rehearse(
            input(ws, "move_path", listOf("a/x.txt", "b/y.txt")),
            override = fake,
        )
        assertEquals("所称报了源消失、实测里源还在 ＝ 对不上", ShadowVerdict.MISMATCH, r.outcome.verdict)
        assertFalse(r.outcome.allow)
        assertEquals("不一致的副本不许清", 0, discarded)
        val effect = r.outcome.items.first { it.key == ShadowRehearsal.ITEM_EFFECT }
        assertTrue(effect.passed == false)
        assertTrue("要说出少碰了谁：${effect.detail}", effect.detail.contains("少碰 1 个"))
    }
}
