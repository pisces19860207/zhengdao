// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.baopu.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * SearchInFilesTool 单测（刀 2 追加包；判据追加④）。
 *
 * 钉死：命中格式「相对路径:行号:内容 ±1 行」、单次 40 条上限并明示、
 * >8 MB 与二进制跳过并计数（「已扫描 N / 跳过 M」）、点开头与软链不搜、
 * 深度默认 3 层、只读（FREE 权限）、越界即拒。
 */
class SearchInFilesToolTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File

    private fun setup() {
        root = tmp.newFolder("workspace")
    }

    private fun search(dir: File, query: String, caseSensitive: Boolean = false) =
        SearchInFilesTool.search(root, dir, query, caseSensitive)

    private fun body(r: ToolOutcome): String = (r as ToolOutcome.Ok).text

    private fun file(rel: String, text: String): File {
        val f = File(root, rel)
        f.parentFile?.mkdirs()
        f.writeText(text)
        return f
    }

    @Test
    fun `命中格式_相对路径冒号行号_带上下一行`() {
        setup()
        file("a.txt", "第一行没有关键词\n第二行有目标词\n第三行也没有")

        val out = body(search(root, "目标词"))

        assertTrue(out.contains("a.txt:2:第二行有目标词"))
        assertTrue("必须带上一行", out.contains("a.txt:1:第一行没有关键词"))
        assertTrue("必须带下一行", out.contains("a.txt:3:第三行也没有"))
        assertTrue(out.contains("命中 1 条"))
        assertTrue(out.contains("已扫描 1 个文件"))
    }

    @Test
    fun `行号从1起且上下文不越界`() {
        setup()
        file("a.txt", "命中词在首行\n普通行\n末行也有命中词")

        val out = body(search(root, "命中词"))

        assertTrue(out.contains("a.txt:1:命中词在首行"))
        assertFalse("首行没有上一行", out.contains("a.txt:0:"))
        assertTrue(out.contains("a.txt:3:末行也有命中词"))
        assertFalse("末行没有下一行", out.contains("a.txt:4:"))
        assertTrue(out.contains("命中 2 条"))
    }

    @Test
    fun `大小写默认不敏感_可切敏感`() {
        setup()
        file("a.txt", "Hello World")

        assertTrue(body(search(root, "hello")).contains("命中 1 条"))
        assertTrue(body(search(root, "hello", caseSensitive = true)).contains("没有命中"))
    }

    @Test
    fun `单次40条上限_触顶明示`() {
        setup()
        file("a.txt", (1..60).joinToString("\n") { "第 $it 行带词" })

        val out = body(search(root, "带词"))

        assertTrue(out.contains("命中 40 条"))
        assertTrue("触顶必须明示", out.contains("已触达命中上限 40"))
    }

    @Test
    fun `二进制文件跳过并计数`() {
        setup()
        file("a.txt", "有词\n")
        val bin = File(root, "b.bin")
        bin.writeBytes(byteArrayOf(0, 1, 2, 0, 3, 4))

        val out = body(search(root, "词"))

        assertTrue(out.contains("命中 1 条"))
        assertTrue("二进制必须计数", out.contains("跳过 1 个"))
        assertTrue(out.contains("二进制 1"))
    }

    @Test
    fun `超过8MB跳过并计数`() {
        setup()
        file("small.txt", "有词\n")
        val big = File(root, "big.txt")
        // 稀疏造一个 >8MB 的文件：写头部一段 + 用 RandomAccessFile 拉长度（不真写 8MB 内容）
        big.outputStream().use { it.write("词\n".toByteArray()) }
        java.io.RandomAccessFile(big, "rw").use { it.setLength(SearchInFilesTool.MAX_FILE_BYTES + 1024) }

        val out = body(search(root, "词"))

        assertTrue(out.contains("过大 1"))
        assertTrue(out.contains("跳过 1 个"))
        // 跳过大文件 ⇒ 扫描计数的只有 small.txt
        assertTrue(out.contains("已扫描 1 个文件"))
    }

    @Test
    fun `点开头文件与内部目录不搜`() {
        setup()
        file("a.txt", "有词\n")
        file(".hidden.txt", "有词\n")
        File(root, ".baopu-bak").mkdirs()
        file(".baopu-bak/x.txt", "有词\n")

        val out = body(search(root, "词"))

        assertTrue(out.contains("命中 1 条"))
        assertFalse(out.contains(".hidden.txt"))
        assertFalse(out.contains("baopu-bak"))
    }

    @Test
    fun `深度默认3层_更浅的够更深的不搜`() {
        setup()
        file("l1/l2/l3.txt", "深层有词\n") // 第 3 层
        file("l1/l2/l3/l4.txt", "第4层有词\n") // 第 4 层（超出默认深度）

        val out = body(search(root, "有词"))

        assertTrue("第 3 层要搜到", out.contains("l1/l2/l3.txt"))
        assertFalse("第 4 层不搜", out.contains("l4.txt"))
        assertTrue(out.contains("深度上限 3 层"))
    }

    @Test
    fun `子目录里搜_相对路径以工作区根渲染`() {
        setup()
        file("资料库/原始/小说.txt", "林深见鹿\n")
        val out = body(search(File(root, "资料库"), "林深"))
        assertTrue(out.contains("资料库/原始/小说.txt:1:林深见鹿"))
    }

    @Test
    fun `目录不存在或不是目录分别报`() {
        setup()
        assertTrue(search(File(root, "none"), "词") is ToolOutcome.Failed)
        val f = file("a.txt", "x")
        val r = search(f, "词")
        assertTrue(r is ToolOutcome.Failed)
        assertTrue((r as ToolOutcome.Failed).error.contains("只搜目录"))
    }

    @Test
    fun `越界目录即拒`() {
        setup()
        val outside = tmp.newFolder("outside")
        File(outside, "a.txt").writeText("有词")
        val r = search(outside, "词")
        assertTrue(r is ToolOutcome.Rejected)
    }

    @Test
    fun `空关键词拒绝`() {
        setup()
        assertTrue(search(root, "  ") is ToolOutcome.Failed)
    }

    @Test
    fun `没命中也明说`() {
        setup()
        file("a.txt", "内容\n")
        val out = body(search(root, "不存在的词"))
        assertTrue(out.contains("没有命中"))
        assertTrue(out.contains("已扫描 1 个文件"))
    }

    @Test
    fun `权限FREE且Schema三参`() {
        val spec = SearchInFilesTool.spec { root }
        assertEquals("search_in_files", spec.name)
        assertEquals(ToolPermission.FREE, spec.permission)
        val props = spec.inputSchema.getJSONObject("properties")
        assertEquals(3, props.length())
        assertFalse(spec.inputSchema.optBoolean("additionalProperties"))
    }

    @Test
    fun `二进制判定_NUL即二进制`() {
        assertTrue(SearchInFilesTool.looksBinary(byteArrayOf(65, 0, 66)))
        assertFalse(SearchInFilesTool.looksBinary("纯文本".toByteArray()))
        assertFalse(SearchInFilesTool.looksBinary(ByteArray(0)))
    }

    @Test
    fun `大目录搜索_不整文件读入且秒回`() {
        setup()
        // 300 个小文件（每个 5 行）⇒ 读的是逐行流，不驻留整库
        repeat(300) { i -> file("d$i/f.txt", (1..5).joinToString("\n") { "第 $it 行 ${if (it == 3) "关键词" else "普通"}" }) }

        val t0 = System.nanoTime()
        val out = body(search(root, "关键词"))
        val ms = (System.nanoTime() - t0) / 1_000_000

        assertTrue("10 秒内必须返回（实际 ${ms}ms）", ms < 10_000)
        // 触顶 40 条并明示（300 个文件全扫会超过 40 条命中）
        assertTrue(out.contains("命中 40 条"))
    }
}
