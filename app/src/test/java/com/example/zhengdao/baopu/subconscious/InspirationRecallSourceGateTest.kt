// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百七十七批 · 源码级门禁。三条都能被"顺手加一行"破坏而编译不红、单测不红，
// 所以在源码层面钉死。每个判别器先喂**故意违规**的样本自证咬得住，再对真实源码
// 断言干净（防恒真绿灯——房型照 BackupSourceGateTest）。
//
// 钉的判据（派工单判据⑤与其余硬线）：
// ⑤ **没有第二个执行器/调度器**：subconscious 包零调度构造、零 *Runner 类；
//    日桶复用 PatrolScheduler（不重造第二把日期尺）；
//    676 未合入 ⇒ 适配层全靠本包三文件，包外不许再长出灵感执行器；
// ③ **不点不进上下文**：InspirationMailbox.offer 全仓唯一调用点＝点开卡那处；
//    引擎拼提示词侧恰一处 drain；
// **无第二浮出入口**：InspirationRowStore.publish 全仓只在控制器（一轮一 publish）；
// **L0 只追加**：本包零删除 API（原文层永不自动删）。
package com.example.zhengdao.baopu.subconscious

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class InspirationRecallSourceGateTest {

    private fun projectFile(rel: String): File {
        val cwd = File(".").canonicalFile
        return listOf(File(cwd, rel), File(cwd.parentFile, rel), File(cwd, "app/$rel"))
            .firstOrNull { it.exists() } ?: error("找不到 $rel（cwd=${cwd.absolutePath}）")
    }

    /** 本包相对 src/main/java/com/example/zhengdao 的路径前缀（键保留完整相对路径）。 */
    private val PKG_PREFIX = "baopu/subconscious/"

    /** src/main/java 下全部 Kotlin 源（walk 一次缓存；判别器都在它上面跑）。 */
    private val allSources: List<Pair<String, String>> by lazy {
        val root = projectFile("src/main/java/com/example/zhengdao")
        root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }
            .map { it.relativeTo(root).path.replace('\\', '/') to it.readText() }
            .toList()
    }

    /** 只删整行注释与块注释（字符串里的英文单词要保留）。 */
    private fun stripComments(src: String): String = src
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .lines()
        .filterNot { it.trimStart().startsWith("//") }
        .joinToString("\n")

    private val pkgSources: Map<String, String> by lazy {
        allSources.filter { it.first.startsWith(PKG_PREFIX) }.toMap()
    }

    // ── 判别器（先自证咬得住） ────────────────────────────────────────────────

    /** 判别器：调度构造（线程/定时器/协程循环）命中行。 */
    private fun schedulingLine(src: String): String? {
        val keys = listOf(
            "Thread(", "Timer(", "ScheduledExecutor", "HandlerThread", "CoroutineScope(",
            "delay(", "while (true)", "while(true)", "Executors.new", "fixedRate", "fixedDelay",
        )
        return stripComments(src).lines().firstOrNull { line -> keys.any { line.contains(it) } }
    }

    /** 判别器：删除 API（L0 永不自动删）命中行。 */
    private fun deleteLine(src: String): String? = stripComments(src).lines().firstOrNull {
        it.contains(".delete()") || it.contains(".deleteRecursively()") ||
            it.contains("deleteOnExit()") || it.contains(".delete(")
    }

    @Test
    fun 判别器咬得住调度构造() {
        assertNotNull(
            "判别器必须咬住违规样本",
            schedulingLine("val t = Thread(runnable)\nval ok = 1"),
        )
        assertNull(
            "「Thread-pool」这种只是名字像的行不该命中（判别器认调用不是认词）",
            schedulingLine("val name = \"Thread-pool\"\nval x = 1"),
        )
    }

    @Test
    fun 判别器咬得住删除API() {
        assertNotNull("判别器必须咬住违规样本", deleteLine("file.delete()"))
        assertNull("正常源码不该命中", deleteLine("dir.mkdirs()\nfile.writeText(x)\n"))
    }

    // ── 判据⑤：没有第二个执行器/调度器 ───────────────────────────────────────

    @Test
    fun 本包零调度构造_执行节奏归676() {
        assertTrue("适配层至少要落了三个文件", pkgSources.size >= 3)
        pkgSources.forEach { (name, src) ->
            assertNull("subconscious/$name 不许自带调度（低频回响归 676 执行器）", schedulingLine(src))
        }
    }

    @Test
    fun 本包无第二执行器类() {
        // 676 合入后口径改成「恰好一个执行器、且必须是 676 的」——判据更精确，牙没掉：
        // 677 自己若长出执行器 ⇒ 下面 second 非空 ⇒ 红。
        val runners = pkgSources.mapNotNull { (name, src) ->
            val decl = stripComments(src).lines().firstOrNull {
                Regex("(class|object)\\s+\\w*Runner").containsMatchIn(it)
            }
            decl?.let { name }
        }
        assertEquals(
            "本包执行器恰好一个（676 的 SubconsciousRunner）",
            listOf(PKG_PREFIX + "SubconsciousRunner.kt"),
            runners,
        )
        assertNull(
            "677 的适配层文件不许带 Runner",
            pkgSources.filterKeys { it != PKG_PREFIX + "SubconsciousRunner.kt" }.values
                .mapNotNull { src ->
                    stripComments(src).lines().firstOrNull {
                        Regex("(class|object)\\s+\\w*Runner").containsMatchIn(it)
                    }
                }
                .firstOrNull(),
        )
    }

    @Test
    fun 日桶复用PatrolScheduler_不重造日期尺() {
        val recall = pkgSources[PKG_PREFIX + "InspirationRecall.kt"] ?: error("InspirationRecall.kt 没找到")
        assertTrue("≤1 次/天必须复用 PatrolScheduler.dayBucket", recall.contains("PatrolScheduler.dayBucket"))
        val own = stripComments(recall).lines().firstOrNull { it.contains("toEpochDay") }
        assertNull("不许自造第二把日期尺（toEpochDay）", own)
    }

    @Test
    fun 全仓没有别的文件长出灵感执行器() {
        val outside = allSources.filter { !it.first.startsWith(PKG_PREFIX) }
            .filter { (_, src) ->
                stripComments(src).contains("runInspirationRecall") ||
                    stripComments(src).contains("InspirationGates.judge")
            }
            .map { it.first }
        assertTrue(
            "判据本体只许在 subconscious 包里被调（$outside）",
            outside.all { it == "ui/baopu/BaopuChatController.kt" || it == "agent/RunEngine.kt" },
        )
    }

    // ── 判据③：不点不进上下文（offer 唯一放行口；drain 唯一拼装口） ─────────────

    @Test
    fun 信箱offer全仓唯一调用点_点开卡那处() {
        val callers = allSources
            .filter { (_, src) -> stripComments(src).contains("InspirationMailbox.offer") }
            .map { it.first }
        assertTrue(
            "offer 只许出现在控制器点开分支（现在：$callers）",
            callers.all { it == "ui/baopu/BaopuChatController.kt" },
        )
        val body = stripComments(pkgSources[PKG_PREFIX + "InspirationRecall.kt"] ?: error("缺主文件"))
        assertTrue("信箱定义必须在本包", body.contains("fun offer(text: String)"))
    }

    @Test
    fun 引擎拼提示词侧恰一处drain() {
        val engine = File(projectFile("src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt").canonicalPath)
        val text = engine.readText()
        assertEquals(
            "extraNotes 链里 InspirationMailbox.drain() 恰一次（多一处＝重放口）",
            1,
            Regex(Regex.escape("InspirationMailbox.drain()")).findAll(text).count(),
        )
    }

    // ── 无第二浮出入口 ────────────────────────────────────────────────────────

    @Test
    fun 行存储publish全仓只在控制器() {
        val writers = allSources
            .filter { (_, src) -> stripComments(src).contains("InspirationRowStore.publish") }
            .map { it.first }
        assertTrue(
            "publish 只许在控制器（一轮一 publish；现在：$writers）",
            writers.all { it == "ui/baopu/BaopuChatController.kt" },
        )
    }

    // ── L0 只追加：本包零删除 API ─────────────────────────────────────────────

    @Test
    fun L0原文层无删除路径() {
        pkgSources.forEach { (name, src) ->
            assertNull("subconscious/$name 是 L0 原文层/账面——不许有删除 API", deleteLine(src))
        }
    }

    // ── 适配层与 676 约定的接口形状在（回归锚） ────────────────────────────────

    @Test
    fun 来源缝与选择器口存在() {
        val recall = pkgSources[PKG_PREFIX + "InspirationRecall.kt"] ?: error("InspirationRecall.kt 没找到")
        assertTrue("676 执行器消费的来源缝必须立着", recall.contains("interface SubconsciousSource"))
        assertTrue("模型只选不造的选择口必须立着", recall.contains("fun interface InspirationSelector"))
        assertTrue("候选外 id 整条丢弃（625 口径）", recall.contains("intersect(known)"))
        assertTrue("候选喂入有上限", recall.contains("MAX_CANDIDATES"))
    }
}
