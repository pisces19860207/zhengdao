// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 判据：派工单-2026-10-21-第一百二十八批 §3 的负例，源码门禁侧：
// - N1「上传云端」：`baopu/kb/**` 与 `KbTools.kt` 不许出现任何网络/上传类 import；
// - N2「无来源编引用」：kb_search 的输出每段必须带「来源：」字样（行为侧在
//   KbRetrieverTest／KbToolsTest，这里补一条**渲染函数恒非空**的源码级钉子）；
// - N3「全盘自动扫描」：缺省目录常量必须是 资料库/投递，不是工作区根。
// 口径沿用本仓老纪律：**先剥注释再断言**（注释里提一嘴不算过）。
package com.example.zhengdao.baopu.kb

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KbGuardTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    /** 剥掉块注释与行注释再断言（源码门禁的老口径：注释里出现不算过）。 */
    private fun code(rel: String): String = repoFile(rel).readText()
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private val kbSources = listOf(
        "src/main/java/com/example/zhengdao/baopu/kb/KbTypes.kt",
        "src/main/java/com/example/zhengdao/baopu/kb/KbChunker.kt",
        "src/main/java/com/example/zhengdao/baopu/kb/KbIndexer.kt",
        "src/main/java/com/example/zhengdao/baopu/kb/KbIndexCodec.kt",
        "src/main/java/com/example/zhengdao/baopu/kb/KbIndexStore.kt",
        "src/main/java/com/example/zhengdao/baopu/kb/KbRetriever.kt",
        "src/main/java/com/example/zhengdao/baopu/kb/KbAutoInject.kt",
        "src/main/java/com/example/zhengdao/baopu/agent/tools/KbTools.kt",
    )

    /** N1：网络/上传类 import 一律不许出现（索引只进本地，别说不小心接一条）。 */
    private val networkTokens = listOf(
        "import okhttp3", "import java.net", "HttpURLConnection", "import retrofit",
        "URLConnection", "Socket(", "import javax.net", "upload", "Upload",
    )

    @Test
    fun `N1_知识库代码零网络上传通路`() {
        for (rel in kbSources) {
            val code = code(rel)
            for (token in networkTokens) {
                assertFalse("$rel 不许出现网络/上传类记号「$token」（索引只进本地）", code.contains(token))
            }
        }
    }

    /** N2：引用渲染函数必须恒产「来源：」字样（黑掉它 ⇒ 本条约红）。 */
    @Test
    fun `N2_引用渲染恒带来源字样`() {
        val code = code("src/main/java/com/example/zhengdao/baopu/kb/KbRetriever.kt")
        assertTrue("renderCitation 必须拼「来源：」", code.contains("【来源："))
        // 无来源块在检索入口就被滤掉（relPath.isNotBlank 判据要在）
        assertTrue("search 必须滤掉无来源块", code.contains("relPath.isNotBlank()"))
    }

    /** N3：缺省索引目录必须是 资料库/投递，不许是工作区根。 */
    @Test
    fun `N3_缺省目录是投递不是根`() {
        val code = code("src/main/java/com/example/zhengdao/baopu/agent/tools/KbTools.kt")
        assertTrue(
            "DEFAULT_DIR_REL 必须由 资料库/投递 两级拼成",
            code.contains("LibraryPaths.DIR_LIBRARY + \"/\" + LibraryPaths.SUB_DELIVER"),
        )
        assertFalse("缺省目录不许直接是工作区根", code.contains("DEFAULT_DIR_REL: String = \".\""))
    }

    /** 索引只落 App 私有目录（壳里出现 filesDir，别处不许拼绝对落盘路径）。 */
    @Test
    fun `索引落点只在壳里`() {
        val store = code("src/main/java/com/example/zhengdao/baopu/kb/KbIndexStore.kt")
        assertTrue("壳必须收 filesDir", store.contains("filesDir"))
        // 纯逻辑文件不许自己碰 File 写盘（写盘口只有壳）
        for (rel in listOf(
            "src/main/java/com/example/zhengdao/baopu/kb/KbChunker.kt",
            "src/main/java/com/example/zhengdao/baopu/kb/KbRetriever.kt",
        )) {
            val code = code(rel)
            assertFalse("$rel 是纯逻辑，不许写盘", code.contains("writeText") || code.contains("FileOutputStream"))
        }
    }
}
