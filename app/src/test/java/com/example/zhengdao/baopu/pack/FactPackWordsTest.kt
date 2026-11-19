// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第七百一十九批 · 649 事实片内容批门禁：
//   ① 真源 24 片、每片 source+asOf 齐；
//   ② 装载后词数 >24 且含默认 24 词（并集语义，负例替换⇒红在 AmygdalaTest 钉）；
//   ③ 默认关零磁盘读（坏 JSON 夹具证明没读）、打开后片可检索喂料；
//   ④ 无数值点值、无病名药名；
//   ⑤ 抽查 5 条 source 指回来源文件与行号。
// 真源从磁盘现读，不拿内存字符串自证；只读 docs/facts，零临时沙盒泄漏。
package com.example.zhengdao.baopu.pack

import com.example.zhengdao.baopu.bio.DangerWords
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FactPackWordsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var packRoot: File
    private val truth by lazy { File(truthRel()).readText(Charsets.UTF_8) }
    private val slices by lazy { FactPackWords.parse(truth) }

    private fun truthRel(): String = listOf(
        "docs/facts/fact-slices.json",
        "../docs/facts/fact-slices.json",
    ).first { File(it).isFile }

    @Before
    fun setUp() {
        packRoot = tmp.newFolder("packs", "男性")
        PackTools.rootProvider = { packRoot }
        PackTools.gateOn = { false }
    }

    @After
    fun tearDown() {
        PackTools.rootProvider = null
        PackTools.gateOn = { false }
    }

    // ── 判据①：真源 24 片，字段齐 ──────────────────────────────────────────

    @Test
    fun `fact片至少24条且每条带source与asOf`() {
        assertTrue("fact 片不足 24：${slices.size}", slices.size >= 24)
        assertEquals("id 应唯一", slices.size, slices.map { it.id }.toSet().size)
        assertTrue(slices.all { it.id.startsWith("fact-") })
        assertTrue("有片缺 source/asOf", slices.all {
            it.source.contains(".md") && Regex("L\\d+").containsMatchIn(it.source) && it.asOf == "2026-11-16"
        })
        assertTrue("有片 words 空", slices.all { it.words.isNotEmpty() })
    }

    // ── 判据④：无点值、无病名药名；判据①的校验纯函数 ────────────────────────

    @Test
    fun `validate全过_无点值无病名药名`() {
        val issues = FactPackWords.validate(slices)
        assertTrue("校验有问题：$issues", issues.isEmpty())
    }

    @Test
    fun `validate抓得住点值与病名药名_负例`() {
        // 变异测试：把一片 body 改成含点值/病名，校验必须报出来（自检判据带负例）。
        val bad = slices.toMutableList()
        val s = bad[0]
        bad[0] = s.copy(body = "长度约 13.12 cm，佩罗尼氏病多见")
        val issues = FactPackWords.validate(bad)
        assertTrue("点值没被抓", issues.any { it.contains("点值") })
        assertTrue("病名没被抓", issues.any { it.contains("病名") })
    }

    // ── 判据⑤：抽查 5 条指回来源文件与行号 ─────────────────────────────────

    @Test
    fun `抽查五条source能指回来源文件与行号`() {
        val ids = listOf("fact-001", "fact-007", "fact-013", "fact-019", "fact-024")
        val refs = FactPackWords.sourceRefs(slices, ids)
        assertEquals(5, refs.size)
        for ((id, ref) in refs) {
            assertTrue("$id source 缺来源文件名", ref.contains("事实底盘-v0"))
            assertTrue("$id source 缺行号", Regex("L\\d+(?:-L\\d+)?").containsMatchIn(ref))
        }
    }

    // ── 判据③：默认关零磁盘读 ──────────────────────────────────────────────

    @Test
    fun `默认关时零磁盘读_坏JSON夹具证明没读`() {
        // 包根放一份坏 JSON：若 load 真去读，结果会与"没读"不同。
        File(packRoot, FactPackWords.FACTS_NAME).writeText("{ this is not json", Charsets.UTF_8)
        PackTools.gateOn = { false }
        val out = FactPackWords.load()
        assertSame("关态必须原样回默认表", DangerWords.DEFAULT, out.words)
        assertEquals("关态不该读一个字节", 0, out.readBytes)
        assertEquals("关态片数应为 0", 0, out.sliceCount)
    }

    // ── 判据②+③：打开后片可检索喂料，词数>24 含默认 ────────────────────────

    @Test
    fun `打开后从包根读片喂料_词数大于24且含默认24词`() {
        File(packRoot, FactPackWords.FACTS_NAME).writeText(truth, Charsets.UTF_8)
        PackTools.gateOn = { true }
        val out = FactPackWords.load()
        assertEquals("应读到 24 片", 24, out.sliceCount)
        assertTrue("应读到正数字节", out.readBytes > 0)
        assertTrue("装载后词数必须 >24，实际 ${out.words.danger.size}",
            out.words.danger.size > DangerWords.DEFAULT.danger.size)
        assertTrue("装载后必须含默认 24 词",
            DangerWords.DEFAULT.danger.all { it in out.words.danger })
        assertTrue("fact 新词应进表（如『分布』）", "分布" in out.words.danger)
    }

    @Test
    fun `打开但文件缺失时回默认表_fail_closed`() {
        PackTools.gateOn = { true }
        // 故意不写 fact-slices.json
        val out = FactPackWords.load()
        assertSame(DangerWords.DEFAULT, out.words)
        assertEquals(0, out.sliceCount)
    }
}
