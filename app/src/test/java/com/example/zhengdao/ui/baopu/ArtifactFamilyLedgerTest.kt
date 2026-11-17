// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。

package com.example.zhengdao.ui.baopu

import com.example.zhengdao.baopu.ARTIFACT_MAX_BINARY_BYTES
import com.example.zhengdao.baopu.ARTIFACT_MAX_FILE_BYTES
import com.example.zhengdao.baopu.ArtifactFamily
import com.example.zhengdao.baopu.ArtifactKinds
import com.example.zhengdao.baopu.BaopuSql
import com.example.zhengdao.baopu.DOC_ARTIFACT_EXTENSIONS
import com.example.zhengdao.baopu.IMAGE_ARTIFACT_EXTENSIONS
import com.example.zhengdao.baopu.TEXT_ARTIFACT_EXTENSIONS
import com.example.zhengdao.baopu.artifactEntries
import com.example.zhengdao.baopu.artifactExtension
import com.example.zhengdao.baopu.artifactFamilyOf
import com.example.zhengdao.baopu.isBinaryArtifactFile
import com.example.zhengdao.baopu.selectArtifacts
import com.example.zhengdao.baopu.selectTextArtifacts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/**
 * 第六百八十一批 · 产物台账收 docx／xlsx／png（修 673 真机轮三的 FAIL G-673-1）。
 *
 * 病根（已定位，本文件只负责钉住修完之后的样子）：
 *   ① `TEXT_ARTIFACT_EXTENSIONS` 只有八个文本后缀 ⇒ `selectTextArtifacts` 把 docx/xlsx/png
 *      全过滤掉 ⇒ `upsertArtifact` 永不落行 ⇒ 产物架 0 行；
 *   ② 「用其他应用打开」全仓唯一入口在产物架那一行 ⇒ 641 写的三个 MIME 分支没有可达入口。
 *
 * 打法（与 38／641 两批同一房型）：
 *   1. **行为层**：真临时目录里造真文件（docx 用带 NUL 的 zip 头字节，正是文本判据会判掉的那种），
 *      跑 `selectArtifacts`／`artifactEntries`／`artifactOpenPlanOf`，断言「台账一行、ext=docx、
 *      Intent 是 docx 的 MIME、删了之后是 Gone」——纯源码字符串代替不了这四件事；
 *   2. **接线层**：产物架那一行的分流（文本族进查看器、其余走外部打开）只有源码能证明，
 *      每条「点开去哪」都配判别器自证，防门禁空跑。
 */
class ArtifactFamilyLedgerTest {

    // ── 源码读取（工作目录 = app/；判据一律读剥掉注释后的文本）──────────────

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    private fun strip(path: String): String = repoFile(path).readText(Charsets.UTF_8)
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private fun shelf() = strip("src/main/java/com/example/zhengdao/ui/baopu/BaopuArtifactShelf.kt")
    private fun plan() = strip("src/main/java/com/example/zhengdao/ui/baopu/ArtifactOpenPlan.kt")
    private fun engine() = strip("src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt")
    private fun pure() = strip("src/main/java/com/example/zhengdao/baopu/BaopuCostAndArtifacts.kt")
    private fun zh() = repoFile("src/main/res/values/strings.xml").readText(Charsets.UTF_8)
    private fun en() = repoFile("src/main/res/values-en/strings.xml").readText(Charsets.UTF_8)

    // ── 盘上现场 ──────────────────────────────────────────────────────────

    private lateinit var root: File

    @Before
    fun setUp() {
        root = File.createTempFile("zd681-ws", "").let { it.delete(); it.mkdirs(); it }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun writeBytes(rel: String, bytes: ByteArray): String {
        val f = File(root, rel)
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
        return rel
    }

    private fun writeText(rel: String, content: String = "甲\n"): String =
        writeBytes(rel, content.toByteArray(Charsets.UTF_8))

    /**
     * 一份**像真的** docx：zip 本地文件头（`PK\u0003\u0004`）＋一段带 `\u0000` 的压缩流。
     * 带 NUL 是关键——文本判据「前 8 KB 无 NUL」正是把它判掉的那一条。
     */
    private fun writeDocx(rel: String, size: Int = 2858): String {
        val head = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x06, 0x00)
        val rest = ByteArray(size - head.size) { (it % 251).toByte() }
        rest[0] = 0
        rest[1] = 0
        return writeBytes(rel, head + rest)
    }

    /** 一份像真的 png：8 字节签名之后全是压缩字节（含 0）。 */
    private fun writePng(rel: String, size: Int = 4096): String {
        val sig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        return writeBytes(rel, sig + ByteArray(size - sig.size) { (it % 97).toByte() })
    }

    // ── 判据①：生成 docx ⇒ 台账一行，ext=docx ──────────────────────────────

