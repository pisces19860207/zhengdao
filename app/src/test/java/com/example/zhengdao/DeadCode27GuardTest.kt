// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第二十七批「死函数清理」的**结论守卫**。
 *
 * ## 为什么要有这个文件
 *
 * 这批的交付物不是功能，是**给每个死函数一个说法**：接上 / 删（进清单）/ 留（写原因）。
 * 说法的载体是代码注释（`保留原因：…` / `待删（第二十七批清单）`）。而注释最容易在
 * 后续批次里被"顺手清理掉"——一旦没了，「为什么这个函数还在」就又要从头查一遍，
 * 下一次审计还会原样报一遍。
 *
 * 所以这里把三件事钉成机器判据：
 * 1. 每个判「留」的函数，定义前必须有 `保留原因（第二十七批审计`；
 * 2. 每个判「删」的函数，定义前必须有 `待删（第二十七批清单`，**且函数仍然存在**
 *    （本单只出清单不动手——不许手滑删掉，那是用户确认后才做的事）；
 * 3. 判「接上」的 `FaceIndex.sortKey` 真的被调用（内联复制那份必须消失）。
 *
 * 判据 2 的后半句（"仍然存在"）看着反直觉，但它防的是最坏情况：有人把这批当成
 * "顺手清理"，在没确认的情况下把函数删了——那会让报告的清单与代码脱节。
 */
class DeadCode27GuardTest {

    private fun appFile(rel: String): File =
        File(rel).let { if (it.exists()) it else File("app/$rel") }

    private fun read(rel: String): String = appFile(rel).readText()

    private val knowledgeBase: String
        get() = read("src/main/java/com/example/zhengdao/terminal/KnowledgeBase.kt")

    private val terminalPrefs: String
        get() = read("src/main/java/com/example/zhengdao/terminal/TerminalPrefs.kt")

    private val chapterDigest: String
        get() = read("src/main/java/com/example/zhengdao/baopu/library/ChapterDigest.kt")

    private val faceIndex: String
        get() = read("src/main/java/com/example/zhengdao/baopu/library/FaceIndex.kt")

    /** 取某个 `fun 名字(` 定义之前的一段（默认 900 字符，够装一段 KDoc）。 */
    private fun kdocBefore(src: String, signature: String, window: Int = 900): String {
        val i = src.indexOf(signature)
        assertTrue("找不到定义：$signature（被删了？本单不许删）", i >= 0)
        return src.substring((i - window).coerceAtLeast(0), i)
    }

    // ── 判「留」的三个：必须有保留原因 ───────────────────────────────────────

    @Test
    fun `留_KnowledgeBase三件都写了保留原因`() {
        listOf(
            "fun listRaw(ctx: Context)",
            "fun writeSummaries(ctx: Context",
            "fun markBusy(ctx: Context)",
        ).forEach { sig ->
            val doc = kdocBefore(knowledgeBase, sig)
            assertTrue(
                "$sig 判了「留」，但 KDoc 里没有「保留原因（第二十七批审计」——" +
                    "没有原因的保留就是装样子，下次审计还得重查一遍",
                doc.contains("保留原因（第二十七批审计"),
            )
        }
    }

    @Test
    fun `留_保留原因必须写出存亡条件`() {
        // 光说"以后可能要"不算原因；必须给一个可判定的条件（谁来做、不做怎么办）
        listOf("fun listRaw(ctx: Context)", "fun writeSummaries(ctx: Context", "fun markBusy(ctx: Context)")
            .forEach { sig ->
                val doc = kdocBefore(knowledgeBase, sig)
                assertTrue(
                    "$sig 的保留原因缺少「一同存亡条件」（不说清什么情况下删，等于永久搁置）",
                    doc.contains("一同存亡条件"),
                )
            }
    }

    // ── 判「删」的三个：必须标待删，且仍存在 ─────────────────────────────────

