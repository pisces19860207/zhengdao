// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十八批（派工单 §1①② ＋ §2 负例）：**读一篇文件的判据直测**（JVM 表驱动，零 Android）。
// 派工单 §2 原话「分词/打分/指纹的 JVM 表驱动测……这是选 B 方案的全部理由，不许只写真机验」
// 里那三项，本文件钉「指纹」与「准入的前半（大小／NUL／编码）」；分词与打分在 `PhoneRankTest`。
//
// ## 三条不是「锦上添花」的用例
// 1. [负例_docx不被NUL判据吃掉]：`.docx` 是 zip，头部**必然**带 `0x00`。曾经这步对所有认领件
//    都跑「见 NUL 即二进制」，结果是**每一个 docx 都进不了索引**——而派工单 §1① 明写 docx 要准入。
//    这条不红，冷扫数字就会好看地少一整类文件，而且没有任何地方报错。
//    所以用例先把「头里真有 NUL」当场证出来（`assertTrue(hasNulByte(…))`），不然它就成了空跑。
// 2. [负例_UTF16文本不被NUL判据吃掉]：UTF-16 的换行是 `0A 00`，「见 NUL 即二进制」会杀掉每一个
//    多行 UTF-16 文本。判序（BOM 先于 NUL）**不在本批重写**，复用 `FileInfoTool.guessEncoding`
//    那一把（仓内 2026-10-17 已被同一条用例逼出过修正）；[结构_判序不重写] 把这条钉成门禁。
// 3. [表驱动_空正文与读不出都不建行]：`doc` 行留着而内容读不出＝「看起来索引了其实搜不到」，
//    是派工单 §2「假账」那一类。宁可行数少，不许行数是假的。
package com.example.zhengdao.baopu.search

