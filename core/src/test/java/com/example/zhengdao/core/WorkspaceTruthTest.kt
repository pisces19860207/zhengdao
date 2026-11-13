// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 第六百一十五批 · 工作区实态的唯一判据与唯一留痕。
 *
 * 钉三件事：
 * 1. **背离判定**只有一种算法（尾斜杠不算两个目录，空读数不许冒充背离）；
 * 2. **「当前实路径」那一行**字段齐全（声明缺读数也要显式说出来，不许省略字段）；
 * 3. **去重闸门**：`hostDir` 是热路径，同一条实走路径只许报一次；实走变了（包括
 *    回落和回落 healed 回去）必须再报一次。这一条是判据③「切换／回落后不残留旧值」
 *    在日志侧的落点——残留旧值在界面上看得见，在日志里就靠这一条闸。
 *
 * 全部 JVM 纯测：[WorkspaceTruth.report] 的落点由参数交进来，本文件自己收着，
 * 不需要 Android Context，也不碰 `RunLog` 的落盘。
 */
class WorkspaceTruthTest {

    private val shared = "/storage/emulated/0/Download/男性"
    private val private = "/storage/emulated/0/Android/data/com.example.zhengdao/files/workspace"

    @Before
    fun clearReportState() = WorkspaceTruth.resetReportedForTest()

    // ── 1. 背离判定 ────────────────────────────────────────────────────────

    @Test
    fun `背离_同一条路尾斜杠不算两个目录`() {
        assertFalse(WorkspaceTruth.diverged(shared, "$shared/"))
        assertFalse(WorkspaceTruth.diverged("$shared/", shared))
    }

    @Test
    fun `背离_授权没了回落私有目录必须判真`() {
        // 569 现场：SP 说男性、实走私有目录。这一条判红就意味着界面欠一句解释。
        assertTrue(WorkspaceTruth.diverged(private, shared))
    }

    @Test
    fun `背离_任一侧没读数不算背离`() {
        // 没有两条路可比就说"背离"是假账；缺读数由 line() 明写出来，不靠布尔位。
        assertFalse(WorkspaceTruth.diverged(private, null))
        assertFalse(WorkspaceTruth.diverged(null, shared))
        assertFalse(WorkspaceTruth.diverged(private, ""))
    }

    // ── 2. 那一行的形状 ────────────────────────────────────────────────────

    @Test
    fun `实路径行_三个字段都在且带锚`() {
        val l = WorkspaceTruth.line("投递", private, shared, "共享存储未授权 ⇒ 回落 App 私有目录")
        assertTrue("锚要在：$l", l.startsWith(WorkspaceTruth.TAG))
        assertTrue(l.contains("当前实路径"))
        assertTrue(l.contains(private))
        assertTrue("声明那栏必须点名用户以为的地方：$l", l.contains(shared))
        assertTrue(l.contains("共享存储未授权"))
    }

    @Test
    fun `实路径行_声明没读数时明写无读数而不是省略`() {
        val l = WorkspaceTruth.line("资料库", private, null, WorkspaceTruth.SAME)
        assertTrue("不许把缺读数糊成一致：$l", l.contains("声明=（无读数）"))
    }

    @Test
    fun `实路径行_路径里的换行压成一行`() {
        // 日志按行读：一个换行能把一条留痕劈成两条（KernelAudit 同一口径）。
        val l = WorkspaceTruth.line("投递", "$shared\n被劈开了", shared, "r")
        assertEquals(1, l.lines().size)
    }

    // ── 3. 去重闸门（判据③ 日志侧） ────────────────────────────────────────

    @Test
    fun `留痕_同一条实走路径只报一次`() {
        val lines = mutableListOf<String>()
        repeat(5) {
            WorkspaceTruth.report("投递", private, shared, "回落", lines::add)
        }
        assertEquals("热路径每次读都记＝把要看的那行冲走", 1, lines.size)
    }

    @Test
    fun `留痕_实走变了必须再报一次`() {
        val lines = mutableListOf<String>()
        WorkspaceTruth.report("投递", shared, shared, WorkspaceTruth.SAME, lines::add)
        WorkspaceTruth.report("投递", private, shared, "授权丢了", lines::add)
        assertEquals(2, lines.size)
        // 最后一行说的是**现在**这条路，不是上一条残留的旧值（判据③负例）。
        assertTrue(lines.last().contains(private))
        assertNotEquals(lines.first(), lines.last())
    }

    @Test
    fun `留痕_回到一致那一侧也算实态变了`() {
        val lines = mutableListOf<String>()
        WorkspaceTruth.report("投递", private, shared, "授权丢了", lines::add)
        // 授权恢复：从背离回到一致。没有这一行的话，"恢复"这个动作在日志里是隐形的。
        WorkspaceTruth.report("投递", shared, shared, WorkspaceTruth.SAME, lines::add)
        assertEquals(2, lines.size)
        assertTrue(lines.last().contains(shared))
    }

    @Test
    fun `留痕_两个解析器各记各的不互相顶掉`() {
        val lines = mutableListOf<String>()
        WorkspaceTruth.report("投递", private, shared, "回落", lines::add)
        WorkspaceTruth.report("资料库", private, shared, "回落", lines::add)
        assertEquals("资料库的读数不该被投递那次报过的路吃掉", 2, lines.size)
    }

    @Test
    fun `留痕_没有背离时也要自证一次当前实路径`() {
        val lines = mutableListOf<String>()
        WorkspaceTruth.report("投递", shared, shared, WorkspaceTruth.SAME, lines::add)
        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("当前实路径"))
    }

    // ── 4. 末级名口径 ──────────────────────────────────────────────────────

    @Test
    fun `显示名_只到末级空名退回整条路`() {
        assertEquals("男性", WorkspaceTruth.leafOf(shared))
        assertEquals("workspace", WorkspaceTruth.leafOf("$private/"))
        assertEquals("/", WorkspaceTruth.leafOf("/"))
        // 末级给不出名字时退回整条路：宁可长也不能显示一个没字的行（W1 同口径）。
        assertEquals("no-name-at-all", WorkspaceTruth.leafOf("no-name-at-all"))
    }
}