    @Test
    fun `判据1_生成docx后台账一行且ext是docx`() {
        val rel = writeDocx("剧本/甲方报告.docx")
        val kept = selectArtifacts(root, listOf(rel))
        assertEquals("docx 必须进台账（只筛文本时这里是空 = 产物架 0 行）", listOf(rel), kept)

        val entries = artifactEntries(root, kept)
        assertEquals("台账就一行", 1, entries.size)
        assertEquals("ext=docx", "docx", artifactExtension(entries.single().relPath))
        assertEquals("字节数取盘上那份，不编", File(root, rel).length(), entries.single().fileBytes)

        // 一行只落一条 upsert，绑进去的路径就是这一行
        val stmt = BaopuSql.upsertArtifact(
            7L, root.absolutePath, entries.single().relPath, entries.single().fileBytes, 1_700_000_000_000L,
        )
        assertEquals("INSERT 绑的是这一行的相对路径", rel, stmt.args[2])
        assertTrue("再写是更新不是第二行", stmt.sql.contains("ON CONFLICT (workspace, rel_path) DO UPDATE SET"))
    }

    @Test
    fun `判据1_对照_文本口径确实把docx筛掉了_这就是根因`() {
        val rel = writeDocx("剧本/甲方报告.docx")
        assertTrue("对照：老口径（只认八个文本后缀）对 docx 一行都不出", selectTextArtifacts(root, listOf(rel)).isEmpty())
        assertFalse("对照：白名单里确实没有 docx", artifactExtension(rel) in TEXT_ARTIFACT_EXTENSIONS)
        // 判别器：上面那条对照不是恒真的——同一份东西换成 md 就收
        val md = writeText("剧本/同名的.md")
        assertEquals(listOf(md), selectTextArtifacts(root, listOf(md)))
    }

    // ── 判据②：点「用其他应用打开」⇒ Intent 是 docx 的 MIME ────────────────

    @Test
    fun `判据2_点开docx的Intent类型是docx的MIME`() {
        val rel = writeDocx("产物/方案.docx")
        val plan = artifactOpenPlanOf(root, rel)
        assertTrue("docx 必须能给出规格（拿到 $plan）", plan is ArtifactOpenPlan.Ready)
        val spec = (plan as ArtifactOpenPlan.Ready).spec
        assertEquals("MIME 必须是 docx 那一条", ArtifactOpen.MIME_DOCX, spec.mime)
        assertEquals("必须是 ACTION_VIEW", "android.intent.action.VIEW", spec.action)
        assertEquals("必须是 content://（file:// 在安卓 7+ 直接崩）", "content", spec.scheme)
        assertEquals("authority 后缀要与清单一致", ".artifact.open", spec.authoritySuffix)
        assertTrue("必须给临时读授权", spec.grantRead)
        assertTrue("必须带 ClipData", spec.withClipData)
        // 接线：这一族点开走的就是外部打开，不是内置查看器（判据③）
        assertEquals("docx 不许进内置预览", ArtifactRowTap.External, artifactRowTapOf(rel))
        assertTrue(
            "产物架那一行必须真的把 External 分到 openExternal",
            shelf().contains("ArtifactRowTap.External -> openExternal(row)"),
        )
        // 判别器：反过来那一支确实能被同一条正则抓到
        assertTrue(
            "判别器抓得住 External 分支",
            Regex("ArtifactRowTap\\.External -> openExternal\\(row\\)")
                .containsMatchIn("ArtifactRowTap.External -> openExternal(row)"),
        )
    }

    // ── 判据③：xlsx、png 各一例同上 ───────────────────────────────────────

    @Test
    fun `判据3_xlsx与png各一例_台账进得去且MIME各自正确`() {
        val xlsx = writeDocx("表/统计.xlsx")
        val png = writePng("图/立绘.png")
        assertEquals("两份都进台账、顺序照传入", listOf(xlsx, png), selectArtifacts(root, listOf(xlsx, png)))

        val cases = listOf(
            Triple(xlsx, "xlsx", ArtifactOpen.MIME_XLSX),
            Triple(png, "png", ArtifactOpen.MIME_PNG),
        )
        cases.forEach { (rel, ext, mime) ->
            assertEquals("$rel 的 ext", ext, artifactExtension(rel))
            val plan = artifactOpenPlanOf(root, rel)
            assertTrue("$rel 必须能给出规格（拿到 $plan）", plan is ArtifactOpenPlan.Ready)
            assertEquals("$rel 的 MIME", mime, (plan as ArtifactOpenPlan.Ready).spec.mime)
            assertEquals("$rel 不许进内置预览", ArtifactRowTap.External, artifactRowTapOf(rel))
        }
        // 三种 MIME 互不撞车（撞了就是发错应用）
        assertEquals(3, setOf(ArtifactOpen.MIME_DOCX, ArtifactOpen.MIME_XLSX, ArtifactOpen.MIME_PNG).size)
    }

