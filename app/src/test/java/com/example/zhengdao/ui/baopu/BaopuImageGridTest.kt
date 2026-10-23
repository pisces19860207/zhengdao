// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.ui.baopu

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第三十批判据（G1–G8）：图片附件「并排」——气泡里的多图区 ＋ 输入框的附件条。
 *
 * 派工单：`docs/派工单-2026-10-19-第三十批-图片并排.md`（用户原话
 * 「传的图片排的好乱啊，并排不好吗？」，以及「三行纯文字跟『图片』这件事完全不搭」）。
 *
 * ## 为什么分两层（跟第二十四批 B 段同一惯例）
 *
 * **行为层**能跑的只有纯函数：`imageGridLayout`（几列、多大）、`imageRows`（每行同样数量）、
 * `imageCells`（哪张缺、位置对应）、`imageCellAt`（索引→该张，越界不崩）。
 * 这四条是"排布对不对"的真判据 —— 喂宽度进去看它给出几列，比在源码里数 `Row` 硬得多。
 *
 * **源码层**钉的是"渲染有没有这件事"：`aspectRatio(1f)`/`ContentScale.Crop`/`horizontalScroll`
 * 这些在 JVM 里根本跑不了 Compose，只能钉字面量 —— 但每条 absence 断言都配了**判别器**
 * （同一处断言里给一个必须存在的正例），不红＝没真测。
 *
 * ## 屏幕算术（判据 G1 的依据，别凭感觉调档）
 *
 * 真机 HONOR PGT-AN10 的实测屏参（`docs/ERRATA.md` 里记的 `屏 1312×2848、density 3.5`）
 * ⇒ 屏宽 1312/3.5 ≈ **374dp**。气泡链路：列表左右各 16dp、用户气泡占 85%、气泡内边距 12dp×2
 * ⇒ 用户气泡内容宽 = (374−32)×0.85−24 ≈ **266dp**、助手气泡 = 374−32−24 = **318dp**。
 * 于是：助手气泡 3×96+2×4 = **296 ≤ 318** ✓ 三张 96dp；
 * 用户气泡 3×84+2×4 = **260 ≤ 266** ✓（96dp 档只能排 2 张 ⇒ 退 84dp，正是派工单「不够就缩到 84」）。
 * 本文件的宽度断言全部用这两个实测数字，不用"看起来差不多"的圆数。
 */
class BaopuImageGridTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    /** 判据一律读**剥掉注释**后的源码（注释里写着 `Column { imageParts.forEach … }` 这类反例词）。 */
    private fun code(rel: String): String = repoFile(rel).readText()
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private fun list() = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuMessageList.kt")

    private fun composer() = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuComposer.kt")

    private fun zh() = repoFile("src/main/res/values/strings.xml").readText()

    private fun en() = repoFile("src/main/res/values-en/strings.xml").readText()

    /** 真机实测口径下的两个内容宽（dp）。 */
    private val userAvail = 266
    private val assistantAvail = 318

    // ── G1：多图横排（行为层，纯函数）──────────────────────────────────────

    @Test
    fun `G1_助手气泡三张用96dp一排放下`() {
        val g = imageGridLayout(count = 3, availDp = assistantAvail)
        assertEquals("3×96+2×4=296 ≤ 318 ⇒ 不必降级", 96, g.sideDp)
        assertEquals("一排三张", 3, g.perLine)
    }

    @Test
    fun `G1_用户气泡三张降到84dp才排得下`() {
        val g = imageGridLayout(count = 3, availDp = userAvail)
        // 96 档在这宽度只能排 2 列（2×96+4=196 行得通、3×96+8=296 > 266）⇒ 84 档多出一列 ⇒ 退 84
        assertEquals("派工单：不够就缩到 84dp", 84, g.sideDp)
        assertEquals("仍是三张一排", 3, g.perLine)
        assertTrue("降级不许降过头（84 是最小档）", g.sideDp >= 84)
    }

    @Test
    fun `G1_四张排两行_每行同样数量`() {
        val g = imageGridLayout(count = 4, availDp = assistantAvail)
        assertEquals(3, g.perLine)
        val rows = imageRows(count = 4, perLine = g.perLine)
        assertEquals("4 张 3 列 ⇒ 两行", 2, rows.size)
        // 判据 A1「每行同样数量」：只有末行可以少，且少的在末尾
        assertEquals(listOf(0, 1, 2), rows[0])
        assertEquals(listOf(3), rows[1])
        assertTrue(
            "除末行外每行必须满",
            rows.dropLast(1).all { it.size == g.perLine },
        )
    }

    @Test
    fun `G1_窄到只放得下一格时不溢出`() {
        // 分屏/小窗：可用宽 100dp ⇒ 96 档只 1 列（不能排 2 张），仍然 96、不溢出
        val g = imageGridLayout(count = 5, availDp = 100)
        assertEquals(1, g.perLine)
        assertTrue("格子宽不许超过可用宽（否则横向溢出气泡）", g.sideDp <= 100)
        // 极端：比最小档还窄 ⇒ 收到可用宽，且不低于 64 的下限
        val tiny = imageGridLayout(count = 3, availDp = 40)
        assertEquals(1, tiny.perLine)
        assertTrue("下限 64dp（再小看不出是图）", tiny.sideDp >= 64)
        assertTrue("也不许反过来撑破容器", tiny.sideDp <= 96)
    }

    @Test
    fun `G1_图片区源码不再是一张一行`() {
        val src = list()
        // 反例（改版前的真因）：Column 里逐张竖着堆
        assertFalse("图片区不许再回到 Column{imageParts.forEach…} 的一张一行", src.contains("imageParts.forEachIndexed"))
        assertFalse("渲染层不许再出现裸的 spacedBy(4.dp)（间距走 IMAGE_GAP_DP）", src.contains("spacedBy(4.dp)"))
        // 判别器：文本那条链路确实还在用 forEachIndexed ⇒ 剥注释与扫描都工作正常
        assertTrue("文字 part 仍按序渲染（同一段扫描的正例）", src.contains("textParts.forEachIndexed"))
        assertTrue("图片区改成了「若干横排」", src.contains("Row(horizontalArrangement = Arrangement.spacedBy(IMAGE_GAP_DP.dp))"))
        assertTrue("行由纯函数切", src.contains("imageRows(cells.size, grid.perLine)"))
    }

    // ── G2：正方形 + Crop ───────────────────────────────────────────────────

    @Test
    fun `G2_每格强制正方形且裁切`() {
        val src = list()
        assertTrue("派工单：全部正方形 aspectRatio(1f)", src.contains(".aspectRatio(1f)"))
        assertTrue("派工单：ContentScale.Crop（比例不跟原图）", src.contains("contentScale = ContentScale.Crop"))
        // 判别器：全屏那一层用的是 Fit（不许被误改成 Crop），两条都在 ⇒ 扫描分得清
        assertTrue("全屏仍是 Fit（不许裁掉内容）", src.contains("contentScale = ContentScale.Fit"))
    }

    // ── G3：间距唯一真源 ────────────────────────────────────────────────────

    @Test
    fun `G3_四个间距常量各只定义一次`() {
        val src = list()
        listOf(
            "IMAGE_GAP_DP = 4" to 1,
            "IMAGE_TEXT_GAP_DP = 6" to 1,
            "IMAGE_SIDE_FULL_DP = 96" to 1,
            "IMAGE_SIDE_SMALL_DP = 84" to 1,
        ).forEach { (decl, want) ->
            val got = Regex(Regex.escape(decl)).findAll(src).count()
            assertEquals("间距/档位常量 $decl 应当且只当定义一次", want, got)
        }
        // 行距与列距引用同一个常量 ⇒ 改一处必然同步
        assertEquals("行距与列距各引用一次 IMAGE_GAP_DP", 2, Regex(Regex.escape("spacedBy(IMAGE_GAP_DP.dp)")).findAll(src).count())
        assertTrue("图片区与文字之间用 6dp 常量", src.contains("padding(top = IMAGE_TEXT_GAP_DP.dp)"))
    }

    @Test
    fun `G3_96dp这条数只出现在两个被钉住的地方`() {
        val src = list()
        // 判据 G4 的字面量（IMAGE_THUMB_DP）与档位常量必须同数 —— 漂开了就是"单图与并排不一致"
        assertTrue("B3/G4 钉的 96dp 字面量还在", src.contains("IMAGE_THUMB_DP = 96.dp"))
        assertTrue("并排档位的 96 与它是同一个数", src.contains("IMAGE_SIDE_FULL_DP = 96"))
        assertFalse("不许再出现第三处裸的 96.dp 尺寸", Regex("""size\(96\.dp\)""").containsMatchIn(src))
        // 判别器：单格尺寸确实是通过变量传的
        assertTrue("格子尺寸来自 side", src.contains(".size(side)"))
    }

    // ── G4：单图仍 96dp ─────────────────────────────────────────────────────

    @Test
    fun `G4_单图恒96dp_不管容器多窄`() {
        listOf(assistantAvail, userAvail, 100, 40).forEach { avail ->
            val g = imageGridLayout(count = 1, availDp = avail)
            assertEquals("单图不因为改版缩小（avail=$avail）", 96, g.sideDp)
            assertEquals(1, g.perLine)
        }
    }

    @Test
    fun `G4_单图渲染显式引用IMAGE_THUMB_DP`() {
        val src = list()
        assertTrue("单图那一格用被钉住的常量", src.contains("if (cells.size == 1) IMAGE_THUMB_DP"))
    }

    // ── G5：点第 N 张打开第 N 张 ────────────────────────────────────────────

    private fun cellsOf(vararg rel: String) = rel.map { ImageCell(relPath = it, present = true) }

    @Test
    fun `G5_索引到该张_逐张对应`() {
        val cells = cellsOf("附件/a.jpg", "附件/b.jpg", "附件/c.jpg")
        listOf("附件/a.jpg", "附件/b.jpg", "附件/c.jpg").forEachIndexed { i, want ->
            assertEquals("第 ${i + 1} 格就是第 ${i + 1} 张", want, imageCellAt(cells, i)?.relPath)
        }
    }

    @Test
    fun `G5_越界与负数与空都不崩`() {
        val cells = cellsOf("附件/a.jpg", "附件/b.jpg")
        assertNull("末尾外", imageCellAt(cells, 2))
        assertNull("离谱的索引", imageCellAt(cells, 9_999))
        assertNull("负索引", imageCellAt(cells, -1))
        assertNull("没打开", imageCellAt(cells, null))
        assertNull("空列表", imageCellAt(emptyList(), 0))
    }

    @Test
    fun `G5_点击带的是自己那一格的索引_全屏层只有一份`() {
        val src = list()
        // 反例（派工单点名的那条）：每格都打开第一张
        assertTrue("点击写入的是本格索引 i", src.contains("onOpen = { openIndex = i }"))
        assertFalse("不许把索引写死成 0", src.contains("onOpen = { openIndex = 0 }"))
        assertFalse("不许退回每张各自弹 Dialog（那样就没有「第 N 张」这条真源）", src.contains("var fullScreen"))
        // 判别器：全屏确实只有一处 Dialog，且拿的是 imageCellAt 的结果
        assertEquals("整文件只应有 1 处 Dialog(", 1, Regex("""\bDialog\(""").findAll(src).count())
        assertTrue(src.contains("imageCellAt(cells, openIndex)"))
    }

    @Test
    fun `G5_长按复制的是这一格的相对路径`() {
        val src = list()
        assertTrue(src.contains("""ClipData.newPlainText("baopu-image-path", relPath)"""))
        assertTrue("复制动作在格子层（拿得到本格 relPath）", src.contains("val relPath = cell.relPath"))
    }

    // ── G6：缺图在原位说话 ──────────────────────────────────────────────────

    private fun tempRootWith(vararg rel: String): File {
        val root = File(System.getProperty("java.io.tmpdir"), "zd30-" + System.nanoTime())
        assertTrue("建不了临时工作区", root.mkdirs())
        root.deleteOnExit()
        rel.forEach { r ->
            val f = File(root, r)
            f.parentFile?.mkdirs()
            f.writeBytes(ByteArray(8))
            f.deleteOnExit()
        }
        return root
    }

    @Test
    fun `G6_哪张缺按顺序算_位置不许前移`() {
        val root = tempRootWith("附件/a.jpg", "附件/c.jpg")
        val cells = imageCells(listOf("附件/a.jpg", "附件/b.jpg", "附件/c.jpg"), root)
        assertEquals("三项还是三项（缺的不许被悄悄跳过）", 3, cells.size)
        assertEquals(listOf(true, false, true), cells.map { it.present })
        assertEquals("附件/b.jpg", cells[1].relPath)
    }

    @Test
    fun `G6_零字节的文件算不在`() {
        val root = tempRootWith()
        val empty = File(root, "附件/空.jpg").apply { parentFile?.mkdirs(); writeBytes(ByteArray(0)) }
        val ok = File(root, "附件/有.jpg").apply { writeBytes(ByteArray(3)) }
        assertFalse("0 字节＝没内容，当「在」处理就永远转圈", imageFilePresent(empty))
        assertTrue(imageFilePresent(ok))
        assertFalse("目录不算图", imageFilePresent(root))
    }

    @Test
    fun `G6_缺失走独立文案且仍在该格`() {
        val src = list()
        assertTrue("缺一格用短文案（格子放不下长句）", src.contains("R.string.baopu_image_missing_short"))
        assertTrue("读屏仍给带路径的整句（B4 那条没丢）", src.contains("R.string.baopu_image_missing"))
        assertTrue("缺失分支看的是该格的 present", src.contains("if (!exists)"))
        assertEquals("「文件在不在」只有一份实现", 1, Regex(Regex.escape("file.isFile && file.length() > 0")).findAll(src).count())
    }

    // ── G7：输入框横排缩略图 chip ───────────────────────────────────────────

    @Test
    fun `G7_附件条横排且放不下横向滚动`() {
        val src = composer()
        assertTrue("一行放不下要横向滚动（判据点名不许换行）", src.contains("horizontalScroll(rememberScrollState())"))
        assertFalse("不许用换行FlowRow 顶高输入区", src.contains("FlowRow("))
        assertFalse("旧版每条附件占一整行的写法要没了", src.contains("Modifier.fillMaxWidth().padding(horizontal = 16.dp),\n                verticalAlignment"))
        // 判别器：滚动前还有一道裁边，chip 不许糊出可视区
        assertTrue(src.contains(".clip(RectangleShape)"))
    }

    @Test
    fun `G7_图片chip是56dp方块圆角8加右上角移除`() {
        val src = composer()
        assertTrue("派工单指定 56dp", src.contains("ATTACH_THUMB_DP = 56.dp"))
        assertTrue("圆角 8dp", src.contains("ATTACH_CHIP_RADIUS_DP = 8.dp"))
        assertTrue("移除用文字符号 ×（不引图标库、不新自绘）", src.contains("""text = "×""""))
        assertFalse("不许 import 图标库", src.contains("material.icons"))
        assertTrue("图片 chip 与文件 chip 分开", src.contains("if (a.isImage) AttachThumbChip(a, remove) else AttachFileChip(a, remove)"))
        assertTrue("缩略图裁成正方块", src.contains("contentScale = ContentScale.Crop"))
    }

    @Test
    fun `G7_路径与字节数不再常驻_但语义仍是相对路径`() {
        val src = composer()
        assertFalse("chip 不许把路径常驻显示（旧版三行纯文字就是这条）", src.contains("stringResource(R.string.baopu_attach_chip"))
        // 判别器：同一条文案还在，只是改成"点一下才说"
        assertTrue("点一下才弹路径（getString 形态）", src.contains("getString(R.string.baopu_attach_chip, a.relPath, a.sizeBytes)"))
        assertTrue("长按复制的仍是相对路径", src.contains("copyRelPath(ctx, relPath)"))
        assertFalse("绝对路径不许进剪贴板/对话", src.contains("absolutePath"))
    }

    @Test
    fun `G7_上限与超限提示沿用MAX_ATTACH`() {
        val src = composer()
        assertTrue("上限仍是 9", src.contains("private const val MAX_ATTACH = 9"))
        assertTrue("满 9 再加要提示（沿用原文案）", src.contains("R.plurals.baopu_attach_too_many") || src.contains("R.string.baopu_attach_too_many"))
        assertTrue("hint 仍拼进要发出去的话（B2 没被改版弄丢）", src.contains("onSend(withAttachmentHint(ctx, body, list), list)"))
    }

    @Test
    fun `G7_缩略图复用同一份解码_不复制不落盘`() {
        val src = composer()
        assertTrue("读的是工作区根 + 相对路径", src.contains("File(BaopuWorkspace.hostDir(ctx), relPath)"))
        assertTrue("解码函数复用图片区那一份（internal 共享）", src.contains("decodeSampledImage(file, CHIP_TARGET_PX)"))
        listOf("copyTo(", "copyRecursively(", "FileOutputStream(").forEach { w ->
            assertFalse("chip 渲染不该写盘：出现了 $w", src.contains(w))
        }
    }

    /**
     * 取根口径（第三十一批 E-159 反转之后）：抱朴的工作区与终端**分成两套**，
     * 附件落盘走 `BaopuWorkspace` ⇒ 读图必须走同一个根。蹭回终端那个根＝在错的
     * 目录里找图，用户会看到「图片找不到了」但其实图好好躺着。
     */
    @Test
    fun `G7_读图的根与附件落盘的根是同一个`() {
        val grid = list()
        val chip = composer()
        assertTrue("气泡图片区取根走 BaopuWorkspace", grid.contains("imageCells(relPaths, BaopuWorkspace.hostDir(ctx))"))
        assertTrue("全屏层同样走 BaopuWorkspace", grid.contains("File(BaopuWorkspace.hostDir(ctx), relPath)"))
        assertTrue("输入框 chip 同样走 BaopuWorkspace", chip.contains("File(BaopuWorkspace.hostDir(ctx), relPath)"))
        // 判别器：终端那套根只要出现一次就算违规（B4 门禁的源码级同一条）
        assertFalse("气泡图片区不许蹭终端根", grid.contains("terminal.Workspace"))
        assertFalse("输入框 chip 不许蹭终端根", chip.contains("terminal.Workspace"))
    }

    // ── G8：中英对称 ───────────────────────────────────────────────────────

    @Test
    fun `G8_三个新键中英都有`() {
        listOf("baopu_image_missing_short", "baopu_attach_thumb_desc", "baopu_attach_path_copied").forEach { key ->
            assertTrue("中文缺 $key", zh().contains("""name="$key""""))
            assertTrue("英文缺 $key", en().contains("""name="$key""""))
        }
    }

    @Test
    fun `G8_新键实义非空_占位不许是空串`() {
        listOf(zh(), en()).forEach { xml ->
            val line = xml.lines().first { it.contains("baopu_image_missing_short") }
            val body = line.substringAfter('>').substringBeforeLast("<")
            assertTrue("占位文案必须有字：$line", body.isNotBlank())
            val desc = xml.lines().first { it.contains("baopu_attach_thumb_desc") }
            assertTrue("读屏名要带路径占位（B4 同一口径）", desc.contains("%1\$s"))
        }
        // 判别器：`baopu_image_missing`（带路径的长句）仍排在短键之前——
        // 第二十四批 B4 的断言用 lines().first{contains("baopu_image_missing")}，
        // 顺序漂了那条会读到短文案（没有 %1$s）⇒ 这里把它钉住。
        val zhLines = zh().lines()
        val long = zhLines.indexOfFirst { it.contains("""name="baopu_image_missing"""") }
        val short = zhLines.indexOfFirst { it.contains("""name="baopu_image_missing_short"""") }
        assertTrue("长句必须先于短句出现", long in 0 until short)
    }

    // ── 本批不许动的东西（红线自查，源码级）────────────────────────────────

    @Test
    fun `红线_图片区没被顺手接上视觉通道`() {
        // 本批只改排布：不许出现"把图 base64 塞进请求"之类的越界动作
        val src = list() + composer()
        assertFalse(src.contains("base64"))
        assertFalse(src.contains("Base64"))
        assertTrue("诚实口径没变：看不见就说看不见", zh().contains("别假装看过"))
    }
}
