// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百九十六批 §2 的行为门禁：影子**第一次真跑**——开工令点的五条判据，逐条落在这里：
//   ① 写一次影子 ⇒ 真文件不动、副本里有、账上留着两侧路径与对比；
//   ② 只改两路径却报一个 ⇒ 不一致（红）；
//   ③ 副本逃逸（实测落回真盘／声明写成绝对路径或上跳）⇒ 接错，不放行；
//   ④ 没接适配器的工具 ⇒ UNVERIFIABLE（reason=UNSUPPORTED），并照样落账；
//   ⑤ 第⑤步有真实调用点 —— 那是源级门禁，住在 [ShadowWiringGateTest]。
//
// 为什么一半用例用假沙盒：判据层的四态要能**各喂一种事实**才测得出走向（真沙盒只会给你
// 现场允许那一种）；而"真跑"那条必须用真 `FileShadowSandbox`，否则 296 的病（只有判定、
// 没有跑的人）换个地方又犯一遍。两边都留，是因为它们钉的是两件不同的事。
//
// 负例照开工令：N1「只比数量」（两条路径各 1 但集合不等）与 N3「结构故障仍放行」
// 都钉在这里；N2「副本写在真目录」由判据③那两条钉；N4「UNVERIFIABLE 当 CONSISTENT」
// 的字面锚在 `TaskGrantGate.shadowAllows`，源级门禁在 [ShadowWiringGateTest]。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.baopu.RiskClass
import com.example.zhengdao.baopu.agent.FileShadowSandbox
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShadowAdapterTest {

    private val now = 1_793_289_600_000L // 2026-10-30 00:00 +08:00（写死：账上的时刻不许随跑漂）

    private fun workspace(): File = Files.createTempDirectory("zd-296-ws-").toFile()

    private fun ledgerRoot(): File = Files.createTempDirectory("zd-296-ledger-").toFile()

    private fun input(
        ws: File,
        tool: String = "write_file",
        declared: List<String> = listOf("notes/a.md"),
        opId: String = "op-296",
        content: String = "改后的内容\n",
        overwrite: Boolean = false,
        editOp: String = "",
        ledger: File? = null,
    ) = ShadowInput(
        opId = opId,
        tool = tool,
        workspaceRoot = ws.absolutePath,
        declaredPaths = declared,
        content = content,
        editOp = editOp,
        overwrite = overwrite,
        risk = RiskClass.R2,
        reversible = false,
        sessionId = 7L,
        step = 3,
        wallMs = now,
        ledgerRoot = ledger?.absolutePath.orEmpty(),
    )

    /**
     * 交回一份**写死的实测**（判据层拿到的事实由用例决定），顺便记 discard 被调了几次。
     *
     * 「不一致的副本不许清」这条只能这么测：真沙盒清完就没了，事后无从知道它清没清。
     */
    private class Fake(
        private val run: ShadowRun,
        private val boom: Boolean = false,
    ) : ShadowSandbox {
        var discarded = 0

        override fun rehearse(input: ShadowInput): ShadowRun {
            if (boom) throw IllegalStateException("副本建不起")
            return run
        }

        override fun discard(copyRoot: String) {
            discarded++
        }
    }

    private fun copyRootOf(ws: File): String = "${ws.absolutePath}\\.shadow\\op-296"

    /** 一份"什么都对得上"的实测，只在指定的那一格上被动过手脚。 */
    private fun runIn(
        ws: File,
        observedPaths: List<String>,
        existed: List<String> = listOf("notes/a.md"),
        newFiles: Int = 0,
        overwriteTotal: Int = 1,
        untouched: Boolean? = true,
    ): ShadowRun {
        val copy = copyRootOf(ws)
        return ShadowRun(
            built = true,
            note = "副本 ${observedPaths.size} 个对象",
            copyRoot = copy,
            existedBefore = existed,
            observedPaths = observedPaths,
            observed = ImpactShape(newFiles = newFiles, overwriteTotal = overwriteTotal, touchedPaths = observedPaths),
            rollback = RollbackPoint(
                path = "$copy/.rollback/a.bak",
                exists = existed.isNotEmpty(),
                bytes = if (existed.isEmpty()) 0L else 128L,
                originalBytes = if (existed.isEmpty()) null else 128L,
                sha256 = if (existed.isEmpty()) "" else "aaaaaaaa11111111",
                originalSha256 = if (existed.isEmpty()) "" else "aaaaaaaa11111111",
                expectsAbsent = existed.isEmpty(),
            ),
            realUntouched = untouched,
            landed = true,
            trialOk = true,
            trialNote = "已覆盖 a.md（1 行）",
        )
    }

    // ── 判据①：真跑一次（真沙盒＋真临时工作区）───────────────────────────────

    @Test
    fun `判据①_覆盖已有对象_真文件不动副本有账留两侧`() {
        val ws = workspace()
        val led = ledgerRoot()
        val target = File(ws, "notes/a.md").apply { parentFile.mkdirs() }
        target.writeText("原来的内容\n", Charsets.UTF_8)
        val realBefore = target.readBytes()
        val sandbox = FileShadowSandbox()
        val in1 = input(ws, content = "改后的内容\n", overwrite = true, ledger = led)

        // 沙盒自己那一半：实测交回的五样事实（先单独看，别和判定混在一条断言里）
        val run = sandbox.rehearse(in1)
        assertTrue("副本没建起来：${run.note}", run.built)
        assertEquals(true, run.realUntouched)
        assertEquals(true, run.landed)
        assertEquals(true, run.trialOk)
        assertEquals("改后的内容\n", File(run.copyRoot, "notes/a.md").readText(Charsets.UTF_8))
        assertEquals(listOf("notes/a.md"), run.existedBefore)
        assertEquals("回滚点读数=${run.rollback}", true, run.rollback?.usable)
        // 实测集合＝声明集合：工具的 `备份/`、快照的 `.rollback/`、原子写的临时文件都不许混进来
        assertEquals(
            "副本里除了声明对象还有别的东西被算进影响面？${run.observedPaths}",
            listOf("${run.copyRoot}/notes/a.md"),
            run.observedPaths,
        )

        // 真盘一个字节没动（§1④ 的第三条——不是"应该会不动"，是实测没动）
        assertArrayEquals(realBefore, target.readBytes())

        // 适配器那一半：判定＋落账＋收尾
        val r = ShadowAdapter.rehearse(in1, override = sandbox)
        assertEquals(ShadowVerdict.CONSISTENT, r.outcome.verdict)
        assertEquals("跑成了就别带原因码", "", r.reason)
        assertTrue("账没落上（判据①第三件）", r.accounted)
        assertEquals(4, r.outcome.items.size)
        assertEquals(ShadowAdapter.ITEM_SANDBOX, r.outcome.items.last().key)
        assertEquals("副本已清理", r.outcome.items.last().cn)
        assertFalse("跑成一致就要把副本收掉：${run.copyRoot}", File(run.copyRoot).exists())
        assertFalse(".shadow/ 里不许留一个空壳", File(ws, FileShadowSandbox.SHADOW_DIR_NAME).exists())

        val entry = ShadowLedger.read(led).single()
        assertEquals(ShadowVerdict.CONSISTENT, entry.verdict)
        assertEquals("7:3", entry.key)
        assertEquals("sh:op-296", entry.id)
        assertEquals(RiskClass.R2.name, entry.riskWire)
        assertTrue("账上要留得下两侧路径：${entry.claimed.touchedPaths}", entry.claimed.touchedPaths.isNotEmpty())
        assertEquals(entry.claimed.touchedPaths, entry.observed.touchedPaths)
        assertEquals(1, entry.observed.overwriteTotal)
        assertTrue("影子自己的现场要进账：${entry.items.last().detail}", entry.items.last().detail.contains("真盘字节=未动"))
    }

    @Test
    fun `判据①_新建对象_原先没有就是退路`() {
        val ws = workspace()
        val led = ledgerRoot()
        val r = ShadowAdapter.rehearse(
            input(ws, declared = listOf("notes/new.md"), content = "新文件\n", ledger = led),
            override = FileShadowSandbox(),
        )
        assertEquals("新建对象不许永远红：${r.outcome.items}", ShadowVerdict.CONSISTENT, r.outcome.verdict)
        assertEquals(1, r.outcome.claimed.newFiles)
        assertEquals(1, r.outcome.observed.newFiles)
        assertEquals("新建对象：原先没有", r.outcome.items.first { it.key == ShadowRehearsal.ITEM_ROLLBACK }.cn)
        assertTrue(r.accounted)
        assertEquals(ShadowVerdict.CONSISTENT, ShadowLedger.read(led).single().verdict)
    }

    @Test
    fun `判据①_edit_file也真跑一次_追加一行算一致`() {
        val ws = workspace()
        val led = ledgerRoot()
        val target = File(ws, "notes/b.md").apply { parentFile.mkdirs() }
        target.writeText("第一行\n", Charsets.UTF_8)
        val realBefore = target.readBytes()
        val r = ShadowAdapter.rehearse(
            input(
                ws,
                tool = "edit_file",
                declared = listOf("notes/b.md"),
                content = "追加的一行\n",
                editOp = "append",
                ledger = led,
            ),
            override = FileShadowSandbox(),
        )
        assertEquals("edit_file 的实测：${r.outcome.items.map { it.detail }}", ShadowVerdict.CONSISTENT, r.outcome.verdict)
        assertArrayEquals("影子里跑的那一趟摸到真盘了", realBefore, target.readBytes())
        assertEquals(1, r.outcome.claimed.overwriteTotal)
        assertEquals(1, r.outcome.observed.overwriteTotal)
    }

    // ── 判据②：实际改两个却只报一个 ⇒ 不一致，并且副本留着当证据 ───────────────

    @Test
    fun `判据②_实际改两个却只报一个_不一致`() {
        val ws = workspace()
        val copy = copyRootOf(ws)
        val fake = Fake(
            runIn(
                ws,
                observedPaths = listOf("$copy/notes/a.md", "$copy/notes/b.md"),
                newFiles = 1,
                overwriteTotal = 1,
            ),
        )
        val r = ShadowAdapter.rehearse(input(ws, overwrite = true), override = fake)
        assertEquals(ShadowVerdict.MISMATCH, r.outcome.verdict)
        assertFalse(r.outcome.allow)
        assertEquals(0, fake.discarded)
        assertEquals("副本留作证据", r.outcome.items.first { it.key == ShadowAdapter.ITEM_SANDBOX }.cn)
        val effect = r.outcome.items.first { it.key == ShadowRehearsal.ITEM_EFFECT }
        assertTrue(effect.passed == false)
        assertTrue("要说出多碰了谁：${effect.detail}", effect.detail.contains("多碰 1 个"))
    }

    // ── 负例 N1：只比数量（两边各 1 个，但对象不是同一个）──────────────────────

    @Test
    fun `负例N1_报一改一但改的是另一个对象_照样红`() {
        val ws = workspace()
        val copy = copyRootOf(ws)
        val fake = Fake(runIn(ws, observedPaths = listOf("$copy/notes/b.md")))
        val r = ShadowAdapter.rehearse(input(ws, overwrite = true), override = fake)
        assertEquals(
            "两边各 1 个、数字格全等，只有集合不等——只比数量的口径在这儿会绿",
            ShadowVerdict.MISMATCH,
            r.outcome.verdict,
        )
        assertEquals(0, fake.discarded)
    }

    // ── 判据③：副本逃逸 ──────────────────────────────────────────────────────

    @Test
    fun `判据③_实测落回真盘_接错`() {
        val ws = workspace()
        val led = ledgerRoot()
        val real = ws.absolutePath.replace('\\', '/')
        val fake = Fake(runIn(ws, observedPaths = listOf("$real/notes/a.md")))
        val r = ShadowAdapter.rehearse(input(ws, overwrite = true, ledger = led), override = fake)
        assertEquals(ShadowVerdict.MISWIRED, r.outcome.verdict)
        assertFalse(r.outcome.allow)
        assertEquals("影子没接在副本上", r.outcome.items.first().cn)
        assertEquals("接错的副本更要留着（那是现场）——适配器没调 discard", 0, fake.discarded)
        assertTrue("接错也必须进账：巡按要知道有人把影子接到了真盘", r.accounted)
        assertEquals(ShadowVerdict.MISWIRED, ShadowLedger.read(led).single().verdict)
    }

    @Test
    fun `判据③_声明写成绝对路径或上跳_沙盒根本不开副本`() {
        val ws = workspace()
        val abs = File(ws, "notes/a.md").apply { parentFile.mkdirs(); writeText("x\n", Charsets.UTF_8) }
            .absolutePath
        val r1 = ShadowAdapter.rehearse(
            input(ws, declared = listOf(abs), overwrite = true),
            override = FileShadowSandbox(),
        )
        assertEquals(ShadowVerdict.UNVERIFIABLE, r1.outcome.verdict)
        assertEquals(ShadowUnverifiable.STRUCTURE, r1.reason)
        val r2 = ShadowAdapter.rehearse(
            input(ws, declared = listOf("../outside.md"), opId = "op-296b"),
            override = FileShadowSandbox(),
        )
        assertEquals(ShadowUnverifiable.STRUCTURE, r2.reason)
        listOf(r1, r2).forEach { assertFalse("结构故障绝不当放行", it.outcome.allow) }
        assertFalse("不该有东西落在工作区外面", File(ws.parentFile, "outside.md").exists())
    }

    // ── 判据④：没接适配器的工具 ───────────────────────────────────────────────

    @Test
    fun `判据④_没接的工具_验不了并报出原因`() {
        val ws = workspace()
        val led = ledgerRoot()
        val r = ShadowAdapter.rehearse(
            input(ws, tool = "bash", declared = listOf("notes/a.md"), ledger = led),
            override = FileShadowSandbox(),
        )
        assertEquals(ShadowVerdict.UNVERIFIABLE, r.outcome.verdict)
        assertEquals("原因码要说得出下一批接谁", ShadowUnverifiable.UNSUPPORTED, r.reason)
        assertFalse(r.outcome.allow)
        assertTrue("短路也要落账，否则巡按看不见「这个工具从没预演过」", r.accounted)
        val entry = ShadowLedger.read(led).single()
        assertEquals(ShadowVerdict.UNVERIFIABLE, entry.verdict)
        assertTrue(entry.items.single().detail.contains("reason=UNSUPPORTED"))
        assertEquals(entry.claimed.touchedPaths, listOf("notes/a.md"))
    }

    @Test
    fun `§2_覆盖表是闭集306起五件加两件不适用`() {
        // 296 交付时是 write/edit 两件；306 开工令点名逐工具接：move_path → delete_path →
        // convert_encoding（副本里真跑真验），phone_dial/notify 无文件副作用判 NOT_APPLICABLE。
        assertEquals(
            listOf("write_file", "edit_file", "move_path", "delete_path", "convert_encoding"),
            SHADOW_TOOLS,
        )
        assertTrue(ShadowAdapter.supported("write_file"))
        assertTrue(ShadowAdapter.supported("edit_file"))
        listOf("phone_dial", "notify").forEach {
            assertTrue("$it 没有文件副作用，适配器认得它（判 NOT_APPLICABLE）", ShadowAdapter.supported(it))
        }
        listOf("bash", "browser_click", "").forEach {
            assertFalse("$it 没接适配器，不许 supported()", ShadowAdapter.supported(it))
        }
        assertEquals(5, ShadowUnverifiable.ALL.size)
        assertEquals(5, ShadowUnverifiable.ALL.distinct().size)
    }

    // ── 负例 N3：结构故障一律不放行（四种跑不成都得报出原因）───────────────────

    @Test
    fun `负例N3_沙盒没装与跑挂与没交回对照_一律结构故障`() {
        val ws = workspace()
        val in1 = input(ws)
        // ① 没装沙盒（生产 JVM 里就是这一格）⇒ 一次预演都没跑
        val none = ShadowAdapter.rehearse(in1, override = null)
        assertEquals(ShadowUnverifiable.NO_SANDBOX, none.reason)
        assertEquals(ShadowVerdict.UNVERIFIABLE, none.outcome.verdict)
        // ② 沙盒跑挂 ⇒ STRUCTURE（不许让它冒充"没这回事"）
        val boom = ShadowAdapter.rehearse(in1, override = Fake(ShadowRun(), boom = true))
        assertEquals(ShadowUnverifiable.STRUCTURE, boom.reason)
        // ③ 交不回真盘 before/after 对照 ⇒ §1④ 的第三条件无从可验，按结构故障收
        val noHash = ShadowAdapter.rehearse(
            in1,
            override = Fake(ShadowRun(built = true, copyRoot = copyRootOf(ws), trialOk = true, landed = true)),
        )
        assertEquals(ShadowUnverifiable.STRUCTURE, noHash.reason)
        // ④ 副本没建起来 ⇒ STRUCTURE
        val notBuilt = ShadowAdapter.rehearse(in1, override = Fake(ShadowRun(built = false, note = "磁盘满了")))
        assertEquals(ShadowUnverifiable.STRUCTURE, notBuilt.reason)
        listOf(none, boom, noHash, notBuilt).forEach {
            assertFalse("结构故障绝不允许免卡", it.outcome.allow)
            assertTrue("原因必须在闭集里：${it.reason}", it.reason in ShadowUnverifiable.ALL)
        }
    }

    @Test
    fun `§2_说不出声明对象_没有可比的两份清单`() {
        val ws = workspace()
        val r = ShadowAdapter.rehearse(input(ws, declared = emptyList()), override = Fake(runIn(ws, emptyList())))
        assertEquals(ShadowUnverifiable.NO_TARGET, r.reason)
        assertEquals(ShadowVerdict.UNVERIFIABLE, r.outcome.verdict)
    }

    @Test
    fun `沙盒没交七格时按路径造_数字格对不上就不免卡`() {
        // 保守方向：拿不到七格读数 ⇒ 不猜一份"看起来一致"的实测，让它红在这一格。
        val ws = workspace()
        val copy = copyRootOf(ws)
        val run = runIn(ws, observedPaths = listOf("$copy/notes/a.md")).copy(observed = null)
        val r = ShadowAdapter.rehearse(input(ws, overwrite = true), override = Fake(run))
        assertEquals(ShadowVerdict.MISMATCH, r.outcome.verdict)
        assertFalse(r.outcome.allow)
    }
}