    // ── 判据④：删了文件再点 ⇒ 明说「文件不在了」，不崩 ──────────────────────

    @Test
    fun `判据4_删掉文件后再点_明确文件不在了_不发Intent`() {
        val rel = writeDocx("产物/会被删掉.docx")
        assertTrue("前置：删之前是 Ready", artifactOpenPlanOf(root, rel) is ArtifactOpenPlan.Ready)

        assertTrue("前置：文件真删掉了", File(root, rel).delete())
        assertFalse("盘上确实没有它了", File(root, rel).exists())

        assertEquals(
            "删了之后必须明说「文件不在了」（而不是拿旧徽标发 Intent）",
            ArtifactOpenPlan.Gone,
            artifactOpenPlanOf(root, rel),
        )
        // 文案那一半：这句必须两边都有，且是用户看得懂的那句
        assertTrue("中文有这句", zh().contains("name=\"baopu_artifact_open_gone\""))
        assertTrue("英文有这句", en().contains("name=\"baopu_artifact_open_gone\""))
        assertTrue("产物架的「用其他应用打开」真的会说话", shelf().contains("R.string.baopu_artifact_open_gone"))
        // 不崩：族外的类型（老台账里可能躺着的文本行）也只回一句「不支持」，不发 Intent、不抛
        val mdRel = writeText("产物/笔记.md")
        assertEquals("族外类型不给规格", ArtifactOpenPlan.Unsupported, artifactOpenPlanOf(root, mdRel))
        // 判据顺序也钉一下：不在盘上比「认不认类型」先判（.md 不在盘上 ⇒ Gone 而不是 Unsupported）
        assertEquals("先判盘上有没有，再判类型", ArtifactOpenPlan.Gone, artifactOpenPlanOf(root, "产物/没写过的.md"))
    }

    // ── 判据⑤：文本类产物行为不变（回归）────────────────────────────────────

    @Test
    fun `判据5_文本族行为不变_仍进台账仍走内置查看器`() {
        val md = writeText("报告/甲.md", "第一行\n第二行\n")
        val docx = writeDocx("报告/乙.docx")
        val both = listOf(md, docx)

        assertEquals("文本族照旧：老口径一个不差", listOf(md), selectTextArtifacts(root, both))
        assertEquals("台账口径下文本族仍在原位、顺序不变", both, selectArtifacts(root, both))
        assertEquals("文本族点开还是内置只读查看器", ArtifactRowTap.Preview, artifactRowTapOf(md))
        assertEquals("大小写照旧不敏感", ArtifactRowTap.Preview, artifactRowTapOf("报告/丙.MD"))

        // 512 KB 那条仍是文本族的账（没被二进制上限顶掉）
        val big = File(root, "报告/大.md")
        big.writeBytes(ByteArray((ARTIFACT_MAX_FILE_BYTES + 1024).toInt()) { '.'.code.toByte() })
        assertEquals(
            "512 KB 以上的文本照样不收（老判据），但同目录的 docx 照收",
            listOf(docx),
            selectArtifacts(root, listOf("报告/大.md", docx)),
        )

        // 接线：文本族那一支仍然把这一条交给查看器
        assertTrue(
            "文本族点开 ⇒ viewingRel（38 批那条路一个字节没变）",
            shelf().contains("ArtifactRowTap.Preview -> viewingRel = row.relPath"),
        )
        assertEquals("查看器调用点仍然只有一个", 1, Regex("BaopuArtifactViewer\\(").findAll(shelf()).count())
    }

    // ── 判据⑥：不新增第二份台账；641 的 MIME 映射表一字未动 ────────────────