    @Test
    fun `删_TerminalPrefs两个写函数标了待删且还在`() {
        listOf("fun saveScheme(ctx: Context", "fun saveInset(ctx: Context").forEach { sig ->
            val doc = kdocBefore(terminalPrefs, sig)
            assertTrue("$sig 判了「删」，KDoc 里必须标「待删（第二十七批清单」", doc.contains("待删（第二十七批清单"))
            assertTrue(
                "$sig 的待删说明必须点出**为什么不该再接**（否则下一个人会把它接上）",
                doc.contains("不该再接"),
            )
        }
    }

    @Test
    fun `删_indexedCount标了待删且还在`() {
        val doc = kdocBefore(chapterDigest, "fun indexedCount(book: String)")
        assertTrue("indexedCount 判了「删」，必须标待删", doc.contains("待删（第二十七批清单"))
        assertTrue(
            "必须写清连带的死 SQL（Sql.indexedChapterCount），否则删一半留一半",
            doc.contains("indexedChapterCount"),
        )
    }

    @Test
    fun `删_只标不改_函数体仍在`() {
        // 本单铁律：删只出清单、不动手。函数体必须还在（不是只留注释）。
        assertTrue(knowledgeBase.contains("fun markBusy(ctx: Context) {"))
        assertTrue(knowledgeBase.contains("fun writeSummaries(ctx: Context, summaries: Map<String, String>): Boolean {"))
        assertTrue(knowledgeBase.contains("fun listRaw(ctx: Context): List<Pair<String, Long>> ="))
        assertTrue(terminalPrefs.contains("fun saveScheme(ctx: Context, scheme: Scheme) {"))
        assertTrue(terminalPrefs.contains("fun saveInset(ctx: Context, dp: Int) {"))
        assertTrue(chapterDigest.contains("fun indexedCount(book: String): Int = db.read { d ->"))
    }

    // ── 判「接上」的：真的接上了 ─────────────────────────────────────────────

    @Test
    fun `接上_FaceIndex排序键被两处调用点共用`() {
        val uses = Regex("""sortedWith\(sortKey\)""").findAll(faceIndex).count()
        assertEquals(
            "dedup 与 render 都必须用 sortKey——少一处就还是各写一遍",
            2,
            uses,
        )
    }

    @Test
    fun `接上_内联复制的排序表达式只剩定义那一处`() {
        val inline = Regex("""compareBy\(\{ it\.name \}, \{ it\.sha256 \}\)""").findAll(faceIndex).count()
        assertEquals(
            "排序表达式只许出现在 sortKey 的定义里；两处内联复制必须消失（否则改规则会漏）",
            1,
            inline,
        )
    }

    @Test
    fun `接上_排序键不再是无用参数的函数`() {
        assertTrue("sortKey 必须是 val（它是常量，不是函数）", faceIndex.contains("val sortKey: Comparator<Item>"))
        // 只查**代码**：KDoc 里那句"原来这里是 fun sortKey(item: Item)"是在记历史，
        // 算进去就变成"不许在文档里提旧签名"，那不是这条判据想说的事。
        val code = faceIndex
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .lines().joinToString("\n") { it.substringBefore("//") }
        assertFalse(
            "旧签名 fun sortKey(item: Item) 的参数从头到尾没被用过，必须消失（代码里）",
            code.contains("fun sortKey(item: Item)"),
        )
    }

    // ── 清单完整性：六项都要有说法 ───────────────────────────────────────────

    @Test
    fun `清单六项在代码里各有归属`() {
        // 判「留」3 + 判「删」3（saveScheme/saveInset/indexedCount）= 6；
        // 第 7 项 navigateActive 属浏览器线、本单只登记不碰，故不在此断言。
        val kept = Regex("保留原因（第二十七批审计").findAll(knowledgeBase).count()
        assertEquals("判「留」的必须是 3 项", 3, kept)

        val doomed = Regex("待删（第二十七批清单").findAll(terminalPrefs).count() +
            Regex("待删（第二十七批清单").findAll(chapterDigest).count()
        assertEquals("判「删」的必须是 3 项（TerminalPrefs 2 ＋ ChapterDigest 1）", 3, doomed)
    }
}
