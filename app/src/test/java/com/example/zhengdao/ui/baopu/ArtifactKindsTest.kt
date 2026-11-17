// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。

package com.example.zhengdao.ui.baopu

import com.example.zhengdao.baopu.ArtifactFamily
import com.example.zhengdao.baopu.ArtifactKind
import com.example.zhengdao.baopu.ArtifactKinds
import com.example.zhengdao.baopu.DOC_ARTIFACT_EXTENSIONS
import com.example.zhengdao.baopu.IMAGE_ARTIFACT_EXTENSIONS
import com.example.zhengdao.baopu.TEXT_ARTIFACT_EXTENSIONS
import com.example.zhengdao.baopu.artifactFamilyOf
import com.example.zhengdao.baopu.selectArtifacts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 第六百八十五批 · 产物类型收口（修 681 交回的那一条）。
 *
 * ## 病
 *
 * 「台账收了 jpg／webp，点开却说『这类文件不能用其他应用打开』」。根因不是少两支 MIME：
 * 台账准入（`*_ARTIFACT_EXTENSIONS`）与打开映射（641 的 `ArtifactOpen.mimeOf`）各维护一份名单，
 * png 两边都有、jpg／webp 只有准入那一半有。
 *
 * ## 打法
 *
 * 1. **行为层**：真临时目录里造真文件，跑 `selectArtifacts`／`artifactOpenPlanOf`，
 *    断言 jpg／jpeg／webp 的 Intent 类型就是 image/jpeg、image/webp——纯源码字符串代替不了这一条；
 * 2. **不变式层**：`ArtifactKinds.selfCheck()` 为空（准入 ⊆ 打开）＋冷启处真的调了它（源码断言）；
 * 3. **反例层**：造一张「只有台账、没有 mime」的表，`admittedIn` 必须一个都不登记；
 * 4. **单一真源**：641 那三支 `when` 已并表，`ArtifactOpenPlan.kt` 里不许再有第二份映射。
 */
class ArtifactKindsTest {

    // ── 源码读取（工作目录 = app/；判据一律读剥掉注释后的文本）──────────────

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    private fun src(rel: String): String =
        repoFile(rel).readText(Charsets.UTF_8).replace("\r\n", "\n")

    private fun code(rel: String): String = src(rel)
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private fun plan() = code("src/main/java/com/example/zhengdao/ui/baopu/ArtifactOpenPlan.kt")
    private fun pure() = code("src/main/java/com/example/zhengdao/baopu/BaopuCostAndArtifacts.kt")
    private fun kinds() = code("src/main/java/com/example/zhengdao/baopu/ArtifactKinds.kt")
    private fun app() = code("src/main/java/com/example/zhengdao/ZhengdaoApp.kt")

    // ── 盘上现场 ──────────────────────────────────────────────────────────

    private lateinit var root: File

    @Before
    fun setUp() {
        root = File.createTempFile("zd685-ws", "").let { it.delete(); it.mkdirs(); it }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    /** 一份「像真的」二进制：签名 ＋ 压缩流（含 0，正是文本判据会判掉的那种）。 */
    private fun writeBin(rel: String, sig: ByteArray, size: Int = 2048): String {
        val f = File(root, rel)
        f.parentFile?.mkdirs()
        val rest = ByteArray(size - sig.size) { (it % 113).toByte() }
        if (rest.isNotEmpty()) rest[0] = 0
        f.writeBytes(sig + rest)
        return rel
    }

    private fun writePng(rel: String) =
        writeBin(rel, byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))

