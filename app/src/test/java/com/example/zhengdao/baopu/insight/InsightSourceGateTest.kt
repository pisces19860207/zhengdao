// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百三十四批 · 源码级门禁（判据②④⑥ + 边界）。
// 房型照 677 InspirationRecallSourceGateTest：每个判别器先喂**故意违规**样本自证
// 咬得住，再对真实源码断言干净（防恒真绿灯），带负例。
package com.example.zhengdao.baopu.insight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class InsightSourceGateTest {

    private fun projectFile(rel: String): File {
        val cwd = File(".").canonicalFile
        return listOf(File(cwd, rel), File(cwd.parentFile, rel), File(cwd, "app/$rel"))
            .firstOrNull { it.exists() } ?: error("找不到 $rel（cwd=${cwd.absolutePath}）")
    }

    private val PKG = "baopu/insight/"

    /** src/main/java 下全部 Kotlin 源（判别器在它上面跑）。 */
    private val allSources: List<Pair<String, String>> by lazy {
        val root = projectFile("src/main/java/com/example/zhengdao")
        root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }
            .map { it.relativeTo(root).path.replace('\\', '/') to it.readText() }
            .toList()
    }

    private val pkgSources: Map<String, String> by lazy {
        allSources.filter { it.first.startsWith(PKG) }.toMap()
    }

    /** 只删整行注释与块注释（字符串里的词保留）。 */
    private fun stripComments(src: String): String = src
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .lines().filterNot { it.trimStart().startsWith("//") }
        .joinToString("\n")

    // ── 判别器（先自证咬得住） ──────────────────────────────────────────────

    /** 判据②：自建向量/嵌入的 API 与标识符。 */
    private fun embeddingLine(src: String): String? {
        val keys = listOf(
            "embedding", "Embedding", "vector", "Vector", "cosine", "Cosine",
            "余弦", "向量", "相似度矩阵", "word2vec", "sentenceBERT", "tfidf", "TfIdf",
        )
        val code = stripComments(src)
        return code.lines().firstOrNull { line -> keys.any { line.contains(it) } }
    }

    /** 调度构造（不做自动执行）。 */
    private fun schedulingLine(src: String): String? {
        val keys = listOf(
            "Thread(", "Timer(", "ScheduledExecutor", "HandlerThread", "CoroutineScope(",
            "delay(", "while (true)", "while(true)", "Executors.new", "fixedRate", "fixedDelay",
        )
        return stripComments(src).lines().firstOrNull { line -> keys.any { line.contains(it) } }
    }

    @Test
    fun `判别器咬得住_embedding`() {
        assertNotNull(embeddingLine("val v = embeddingOf(text)"))
        assertNotNull(embeddingLine("val c = cosineSimilarity(a, b)"))
        assertNull("正常源码不该命中", embeddingLine("val n = index.search(q, 20)"))
    }

    @Test
    fun `判别器咬得住_调度`() {
        assertNotNull(schedulingLine("val t = Thread(runnable)"))
        assertNull("只是名字像不算", schedulingLine("val name = \"Thread-pool\""))
    }

    // ── 判据②：关联只读既有索引，无自建 embedding ────────────────────────────

    @Test
    fun `判据2_本包零自建embedding_向量库`() {
        assertTrue("insight 包至少落了六件生产文件", pkgSources.size >= 6)
        pkgSources.forEach { (name, src) ->
            assertNull("insight/$name 不许自建向量/嵌入（判据②）", embeddingLine(src))
        }
    }

    @Test
    fun `判据2_关联只调既有索引search_digest_status`() {
        val assoc = pkgSources[PKG + "InsightAssociate.kt"] ?: error("缺 InsightAssociate.kt")
        val code = stripComments(assoc)
        assertTrue("只读既有 search", code.contains("index.search("))
        assertTrue("只读既有 digest", code.contains("index.digest("))
        assertTrue("只读既有 status", code.contains("index.status()"))
        // 不 import msgsearch（那是 540 唯一转接层的事），不自建索引库类。
        assertNull("不许直接 import 533 的实现", code.lines().firstOrNull { it.contains("baopu.msgsearch") })
        assertNull("不许自建索引类", code.lines().firstOrNull { Regex("(class|object)\\s+\\w*Index\\b").containsMatchIn(it) })
    }

    // ── 判据④：与 625 共用 runner 与去重表 ──────────────────────────────────

    @Test
    fun `判据4_共用runner_本包零Runner类零调度`() {
        pkgSources.forEach { (name, src) ->
            assertNull("insight/$name 不许自带调度", schedulingLine(src))
            assertNull(
                "insight/$name 不许另造执行器（复用 625 SubconsciousRunner）",
                stripComments(src).lines().firstOrNull { Regex("(class|object)\\s+\\w*Runner").containsMatchIn(it) },
            )
        }
        val wake = pkgSources[PKG + "InsightWake.kt"] ?: error("缺 InsightWake.kt")
        // 625 的门禁（HabitSourceGateTest）要求 `SubconsciousRunner.run(` 全仓**只许一处**，
        // 所以 wake 只能经唯一入口 `runSubconsciousOnce(` 进执行器，不许直调。
        // 2026-11-26 修 ci #2173/#2174：534 起初直调 ⇒ 两条门禁互相打架；现在直调只剩装配层那一处。
        val wakeCode = stripComments(wake)
        assertTrue("wake 必须经唯一入口调 625 执行器", wakeCode.contains("runSubconsciousOnce("))
        assertFalse("wake 不许直调 SubconsciousRunner.run（625 判据②：全仓唯一入口）", wakeCode.contains("SubconsciousRunner.run("))
    }

    @Test
    fun `判据4_候选id用echo前缀_去重键指回锚点`() {
        val wake = stripComments(pkgSources[PKG + "InsightWake.kt"] ?: error("缺主文件"))
        assertTrue("id 用 echo:<ref> 让 625 去重表读回", wake.contains("id = \"echo:\${a.thought.anchor.ref}\""))
        assertTrue("去重键＝锚点 ref", wake.contains("ledgerId = a.thought.anchor.ref"))
        assertTrue("传 625 的去重表进 runner", wake.contains("suggestedAtByKey = suggestedAtByKey"))
    }

    // ── 判据③：唯一 offer 点 + 唯一 drain 点 ────────────────────────────────

    @Test
    fun `判据3_offer全仓唯一调用点在装配层`() {
        val callers = allSources.filter { (_, src) -> stripComments(src).contains("InsightMailbox.offer") }
            .map { it.first }
        assertEquals(
            "offer 只许在 InsightWiring.onInsightOpened（点开那一次）",
            listOf(PKG + "InsightWiring.kt"),
            callers,
        )
    }

    @Test
    fun `判据3_引擎拼提示词侧恰一处drain`() {
        val engine = projectFile("src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt")
        assertEquals(
            "InsightMailbox.drain() 恰一次（空表 ⇒ 逐字节一致）",
            1,
            Regex(Regex.escape("InsightMailbox.drain()")).findAll(engine.readText()).count(),
        )
    }

    // ── 边界：不改 677/625/540 三包 ────────────────────────────────────────

    @Test
    fun `边界_不调用677判据本体_不写540转接层`() {
        val wake = stripComments(pkgSources[PKG + "InsightWake.kt"].orEmpty())
        assertNull("不许调 677 的灵感判据本体", wake.lines().firstOrNull {
            it.contains("runInspirationRecall") || it.contains("InspirationGates.judge")
        })
    }

    @Test
    fun `判据6_关时只记不唤_不查索引不offer`() {
        val wiring = stripComments(pkgSources[PKG + "InsightWiring.kt"] ?: error("缺装配文件"))
        assertTrue("关时提前返回空轮", wiring.contains("if (!enabled)"))
        assertTrue("关时 note 明说", wiring.contains("只记不唤"))
    }
}