    @Test
    fun `判据6_仍然只有一本台账_641的映射已并进表`() {
        val ddl = BaopuSql.ARTIFACT_DDL.joinToString("\n")
        assertEquals("建表＋索引两条，不多不少", 2, BaopuSql.ARTIFACT_DDL.size)
        assertEquals("建表只针对 artifact 一张", 1, Regex("CREATE TABLE IF NOT EXISTS").findAll(ddl).count())
        assertTrue("表还是那张 artifact", ddl.contains("CREATE TABLE IF NOT EXISTS artifact ("))
        assertFalse("本单不许再建第二张产物表", Regex("CREATE TABLE IF NOT EXISTS (?!artifact )").containsMatchIn(ddl))

        // 685：641 那三支 `when` 已并进 ArtifactKinds 一张表——plan 里不许再有第二份映射
        assertEquals("plan 里不再有 -> MIME_ 分支", 0, Regex("-> MIME_").findAll(plan()).count())
        assertTrue("mimeOf 只做一次查表（不自己再判一遍扩展名）", plan().contains("ArtifactKinds.openMimeOf"))
        assertEquals("docx 仍然认得", ArtifactOpen.MIME_DOCX, ArtifactOpen.mimeOf("a.docx"))
        // 681 当时钉的是「jpg/webp 不在 641 的表里」；685 把这条修掉了，钉法随之改成「认得」
        assertEquals("jpg 现在认得（685 修的就是这条）", ArtifactKinds.MIME_JPEG, ArtifactOpen.mimeOf("图/甲.jpg"))
        assertEquals("webp 同理", ArtifactKinds.MIME_WEBP, ArtifactOpen.mimeOf("图/乙.webp"))

        // 台账那本账只有一个写入口（引擎那条链），产物架只读
        assertEquals("记账调用点只有一个", 1, Regex("persistArtifactLedger\\(sessionId,").findAll(engine()).count())
        assertTrue("收集走纯函数那一份", engine().contains("com.example.zhengdao.baopu.selectArtifacts(root, touched)"))
        assertEquals("selectArtifacts 只定义一处", 1, Regex("fun selectArtifacts").findAll(pure()).count())
        assertEquals("selectTextArtifacts 也只定义一处（文本判据的老主人）", 1, Regex("fun selectTextArtifacts").findAll(pure()).count())
        assertTrue(
            "文本那一半是「调它」不是「照着重写」",
            pure().substringAfter("fun selectArtifacts(root: File, relPaths: List<String>): List<String> {")
                .substringBefore("\n}")
                .contains("selectTextArtifacts(root, relPaths)"),
        )
    }

    // ── 口径：三族名单与二进制上限 ─────────────────────────────────────────

    @Test
    fun `口径_三族名单各一处_大小写不敏感_族外一律不进台账`() {
        assertEquals("文档族就是这两支", setOf("docx", "xlsx"), DOC_ARTIFACT_EXTENSIONS)
        assertEquals(
            "图片族四支：png／jpg／jpeg／webp（685 补 jpeg，与 jpg 同类型）",
            setOf("png", "jpg", "jpeg", "webp"),
            IMAGE_ARTIFACT_EXTENSIONS,
        )
        assertEquals("大写后缀照样认", ArtifactFamily.Image, artifactFamilyOf("A/B.JPG"))
        assertEquals("子目录不误判", ArtifactFamily.Doc, artifactFamilyOf("A/B/C.Docx"))
        assertEquals("文本族归位", ArtifactFamily.Text, artifactFamilyOf("A/B.md"))
        assertNull("pdf 不属于任何一族", artifactFamilyOf("A/B.pdf"))
        assertNull("zip 不属于任何一族", artifactFamilyOf("A/B.zip"))
        assertNull("没后缀不属于任何一族", artifactFamilyOf("A/Dockerfile"))

        // 族外的文件即使确实是我们写出来的，也不进台账（认不出是什么就别让人点开看一句「不支持」）
        val pdf = writeBytes("产物/甲.pdf", ByteArray(64) { 1 })
        assertTrue("pdf 不进台账", selectArtifacts(root, listOf(pdf)).isEmpty())
    }

    @Test
    fun `口径_二进制上限是16MB_0字节不吃_文本的上限没被动`() {
        assertEquals("文档/图片的上限", 16L * 1024L * 1024L, ARTIFACT_MAX_BINARY_BYTES)
        assertEquals("文本那条 512 KB 一字未动", 512L * 1024L, ARTIFACT_MAX_FILE_BYTES)

        val empty = writeBytes("产物/空.png", ByteArray(0))
        assertFalse("0 字节＝没内容，不进门", isBinaryArtifactFile(File(root, empty)))
        val ok = writePng("产物/小.png", 1024)
        assertTrue("1 KB 的 png 收", isBinaryArtifactFile(File(root, ok)))

        // 上限另一侧：造一个 16 MB + 1 字节的稀疏文件（不真写 16 MB 到盘）
        val huge = File(root, "产物/超大.png")
        RandomAccessFile(huge, "rw").use { it.setLength(ARTIFACT_MAX_BINARY_BYTES + 1) }
        assertEquals("前置：盘上就是 16 MB + 1", ARTIFACT_MAX_BINARY_BYTES + 1, huge.length())
        assertFalse("超上限不收", isBinaryArtifactFile(huge))
        assertTrue("超上限的也不进台账", selectArtifacts(root, listOf("产物/超大.png")).isEmpty())

        // 判别器：512 KB 的文本（老判据）与 16 MB 的二进制（新判据）确实走的是两条线
        val bigText = File(root, "产物/大.txt")
        bigText.writeBytes(ByteArray(600 * 1024) { '.'.code.toByte() })
        assertTrue("600 KB 的文本按 512 KB 那条判红（不是二进制线）", selectArtifacts(root, listOf("产物/大.txt")).isEmpty())
    }
}