    /** JPEG：`FF D8 FF E0` 之后接 JFIF 头。 */
    private fun writeJpeg(rel: String) =
        writeBin(rel, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10, 0x4A, 0x46))

    /** WebP：`RIFF` ＋ 4 字节长度 ＋ `WEBP`。 */
    private fun writeWebp(rel: String) =
        writeBin(rel, byteArrayOf(0x52, 0x49, 0x46, 0x46, 0x00, 0x08, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50))

    private fun writeDocx(rel: String) =
        writeBin(rel, byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x06, 0x00))

    private fun specOf(rel: String): ArtifactOpenIntent {
        val plan = artifactOpenPlanOf(root, rel)
        assertTrue("$rel 必须能给出规格（拿到 $plan）", plan is ArtifactOpenPlan.Ready)
        return (plan as ArtifactOpenPlan.Ready).spec
    }

    // ── 判据①：jpg／jpeg／webp 点开走 image/jpeg、image/webp ──────────────────

    @Test
    fun `判据1_jpg与webp点开走image_JPEG与image_WEBP`() {
        val jpg = writeJpeg("图/甲.jpg")
        val jpeg = writeJpeg("图/乙.jpeg")
        val webp = writeWebp("图/丙.webp")

        // 前置：三份都真的进了台账（681 那一半本来就没问题，问题在另一半）
        assertEquals("三份都要进台账", listOf(jpg, jpeg, webp), selectArtifacts(root, listOf(jpg, jpeg, webp)))

        val cases = listOf(
            Triple(jpg, "jpg", ArtifactKinds.MIME_JPEG),
            Triple(jpeg, "jpeg", ArtifactKinds.MIME_JPEG),
            Triple(webp, "webp", ArtifactKinds.MIME_WEBP),
        )
        cases.forEach { (rel, ext, mime) ->
            assertEquals("$rel 的族是图片", ArtifactFamily.Image, artifactFamilyOf(rel))
            assertEquals("$rel 点开走外部应用", ArtifactRowTap.External, artifactRowTapOf(rel))
            val spec = specOf(rel)
            assertEquals("$rel 的 MIME（681 这里回的是 Unsupported）", mime, spec.mime)
            assertEquals("必须是 ACTION_VIEW", "android.intent.action.VIEW", spec.action)
            assertEquals("必须是 content://", "content", spec.scheme)
            assertEquals("authority 后缀要与清单一致", ".artifact.open", spec.authoritySuffix)
            assertTrue("必须给临时读授权", spec.grantRead)
            assertTrue("必须带 ClipData", spec.withClipData)
            assertEquals("$ext 的大小写不敏感", mime, ArtifactOpen.mimeOf("图/大写.${ext.uppercase()}"))
        }

        // 判别器：上面三条不是恒真——同一个函数对表外的 pdf 仍然回 Unsupported
        val pdf = writeBin("图/丁.pdf", byteArrayOf(0x25, 0x50, 0x44, 0x46))
        assertEquals("表外类型照样 Unsupported", ArtifactOpenPlan.Unsupported, artifactOpenPlanOf(root, pdf))
    }

    // ── 判据②：docx／xlsx／png 回归不变 ─────────────────────────────────────

    @Test
    fun `判据2_docx与xlsx与png回归不变`() {
        val docx = writeDocx("产物/方案.docx")
        val xlsx = writeDocx("表/统计.xlsx")
        val png = writePng("图/立绘.png")

        assertEquals("三份都要进台账", listOf(docx, xlsx, png), selectArtifacts(root, listOf(docx, xlsx, png)))
        assertEquals("docx 的 MIME 一字未动", ArtifactKinds.MIME_DOCX, specOf(docx).mime)
        assertEquals("xlsx 的 MIME 一字未动", ArtifactKinds.MIME_XLSX, specOf(xlsx).mime)
        assertEquals("png 的 MIME 一字未动", ArtifactKinds.MIME_PNG, specOf(png).mime)

        // 641 的公开常量仍是同一个值（调用点没改）
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", ArtifactOpen.MIME_DOCX)
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ArtifactOpen.MIME_XLSX)
        assertEquals("image/png", ArtifactOpen.MIME_PNG)

        // 文本族的外部打开口径不变：表里有 mime，但去处是内置查看器（641 的 Unsupported 照旧）
        assertNull("md 不走外部打开", ArtifactOpen.mimeOf("笔记.md"))
        assertEquals("md 点开还是内置查看器", ArtifactRowTap.Preview, artifactRowTapOf("笔记.md"))
        assertEquals("族仍是文本", ArtifactFamily.Text, artifactFamilyOf("笔记.md"))
    }

    // ── 判据③：台账准入 ⊆ 打开映射（自检 ＋ 源码断言）──────────────────────

    @Test
    fun `判据3_台账准入是打开映射的子集_自检为空且冷启真跑`() {
        // ① 不变式本体：自检一个违规都不许有
        assertEquals("自检必须为空：${ArtifactKinds.selfCheck()}", emptyList<String>(), ArtifactKinds.selfCheck())

        // ② 逐条走一遍：三族每个扩展名都得有去处
        (TEXT_ARTIFACT_EXTENSIONS + DOC_ARTIFACT_EXTENSIONS + IMAGE_ARTIFACT_EXTENSIONS).forEach { ext ->
            assertNotNull("$ext 没有去处（登记了就会点不开）", ArtifactKinds.routeOf(ext))
            assertTrue("$ext 必须被台账准入", ArtifactKinds.isAdmitted(ext))
        }

        // ③ 681 的病灶就在这一句：文档／图片族的每一个扩展名，「用其他应用打开」都要给得出类型
        (DOC_ARTIFACT_EXTENSIONS + IMAGE_ARTIFACT_EXTENSIONS).forEach { ext ->
            assertNotNull("$ext 进得了台账却打不开——这就是 681 那句「不支持」", ArtifactOpen.mimeOf("产物/样本.$ext"))
        }

        // ④ 集合关系本身：准入 ⊆ 打开映射
        val admitted = ArtifactKinds.admittedIn(ArtifactKinds.TABLE)
        val openable = ArtifactKinds.openableIn(ArtifactKinds.TABLE)
        assertTrue("准入必须是打开映射的子集：${admitted - openable}", openable.containsAll(admitted))
        assertEquals("准入就是表里那 14 条", 14, admitted.size)

        // ⑤ 冷启真的跑了自检（不然「启动自检」只是句注释）
        assertTrue("冷启必须调 selfCheck", app().contains("ArtifactKinds.selfCheck()"))
        assertTrue("自检结果必须留痕", app().contains("产物类型表自检失败"))

        // 判别器：自检抓得住坏表（否则 ① 就是空转）
        val broken = mapOf("jpg" to ArtifactKind(ArtifactFamily.Image, ""))
        assertTrue("坏表必须被判红", ArtifactKinds.selfCheck(broken).isNotEmpty())
    }

    // ── 判据④：表里查不到 mime 的扩展名 ⇒ 不登记 ───────────────────────────

    @Test
    fun `判据4_只有台账没有mime的扩展名一个都不登记`() {
        // 造一张「只有准入、没有 mime」的表：docx 是好的，foo 是 681 那种坏行
        val injected = mapOf(
            "docx" to ArtifactKind(ArtifactFamily.Doc, ArtifactKinds.MIME_DOCX),
            "foo" to ArtifactKind(ArtifactFamily.Doc, ""),
        )
        assertEquals("没有 mime 的那一行不许登记", setOf("docx"), ArtifactKinds.admittedIn(injected))
        assertNull("没有 mime 就没有去处", ArtifactKinds.routeOf(injected.getValue("foo")))
        assertTrue(
            "自检必须点名这一行：${ArtifactKinds.selfCheck(injected)}",
            ArtifactKinds.selfCheck(injected).any { it.contains("没有 mime") && it.contains("foo") },
        )

        // 真实那张表上的对照组：表外的后缀一律不登记（老台账里可能躺着的 .doc / .pdf / .zip）
        listOf("doc", "pdf", "zip", "bin", "").forEach { ext ->
            assertNull("$ext 不在表里", artifactFamilyOf("产物/样本.$ext"))
            assertFalse("$ext 不许被准入", ArtifactKinds.isAdmitted(ext))
        }
        val doc = writeBin("产物/旧版.doc", byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte()))
        assertTrue("表外文件即使真写出来了也不进台账", selectArtifacts(root, listOf(doc)).isEmpty())

        // 判别器：同一份东西换个表内的后缀就收（证明上面不是「什么都不收」）
        val docx = writeDocx("产物/新版.docx")
        assertEquals("表内后缀照收", listOf(docx), selectArtifacts(root, listOf(docx)))
    }

    // ── 判据⑤：不新增第二份映射（641 三支已并入表）────────────────────────

    @Test
    fun `判据5_全仓只有一张扩展名到类型的表`() {
        // ① 641 那三支 `when` 必须消失，只留一次查表
        val p = plan()
        assertEquals("plan 里不许再有 `-> MIME_` 分支", 0, Regex("->\\s*MIME_").findAll(p).count())
        assertEquals("plan 里不许再有 MIME 字面量", 0, Regex("\"(image|application|text)/").findAll(p).count())
        assertTrue("mimeOf 只做一次查表", p.contains("ArtifactKinds.openMimeOf"))
        assertTrue("MIME 常量是表的别名", p.contains("const val MIME_PNG = ArtifactKinds.MIME_PNG"))

        // ② 三份老名单不再各写一份，全部派生自那张表
        val u = pure()
        assertEquals("三份名单都派生自表", 3, Regex("ArtifactKinds\\.extensionsIn\\(").findAll(u).count())
        assertEquals("本文件里不许再出现 setOf(\"json\"", 0, Regex("setOf\\(\"json\"").findAll(u).count())
        assertEquals("族判定只查表", 1, Regex("ArtifactKinds\\.familyOf\\(").findAll(u).count())

        // ③ 表本身：14 条、键是小写无点、值都是三族之一
        assertEquals("表里 14 条", 14, ArtifactKinds.TABLE.size)
        ArtifactKinds.TABLE.forEach { (ext, kind) ->
            assertEquals("键必须小写无点：$ext", ext, ext.lowercase())
            assertFalse("键不许带点：$ext", ext.startsWith("."))
            assertTrue("族必须是三族之一：$ext", kind.family in ArtifactFamily.entries)
        }
        assertEquals("jpg 与 jpeg 同一个类型", ArtifactKinds.TABLE.getValue("jpg").mime, ArtifactKinds.TABLE.getValue("jpeg").mime)
        assertEquals("那张表只在 ArtifactKinds.kt 里出现一次", 1, Regex("val TABLE: Map<String, ArtifactKind>").findAll(kinds()).count())

        // 判别器：正则确实抓得住一段真映射（门禁不是空转）
        assertTrue(
            "判别器抓得住 -> MIME_ 分支",
            Regex("->\\s*MIME_").containsMatchIn("        \"png\" -> MIME_PNG"),
        )
    }
}
