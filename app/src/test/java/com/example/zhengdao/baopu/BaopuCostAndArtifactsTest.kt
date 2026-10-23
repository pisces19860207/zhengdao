// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三十八批（成本进跑马灯 ＋ 产物在 App 内点开看）· A1–A5／B1–B5／B7–B8 的行为层判据。
// 派工单口径：这一批的账全部要能在 JVM 里直测 ⇒ 判据都打在 `BaopuCostAndArtifacts` 的
// 纯函数上（壳层接线由 BaopuCostWiringTest 从源码层钉）。
// 用真实临时目录和真实文件，不用 mock：产物这条链路唯一值得测的就是「盘上那份读出来对不对」。
package com.example.zhengdao.baopu

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Locale

class BaopuCostAndArtifactsTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = File.createTempFile("zd-b38-root", "").let { f ->
            f.delete()
            f.mkdirs()
            f
        }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun write(rel: String, content: String): File =
        File(root, rel).apply {
            parentFile?.mkdirs()
            writeBytes(content.toByteArray(Charsets.UTF_8))
        }

    private fun writeBytes(rel: String, bytes: ByteArray): File =
        File(root, rel).apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }

    private fun copy(zhIn: String = "入", zhOut: String = "出", zhCache: String = "缓存命中") =
        UsageCopy(
            stepLabel = { step -> "第 $step 步" },
            inLabel = zhIn,
            outLabel = zhOut,
            cacheHitLabel = zhCache,
        )

    // ── A 段 · 本轮累计与每步明细 ───────────────────────────────────────────

    /** A1：三步 100/200/300 ⇒ 本轮累计 600（**不是**「最后一步 300」——§4 负例 1 的靶心）。 */
    @Test
    fun A1_三步读数累计成本轮总和而不是最后一步() {
        var usage = emptyRoundUsage()
        listOf(100L to 1L, 200L to 2L, 300L to 3L).forEachIndexed { i, (inn, out) ->
            usage = accumulateUsage(
                prev = usage,
                row = BaopuUsageRow(step = i + 1, tokensIn = inn, tokensOut = out, cacheHit = null, elapsedMs = 1_000L),
            )
        }
        assertEquals("入必须三步相加", 600L, usage.totalIn)
        assertEquals("出必须三步相加", 6L, usage.totalOut)
        assertEquals("没有命中的列保持 null（不编 0）", null, usage.totalCacheHit)
        assertEquals(3, usage.rows.size)
    }

    /** A1 补：命中缓存数同样累加，且 null 步不吃掉已有账。 */
    @Test
    fun A1_某步没读到命中数时累计仍按有读数的步相加() {
        var usage = emptyRoundUsage()
        usage = accumulateUsage(usage, BaopuUsageRow(1, 10L, 5L, 4L, 100L))
        usage = accumulateUsage(usage, BaopuUsageRow(2, 10L, 5L, null, 100L))
        usage = accumulateUsage(usage, BaopuUsageRow(3, 10L, 5L, 6L, 100L))
        assertEquals(30L, usage.totalIn)
        assertEquals(10L, usage.totalCacheHit)
    }

    /** A2：一步一行、行数 = 步数、步号从 1 连续（步号用引擎给的那一个，不是到达序号）。 */
    @Test
    fun A2_每步一行_行数等于步数_步号从1连续() {
        var usage = emptyRoundUsage()
        (1..4).forEach { step ->
            usage = accumulateUsage(
                usage,
                BaopuUsageRow(step = step, tokensIn = step * 100L, tokensOut = step * 10L, cacheHit = null, elapsedMs = step * 500L),
            )
        }
        val lines = usageDetailLines(usage, copy())
        assertEquals("四步 ⇒ 四行明细", 4, lines.size)
        assertEquals(listOf("第 1 步", "第 2 步", "第 3 步", "第 4 步"), lines.map { it.substringBefore(" · ") })
    }

    /** A2 的形制：`第 3 步 · 入 12.3k · 出 2.1k · 4.2s`（派工单 §1.2 逐字举例）。 */
    @Test
    fun A2_明细行形如第N步入X出Y耗时Z() {
        val usage = accumulateUsage(
            emptyRoundUsage(),
            BaopuUsageRow(step = 3, tokensIn = 12_345L, tokensOut = 2_100L, cacheHit = 8_000L, elapsedMs = 4_200L),
        )
        assertEquals(listOf("第 3 步 · 入 12.3k · 出 2.1k · 4.2s"), usageDetailLines(usage, copy()))
        // 累计行不显示命中为 null 的列，但这里三列都有 ⇒ 三列都写
        assertEquals("入 12.3k · 出 2.1k · 缓存命中 8.0k", usageTotalLine(usage, copy()))
    }

    /** A3：整轮没有任何读数 ⇒ 跑马灯**不出**这一条（不是显示「入 0」）。 */
    @Test
    fun A3_没有任何读数时不出这一条() {
        assertFalse(shouldShowUsageNotice(emptyRoundUsage()))
        assertEquals("", usageNoticeBody(emptyRoundUsage(), copy()))
        // 有步但三列全 null（服务端只回了 reasoning 这类我们不显示的列）⇒ 同样不出
        val allNull = accumulateUsage(emptyRoundUsage(), BaopuUsageRow(1, null, null, null, 1_200L))
        assertFalse("三列全空就不许出条目（别显示 0）", shouldShowUsageNotice(allNull))
    }

    /** A3 的另一面：只要有一步带着数 ⇒ 出。 */
    @Test
    fun A3_有一步带读数就出() {
        val usage = accumulateUsage(emptyRoundUsage(), BaopuUsageRow(1, 42L, null, null, 900L))
        assertTrue(shouldShowUsageNotice(usage))
        assertEquals("入 42", usageTotalLine(usage, copy()))
        assertEquals("第 1 步 · 入 42 · 0.9s", usageDetailLines(usage, copy()).single())
    }

    /** A4：新开一轮回到空账，第二轮不带着上一轮的累计（清零点由 BaopuCostWiringTest 钉）。 */
    @Test
    fun A4_新开一轮清零_第二轮不继承上一轮的账() {
        val first = accumulateUsage(emptyRoundUsage(), BaopuUsageRow(1, 100L, 20L, 5L, 1_000L))
        assertEquals(100L, first.totalIn)
        val fresh = emptyRoundUsage()
        assertTrue("清零后没有明细", fresh.rows.isEmpty())
        assertNull(fresh.totalIn)
        assertFalse("清零后这轮不该再出条目", shouldShowUsageNotice(fresh))
        val second = accumulateUsage(fresh, BaopuUsageRow(1, 7L, 3L, null, 500L))
        assertEquals("新一轮从 7 开始，不是 107", 7L, second.totalIn)
    }

    /** A5：`999`→`999`、`1000`→`1.0k`、`12345`→`12.3k`；换区域设置读数不变（不许 12,3k）。 */
    @Test
    fun A5_千位口径_且不依赖区域设置() {
        val saved = Locale.getDefault()
        try {
            listOf(Locale.ENGLISH, Locale.GERMANY, Locale.FRANCE, Locale.SIMPLIFIED_CHINESE).forEach { loc ->
                Locale.setDefault(loc)
                assertEquals("999 原样（$loc）", "999", formatTokenCount(999L))
                assertEquals("1000 换 k（$loc）", "1.0k", formatTokenCount(1_000L))
                assertEquals("12345 一位小数（$loc）", "12.3k", formatTokenCount(12_345L))
                assertEquals("0 也是原样（$loc）", "0", formatTokenCount(0L))
                assertEquals("1049 舍到 1.0k（$loc）", "1.0k", formatTokenCount(1_049L))
                assertEquals("1050 入到 1.1k（$loc）", "1.1k", formatTokenCount(1_050L))
                assertEquals("1990 ⇒ 2.0k（$loc）", "2.0k", formatTokenCount(1_990L))
                assertFalse("任何区域下都不许出现逗号小数点（$loc）", formatTokenCount(12_345L).contains(','))
            }
        } finally {
            Locale.setDefault(saved)
        }
    }

    /** 耗时：负数 = 不知道 ⇒ 不显示；不足 0.1s 说 `<0.1s`；分钟收成 `1m30s`。 */
    @Test
    fun 耗时未知就不显示_不假装知道() {
        assertNull("续跑的第一步没有开步时刻 ⇒ 不编数", formatStepElapsed(-1L))
        assertEquals("<0.1s", formatStepElapsed(0L))
        assertEquals("4.2s", formatStepElapsed(4_200L))
        assertEquals("1m30s", formatStepElapsed(90_000L))
        assertEquals("2m", formatStepElapsed(120_000L))
        // 明细行里没耗时读数时整段不写（不是「0.0s」）
        val usage = accumulateUsage(emptyRoundUsage(), BaopuUsageRow(1, 10L, 2L, null, -1L))
        assertEquals(listOf("第 1 步 · 入 10 · 出 2"), usageDetailLines(usage, copy()))
    }

    // ── B 段 · 哪些够格当产物 ───────────────────────────────────────────────

    /** B1：八种文本扩展名都收；jpg / zip / bin 一律不出现（派工单 §2.1）。 */
    @Test
    fun B1_白名单八种都收_jpg_zip_bin不收() {
        val texts = listOf("a.json", "b.txt", "c.md", "d.csv", "e.yml", "f.yaml", "g.log", "h.html")
        val binaries = listOf("p.jpg", "q.jpeg", "r.png", "s.zip", "t.bin", "u.pdf", "v.docx", "w")
        texts.forEach { write(it, "{}") }
        binaries.forEach { write(it, "junk") }
        val picked = selectTextArtifacts(root, texts + binaries)
        assertEquals(texts, picked)
        assertFalse("jpg 不许出现在产物行", picked.any { it.endsWith(".jpg") })
        assertFalse("zip 不许出现", picked.any { it.endsWith(".zip") })
        assertFalse("bin 不许出现", picked.any { it.endsWith(".bin") })
        assertEquals(
            "白名单就是这八个，多一个是越界",
            setOf("json", "txt", "md", "csv", "yml", "yaml", "log", "html"),
            TEXT_ARTIFACT_EXTENSIONS,
        )
    }

    /** B1 补：扩展名大小写不敏感（`.JSON` / `.Html` 也认）。 */
    @Test
    fun B1_扩展名大小写不敏感() {
        write("upper.JSON", "{}")
        write("nested/页面.HTML", "<p>hi</p>")
        write("noext", "x")
        assertEquals(listOf("upper.JSON", "nested/页面.HTML"), selectTextArtifacts(root, listOf("upper.JSON", "nested/页面.HTML", "noext")))
        assertFalse("没扩展名不算文本产物", isTextArtifactPath("Dockerfile"))
    }

    /** B2 之一：>512 KB 不收（用真文件卡在门槛两侧：511 KB 收、512 KB+1KB 不收）。 */
    @Test
    fun B2_超过512KB的不收() {
        val ok = write("small.txt", "x".repeat(100))
        val justUnder = File(root, "justUnder.txt")
        justUnder.writeBytes(ByteArray((512 * 1024) - 1024) { '.'.code.toByte() })
        val big = File(root, "big.txt")
        big.writeBytes(ByteArray((512 * 1024) + 1024) { '.'.code.toByte() })
        assertTrue("512 KB 以内且是文本 ⇒ 收", isTextArtifactFile(ok))
        assertTrue("511 KB 的纯文本照样收（差别只在大小）", isTextArtifactFile(justUnder))
        assertFalse("512 KB 以上 ⇒ 不收（重文件不进产物行）", isTextArtifactFile(big))
        assertEquals(listOf("small.txt", "justUnder.txt"), selectTextArtifacts(root, listOf("small.txt", "big.txt", "justUnder.txt")))
    }

    /** B2 之二：前 8 KB 里有 `\u0000` ⇒ 当二进制，不收。 */
    @Test
    fun B2_前8KB含NUL的不收() {
        write("clean.txt", "正常文本，没有零字节")
        val withNul = File(root, "withNul.txt")
        withNul.writeBytes("前半段正常".toByteArray(Charsets.UTF_8) + byteArrayOf(0, 1, 2) + "后半段".toByteArray(Charsets.UTF_8))
        assertTrue(isTextArtifactFile(File(root, "clean.txt")))
        assertFalse("前 8 KB 含 NUL ⇒ 不收", isTextArtifactFile(withNul))
        assertTrue(hasNulByte(byteArrayOf(1, 0, 3)))
        assertFalse(hasNulByte("纯文本".toByteArray(Charsets.UTF_8)))
        assertEquals(listOf("clean.txt"), selectTextArtifacts(root, listOf("clean.txt", "withNul.txt")))
    }

    /** B2 补：空文件、不在盘上的路径都不收（点开是空白一片比不列更让人困惑）。 */
    @Test
    fun B2_空文件与不存在的文件不收() {
        write("empty.txt", "")
        val picked = selectTextArtifacts(root, listOf("empty.txt", "never_written.json"))
        assertTrue(picked.isEmpty())
    }

    // ── B 段 · 只读查看 ─────────────────────────────────────────────────────

    /** B3：显示的那份与盘上**逐字**一致（中文、换行、制表符、CRLF 一个都不动）。 */
    @Test
    fun B3_查看文本与盘上逐字一致() {
        val raw = "<!doctype html>\n<html>\n\t<head><meta charset=\"utf-8\"></head>\r\n" +
            "<body>  中文与 emoji 🎉 · 制表符→\t结束  </body>\n</html>\n"
        write("作品.html", raw)
        val view = artifactViewOf(root, "作品.html")
        assertTrue("必须读到", view != null)
        assertEquals("显示串与盘上原文逐字一致", raw, view!!.text)
        assertEquals(raw.toByteArray(Charsets.UTF_8).size.toLong(), view.fileBytes)
        assertFalse("小文件不该被截断", view.truncated)
        assertEquals(countTextLines(raw), view.totalLines)
        assertEquals(countTextLines(raw), view.shownLines)
    }

    /** B4 之一：>3000 行 ⇒ 截断，并报出「显示了 N 行 / 共 M 行」两个数。 */
    @Test
    fun B4_超三千行截断并报出行数() {
        val sb = StringBuilder()
        repeat(4_000) { i -> sb.append("第 ").append(i + 1).append(" 行 · 一些填充内容 abcdefghijklmn\n") }
        write("长日志.log", sb.toString())
        val view = artifactViewOf(root, "长日志.log")
        assertTrue(view != null)
        assertTrue("超 3000 行必须标 truncated", view!!.truncated)
        assertEquals("只显示前 3000 行", ARTIFACT_VIEW_MAX_LINES, view.shownLines)
        assertEquals("总数要能报出来（不编数：这份没到扫描上限，数得完）", 4_000, view.totalLines)
        assertTrue("显示那份确实短于原文", view.text.length < sb.length)
        // 显示的必须是原文的前缀——截断只能砍尾巴，不许中间动刀
        assertTrue("显示的是原文前缀", sb.toString().startsWith(view.text))
    }

    /** B4 之二：>200 KB ⇒ 按字节截断；顶部字节数是**盘上**长度。 */
    @Test
    fun B4_超200KB按字节截断_顶部字节数仍是盘上长度() {
        val line = "这是一行中文内容带一些 ASCII 用于撑字节 abcdefghijklmnopqrst\n"
        val sb = StringBuilder()
        repeat(4_000) { sb.append(line) } // ≈ 284 KB > 200 KB（先卡字节这一道）
        write("很大.txt", sb.toString())
        val view = artifactViewOf(root, "很大.txt")
        assertTrue("超 200 KB 必须截断", view!!.truncated)
        assertTrue("内存里那份不超过 200 KB", view.text.toByteArray(Charsets.UTF_8).size <= ARTIFACT_VIEW_MAX_BYTES)
        assertEquals("顶部报的是盘上总长度，不是显示那份", sb.toString().toByteArray(Charsets.UTF_8).size.toLong(), view.fileBytes)
        assertEquals("总行数照样数得完", 4_000, view.totalLines)
        assertTrue("显示行数在 1 与总数之间", view.shownLines in 1 until view.totalLines)
        assertEquals("显示行数与显示那份文本自洽", countTextLines(view.text), view.shownLines)
        // 按字节截断 ⇒ 切点可能落在一个汉字的中间：显示串必须等于「盘上前 200 KB 解码出来的那一份」，
        // 不多不少（尾巴上那个残缺字符是替换符，复制路径不受影响——那是 artifactCopyOf 的事）。
        val first = File(root, "很大.txt").readBytes().copyOfRange(0, ARTIFACT_VIEW_MAX_BYTES)
        assertEquals(String(first, Charsets.UTF_8), view.text)
    }

    /** B4 补：文件不在 ⇒ null（查看器报「文件不在了」，不许空白屏）。 */
    @Test
    fun B4_文件不在时查看返回null() {
        assertNull(artifactViewOf(root, "ghost.json"))
        assertNull(artifactCopyOf(root, "ghost.json"))
    }

    // ── B 段 · 摆几片、去重 ─────────────────────────────────────────────────

    /** B5 之一：同一路径写两次只出现一次，顺序按第一次。 */
    @Test
    fun B5_同一路径只出现一次且保持写入顺序() {
        write("a.txt", "1")
        write("b.json", "2")
        write("c.md", "3")
        val picked = selectTextArtifacts(root, listOf("c.md", "a.txt", "b.json", "a.txt", "c.md"))
        assertEquals(listOf("c.md", "a.txt", "b.json"), picked)
    }

    /** B5 之二：最多 8 片，超出的只报「还有 N 个」。 */
    @Test
    fun B5_最多八片_超出只报还有N个() {
        (1..11).forEach { i -> write("文件$i.txt", "x") }
        val all = (1..11).map { "文件$it.txt" }
        val plan = artifactChipPlan(selectTextArtifacts(root, all))
        assertEquals(ARTIFACT_CHIP_MAX, plan.shown.size)
        assertEquals("11 个里摆了 8 个 ⇒ 还有 3 个", 3, plan.more)
        assertEquals((1..8).map { "文件$it.txt" }, plan.shown)
        val few = artifactChipPlan(listOf("a.txt", "b.txt"))
        assertEquals(2, few.shown.size)
        assertEquals("没超就不报还有几个", 0, few.more)
    }

    /** 片上只显最后一段文件名（`资料库/原始/作品.html` ⇒ `作品.html`）。 */
    @Test
    fun 片名只取路径最后一段() {
        assertEquals("作品.html", artifactFileName("资料库/原始/作品.html"))
        assertEquals("a.txt", artifactFileName("C:\\x\\y\\a.txt"))
        assertEquals("json", artifactExtension("资料库/原始/作品.JSON"))
    }

    // ── §7 硬要求 · 复制必须是全文 ──────────────────────────────────────────

    /**
     * B7：4000 行／约 300 KB 的文本产物 ⇒ 查看截断（「只显示前 N 行」），
     * 但复制结果与盘上文件**逐字节相同**（sha256 相等）。
     *
     * 这条同时自证「复制显示内容」那种写法必红：断言里明确比了
     * `copy.text != view.text`（显示那份确实是截断过的）。
     */
    @Test
    fun B7_查看截断但复制是全文_逐字节sha相同() {
        val sb = StringBuilder()
        repeat(4_000) { i ->
            sb.append("<tr><td>第 ").append(i + 1).append(" 行</td><td>一些用于撑字节的中文与 ASCII abcdefghij</td></tr>\r\n")
        }
        write("作品.html", sb.toString())
        val diskBytes = File(root, "作品.html").readBytes()
        assertTrue("这份得真超 200 KB（否则「复制显示内容」也能过，测不出差别）", diskBytes.size > ARTIFACT_VIEW_MAX_BYTES)
        assertEquals("4000 行的行数口径自洽", 4_000, countTextLines(sb.toString()))

        val view = artifactViewOf(root, "作品.html")
        assertTrue("查看必须截断（界面上要出现「只显示前 N 行」）", view!!.truncated)
        assertTrue("显示那份短于盘上那份", view.text.length < sb.length)
        assertEquals("共 4000 行要报得出来（不编数）", 4_000, view.totalLines)
        assertTrue("显示行数小于总数", view.shownLines < view.totalLines)

        val copied = artifactCopyOf(root, "作品.html")
        assertTrue("复制必须自证通过", copied!!.complete)
        assertNotEquals("复制的那份不许等于显示的那份（截断串不是全文）", view.text, copied.text)
        assertEquals("字符数与盘上那份一致", sb.toString().length, copied.chars)
        assertEquals("行数按同一口径", countTextLines(sb.toString()), copied.lines)
        assertEquals("盘上字节数", diskBytes.size.toLong(), copied.fileBytes)
        assertEquals(
            "复制结果与盘上文件逐字节相同（sha256）",
            sha256(diskBytes),
            sha256(copied.text.toByteArray(Charsets.UTF_8)),
        )
    }

    /** B8：含 `&`、`<`、`"`、中文、制表符、CRLF 的源码 ⇒ 原样复制，不转义、不换行、不加 BOM。 */
    @Test
    fun B8_复制不转义不改换行不加BOM不trim不补换行() {
        val raw = "const s = \"a & b < c > d\";\n" +
            "\t// 中文注释 · 引号\"与&号<>\n" +
            "let 配置 = { \"键\": \"值&不是转义\" };\r\n" +
            "  末尾留空格不 trim  "
        // 派工单白名单里没有 .js —— 这条测的是「读回来的字节口径」，落盘用 .txt（产物行那一层由 B1 测）
        write("配置器.txt", raw)
        val copied = artifactCopyOf(root, "配置器.txt")
        assertTrue(copied!!.complete)
        assertEquals("逐字符原样（不做任何加工）", raw, copied.text)
        assertTrue("原文里的 & < \" 都还在", copied.text.contains("&") && copied.text.contains("<") && copied.text.contains("\""))
        assertFalse("& 不许被转成 &amp;", copied.text.contains("&amp;"))
        assertFalse("< 不许被转成 &lt;", copied.text.contains("&lt;"))
        assertFalse("不许把 \" 变成 &quot;", copied.text.contains("&quot;"))
        assertFalse("不许加 BOM", copied.text.startsWith("\uFEFF"))
        assertEquals("盘上一处 CRLF ⇒ 复制后还是一处（不许换成 LF）", 1, Regex("\r\n").findAll(copied.text).count())
        assertEquals(
            "裸 LF 的处数也不许被改",
            Regex("(?<!\r)\n").findAll(raw).count(),
            Regex("(?<!\r)\n").findAll(copied.text).count(),
        )
        assertTrue("末尾空格不许被 trim", copied.text.endsWith("trim  "))
        assertFalse("不许补末尾换行", copied.text.endsWith("\n"))
        assertEquals("中文一字三字节：字符数按 char 算", raw.length, copied.chars)
        assertTrue("字节数 > 字符数（自证用的是字节不是字符）", copied.fileBytes > copied.chars)
    }

    /** B8 补：文件带 BOM 就照原样带过去（盘上有的字节不算「我们加的」）。 */
    @Test
    fun B8_盘上本来带BOM的原样保留() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "第一行\n".toByteArray(Charsets.UTF_8)
        writeBytes("带BOM.txt", bytes)
        val copied = artifactCopyOf(root, "带BOM.txt")
        assertTrue(copied!!.complete)
        assertEquals(bytes.toList(), copied.text.toByteArray(Charsets.UTF_8).toList())
    }

    /** §7 纪律 2：非 UTF-8（往返对不上）⇒ complete=false，界面必须报「复制不完整」，不许静默。 */
    @Test
    fun 复制自证不过时报不完整而不是静默给半截() {
        val gbk = "GBK 编码的中文：中文中文中文".toByteArray(charset("GBK"))
        writeBytes("老编码.txt", gbk)
        val copied = artifactCopyOf(root, "老编码.txt")
        assertTrue(copied != null)
        assertFalse("UTF-8 往返对不上 ⇒ 自证不过", copied!!.complete)
        assertEquals("盘上长度照报", gbk.size.toLong(), copied.fileBytes)
    }

    /** 上限口径钉死：查看截断的额度与复制的额度是两件事（§7 纪律 1 的常量证据）。 */
    @Test
    fun 查看上限与复制上限是两个数() {
        assertEquals(200 * 1024, ARTIFACT_VIEW_MAX_BYTES)
        assertEquals(3_000, ARTIFACT_VIEW_MAX_LINES)
        assertTrue("复制的上限必须高于查看的上限（否则「全文」无从谈起）", ARTIFACT_COPY_MAX_BYTES > ARTIFACT_VIEW_MAX_BYTES)
        assertTrue("收集上限 512 KB", ARTIFACT_MAX_FILE_BYTES == 512L * 1024L)
        assertEquals(8, ARTIFACT_CHIP_MAX)
    }

    // ── §8 · 顶栏产物条（B9／B10／B11；用户 2026-10-19 追加） ────────────────
    // 派工单口径：「用纯函数决定『顶栏该显示什么』以便 JVM 直测」。这一层测的是
    // topBarSlot / toggleTopBarExpanded / firstArtifactForCopy / artifactEntries 四个纯函数；
    // 「壳层确实照它渲染」由 BaopuCostWiringTest 从源码层钉。

    /** B9（正）：本轮有文本产物 ⇒ 顶栏是「产物 · N」＋第一个文件的短名。 */
    @Test
    fun B9_有产物时顶栏出产物条() {
        write("a/report.json", "{}")
        write("b/notes.md", "# 标题\n正文")
        val arts = selectTextArtifacts(root, listOf("a/report.json", "b/notes.md"))
        assertEquals("两个都是白名单文本", 2, arts.size)
        val slot = topBarSlot(arts, lastAssistant = "上一轮的回复正文", expanded = false)
        assertTrue("有产物 ⇒ Artifacts 分支，不是回复预览", slot is TopBarSlot.Artifacts)
        val a = slot as TopBarSlot.Artifacts
        assertEquals("N = 本轮产物个数（不是摆出来的片数）", 2, a.total)
        assertEquals("短名只取最后一段", "report.json", a.firstName)
        assertEquals("整条相对路径留着给查看器", "a/report.json", a.firstRelPath)
        assertFalse("产物分支里不许夹回复正文", a.toString().contains("上一轮的回复正文"))
        // 判别器：把「第一个」换成「最后一个」，上面两条就红——说明真的钉的是 first
        val last = arts.last()
        assertNotEquals("第一个与最后一个必须不是同一个东西（用例才有区分度）", a.firstRelPath, last)
    }

    /** B9（反）：没有产物 ⇒ 仍显示回复预览。**不许**硬出一条「产物 · 0」。 */
    @Test
    fun B9_没有产物时退回回复预览而不是产物0() {
        val slot = topBarSlot(emptyList(), lastAssistant = "在的，抱朴在线", expanded = true)
        assertTrue(slot is TopBarSlot.ReplyPreview)
        assertEquals("回复原样交给调用方（纯函数不加工文本）", "在的，抱朴在线", (slot as TopBarSlot.ReplyPreview).text)
        // 负例形状（派工单 §8「无产物时也硬显示产物 · 0」）：空列表绝不能落进 Artifacts
        val again = topBarSlot(emptyList(), "x", false)
        assertFalse("空产物硬出产物条 = B9 的负例，这里钉死", again is TopBarSlot.Artifacts)
        // 回复也是空串 ⇒ 整条不出（由调用方按 text.isBlank() 决定，不在这层编内容）
        assertEquals("", (topBarSlot(emptyList(), "", false) as TopBarSlot.ReplyPreview).text)
    }

    /** B10：点折叠行 ⇒ 展开；再点 ⇒ 收起。翻转本身是纯函数，壳层只管接线。 */
    @Test
    fun B10_展开收起由纯函数翻转() {
        assertTrue("折叠 ⇒ 点一下展开", toggleTopBarExpanded(expanded = false))
        assertFalse("展开 ⇒ 再点一下收起", toggleTopBarExpanded(expanded = true))
        assertFalse("点两下回到原点（不是只会展开的单向开关）", toggleTopBarExpanded(toggleTopBarExpanded(false)))
        // slot.expanded 是透传的：界面按它决定展开不展开（B10 的接线侧在 wiring 用例里）
        val arts = listOf("x.md")
        assertFalse((topBarSlot(arts, "r", false) as TopBarSlot.Artifacts).expanded)
        assertTrue((topBarSlot(arts, "r", true) as TopBarSlot.Artifacts).expanded)
    }

    /** §8.1 展开态每行 =「文件名＋大小」：大小取自盘，文件不在了给 0（不编数）。 */
    @Test
    fun B10_展开态每行有短名与盘上字节() {
        write("a/one.txt", "hello")
        val rows = artifactEntries(root, listOf("a/one.txt", "a/gone.txt"))
        assertEquals(2, rows.size)
        assertEquals("one.txt", rows[0].name)
        assertEquals("盘上是几个字节就报几个", 5L, rows[0].fileBytes)
        assertEquals("名字取不到盘也照给（列表不许空一格）", "a/gone.txt", rows[1].relPath)
        assertEquals("文件不在 ⇒ 0，不许编", 0L, rows[1].fileBytes)
    }

    /** B11：折叠态那颗「小号复制」只复制**第一个**产物，且是 §7 自证过的全文。 */
    @Test
    fun B11_折叠态只复制第一个产物全文() {
        val first = "a/one.txt"
        val second = "b/two.txt"
        write(first, "第一份\n第二行\n")
        write(second, "另一份完全不同的内容，中文更多，字节也更长。".repeat(40))
        val arts = selectTextArtifacts(root, listOf(first, second))
        assertEquals(2, arts.size)
        assertEquals("折叠按钮拿的就是列表第一个", first, firstArtifactForCopy(arts))
        val c = artifactCopyOf(root, firstArtifactForCopy(arts)!!)
        assertTrue("第一个产物的全文自证通过", c!!.complete)
        assertEquals("复制的字节就是第一个那份", File(root, first).length(), c.fileBytes)
        assertNotEquals("跟第二个的字节数是两回事（钉『只复制第一个』）", File(root, second).length(), c.fileBytes)
        assertEquals("字符数照 Kotlin 的 length（中文一字算一字符）", 8, c.chars)
        assertEquals("行尾带换行 ⇒ 行数就是换行数", 2, c.lines)
        assertNull("没有产物 ⇒ null（不许回个空串假装复制成功）", firstArtifactForCopy(emptyList()))
    }

    // ── 文案对称（中英都要有，键名成对） ────────────────────────────────────

    /** 本批新键中英各一份（跑马灯＋产物行＋查看器）。 */
    @Test
    fun 中英两个strings都有本批新键() {
        val zh = resFile("values/strings.xml").readText()
        val en = resFile("values-en/strings.xml").readText()
        listOf(
            "baopu_usage_label", "baopu_usage_in", "baopu_usage_out", "baopu_usage_cache_hit", "baopu_usage_step",
            "baopu_artifact_row_label", "baopu_artifact_more", "baopu_artifact_loading", "baopu_artifact_bytes",
            "baopu_artifact_truncated", "baopu_artifact_truncated_only", "baopu_artifact_copy",
            "baopu_artifact_copied", "baopu_artifact_copy_incomplete", "baopu_artifact_missing",
            "baopu_artifact_top_label",
        ).forEach { key ->
            assertTrue("中文缺 $key", zh.contains("name=\"$key\""))
            assertTrue("英文缺 $key", en.contains("name=\"$key\""))
        }
        // 带参数的键：占位符种类中英必须一致（少一个 %d 就是「界面崩或显示 %1$d」）
        assertSamePlaceholders(zh, en, "baopu_artifact_truncated")
        assertSamePlaceholders(zh, en, "baopu_artifact_copied")
        assertSamePlaceholders(zh, en, "baopu_usage_step")
        assertSamePlaceholders(zh, en, "baopu_artifact_more")
        assertSamePlaceholders(zh, en, "baopu_artifact_missing")
        assertSamePlaceholders(zh, en, "baopu_artifact_top_label")
    }

    private fun resFile(rel: String): File =
        File("src/main/res/$rel").let { if (it.isFile) it else File("app/src/main/res/$rel") }

    private fun assertSamePlaceholders(zh: String, en: String, key: String) {
        fun pattern(text: String): List<String> {
            // 134 批：部分键改成 <plurals>（lint PluralsCandidate），取值范围要涵盖两种形态。
            val body = Regex("name=\"$key\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
                .find(text)?.groupValues?.get(1)
                ?: Regex("name=\"$key\">(.*?)</plurals>", RegexOption.DOT_MATCHES_ALL)
                    .find(text)?.groupValues?.get(1)
                ?: throw AssertionError("$key 的中英文案都找不到（<string> / <plurals> 都试过）")
            return Regex("%\\d+\\$[a-z]").findAll(body).map { it.value }.distinct().toList()
        }
        assertEquals("$key 的中英占位符要成对", pattern(zh), pattern(en))
    }
}
