// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百九十七批 · 判据⑥的**红线门禁**：立绘这条线只做"图片里的颜色/比例/取景"，
// **不做人脸识别、不做人脸模板、不做身份比对**——这不是"以后再说"，是本单的边界本体。
//
// 为什么用源码形状门禁而不是行为用例：要钉的是"这条线不存在第二条路"——行为用例只能
// 证明写过的路径对，证明不了别处没有暗仓。同款范式见 ModelRoutingGateTest（G1 反向门禁）/
// ChannelSwitchTruthGateTest。三条断言：
// ① 生产源码（app/core/native-core 的 main）剥注释后 **0 命中**（注释里的讨论不算引用，
//   代码里出现才算）；
// ② 依赖面（五个 build.gradle.kts ＋ libs.versions.toml ＋ rust 全部 Cargo.toml ＋
//   THIRD-PARTY-LICENSES.md ＋ licenses/*.md）**0 命中**——不引第三方人脸 SDK；
// ③ 门禁自检：词表对假目标**必须**命中（防词表写错导致门禁永远绿——空洞的门禁比没有更坏）。
package com.example.zhengdao.baopu.portrait

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortraitRedLineGateTest {

    // ── 禁止词表（判据⑥的口径；改这里＝改红线口径，要走批）────────────────

    /** 中文红线词：人脸（识别/模板都在内）、身份比对、生物识别。 */
    private val cnTerms = Regex("人脸|身份比对|生物识别")

    /** 英文红线词：人脸 SDK/算法族名（词边界防误伤 interface/surface/Typeface）。 */
    private val enTerms = Regex(
        "(?i)\\bface[-_. ]?(detect|recognition|recog|verif|template|net|mesh|landmark|embedding)\\b" +
            "|facenet|insightface|arcface|seetaface|deepface",
    )

    /** 依赖面加扫：常见人脸检测 SDK 的坐标词。 */
    private val depTerms = Regex(
        "(?i)face[-_. ]?(detect|recognition|recog|verif|template)|facenet|insightface|arcface" +
            "|seetaface|deepface|face_recognition|mlkit|ml-kit|play-services-vision|play-services-mlkit",
    )

    // ── 扫描范围 ────────────────────────────────────────────────────────────

    /** 单测工作目录可能是仓根或 app/，两处都认（ModelRoutingGateTest 同款）。 */
    private fun repoFile(rel: String): File = File(rel).let { if (it.exists()) it else File("app/$rel") }

    private fun mainSourceFiles(): List<File> = listOf(
        "src/main/java", "src/main/kotlin",
    ).flatMap { dirOf("app", it) + dirOf("core", it) + dirOf("native-core", it) }

    private fun dirOf(module: String, dir: String): List<File> {
        val f = repoFile("$module/$dir")
        return if (!f.isDirectory) emptyList() else f.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun dependencyFiles(): List<File> = listOf(
        "build.gradle.kts",
        "app/build.gradle.kts",
        "core/build.gradle.kts",
        "native-core/build.gradle.kts",
        "macrobenchmark/build.gradle.kts",
        "gradle/libs.versions.toml",
        "THIRD-PARTY-LICENSES.md",
        "licenses/FIRST-PARTY.md",
        "licenses/ASSETS.md",
    ).map { repoFile(it) }.filter { it.isFile } +
        (repoFile("rust").takeIf { it.isDirectory }?.walkTopDown()
            ?.filter { it.isFile && it.name == "Cargo.toml" }?.toList() ?: emptyList())

    /** 去注释后的代码文本（注释里的纪律声明/讨论不算引用；ModelRoutingGateTest 同款）。 */
    private fun codeOnly(src: String): String = src.split("\n").joinToString("\n") { it.substringBefore("//") }
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")

    // ── ①②③ ───────────────────────────────────────────────────────────────

    @Test
    fun `判据六_生产源码零人脸词`() {
        val hits = mainSourceFiles()
            .flatMap { f ->
                val code = codeOnly(f.readText(Charsets.UTF_8))
                listOfNotNull(
                    cnTerms.find(code)?.let { "中文:${it.value}" },
                    enTerms.find(code)?.let { "英文:${it.value}" },
                ).map { "${f.path} → $it" }
            }
        assertEquals(
            "红线⑥：生产代码出现人脸词（允许=0）：$hits",
            emptyList<String>(),
            hits,
        )
    }

    @Test
    fun `判据六_依赖与许可零人脸SDK`() {
        val hits = dependencyFiles().flatMap { f ->
            val text = f.readText(Charsets.UTF_8)
            listOfNotNull(
                depTerms.find(text)?.let { "依赖:${it.value}" },
                cnTerms.find(text)?.let { "中文:${it.value}" },
            ).map { "${f.path} → $it" }
        }
        assertEquals("红线⑥：依赖/许可出现人脸 SDK（允许=0）：$hits", emptyList<String>(), hits)
    }

    @Test
    fun `门禁自检_词表必须抓得到假目标_防空洞`() {
        // 中文三条都要能抓
        assertTrue(cnTerms.containsMatchIn("做人脸识别"))
        assertTrue(cnTerms.containsMatchIn("身份比对通过"))
        assertTrue(cnTerms.containsMatchIn("调用生物识别"))
        // 英文词边界：SDK 名抓得到；interface/surface/Typeface 不许误伤
        assertTrue(enTerms.containsMatchIn("use FaceDetect()"))
        assertTrue(enTerms.containsMatchIn("import FaceNet"))
        assertTrue(enTerms.containsMatchIn("face_template"))
        assertTrue(enTerms.containsMatchIn("InsightFace"))
        assertFalse("词边界不误伤 interface", enTerms.containsMatchIn("val interface"))
        assertFalse("词边界不误伤 surface", enTerms.containsMatchIn("drawSurfaceTexture"))
        assertFalse("词边界不误伤 Typeface", enTerms.containsMatchIn("Typeface.DEFAULT"))
        // 依赖词表
        assertTrue(depTerms.containsMatchIn("implementation \"com.google.mlkit:face-detection\""))
        assertTrue(depTerms.containsMatchIn("face_recognition = \"0.1\""))
        // 本项目自己的词不许被抓（认脸=格式识别的仓内黑话，不是人脸）
        assertFalse(cnTerms.containsMatchIn("认脸结果"))
    }
}
