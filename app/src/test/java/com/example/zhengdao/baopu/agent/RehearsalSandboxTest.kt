// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百五十八批 · 推演盘 **副本沙盒**的判据测试（派单 §四判据②③④＋【隔离】①②）。
//
// 全部落在临时目录里：一份 `cacheDir` 假根 ＋ 一份 `workspace` 假根，两棵树各自独立，
// 于是"真盘只读"这件事是可证的——真盘那棵跑完之后必须一格没动（[RehearsalRunnerTest] 钉那一半）。
//
// 平台口径：路径一律用正斜杠写法拼（`File(dir, "notes/a.md")`），
// 临时根一律走 `Files.createTempDirectory`——测试平台的闸（362 批）不许这里出现盘符与反斜杠。
//
// 大文件用 `setLength` 撑出大小而不写内容：判据看的是**长度与摘要**这两格，
// 不是磁盘真占了几个簇；这样 8MB／9MB 两条边界能在一趟测试里跑完。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.core.PathGuard
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RehearsalSandboxTest {

    private val tempRoots = mutableListOf<File>()

    /** 一次测试的现场：cacheDir 假根（副本要落这里）＋ workspace 假根（真盘）。 */
    private class Fixture(val cache: File, val workspace: File)

    private fun fixture(): Fixture {
        val cache = Files.createTempDirectory("rh-cache-").toFile().also { tempRoots += it }
        val workspace = Files.createTempDirectory("rh-ws-").toFile().also { tempRoots += it }
        return Fixture(cache, workspace)
    }

    @After
    fun cleanup() {
        tempRoots.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun File.put(path: String, text: String): File {
        val f = File(this, path)
        f.parentFile?.mkdirs()
        f.writeText(text)
        return f
    }

    /** 撑出 [bytes] 大小但不写内容（长度是真读数，占的簇不是判据）。 */
    private fun File.putSize(path: String, bytes: Long): File {
        val f = File(this, path)
        f.parentFile?.mkdirs()
        RandomAccessFile(f, "rw").use { it.setLength(bytes) }
        return f
    }

    private fun sandbox(fx: Fixture, quota: Long, cap: Long = RehearsalRules.METADATA_ONLY_BYTES) =
        RehearsalSandbox(fx.cache, quota, cap)

    // ── 判据②的形制：副本只落在 cacheDir/rehearsal/rh-* ──────────────────────

    @Test
    fun `副本根的位置与名字都按派单写死`() {
        val fx = fixture()
        val box = sandbox(fx, quota = 10L * 1024 * 1024)
        assertNull(box.open("run-1"))
        val root = box.copyRoot()!!
        assertEquals("rehearsal", root.parentFile?.name)
        assertTrue(root.name.startsWith("rh-"))
        assertTrue("副本必须在工作区之外的 cacheDir 里", PathGuard.isInside(fx.cache, root))
        assertEquals(fx.cache.resolve("rehearsal").resolve("rh-run-1"), root)
    }

    @Test
    fun `runId 里的非法字符折成下划线_但保留能看出的形状`() {
        val fx = fixture()
        val box = sandbox(fx, quota = 1L)
        assertNull(box.open("r7@1730000000000#2"))
        // 非法字符折成下划线（照影子那一套字符规则），数字串原样留着才认得出哪一趟。
        assertTrue(box.copyRoot()!!.name.startsWith("rh-r7_1730000000000_"))
        assertEquals("a_b_c", box.safeName("a/b:c"))
        assertEquals("rh", box.safeName("   "))
    }

    @Test
    fun `没有runId就不开副本_不建一个无归属的目录`() {
        val fx = fixture()
        val note = sandbox(fx, quota = 1L).open("  ")
        assertNotNull(note)
        assertFalse(File(fx.cache, "rehearsal").exists())
    }

    // ── 判据③：>8MB 只做元数据，不复制 ───────────────────────────────────────

    @Test
    fun `判据3_超阈值的对象不复制进副本只做元数据`() {
        val fx = fixture()
        val cap = 4_096L
        fx.workspace.putSize("video/clip.mp4", cap + 1L)
        val box = sandbox(fx, quota = 20L * 1024 * 1024, cap = cap)
        assertNull(box.open("run-3"))
        val one = box.stage(fx.workspace, "video/clip.mp4")
        assertEquals(RehearsalSandbox.Stage.METADATA, one.stage)
        assertFalse("副本里不许长出这份原件的拷贝", File(box.copyRoot()!!, "video/clip.mp4").exists())
        assertEquals(0L, box.usedBytes)
        val meta = one.meta!!
        assertEquals(cap + 1L, meta.bytes)
        assertTrue(meta.path + " 应该带可写格", meta.writable)
        // 抽样格：首尾各读 SAMPLE_BYTES，是"确实读到了"的实测证据。
        assertEquals(RehearsalRules.SAMPLE_BYTES, meta.headBytes)
        assertEquals(RehearsalRules.SAMPLE_BYTES, meta.tailBytes)
    }

    @Test
    fun `阈值分界按大于算_正好等于阈值的照样复制进副本`() {
        val fx = fixture()
        val cap = 4_096L
        fx.workspace.putSize("video/exactly.mp4", cap)
        val box = sandbox(fx, quota = 20L * 1024 * 1024, cap = cap)
        assertNull(box.open("run-3b"))
        assertEquals(RehearsalSandbox.Stage.COPIED, box.stage(fx.workspace, "video/exactly.mp4").stage)
        assertTrue(File(box.copyRoot()!!, "video/exactly.mp4").isFile)
        assertEquals(cap, box.usedBytes)
    }

    @Test
    fun `默认阈值就是派单点名的8MB`() {
        assertEquals(8L * 1024 * 1024, RehearsalRules.METADATA_ONLY_BYTES)
    }

    // ── 判据④：配额装不下 ⇒ QUOTA_FULL（由跑法层降级）───────────────────────

    @Test
    fun `判据4_配额装不下时报QUOTA_FULL而不是硬塞`() {
        val fx = fixture()
        fx.workspace.put("notes/big.md", "x".repeat(2_000))
        val box = sandbox(fx, quota = 1_000L)
        assertNull(box.open("run-4"))
        val one = box.stage(fx.workspace, "notes/big.md")
        assertEquals(RehearsalSandbox.Stage.QUOTA_FULL, one.stage)
        assertTrue(one.note.contains("配额"))
        assertFalse(File(box.copyRoot()!!, "notes/big.md").exists())
        // 拒了就不许扣配额：账只在真搬进副本时才算花掉（扣一半会让下一趟假报 QUOTA_FULL）。
        assertEquals(1000L, box.remainingBytes())
    }

    @Test
    fun `零配额的副本一进门就报剩余0_不出现负数`() {
        val fx = fixture()
        val box = sandbox(fx, quota = 0L)
        assertEquals(0L, box.remainingBytes())
    }

    // ── 【隔离】①：军师不读影子箱 ────────────────────────────────────────────

    @Test
    fun `隔离1_指向影子箱的声明路径一律拒_引用的是那个常量`() {
        val fx = fixture()
        val box = sandbox(fx, quota = 10L * 1024 * 1024)
        assertNull(box.open("run-iso"))
        val shadowRel = FileShadowSandbox.SHADOW_DIR_NAME + "/op-1/notes/a.md"
        val one = box.stage(fx.workspace, shadowRel)
        assertEquals(RehearsalSandbox.Stage.REFUSED, one.stage)
        assertTrue("拒绝理由要说清是哪条规矩：" + one.note, one.note.contains("影子"))
        // 常量改名的话这条测试会跟着红——不是靠这里再抄一遍字符串。
        assertEquals(".shadow", FileShadowSandbox.SHADOW_DIR_NAME)
    }

    @Test
    fun `绝对写法上跳与指向军师自己根的都拒`() {
        val fx = fixture()
        val box = sandbox(fx, quota = 1L)
        assertNotNull(box.refuse(File(fx.workspace, "a.md").absolutePath))
        assertNotNull(box.refuse("../../outside/a.md"))
        assertNotNull(box.refuse("rehearsal/rh-x/a.md"))
        assertNull(box.refuse("notes/sub/a.md"))
        // 反斜杠写法的上跳也得抓：判据不能只认一种分隔符（这串只喂纯函数，不落盘）。
        assertNotNull("反斜杠写法的上跳不许绕过越界判据", box.refuse("..\\notes\\a.md"))
        // `./` 前缀是合法写法（归一在 relKey 那一步做），不能被越界判据误伤。
        assertNull(box.refuse("./notes/sub/a.md"))
        // 空声明也得拒（不然 stage 会拿工作区根当对象）。
        assertEquals(RehearsalSandbox.Stage.REFUSED, box.stage(fx.workspace, "   ").stage)
        assertNotNull(box.refuse("   "))
    }

    // ── 目录归置与"新建对象" ─────────────────────────────────────────────────

    @Test
    fun `目录声明逐个子文件走同一条阈值与配额规则`() {
        val fx = fixture()
        val cap = 256L
        fx.workspace.put("docs/a.md", "x".repeat(100))
        fx.workspace.put("docs/b.md", "y".repeat(100))
        fx.workspace.putSize("docs/huge.bin", cap + 1L)
        val box = sandbox(fx, quota = 10L * 1024 * 1024, cap = cap)
        assertNull(box.open("run-dir"))
        val one = box.stage(fx.workspace, "docs")
        assertEquals(RehearsalSandbox.Stage.COPIED, one.stage)
        assertTrue(one.note.contains("搬 2 个子文件"))
        assertTrue(one.note.contains("1 个超阈值"))
        assertTrue(File(box.copyRoot()!!, "docs/a.md").isFile)
        assertFalse("超阈值那个子文件不许混进副本", File(box.copyRoot()!!, "docs/huge.bin").exists())
        assertEquals(200L, box.usedBytes)
        // 元数据那格报的是**源树全量**（100+100+257）——只报搬进来的 200 就把没复制的那个藏掉了。
        assertEquals(457L, one.meta?.bytes)
    }

    @Test
    fun `目录里装不下就整体报QUOTA_FULL_不留半棵树算成功`() {
        val fx = fixture()
        fx.workspace.put("docs/a.md", "x".repeat(100))
        fx.workspace.put("docs/b.md", "y".repeat(100))
        val box = sandbox(fx, quota = 150L)
        assertNull(box.open("run-dir-quota"))
        assertEquals(RehearsalSandbox.Stage.QUOTA_FULL, box.stage(fx.workspace, "docs").stage)
        assertEquals(100L, box.usedBytes)
        // 半截的那一棵由调用方降级后的 cleanup 收走（跑法层测试钉），这里只保证不谎报成功。
    }

    @Test
    fun `还不存在的对象按ABSENT算_可写格问的是要落它的目录`() {
        val fx = fixture()
        File(fx.workspace, "notes").mkdirs()
        val box = sandbox(fx, quota = 10L * 1024 * 1024)
        assertNull(box.open("run-absent"))
        val one = box.stage(fx.workspace, "notes/new.md")
        assertEquals(RehearsalSandbox.Stage.ABSENT, one.stage)
        assertTrue("父目录可写 ⇒ 说得出'能建'", one.meta?.writable == true)
        assertFalse(one.meta!!.exists)
        val outside = box.stage(fx.workspace, "no-such-dir/new.md")
        assertEquals(RehearsalSandbox.Stage.ABSENT, outside.stage)
        assertFalse("父目录不存在 ⇒ 可写格必须为假", outside.meta?.writable == true)
    }

    // ── 实测影响面：diff 按摘要，不按大小 ────────────────────────────────────

    @Test
    fun `副本树diff按摘要比_等长覆盖也算改了`() {
        val fx = fixture()
        fx.workspace.put("notes/a.md", "aaaa")
        fx.workspace.put("notes/b.md", "bbbb")
        val box = sandbox(fx, quota = 10L * 1024 * 1024)
        assertNull(box.open("run-diff"))
        box.stage(fx.workspace, "notes/a.md")
        box.stage(fx.workspace, "notes/b.md")
        val baseline = box.snapshot()
        assertEquals(2, baseline.size)
        // 等长覆盖：大小一格没变，只有摘要能认出这一下。
        File(box.copyRoot()!!, "notes/a.md").writeText("zzzz")
        assertEquals(listOf("notes/a.md"), box.diffPaths(baseline))
    }

    @Test
    fun `新增与消失都进影响面`() {
        val fx = fixture()
        fx.workspace.put("notes/a.md", "aaaa")
        val box = sandbox(fx, quota = 10L * 1024 * 1024)
        assertNull(box.open("run-diff2"))
        box.stage(fx.workspace, "notes/a.md")
        val baseline = box.snapshot()
        File(box.copyRoot()!!, "notes/new.md").writeText("n")
        File(box.copyRoot()!!, "notes/a.md").delete()
        assertEquals(listOf("notes/a.md", "notes/new.md"), box.diffPaths(baseline))
    }

    @Test
    fun `工具自己的退路目录不算影响面`() {
        val fx = fixture()
        fx.workspace.put("notes/a.md", "aaaa")
        val box = sandbox(fx, quota = 10L * 1024 * 1024)
        assertNull(box.open("run-book"))
        box.stage(fx.workspace, "notes/a.md")
        val baseline = box.snapshot()
        val root = box.copyRoot()!!
        File(root, "备份/a.md").apply { parentFile?.mkdirs() }.writeText("old")
        File(root, "回收站/a.md").apply { parentFile?.mkdirs() }.writeText("old")
        File(root, "notes/.tmp-write-1.tmp").apply { parentFile?.mkdirs() }.writeText("half")
        assertTrue("记账位混进影响面就等于每次写都多碰几个对象", box.diffPaths(baseline).isEmpty())
    }

    // ── 判据②⑤的落点：清理 ──────────────────────────────────────────────────

    @Test
    fun `判据2_清理把副本称完重量再删且目录确实消失`() {
        val fx = fixture()
        fx.workspace.put("notes/a.md", "x".repeat(1_024))
        fx.workspace.put("notes/b.md", "y".repeat(2_048))
        val box = sandbox(fx, quota = 10L * 1024 * 1024)
        assertNull(box.open("run-clean"))
        box.stage(fx.workspace, "notes/a.md")
        box.stage(fx.workspace, "notes/b.md")
        val root = box.copyRoot()!!
        val clean = box.cleanup()
        assertEquals(3_072L, clean.bytesFreed)
        assertTrue("删除的目录数至少有副本根与它上面那一层", clean.removedDirs >= 2)
        assertFalse(root.exists())
        assertTrue(clean.gone)
        assertNull("清了之后根位交出，二次清理不许炸", box.copyRoot())
    }

    @Test
    fun `清理完把空的rehearsal壳也摘掉_cacheDir里不留军师的空目录`() {
        val fx = fixture()
        fx.workspace.put("a.md", "x")
        val box = sandbox(fx, quota = 10L * 1024 * 1024)
        assertNull(box.open("run-shell"))
        box.stage(fx.workspace, "a.md")
        assertTrue(box.cleanup().gone)
        assertFalse("空的 rehearsal 壳也不该留着", File(fx.cache, "rehearsal").exists())
    }

    @Test
    fun `二次清理幂等_不建目录也不报错`() {
        val fx = fixture()
        val box = sandbox(fx, quota = 1L)
        assertNull(box.open("run-twice"))
        box.cleanup()
        val second = box.cleanup()
        assertEquals(0L, second.bytesFreed)
        assertTrue(second.gone)
        assertFalse(File(fx.cache, "rehearsal").exists())
    }

    @Test
    fun `负例_清理走的路径不在rehearsal那一层就一动不动`() {
        val fx = fixture()
        // 手工摆一棵"看着像副本"的真盘树：名字带 rh- 但父目录不是 rehearsal。
        val rogue = File(fx.workspace, "rh-not-a-copy")
        rogue.mkdirs()
        File(rogue, "precious.txt").writeText("do not delete")
        val box = sandbox(fx, quota = 1L)
        val clean = box.discardExisting("not-a-copy")
        assertEquals(0L, clean.bytesFreed)
        assertTrue("别人的树必须还在", File(rogue, "precious.txt").isFile)
    }

    @Test
    fun `删除前的assert逐条单独算_少一条就敢动别人的树`() {
        val fx = fixture()
        val box = sandbox(fx, quota = 10L * 1024 * 1024)
        assertNull(box.open("run-del"))
        val good = box.copyRoot()!!
        File(good, "f.txt").writeText("x")
        assertTrue("该删的那棵必须判得到（判不到就是四条全摆设）", box.deletable(good))
        // 三条各造一棵"只差这一条不满足"的树：摘掉哪条，对应那棵就必须还判不过。
        val wrongParent = File(fx.cache, "elsewhere/rh-stray").apply { mkdirs() }
        assertFalse("父目录名不对（不是 rehearsal）不许动手", box.deletable(wrongParent))
        val noPrefix = File(fx.cache, "rehearsal/shadow-copy").apply { mkdirs() }
        assertFalse("名字没有 rh- 前缀的不许动手（别人的目录）", box.deletable(noPrefix))
        val outside = File(fx.workspace, "rehearsal/rh-outside").apply { mkdirs() }
        assertFalse("不在 cacheDir 里的不许动手：root=${fx.cache} dir=$outside", box.deletable(outside))
        listOf(wrongParent, noPrefix, outside).forEach {
            assertTrue("${it.name} 判不过就得原样还在", it.isDirectory)
        }
    }

    @Test
    fun `discardExisting不建目录_没跑过的runId交回干净`() {
        val fx = fixture()
        val clean = sandbox(fx, quota = 1L).discardExisting("never-ran")
        assertTrue(clean.gone)
        assertEquals(0L, clean.bytesFreed)
        assertFalse(File(fx.cache, "rehearsal").exists())
    }

    @Test
    fun `sweepStale只扫遗留的rh目录_不动正在用的那根也不动别人的`() {
        val fx = fixture()
        val root = File(fx.cache, "rehearsal")
        val stale = File(root, "rh-stale").apply { mkdirs() }
        File(stale, "x.txt").writeText("x")
        val live = File(root, "rh-live").apply { mkdirs() }
        File(live, "keep.txt").writeText("k")
        val foreign = File(root, "sh-shadow").apply { mkdirs() }
        File(foreign, "not-mine.txt").writeText("f")
        val now = System.currentTimeMillis()
        stale.setLastModified(now - 60L * 60L * 1000L)
        live.setLastModified(now)
        val swept = sandbox(fx, quota = 1L).sweepStale("live", olderThanMs = 60L * 1000L, nowMs = now)
        assertEquals(1L, swept.bytesFreed)
        assertFalse("遗留的那根必须清掉", stale.exists())
        assertTrue("正在用的那一根永不动", File(live, "keep.txt").isFile)
        assertTrue("带别人前缀的目录不归军师扫", File(foreign, "not-mine.txt").isFile)
    }

    @Test
    fun `开根之前先清同名旧副本_第二次跑不许拿上一次的现场`() {
        val fx = fixture()
        fx.workspace.put("a.md", "old")
        val first = sandbox(fx, quota = 10L * 1024 * 1024)
        assertNull(first.open("run-reopen"))
        first.stage(fx.workspace, "a.md")
        val second = sandbox(fx, quota = 10L * 1024 * 1024)
        assertNull(second.open("run-reopen"))
        assertTrue("同 runId 重开必须从空树开始", second.snapshot().isEmpty())
    }
}
