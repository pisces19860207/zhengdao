// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 332 的单测：四类任务各一组「怎么算完成」的正例＋造红负例，三条硬规矩各带反例钉死。
// 造红思路照 340 `ValueCheckTest`：不写「大概能过」的断言，只写「谎报/缺证据/拿错尺子」必须红。
package com.example.zhengdao.baopu.verify

import com.example.zhengdao.baopu.FileOpEntry
import com.example.zhengdao.baopu.FileOpKind
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ContractsTest {

    private lateinit var root: File
    private lateinit var src: File
    private lateinit var dst: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("zd-contracts").toFile()
        src = File(root, "src").apply { mkdirs() }
        dst = File(root, "out").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    // ── 材料 ─────────────────────────────────────────────────────────────

    private fun write(dir: File, rel: String, text: String): File {
        val f = File(dir, rel)
        f.parentFile?.mkdirs()
        f.writeText(text, StandardCharsets.UTF_8)
        return f
    }

    private fun row(kind: FileOpKind, path: String): FileOpEntry =
        FileOpEntry(at = 0L, sessionId = 0L, tool = "copy_path", kind = kind, path = path, purpose = "332 测试")

    /** 两个文件复制到位＋两行账（四判据都该过的底料）。 */
    private fun cleanCopy(ledger: List<FileOpEntry>? = null): Evidence.FileCopy {
        write(src, "a.txt", "甲")
        write(src, "b.txt", "乙")
        write(dst, "a.txt", "甲")
        write(dst, "b.txt", "乙")
        val rows = ledger ?: listOf(
            row(FileOpKind.COPY, File(dst, "a.txt").absolutePath),
            row(FileOpKind.COPY, File(dst, "b.txt").absolutePath),
        )
        return Contracts.measureCopy(src, dst, listOf("a.txt", "b.txt"), rows)
    }

    private fun codeEvidence(
        compile: Evidence.Compile? = Evidence.Compile(":app:compileDebugKotlin", true, 0),
        tests: Evidence.Tests? = Evidence.Tests("ContractsTest", 30, 0, 0),
        scope: List<String> = listOf("app/src/main/java/com/example/zhengdao/baopu/verify/"),
        changed: List<String> = listOf("app/src/main/java/com/example/zhengdao/baopu/verify/Contracts.kt"),
        budget: Evidence.Budget? = Evidence.Budget(418, 418, 418, emptyList()),
    ) = Evidence.Code(compile, tests, scope, changed, budget)

    private fun tableEvidence(
        rows: Int = 120,
        cols: Int = 6,
        samples: List<Evidence.RowSample>? = null,
        reopen: Evidence.Reopen? = Evidence.Reopen(true, 120, 6, "CSV 再读"),
    ): Evidence.Table {
        val picked = samples ?: Contracts.sampleRowIndices(rows).map {
            Evidence.RowSample(it, listOf("$it", "甲", "1"), listOf("$it", "甲", "1"))
        }
        return Evidence.Table(rows, cols, rows, cols, picked, reopen)
    }

    // ── 一、文件整理/复制 ────────────────────────────────────────────────

    @Test
    fun `复制_路径集合字节账行全对_判PASS`() {
        val r = ContractSelector.evaluate(TaskType.FILE_ORGANIZE, cleanCopy())
        assertEquals(Status.PASS, r.status)
        assertEquals(Reason.OK, r.reason)
        assertEquals(4, r.checks.size)
        assertTrue(r.render().contains("PASS（过）"))
    }

    @Test
    fun `目标多出一个文件_路径集合不精确相等_判FAIL`() {
        write(dst, "c.txt", "多的")
        val r = ContractSelector.evaluate(TaskType.FILE_ORGANIZE, cleanCopy())
        assertEquals(Status.FAIL, r.status)
        assertTrue(r.failed.contains(CheckId.PATH_SET))
        assertTrue(r.render().contains("c.txt"))
    }

    @Test
    fun `同尺寸不同内容_哈希不等_判FAIL且报我说实际`() {
        write(src, "a.txt", "abc")
        write(dst, "a.txt", "xyz") // 同 3 字节，字节不同
        val ev = Contracts.measureCopy(
            src, dst, listOf("a.txt"),
            listOf(row(FileOpKind.COPY, File(dst, "a.txt").absolutePath)),
        )
        val r = ContractSelector.evaluate(TaskType.FILE_ORGANIZE, ev)
        assertEquals(Status.FAIL, r.status)
        assertTrue(r.failed.contains(CheckId.BYTE_IDENTICAL))
        val detail = r.checks.first { it.id == CheckId.BYTE_IDENTICAL }.detail
        assertTrue("要说清是 sha 对不上：$detail", detail.contains("sha 源"))
    }

    @Test
    fun `源清单带点点_跑出根_判FAIL`() {
        write(src, "a.txt", "甲")
        write(dst, "a.txt", "甲")
        write(root, "evil.txt", "根外的东西")
        val ev = Contracts.measureCopy(src, dst, listOf("../evil.txt"), emptyList())
        val r = ContractSelector.evaluate(TaskType.FILE_ORGANIZE, ev)
        assertTrue(r.failed.contains(CheckId.NO_ESCAPE))
        assertTrue(r.render().contains("跑出了根"))
    }

    @Test
    fun `绝对路径不许混进相对清单_判FAIL`() {
        write(src, "a.txt", "甲")
        write(dst, "a.txt", "甲")
        val ev = Contracts.measureCopy(src, dst, listOf(File(src, "a.txt").absolutePath), emptyList())
        val r = ContractSelector.evaluate(TaskType.FILE_ORGANIZE, ev)
        assertTrue(r.failed.contains(CheckId.NO_ESCAPE))
    }

    @Test
    fun `账行少一行_账不齐_判FAIL`() {
        val one = listOf(row(FileOpKind.COPY, File(dst, "a.txt").absolutePath))
        val r = ContractSelector.evaluate(TaskType.FILE_ORGANIZE, cleanCopy(one))
        assertTrue(r.failed.contains(CheckId.LEDGER_COMPLETE))
        assertTrue(r.render().contains("没有账行"))
    }

    @Test
    fun `账行指向目标根之外_判FAIL`() {
        write(src, "a.txt", "甲")
        write(dst, "a.txt", "甲")
        val stray = row(FileOpKind.COPY, File(root, "elsewhere/a.txt").absolutePath)
        val ev = Contracts.measureCopy(src, dst, listOf("a.txt"), listOf(stray))
        val r = ContractSelector.evaluate(TaskType.FILE_ORGANIZE, ev)
        assertTrue(r.failed.contains(CheckId.LEDGER_COMPLETE))
        assertTrue(r.render().contains("目标根之外"))
    }

    @Test
    fun `同一路径两行账_判FAIL`() {
        val a = File(dst, "a.txt").absolutePath
        val dup = listOf(row(FileOpKind.COPY, a), row(FileOpKind.MOVE, a))
        val r = ContractSelector.evaluate(TaskType.FILE_ORGANIZE, cleanCopy(dup))
        assertTrue(r.failed.contains(CheckId.LEDGER_COMPLETE))
        assertTrue(r.render().contains("多行账"))
    }

    @Test
    fun `目标清单是扫出来的_不是抄源清单`() {
        write(src, "a.txt", "甲")
        write(dst, "a.txt", "甲")
        write(dst, "偷偷多写的.txt", "x")
        val ev = Contracts.measureCopy(src, dst, listOf("a.txt"), emptyList())
        assertTrue(ev.targetPaths.contains("偷偷多写的.txt"))
    }

    // ── 二、文档/内容产出 ────────────────────────────────────────────────

    private fun doc(
        text: String,
        declared: List<String> = listOf("开头一句说结论", "有数据支撑"),
        ticks: List<Evidence.Tick> = listOf(
            Evidence.Tick("开头一句说结论", true, "首段第一句"),
            Evidence.Tick("有数据支撑", true, "第二节引 340 的读数"),
        ),
    ): Evidence.Doc = Contracts.readDoc(write(root, "doc/report.md", text), declared, ticks)

    @Test
    fun `文档_存在结构合规检查项逐条打勾_判PASS`() {
        val ev = doc("# 结论\n先给结论。\n## 依据\n这里放数据。\n")
        val r = ContractSelector.evaluate(TaskType.DOC_PRODUCE, ev)
        assertEquals(Status.PASS, r.status)
        assertEquals(3, r.checks.size)
    }

    @Test
    fun `文档跳级_结构校验判FAIL`() {
        val ev = doc("# 结论\n先给结论。\n### 依据\n这里放数据。\n")
        val r = ContractSelector.evaluate(TaskType.DOC_PRODUCE, ev)
        assertTrue(r.failed.contains(CheckId.STRUCTURE))
        assertTrue(r.render().contains("跳级"))
    }

    @Test
    fun `文档空小节_结构校验判FAIL`() {
        val ev = doc("# 结论\n先给结论。\n## 依据\n## 尾巴\n收尾。\n")
        val r = ContractSelector.evaluate(TaskType.DOC_PRODUCE, ev)
        assertTrue(r.failed.contains(CheckId.STRUCTURE))
        assertTrue(r.render().contains("正文为空"))
    }

    @Test
    fun `文档围栏里的井号不算标题_不误伤`() {
        val text = "# 结论\n先给结论。\n```\n# 这不是标题\n```\n## 依据\n数据。\n"
        val r = ContractSelector.evaluate(TaskType.DOC_PRODUCE, doc(text))
        assertEquals(Status.PASS, r.status)
    }

    @Test
    fun `文档检查项没打勾_判FAIL`() {
        val ev = doc(
            "# 结论\n先给结论。\n## 依据\n数据。\n",
            ticks = listOf(
                Evidence.Tick("开头一句说结论", true, "首段第一句"),
                Evidence.Tick("有数据支撑", false, ""),
            ),
        )
        val r = ContractSelector.evaluate(TaskType.DOC_PRODUCE, ev)
        assertTrue(r.failed.contains(CheckId.USER_CHECKLIST))
        assertTrue(r.render().contains("没打勾"))
    }

    @Test
    fun `文档打了勾但没写凭什么_算假打勾_判FAIL`() {
        val ev = doc(
            "# 结论\n先给结论。\n## 依据\n数据。\n",
            ticks = listOf(
                Evidence.Tick("开头一句说结论", true, "首段第一句"),
                Evidence.Tick("有数据支撑", true, "   "),
            ),
        )
        val r = ContractSelector.evaluate(TaskType.DOC_PRODUCE, ev)
        assertTrue(r.failed.contains(CheckId.USER_CHECKLIST))
        assertTrue(r.render().contains("没写凭什么"))
    }

    @Test
    fun `用户给了两条检查项_只交回一条打勾_判FAIL缺证据`() {
        val ev = doc(
            "# 结论\n先给结论。\n## 依据\n数据。\n",
            ticks = listOf(Evidence.Tick("开头一句说结论", true, "首段第一句")),
        )
        val r = ContractSelector.evaluate(TaskType.DOC_PRODUCE, ev)
        assertEquals(Reason.MISSING_EVIDENCE, r.reason)
        assertTrue(r.missing.contains(CheckId.USER_CHECKLIST))
        assertTrue(r.render().contains("没有打勾记录"))
    }

    @Test
    fun `文档产物不存在_判FAIL`() {
        val ev = Contracts.readDoc(File(root, "doc/没有这个.md"), listOf("x"), emptyList())
        val r = ContractSelector.evaluate(TaskType.DOC_PRODUCE, ev)
        assertTrue(r.failed.contains(CheckId.EXISTS))
    }

    // ── 三、代码修改 ────────────────────────────────────────────────────

    @Test
    fun `代码_编译测试diff预算全过_判PASS`() {
        val r = ContractSelector.evaluate(TaskType.CODE_CHANGE, codeEvidence())
        assertEquals(Status.PASS, r.status)
        assertEquals(4, r.checks.size)
    }

    @Test
    fun `代码测试红_判FAIL`() {
        val r = ContractSelector.evaluate(
            TaskType.CODE_CHANGE,
            codeEvidence(tests = Evidence.Tests("ContractsTest", 30, 2, 0)),
        )
        assertTrue(r.failed.contains(CheckId.TESTS_GREEN))
        assertTrue(r.render().contains("红 2 例"))
    }

    @Test
    fun `代码测试0例_等于没跑_算缺证据`() {
        val r = ContractSelector.evaluate(
            TaskType.CODE_CHANGE,
            codeEvidence(tests = Evidence.Tests("ContractsTest", 0, 0, 0)),
        )
        assertEquals(Reason.MISSING_EVIDENCE, r.reason)
        assertTrue(r.missing.contains(CheckId.TESTS_GREEN))
    }

    @Test
    fun `代码改动跑到声明范围外_判FAIL`() {
        val r = ContractSelector.evaluate(
            TaskType.CODE_CHANGE,
            codeEvidence(changed = listOf("app/src/main/java/com/example/zhengdao/baopu/verify/Contracts.kt", "core/x.kt")),
        )
        assertTrue(r.failed.contains(CheckId.DIFF_IN_SCOPE))
        assertTrue(r.render().contains("core/x.kt"))
    }

    @Test
    fun `代码diff为空_这单没改动_判FAIL`() {
        val r = ContractSelector.evaluate(TaskType.CODE_CHANGE, codeEvidence(changed = emptyList()))
        assertTrue(r.failed.contains(CheckId.DIFF_IN_SCOPE))
        assertTrue(r.render().contains("diff 为空"))
    }

    @Test
    fun `声明范围是目录_同前缀的别的目录不许蹭进来`() {
        val r = ContractSelector.evaluate(
            TaskType.CODE_CHANGE,
            codeEvidence(changed = listOf("app/src/main/java/com/example/zhengdao/baopu/verifyX/Other.kt")),
        )
        assertTrue("verify/ 不该覆盖 verifyX/", r.failed.contains(CheckId.DIFF_IN_SCOPE))
    }

    @Test
    fun `声明范围带反斜杠也认_别让路径分隔符把人坑了`() {
        val r = ContractSelector.evaluate(
            TaskType.CODE_CHANGE,
            codeEvidence(
                scope = listOf("app\\src\\main\\java\\com\\example\\zhengdao\\baopu\\verify\\"),
                changed = listOf("app\\src\\main\\java\\com\\example\\zhengdao\\baopu\\verify\\Contracts.kt"),
            ),
        )
        assertEquals(Status.PASS, r.status)
    }

    @Test
    fun `代码预算涨了_判FAIL`() {
        val r = ContractSelector.evaluate(
            TaskType.CODE_CHANGE,
            codeEvidence(budget = Evidence.Budget(418, 420, 418, listOf("LongMethod|X.kt|run#1"))),
        )
        assertTrue(r.failed.contains(CheckId.BUDGET))
        assertTrue(r.render().contains("新增超预算"))
    }

    @Test
    fun `代码编译自报成功但退出码非0_自相矛盾判FAIL`() {
        val r = ContractSelector.evaluate(
            TaskType.CODE_CHANGE,
            codeEvidence(compile = Evidence.Compile(":app:compileDebugKotlin", true, 1)),
        )
        assertTrue(r.failed.contains(CheckId.COMPILE))
        assertTrue(r.render().contains("自相矛盾"))
    }

    // ── 四、数据/表格 ───────────────────────────────────────────────────

    @Test
    fun `表格_行列抽样再读全过_判PASS`() {
        val r = ContractSelector.evaluate(TaskType.DATA_TABLE, tableEvidence())
        assertEquals(Status.PASS, r.status)
        assertEquals(3, r.checks.size)
        assertTrue(r.render().contains("0, 30, 60, 90, 119"))
    }

    @Test
    fun `表格行数不符_判FAIL`() {
        val ev = Evidence.Table(120, 6, 118, 6, Contracts.sampleRowIndices(118).map {
            Evidence.RowSample(it, listOf("x"), listOf("x"))
        }, Evidence.Reopen(true, 118, 6, "CSV 再读"))
        val r = ContractSelector.evaluate(TaskType.DATA_TABLE, ev)
        assertTrue(r.failed.contains(CheckId.SHAPE))
        assertTrue(r.render().contains("说 120、实际 118"))
    }

    @Test
    fun `表格挑了别的行来抽_判FAIL`() {
        val ev = tableEvidence(samples = (0 until 5).map {
            Evidence.RowSample(it, listOf("$it"), listOf("$it"))
        })
        val r = ContractSelector.evaluate(TaskType.DATA_TABLE, ev)
        assertTrue(r.failed.contains(CheckId.SAMPLE_5))
        assertTrue(r.render().contains("挑行"))
    }

    @Test
    fun `表格抽样只抽了3行_判FAIL缺证据`() {
        val ev = tableEvidence(samples = listOf(0, 30, 60).map {
            Evidence.RowSample(it, listOf("$it"), listOf("$it"))
        })
        val r = ContractSelector.evaluate(TaskType.DATA_TABLE, ev)
        assertEquals(Reason.MISSING_EVIDENCE, r.reason)
        assertTrue(r.missing.contains(CheckId.SAMPLE_5))
    }

    @Test
    fun `表格抽样某格与源不一致_判FAIL`() {
        val picked = Contracts.sampleRowIndices(120).map {
            if (it == 60) {
                Evidence.RowSample(it, listOf("60", "甲"), listOf("60", "乙"))
            } else {
                Evidence.RowSample(it, listOf("$it", "甲"), listOf("$it", "甲"))
            }
        }
        val r = ContractSelector.evaluate(TaskType.DATA_TABLE, tableEvidence(samples = picked))
        assertTrue(r.failed.contains(CheckId.SAMPLE_5))
        assertTrue(r.render().contains("第 60 行"))
    }

    @Test
    fun `表格再读尺寸对不上_判FAIL`() {
        val r = ContractSelector.evaluate(
            TaskType.DATA_TABLE,
            tableEvidence(reopen = Evidence.Reopen(true, 118, 5, "CSV 再读")),
        )
        assertTrue(r.failed.contains(CheckId.REOPENABLE))
    }

    @Test
    fun `表格不足5行_全抽_照样能判`() {
        val ev = Evidence.Table(
            3, 2, 3, 2,
            (0 until 3).map { Evidence.RowSample(it, listOf("$it", "x"), listOf("$it", "x")) },
            Evidence.Reopen(true, 3, 2, "CSV 再读"),
        )
        val r = ContractSelector.evaluate(TaskType.DATA_TABLE, ev)
        assertEquals(Status.PASS, r.status)
        assertTrue(r.render().contains("抽 3 行"))
    }

    // ── 三条硬规矩 ──────────────────────────────────────────────────────

    @Test
    fun `硬规矩①_复制证据再齐也不许硬套成文档合格_判UNVERIFIABLE`() {
        val copy = cleanCopy()
        // 先用它自己的契约验一遍：四判据全过
        assertEquals(Status.PASS, ContractSelector.evaluate(TaskType.FILE_ORGANIZE, copy).status)
        // 拿同一套「六种证据最齐」的材料去判文档 ⇒ 判不了，不是勉强算过
        val r = ContractSelector.evaluate(TaskType.DOC_PRODUCE, copy)
        assertEquals(Status.UNVERIFIABLE, r.status)
        assertEquals(Reason.TYPE_MISMATCH, r.reason)
        assertTrue("一条判据都不许跑", r.checks.isEmpty())
        assertTrue(r.missing.isEmpty())
        assertTrue(r.failed.isEmpty())
        assertTrue(r.render().contains("不许硬套"))
    }

    @Test
    fun `硬规矩②_契约列了但一条证据都没交_判FAIL缺证据`() {
        val r = ContractSelector.evaluate(TaskType.CODE_CHANGE, null)
        assertEquals(Status.FAIL, r.status)
        assertEquals(Reason.MISSING_EVIDENCE, r.reason)
        assertEquals(4, r.checks.size)
        assertEquals(4, r.missing.size)
    }

    @Test
    fun `硬规矩③_四条里过三条缺一条_仍判FAIL_不许三项就算过`() {
        // 路径集合/字节/无逃逸都过，账行一条没有
        val r = ContractSelector.evaluate(TaskType.FILE_ORGANIZE, cleanCopy(ledger = emptyList()))
        assertEquals(Status.FAIL, r.status)
        assertEquals(Reason.MISSING_EVIDENCE, r.reason)
        assertTrue(r.missing.contains(CheckId.LEDGER_COMPLETE))
        assertEquals(3, r.checks.count { it.ok })
        assertFalse("三条过不许算过", r.status == Status.PASS)
    }

    @Test
    fun `硬规矩③_报告里没有评分字段_想加分数先过这条`() {
        val forbidden = listOf("score", "grade", "weight", "ratio", "percent", "point", "level")
        val names = (
            ContractReport::class.java.declaredMethods.map { it.name } +
                CheckOutcome::class.java.declaredMethods.map { it.name } +
                ContractSelector::class.java.declaredMethods.map { it.name }
            ).map { it.lowercase() }
        val hit = names.filter { n -> forbidden.any { n.contains(it) } }
        assertTrue("不许有评分/权重类成员：$hit", hit.isEmpty())
    }

    @Test
    fun `判据表_四类条数固定且两两不相交`() {
        val byType = ContractSelector.types().map { ContractSelector.checksOf(it) }
        assertEquals(listOf(4, 3, 4, 3), byType.map { it.size })
        val flat = byType.flatten()
        assertEquals("判据不许跨类型复用", flat.size, flat.toSet().size)
        assertEquals(14, flat.toSet().size)
    }

    @Test
    fun `判据wire名可回读_闭集不许悄悄加`() {
        CheckId.entries.forEach { assertEquals(it, CheckId.ofWire(it.wire)) }
        assertNull(CheckId.ofWire("composite_score"))
    }

    @Test
    fun `render_UNVERIFIABLE说明一条都没判`() {
        val r = ContractSelector.mismatch(TaskType.DATA_TABLE, TaskType.CODE_CHANGE)
        val text = r.render()
        assertTrue(text.contains("UNVERIFIABLE（判不了）"))
        assertTrue(text.contains("一条都没判"))
        assertTrue(text.contains("数据/表格"))
        assertTrue(text.contains("代码修改"))
    }
}
