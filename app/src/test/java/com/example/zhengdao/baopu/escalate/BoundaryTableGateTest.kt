// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百一十七批 · 边界声明**唯一数据源表**的门禁（派工单 §三 判据③④⑤）。
//
// 判据③ 声明表唯一源：**改表即改行为**——某工具（能力类）writableRoots 置空 ⇒ 写被拒；
//   同一行把根加回来 ⇒ 写放行（行为跟着表走，不跟代码走）。
// 判据④ 缺行 ⇒ 按最严（不可写）**并记账**——边界执行账上要查得到那一笔。
// 判据⑤ 表有 schema 校验：schema 不对/行形状坏 ⇒ **整表拒载**并报错（拒载本身记账）。
// 另钉三件形状：改表文件重装即生效（唯一数据源的字面）、体检展示读表、缺行时
// 越界判定层仍是"说不准不升级"（475 的红线不因本批回退）。
package com.example.zhengdao.baopu.escalate

import com.example.zhengdao.baopu.DecisionLedger
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BoundaryTableGateTest {

    private lateinit var root: File
    private val now = 1_700_000_000_000L

    /** 根类展开表：workspace=两个临时根，private=一个临时根（测试自己的"本机路径"）。 */
    private lateinit var workspaceA: File
    private lateinit var workspaceB: File
    private lateinit var privateDir: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("zd517-boundary-").toFile()
        DecisionLedger.install(root)
        workspaceA = Files.createTempDirectory("zd517-ws-a-").toFile()
        workspaceB = Files.createTempDirectory("zd517-ws-b-").toFile()
        privateDir = Files.createTempDirectory("zd517-private-").toFile()
    }

    @After
    fun tearDown() {
        BoundaryTable.resetForTest()
        DecisionLedger.uninstallForTest()
        root.deleteRecursively()
        workspaceA.deleteRecursively()
        workspaceB.deleteRecursively()
        privateDir.deleteRecursively()
    }

    private fun roots(): Map<String, List<File>> = mapOf(
        BoundaryTable.ROOT_WORKSPACE to listOf(workspaceA, workspaceB),
        BoundaryTable.ROOT_PRIVATE to listOf(privateDir),
    )

    /** 装一份**两行为空**的表（每类自己放行/收紧），并断言种子文件落了盘。 */
    private fun install(vararg rows: BoundaryRow) {
        val dir = root
        if (rows.isNotEmpty()) {
            File(dir, BoundaryTable.FILE_NAME).writeText(
                BoundaryTable.toJson(rows.toList()).toString(),
                Charsets.UTF_8,
            )
        }
        BoundaryTable.install(dir) { roots() }
    }

    // ── 判据③ 改表即改行为 ─────────────────────────────────────────────────

    @Test
    fun `判据三_可写根置空_写被拒_根加回来_写放行`() {
        // 第一步：FILE_WRITE 声明了 workspace＋private ⇒ 工作区内的写放行
        install(
            BoundaryRow(CapabilityKind.FILE_WRITE, writableRoots = listOf(BoundaryTable.ROOT_WORKSPACE)),
            BoundaryRow(CapabilityKind.FILE_READ, writableRoots = listOf(BoundaryTable.ROOT_WORKSPACE)),
        )
        val inside = File(workspaceA, "notes/today.md")
        assertTrue("声明根内的写要放行", BoundaryTable.checkWrite(CapabilityKind.FILE_WRITE, inside, now).allowed)

        // 第二步：**同一张表**把 FILE_WRITE 的 writableRoots 置空 ⇒ 同一个目标被拒
        install(
            BoundaryRow(CapabilityKind.FILE_WRITE, writableRoots = emptyList()),
            BoundaryRow(CapabilityKind.FILE_READ, writableRoots = listOf(BoundaryTable.ROOT_WORKSPACE)),
        )
        val ruling = BoundaryTable.checkWrite(CapabilityKind.FILE_WRITE, inside, now + 1)
        assertFalse("writableRoots 置空 ⇒ 写被拒（判据③的字面）", ruling.allowed)
        assertEquals("empty_roots", ruling.base)
        assertTrue(ruling.reason.contains("不可写"))

        // 第三步：把根类改回来 ⇒ 行为跟着表变（不改任何代码）
        install(BoundaryRow(CapabilityKind.FILE_WRITE, writableRoots = listOf(BoundaryTable.ROOT_WORKSPACE)))
        assertTrue("改表即改行为", BoundaryTable.checkWrite(CapabilityKind.FILE_WRITE, inside, now + 2).allowed)
    }

    @Test
    fun `判据三_声明根之外的目标_拒并点名声明根`() {
        install(BoundaryRow(CapabilityKind.FILE_WRITE, writableRoots = listOf(BoundaryTable.ROOT_WORKSPACE)))
        val outside = File(privateDir, "secret.txt")
        val ruling = BoundaryTable.checkWrite(CapabilityKind.FILE_WRITE, outside, now)
        assertFalse(ruling.allowed)
        assertEquals("outside_roots", ruling.base)
        assertTrue("拒因要点名声明的根类：${ruling.reason}", ruling.reason.contains("workspace"))
    }

    // ── 判据④ 缺行 ⇒ 最严并记账 ────────────────────────────────────────────

    @Test
    fun `判据四_缺行按最严_不可写并记一笔账`() {
        // 表里只有 FILE_WRITE 一行 ⇒ 其余能力类全缺行
        install(BoundaryRow(CapabilityKind.FILE_WRITE, writableRoots = listOf(BoundaryTable.ROOT_WORKSPACE)))
        val target = File(workspaceA, "x.txt")
        val ruling = BoundaryTable.checkWrite(CapabilityKind.BROWSER, target, now)
        assertFalse("缺行 ⇒ 不可写（最严）", ruling.allowed)
        assertEquals("missing_row", ruling.base)
        // 记账：边界执行账上要有这一行（at 为 epoch 绝对时刻，why 带原话）
        val ledger = BoundaryTable.enforcementReplay(root)
        assertEquals(1, ledger.size)
        assertTrue(ledger.first().contains("\"k\":\"write_denied\""))
        assertTrue(ledger.first().contains("\"at\":$now"))
        assertTrue(ledger.first().contains("missing_row"))
        assertTrue("账根没挂时也要留痕（日志）——这里挂了，账必须在", ledger.isNotEmpty())
    }

    // ── 判据⑤ schema 校验 ──────────────────────────────────────────────────

    @Test
    fun `判据五_非法表拒加载并报错_全部按最严`() {
        // schema 错 ⇒ 拒载
        File(root, BoundaryTable.FILE_NAME).writeText(
            """{"schema":"zd-boundary/999","rows":[]}""",
            Charsets.UTF_8,
        )
        BoundaryTable.install(root) { roots() }
        assertNull("schema 不对 ⇒ fromJson 整表作废", BoundaryTable.fromJson(File(root, BoundaryTable.FILE_NAME).readText()))
        assertFalse("拒载 ⇒ 一切写被拒（最严）", BoundaryTable.checkWrite(CapabilityKind.FILE_WRITE, File(workspaceA, "a"), now).allowed)
        assertTrue("拒载要报错并记账", BoundaryTable.enforcementReplay(root).any { it.contains("table_rejected") })

        // 行形状坏（缺 allowOutbound 键）⇒ 同样整表拒载
        BoundaryTable.resetForTest()
        (root.listFiles() ?: emptyArray()).forEach { it.delete() }
        File(root, BoundaryTable.FILE_NAME).writeText(
            """{"schema":"zd-boundary/1","rows":[{"kind":"file_write","writableRoots":[],"maxDurationMs":0}]}""",
            Charsets.UTF_8,
        )
        BoundaryTable.install(root) { roots() }
        assertFalse("半份声明比没有更危险 ⇒ 也是最严", BoundaryTable.checkWrite(CapabilityKind.FILE_WRITE, File(workspaceA, "b"), now + 1).allowed)
        assertEquals(2, BoundaryTable.enforcementReplay(root).size)
    }

    @Test
    fun `判据五_合法表装得上_默认种子落盘且可读回`() {
        // 不预置文件 ⇒ install 播默认种子并落盘
        BoundaryTable.install(root) { roots() }
        val f = File(root, BoundaryTable.FILE_NAME)
        assertTrue("种子文件要落盘（唯一数据源要有盘面）", f.isFile)
        val parsed = BoundaryTable.fromJson(f.readText())
        assertNotNull(parsed)
        assertEquals("默认六类各一行", CapabilityKind.entries.filter { it != CapabilityKind.OTHER }.size, parsed!!.size)
        // 越界判定读表：FILE_WRITE 的可写根展开后喂给判定层（管线没拦 ⇒ 硬事实=false）。
        // （scopeEscapeDenied 必须给布尔：null＝管线没这条事实 ⇒ 那一维"说不准"，505 的口径。）
        val b = BoundaryTable.boundaryOf(CapabilityKind.FILE_WRITE)
        assertNotNull(b)
        assertTrue("展开的根路径要进判定对象", b!!.writableRoots.isNotEmpty())
        // 默认种子没声明时长维 ⇒ 根内也算"说不准"（有一维不知道就不说"全在界内"——505 的规则）。
        assertEquals(
            BoundaryVerdict.UNCERTAIN,
            BoundaryRules.verdictOf(
                b,
                BoundaryFacts(tool = "write_file", writeTarget = File(workspaceA, "p").absolutePath, scopeEscapeDenied = false),
            ),
        )
        // 表把时长维也声明了 ⇒ 判定就判到那一维：根内全绿 ⇒ 在界内（改表即改判定）。
        install(BoundaryRow(CapabilityKind.FILE_WRITE, writableRoots = listOf(BoundaryTable.ROOT_WORKSPACE), maxDurationMs = 60_000L))
        val full = BoundaryTable.boundaryOf(CapabilityKind.FILE_WRITE)!!
        assertEquals(
            BoundaryVerdict.IN_SCOPE,
            BoundaryRules.verdictOf(
                full,
                BoundaryFacts(tool = "write_file", writeTarget = File(workspaceA, "p").absolutePath, scopeEscapeDenied = false, elapsedMs = 1_000L),
            ),
        )
        assertEquals(
            "根外 ⇒ 判定层判越界（硬判定保留 505 语义）",
            BoundaryVerdict.OUT_OF_SCOPE,
            BoundaryRules.verdictOf(
                full,
                BoundaryFacts(
                    tool = "write_file",
                    writeTarget = File(privateDir.parentFile, "elsewhere/x").absolutePath,
                    scopeEscapeDenied = false,
                ),
            ),
        )
    }

    // ── 形状：缺行不把"不确定"当越界（475 红线原样保留） ─────────────────────

    @Test
    fun `形状_缺行时判定层仍是说不准_升级只认硬事实`() {
        install(BoundaryRow(CapabilityKind.FILE_WRITE, writableRoots = listOf(BoundaryTable.ROOT_WORKSPACE)))
        // BROWSER 缺行 ⇒ boundaryOf null ⇒ 没有硬事实 ⇒ UNCERTAIN（不许当越界）
        val verdict = BoundaryRules.verdictOf(
            BoundaryTable.boundaryOf(CapabilityKind.BROWSER),
            BoundaryFacts(tool = "browser_open"),
        )
        assertEquals(BoundaryVerdict.UNCERTAIN, verdict)
        // 但执行闸已经把"做"这一头拦死（两层各司其职）
        assertFalse(BoundaryTable.checkWrite(CapabilityKind.BROWSER, File(workspaceA, "y"), now).allowed)
    }

    // ── 体检展示读表 ────────────────────────────────────────────────────────

    @Test
    fun `体检行_展示读表_声明数与最严口径都在行里`() {
        assertEquals("没装配 ⇒ 明说", "边界声明：机制没装配（执行闸不拦）", BoundaryTable.sheetLine())
        install(BoundaryRow(CapabilityKind.FILE_WRITE, writableRoots = listOf(BoundaryTable.ROOT_WORKSPACE)))
        val line = BoundaryTable.sheetLine()
        assertTrue(line, line.contains("1 类已声明"))
        assertTrue(line, line.contains("缺行按最严"))
    }

    // ── 站点闸（缺行/置空 ⇒ 不许外发） ──────────────────────────────────────

    @Test
    fun `站点闸_缺行或置空都不许外发_在名单内放行`() {
        // 缺行（row=null）⇒ 不许外发（最严）
        assertFalse(SiteGate.allows("example.com", null).allowed)
        install(BoundaryRow(CapabilityKind.BROWSER, allowedSites = emptyList()))
        val row = BoundaryTable.rowOf(CapabilityKind.BROWSER)!!
        assertFalse("置空 ⇒ 不许外发（最严）", SiteGate.allows("example.com", row).allowed)
        val withSites = row.copy(allowedSites = listOf("example.com"))
        assertTrue(SiteGate.allows("example.com", withSites).allowed)
        assertFalse(SiteGate.allows("evil.example.net", withSites).allowed)
    }
}