import com.example.zhengdao.baopu.agent.FileInfoTool
import com.example.zhengdao.baopu.hasNulByte
import com.example.zhengdao.baopu.library.LibraryPaths
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneTextTest {

    // ── 助手（真源都在被测件里，这里只造字节） ────────────────────────────────

    private fun bytesOf(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)

    /** 造一个**真 zip**（一个 `word/document.xml` 条目）；zip 头部天然带 NUL，见 [负例_docx不被NUL判据吃掉]。 */
    private fun docxBytes(documentXml: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zin ->
            zin.putNextEntry(ZipEntry("word/document.xml"))
            zin.write(documentXml.toByteArray(Charsets.UTF_8))
            zin.closeEntry()
        }
        return out.toByteArray()
    }

    // ── 1. 第一步（不读盘）：大小与扩展名（派工单 §1①「≤2MiB」「TEXT_EXTS＋docx」） ──

    @Test
    fun `表驱动_preCall只由大小与扩展名决定`() {
        val cap = PhoneText.MAX_FILE_BYTES
        val cases = listOf(
            // size, kindTag, 期望
            Triple(0L, PhoneText.KIND_TEXT, ReadCall.PARSE_TEXT),
            Triple(1L, PhoneText.KIND_TEXT, ReadCall.PARSE_TEXT),
            Triple(1024L, PhoneText.KIND_TEXT, ReadCall.PARSE_TEXT),
            Triple(cap, PhoneText.KIND_TEXT, ReadCall.PARSE_TEXT), // 正好 2 MiB ⇒ 不超（"≤" 的边界）
            Triple(cap + 1L, PhoneText.KIND_TEXT, ReadCall.SKIP_TOO_BIG),
            Triple(20L, PhoneText.KIND_DOCX, ReadCall.PARSE_DOCX),
            Triple(cap, PhoneText.KIND_DOCX, ReadCall.PARSE_DOCX),
            Triple(cap + 1L, PhoneText.KIND_DOCX, ReadCall.SKIP_TOO_BIG),
            Triple(20L, PhoneText.KIND_UNCLAIMED, ReadCall.SKIP_UNCLAIMED),
            Triple(cap, PhoneText.KIND_UNCLAIMED, ReadCall.SKIP_UNCLAIMED),
            // FileKind 以后添了新名字 ⇒ 不猜扩展名，一律不认领（失效方向是「少搜」）
            Triple(20L, "PDF", ReadCall.SKIP_UNCLAIMED),
            Triple(20L, "", ReadCall.SKIP_UNCLAIMED),
        )
        cases.forEach { (size, kind, want) ->
            assertEquals("size=$size kind=$kind", want, PhoneText.preCall(size, kind))
        }
    }

    @Test
    fun `表驱动_先判大小再判扩展名`() {
        // 一个 40 MB 的 pdf 既超大小又不认领。记 TOO_BIG 更有用：用户能做的动作是「拆文件」，
        // 不是「换阅读器」。判序在 `preCall` 的 when 分支顺序里，本用例钉住它的结果。
        val big = PhoneText.MAX_FILE_BYTES + 1L
        assertEquals(ReadCall.SKIP_TOO_BIG, PhoneText.preCall(big, PhoneText.KIND_UNCLAIMED))
        assertEquals(ReadCall.SKIP_TOO_BIG, PhoneText.preCall(big, PhoneText.KIND_DOCX))
        assertEquals(ReadCall.SKIP_TOO_BIG, PhoneText.preCall(big, PhoneText.KIND_TEXT))
        // 反过来：没超大小的不认领件仍然是不认领，没被大小判据误伤
        assertEquals(ReadCall.SKIP_UNCLAIMED, PhoneText.preCall(1L, PhoneText.KIND_UNCLAIMED))
    }

    @Test
    fun `表驱动_认领口径与LibraryPaths同源`() {
        // 「TEXT_EXTS＋docx」这句话全仓只许 `LibraryPaths.classify` 一处说；本批是第二个消费者，
        // 只吃它给出的 FileKind **名字**。名字对不上 ⇒ preCall 会把真文本判成不认领（少搜）。
        val known = setOf(PhoneText.KIND_TEXT, PhoneText.KIND_DOCX, PhoneText.KIND_UNCLAIMED)
        LibraryPaths.FileKind.values().forEach {
            assertTrue("LibraryPaths 新添了 FileKind.${it.name}，PhoneText 不认得它 ⇒ 会被判成不认领", it.name in known)
        }
        listOf(
            "a.md" to LibraryPaths.FileKind.TEXT,
            "a.txt" to LibraryPaths.FileKind.TEXT,
            "a.docx" to LibraryPaths.FileKind.DOCX,
            "a.pdf" to LibraryPaths.FileKind.UNCLAIMED,
            "a.zip" to LibraryPaths.FileKind.UNCLAIMED,
            "无扩展名" to LibraryPaths.FileKind.UNCLAIMED,
        ).forEach { (name, want) -> assertEquals(name, want, LibraryPaths.classify(name).first) }
    }

    // ── 2. 第二步：前 8KB 的 NUL 判据（派工单 §1①「前 8KB 有 NUL 跳过」） ────────

    @Test
    fun `负例_docx不被NUL判据吃掉`() {
        val xml = "<w:document><w:body><w:p><w:r><w:t>抱朴测试正文</w:t></w:r></w:p></w:body></w:document>"
        val zip = docxBytes(xml)
        // 先证场景成立：zip 头部真带 NUL。不带的话下面几条断言就是空跑，这条回归什么也没钉住。
        assertTrue("造场景失败：zip 头部必须真带 0x00，否则本用例钉不住 docx 这条回归", hasNulByte(zip))
        // docx 走的是抽取器，不是「按编码解字节」⇒ NUL 判据对它不生效（headCall 第一步就原样放行）
        assertEquals(ReadCall.PARSE_DOCX, PhoneText.headCall(ReadCall.PARSE_DOCX, zip))
        // 真抽出正文 ⇒ 建 OK 行（派工单点名的 docx 准入，端到端只到纯函数这一层）
        val body = PhoneText.bodyOf(ReadCall.PARSE_DOCX, File("x.docx"), zip)
        assertEquals("抱朴测试正文", body.text)
        assertEquals(ReadCall.PARSE_DOCX, body.call)
        assertEquals(DocStatus.OK, PhoneText.docStatusOf(body.call, body.text))
    }

    @Test
    fun `负例_抽不出正文的docx不谎报成加密`() {
        val garbage = bytesOf("这根本不是 zip") + byteArrayOf(0) + bytesOf("的文本")
        assertTrue("造场景：这段字节要带 NUL", hasNulByte(garbage))
        // 判据不许在这里「猜」它是加密文档：抽不出正文就是抽不出，落「不建行」，不落 ENCRYPTED。
        val body = PhoneText.bodyOf(ReadCall.PARSE_DOCX, File("坏.docx"), garbage)
        assertEquals("", body.text)
        assertEquals(ReadCall.PARSE_DOCX, body.call)
        assertNull("抽不出正文 ⇒ 不建 doc 行（留着＝假账）", PhoneText.docStatusOf(body.call, body.text))
    }

    @Test
    fun `负例_UTF16文本不被NUL判据吃掉`() {
        val text = "# 标题\n第二行正文\n"
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(charset("UTF-16LE"))
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(charset("UTF-16BE"))
        listOf(le to "UTF-16LE", be to "UTF-16BE").forEach { (head, label) ->
            assertTrue("造场景：$label 的换行必然是 0A 00 ⇒ 带 NUL", hasNulByte(head))
            assertEquals("$label 文本仍要走文本件", ReadCall.PARSE_TEXT, PhoneText.headCall(ReadCall.PARSE_TEXT, head))
            val body = PhoneText.bodyOf(ReadCall.PARSE_TEXT, File("u16.md"), head)
            assertEquals(ReadCall.PARSE_TEXT, body.call)
            assertEquals("$label 解不出正文", "标题", PhoneText.titleOf(body.text))
            assertEquals(DocStatus.OK, PhoneText.docStatusOf(body.call, body.text))
        }
    }

    @Test
    fun `表驱动_无BOM的NUL件仍然判二进制`() {
        // 例外只有两条（docx／带 BOM 的 UTF-16）。没有 BOM 又带 NUL ⇒ 照旧是二进制，
        // 「不建行」这条不能因为上面那两条例外被顺带放宽。
        val head = bytesOf("plain") + byteArrayOf(0) + bytesOf("tail")
        assertTrue(hasNulByte(head))
        assertEquals(ReadCall.SKIP_BINARY, PhoneText.headCall(ReadCall.PARSE_TEXT, head))
        assertNull(PhoneText.docStatusOf(ReadCall.SKIP_BINARY, ""))
        assertEquals(PhoneText.TAG_BINARY, PhoneText.skipTagOf(ReadCall.SKIP_BINARY))
    }

    @Test
    fun `表驱动_正常文本件过第二步不改判`() {
        val cases = listOf(
            bytesOf("# 普通 UTF-8\n正文") to "UTF-8",
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + bytesOf("# 带 BOM") to "UTF-8 带 BOM",
            "中文老书 GBK".toByteArray(charset("GBK")) to "GBK",
            ByteArray(0) to "读不到内容（空头）",
        )
        cases.forEach { (head, label) ->
            assertEquals("$label 不该被二进制判据挡掉", ReadCall.PARSE_TEXT, PhoneText.headCall(ReadCall.PARSE_TEXT, head))
        }
    }

    @Test
    fun `表驱动_SKIP件不再往里塞NUL判据`() {
        // 第一步就挡下的那些根本没读盘；把 NUL 判据塞进去只会把「超大」误报成「二进制」，
        // 于是 TOO_BIG／UNCLAIMED 这两个状态永远进不了库（报告里的「未认领 N 个」会恒为 0）。
        val nul = byteArrayOf(0, 1, 2, 0)
        listOf(
            ReadCall.SKIP_TOO_BIG, ReadCall.SKIP_UNCLAIMED, ReadCall.SKIP_BINARY, ReadCall.SKIP_UNDECODABLE,
            ReadCall.PARSE_DOCX,
        ).forEach { call ->
            assertEquals("$call 的结论在第一步就定了", call, PhoneText.headCall(call, nul))
        }
    }

    // ── 3. 状态与计数（派工单 §2「截断与不认领必须可见」） ────────────────────────

    @Test
    fun `表驱动_状态字面量与设计62同源`() {
        assertEquals(DocStatus.OK, PhoneText.docStatusOf(ReadCall.PARSE_TEXT, "有正文"))
        assertEquals(DocStatus.OK, PhoneText.docStatusOf(ReadCall.PARSE_DOCX, "有正文"))
        assertEquals(DocStatus.TOO_BIG, PhoneText.docStatusOf(ReadCall.SKIP_TOO_BIG, ""))
        assertEquals(DocStatus.UNCLAIMED, PhoneText.docStatusOf(ReadCall.SKIP_UNCLAIMED, ""))
        assertNull(PhoneText.docStatusOf(ReadCall.SKIP_BINARY, ""))
        assertNull(PhoneText.docStatusOf(ReadCall.SKIP_UNDECODABLE, ""))
        // 每个被产出的状态都要能在 `doc.status` 的 CHECK 里活下来（DDL 与 enum 同源由 SearchSqlTest 钉）
        listOf(DocStatus.OK, DocStatus.TOO_BIG, DocStatus.UNCLAIMED).forEach {
            assertNotNull("enum 与 tag 走丢了：$it", DocStatus.fromTag(it.tag))
        }
    }

    @Test
    fun `表驱动_空正文与读不出都不建行`() {
        listOf("" to "空串", "   \n\t " to "全空白", "　" to "全角空格").forEach { (text, label) ->
            assertNull("$label 不能占一行（占了就永远搜不到＝假账）", PhoneText.docStatusOf(ReadCall.PARSE_TEXT, text))
        }
        // 只有 SKIP_BINARY／SKIP_UNDECODABLE 有 tag；空正文只进 files_skipped，不编一个假原因
        assertEquals(PhoneText.TAG_UNDECODABLE, PhoneText.skipTagOf(ReadCall.SKIP_UNDECODABLE))
        assertNull(PhoneText.skipTagOf(ReadCall.PARSE_TEXT))
        assertNull(PhoneText.skipTagOf(ReadCall.PARSE_DOCX))
        assertNull(PhoneText.skipTagOf(ReadCall.SKIP_TOO_BIG))
        assertNull(PhoneText.skipTagOf(ReadCall.SKIP_UNCLAIMED))
    }

    // ── 4. 正文：解码器唯一真源（不在本批重写判序） ──────────────────────────────

    @Test
    fun `表驱动_解不出编码改判并落到不建行`() {
        // 0x80 既不是合法 UTF-8 首字节，也不在 GBK 的首字节范围（0x81..0xFE）
        // ⇒ guessEncoding 给「未知」⇒ TextDecode 拒绝解码 ⇒ 本批改判成 SKIP_UNDECODABLE。
        val weird = byteArrayOf(0x80.toByte(), 'A'.code.toByte(), 0x80.toByte(), 'B'.code.toByte())
        assertEquals(ReadCall.PARSE_TEXT, PhoneText.headCall(ReadCall.PARSE_TEXT, weird))
        val body = PhoneText.bodyOf(ReadCall.PARSE_TEXT, File("怪.bin"), weird)
        assertEquals(ReadCall.SKIP_UNDECODABLE, body.call)
        assertEquals("", body.text)
        assertNull(PhoneText.docStatusOf(body.call, body.text))
        assertEquals(PhoneText.TAG_UNDECODABLE, PhoneText.skipTagOf(body.call))
    }

    @Test
    fun `表驱动_GBK老书读得出来`() {
        val src = "第 1 章\n抱朴存真，去伪存真。\n"
        val gbk = src.toByteArray(charset("GBK"))
        val head = PhoneText.headCall(ReadCall.PARSE_TEXT, gbk)
        assertEquals(ReadCall.PARSE_TEXT, head)
        val body = PhoneText.bodyOf(head, File("老书.txt"), gbk)
        assertEquals(ReadCall.PARSE_TEXT, body.call)
        assertEquals("第 1 章", PhoneText.titleOf(body.text))
    }

    @Test
    fun `表驱动_空文件不建行`() {
        // 薄壳读不到内容时给空字节表 ⇒ 判成「空正文」，不建行、不假装知道它是什么。
        assertEquals(ReadCall.PARSE_TEXT, PhoneText.headCall(ReadCall.PARSE_TEXT, ByteArray(0)))
        val body = PhoneText.bodyOf(ReadCall.PARSE_TEXT, File("空.txt"), ByteArray(0))
        assertEquals("", body.text)
        assertNull(PhoneText.docStatusOf(body.call, body.text))
    }

    @Test
    fun `结构_判序不重写_复用FileInfoTool那一把`() {
        // 比对**剥掉注释后的代码**：本文件的文件头就是在解释「为什么不另写一份」，
        // 拿原文比对会被自己的注释判红（房型同 `SearchAdmissionTest.code`）。
        val src = code(sourceOf("PhoneText.kt"))
        assertTrue("headCall 必须走 FileInfoTool.guessEncoding（判序只有一处说话）", src.contains("FileInfoTool.guessEncoding("))
        assertTrue("二进制判据用公开纯函数 hasNulByte，不另写一份", src.contains("hasNulByte("))
        assertFalse("不许在本文件重写「见 NUL 即二进制」", src.contains("it == 0.toByte()"))
        assertTrue("例外只对 PARSE_TEXT 生效：docx 不在 NUL 判据的射程里", src.contains("if (call != ReadCall.PARSE_TEXT) return call"))
        assertFalse("扩展名认领只在 LibraryPaths 一处说，本文件的代码不许出现 TEXT_EXTS", src.contains("TEXT_EXTS"))
    }

    // ── 5. 标题（doc.title 给人认文件用） ────────────────────────────────────────

    @Test
    fun `表驱动_标题取第一条标题否则第一条非空行`() {
        val cases = listOf(
            "# 一级" to "一级",
            "### 三级标题" to "三级标题", // 井号成串剥：只剥一个会留下 "## 三级标题"
            "   # 缩进过的标题" to "缩进过的标题",
            "#紧贴没有空格" to "紧贴没有空格",
            "#" to "", // 整行只有井号 ⇒ 既不是标题也不是正文
            "#\n正文行" to "正文行",
            "# \n正文行" to "正文行",
            "无标题的一行\n第二行" to "无标题的一行",
            "   \n  缩进的非空行  " to "缩进的非空行",
            "" to "",
            "   \n\n  " to "",
            "正文\n\n# 后面的标题" to "后面的标题", // markdown 标题优先于它前面的正文行
        )
        cases.forEach { (text, want) -> assertEquals("text=${text.replace("\n", "\\n")}", want, PhoneText.titleOf(text)) }
    }

    @Test
    fun `表驱动_标题窗口只看前40行`() {
        val beyond = (1..PhoneText.TITLE_SCAN_LINES).joinToString("\n") { "数据行 $it" } + "\n# 第 41 行的标题"
        assertEquals("数据行 1", PhoneText.titleOf(beyond))
        val inside = (1 until PhoneText.TITLE_SCAN_LINES).joinToString("\n") { "数据行 $it" } + "\n# 第 40 行的标题"
        assertEquals("第 40 行的标题", PhoneText.titleOf(inside))
    }

    @Test
    fun `表驱动_标题截断必须看得见`() {
        val cap = PhoneText.TITLE_MAX_CHARS
        val exact = "字".repeat(cap)
        assertEquals("正好 $cap 字不加省略号", exact, PhoneText.titleOf("# $exact"))
        val over = PhoneText.titleOf("# ${exact}多出来的字")
        assertEquals("截断后要留一个省略号", cap + 1, over.length)
        assertEquals("前 $cap 字原样保留", exact, over.take(cap))
        assertTrue("截断要留记号（派工单 §2「静默截断」负例红在这里）", over.endsWith("…"))
    }

    // ── 6. 指纹（派工单 §1④ 的「身份检测」，与 size+mtime 的「变更检测」两件事） ──

    @Test
    fun `表驱动_指纹是头8KB的SHA256十六进制小写`() {
        assertEquals(64, PhoneText.sha256Head(bytesOf("abc")).length)
        // SHA-256 定值（公开测试向量），不是「我们自己的哈希」
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            PhoneText.sha256Head(bytesOf("abc")),
        )
        // 空输入给 SHA-256 的定值而不是空串：空串读起来像「没算」
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            PhoneText.sha256Head(ByteArray(0)),
        )
        assertTrue(PhoneText.sha256Head(bytesOf("中文")).all { it in "0123456789abcdef" })
    }

    @Test
    fun `表驱动_指纹只算头8KB`() {
        val head = bytesOf("同一个开头".repeat(1000))
        assertTrue("造场景：头要真的超过 8KB", head.size > PhoneText.HEAD_BYTES)
        val a = head + bytesOf("-尾巴甲")
        val b = head + bytesOf("-尾巴乙")
        assertEquals(
            "头部指纹只看前 8KB：尾部不同也必须同值（这就是省掉整本哈希的设计）",
            PhoneText.sha256Head(PhoneText.headOf(a)), PhoneText.sha256Head(PhoneText.headOf(b)),
        )
        // 反证：整本哈希就会不同 ⇒ 说明上面那个「同值」是设计，不是哈希算错了
        assertFalse(PhoneText.sha256Head(a) == PhoneText.sha256Head(b))
    }

    @Test
    fun `表驱动_headOf取不满就给读到的那些`() {
        val big = ByteArray(100_000) { (it % 251).toByte() }
        listOf(0, 1, PhoneText.HEAD_BYTES - 1, PhoneText.HEAD_BYTES, PhoneText.HEAD_BYTES + 1, 100_000).forEach { n ->
            val slice = big.copyOf(n)
            val head = PhoneText.headOf(slice)
            val want = minOf(n, PhoneText.HEAD_BYTES)
            assertEquals("输入 $n 字节 ⇒ 头部 $want 字节", want, head.size)
            assertEquals("头部字节必须就是原样的前缀", slice.take(want).toList(), head.toList())
        }
    }

    // ── 7. 常量对账（派工单点名的数字不许漂） ────────────────────────────────────

    @Test
    fun `表驱动_上限常量照派工单`() {
        assertEquals(8192, PhoneText.HEAD_BYTES)
        assertEquals(2L * 1024L * 1024L, PhoneText.MAX_FILE_BYTES)
        assertTrue("标题长度得够给人认文件", PhoneText.TITLE_MAX_CHARS in 60..255)
        assertTrue(PhoneText.TITLE_SCAN_LINES >= 20)
        // 与知识库那条同一把尺子：本批不复用它的代码，但同一个数只能有一个答案（报告 §3）
        assertEquals(
            "单文件上限与 KbIndexer 同一个数（两处各写一遍就是将来漂的开始）",
            com.example.zhengdao.baopu.kb.KbIndexer.MAX_FILE_BYTES, PhoneText.MAX_FILE_BYTES,
        )
        assertEquals("二进制（不猜编码）", FileInfoTool.Encodings.BINARY)
    }

    private fun sourceOf(name: String): String = repoFile("src/main/java/com/example/zhengdao/baopu/search/$name").readText()

    /** 剥掉块注释与整行 `//`（行尾注释不剥）——源码文本门禁比的是代码，不是解释（房型同 `SearchAdmissionTest`）。 */
    private fun code(text: String): String =
        text.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    /** 从工作目录逐级向上找仓库里的相对路径（同 `PhoneScanTest`／`SearchAdmissionTest` 的房型）。 */
    private fun repoFile(rel: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            listOf(File(dir, "app/$rel"), File(dir, rel)).firstOrNull { it.exists() }?.let { return it }
            dir = dir.parentFile
        }
        throw AssertionError("找不到 $rel（工作目录 ${File(".").absolutePath}）")
    }
}
