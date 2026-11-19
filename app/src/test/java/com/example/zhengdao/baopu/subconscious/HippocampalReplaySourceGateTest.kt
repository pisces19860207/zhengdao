// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百七十八批 · 源码级门禁。这些判据都能被"顺手加一行"破坏而编译不红、单测不红，
// 所以在源码层面钉死。每个判别器先喂**故意违规**的样本自证咬得住，再对真实源码
// 断言干净（防恒真绿灯——房型照 677 InspirationRecallSourceGateTest／BackupSourceGateTest）。
//
// 钉的判据（派工单 §二）：
//   ③ **不自动合并/不改记忆正文（只写建议区）**：本包零记忆写口（MemoryEntry 构造、
//      MemoryStore 写路径、删除 API 全禁）；自身产出只许行槽 publish／本账 append／状态文件；
//   ⑤ **复用 676 执行器（没有第二个调度器）**：本包零调度构造、零 Runner 类；
//      日桶复用 PatrolScheduler；夜间边界复用 SessionShape；
//   ⑥ **不引向量检索**（485：结构化过滤＋中文 BM25）＋ **与形神不复制数据**；
//   694 缝：海马·情景键形状（{sessionId, seq, hint}）——第七百一十四批已整缝交接
//   （G-708-2：键定义全仓只此一处＝bio/Hippocampus.EpisodeKey，seq Int；本包不自造键）。
package com.example.zhengdao.baopu.subconscious

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HippocampalReplaySourceGateTest {

    private fun projectFile(rel: String): File {
        val cwd = File(".").canonicalFile
        return listOf(File(cwd, rel), File(cwd.parentFile, rel), File(cwd, "app/$rel"))
            .firstOrNull { it.exists() } ?: error("找不到 $rel（cwd=${cwd.absolutePath}）")
    }

    /** 本单三件生产文件的相对路径（strings 是 XML 不在此扫）。 */
    private val replaySources: List<String> = listOf(
        "baopu/subconscious/HippocampalReplay.kt",
        "baopu/subconscious/ReplayStores.kt",
        "baopu/subconscious/ReplayWiring.kt",
    )

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
        replaySources.associateWith { rel ->
            val file = allSources.firstOrNull { it.first == rel }
                ?: error("$rel 没找到（本单三件生产文件是门禁的靶子，不许改名躲闸）")
            file.second
        }
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

    /** 判别器：记忆层写口/删除口命中行（判据③「只写建议区」的禁区）。 */
    private fun memoryWriteLine(src: String): String? = stripComments(src).lines().firstOrNull {
        it.contains(".update(") ||
            it.contains(".delete(") ||
            it.contains(".deleteRecursively(") ||
            it.contains("deleteOnExit") ||
            it.contains(".write(") ||
            it.contains("MemoryEntry(")
    }

    /** 判别器：向量检索痕迹命中行（485 口径：不引向量）。 */
    private fun vectorLine(src: String): String? = stripComments(src).lines().firstOrNull {
        it.contains("embedding") || it.contains("Embedding") ||
            it.contains("cosine") || it.contains("ONNX") ||
            it.contains("tflite") || it.contains("TensorFlow") ||
            it.contains("VectorSearch") || it.contains("向量检索工具")
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
    fun 判别器咬得住记忆写口() {
        assertNotNull("判别器必须咬住违规样本", memoryWriteLine("store.write(entry)\nval x = 1"))
        assertNotNull("判别器必须咬住条目构造", memoryWriteLine("val e = MemoryEntry(id = \"x\")\n"))
        assertNotNull("判别器必须咬住删除", memoryWriteLine("file.deleteRecursively()\n"))
        assertNull(
            "writeText／appendText 是行槽与账的合法写法，不许误伤",
            memoryWriteLine("stateFile.writeText(o.toString())\nfile.appendText(line)\n"),
        )
    }

    @Test
    fun 判别器咬得住向量痕迹() {
        assertNotNull("判别器必须咬住违规样本", vectorLine("val v = embeddingOf(text)\n"))
        assertNull("正常源码不该命中", vectorLine("val bm25 = ReplayBm25.similarity(a, b)\n"))
    }

    // ── 判据⑤：没有第二个执行器/调度器 ───────────────────────────────────────

    @Test
    fun 本包零调度构造_执行节奏归676() {
        assertTrue("本单至少落了三件生产文件", pkgSources.size >= 3)
        pkgSources.forEach { (name, src) ->
            assertNull("subconscious/$name 不许自带调度（机会式触发线程由调用方给）", schedulingLine(src))
        }
    }

    @Test
    fun 本包无第二执行器类() {
        pkgSources.forEach { (name, src) ->
            val runnerDecl = stripComments(src).lines().firstOrNull {
                Regex("(class|object)\\s+\\w*Runner").containsMatchIn(it)
            }
            assertNull("subconscious/$name 不许定义 Runner（SubconsciousRunner 是 676 的）", runnerDecl)
        }
    }

    @Test
    fun 日桶复用PatrolScheduler_不重造日期尺() {
        val pure = pkgSources["baopu/subconscious/HippocampalReplay.kt"] ?: error("缺主文件")
        assertTrue("≤1 次/天必须复用 PatrolScheduler.dayBucket", pure.contains("PatrolScheduler.dayBucket"))
        val own = stripComments(pure).lines().firstOrNull { it.contains("toEpochDay") }
        assertNull("不许自造第二把日期尺（toEpochDay）", own)
    }

    @Test
    fun 夜间边界复用SessionShape_不重造钟点尺() {
        val pure = pkgSources["baopu/subconscious/HippocampalReplay.kt"] ?: error("缺主文件")
        assertTrue("夜间判定必须复用 SessionShape.atNight（QuietLimits 22/7 唯一一份）", pure.contains("SessionShape(hourOfDay"))
        val ownBoundary = stripComments(pure).lines().firstOrNull {
            it.contains("NIGHT_FROM_HOUR") && !it.contains("QuietLimits")
        }
        assertNull("夜间边界只许从 QuietLimits/SessionShape 引用，不许另写死数字", ownBoundary)
    }

    @Test
    fun 判据本体只在本包被调() {
        // 认「全仓唯一」的编排入口（anchorOf 是仓里既有的重名——EvoSample/TriggerTool
        // 各有一个，不许拿它当判别键）；mergeSuggestions 同理是本包专属名。
        val outside = allSources
            .filter { (name, _) -> !replaySources.contains(name) }
            .filter { (_, src) ->
                val s = stripComments(src)
                s.contains("runHippocampalReplay") || s.contains("mergeSuggestions(")
            }
            .map { it.first }
        assertTrue(
            "重放判据本体只许在 subconscious 包里被调（$outside）",
            outside.all { it == "ui/baopu/BaopuChatController.kt" },
        )
    }

    // ── 判据③：不自动合并/不改记忆正文（只写建议区） ─────────────────────────

    @Test
    fun 本包零记忆写口_零删除() {
        pkgSources.forEach { (name, src) ->
            assertNull("subconscious/$name 对记忆层零写口（判据③：只写建议区）", memoryWriteLine(src))
        }
    }

    @Test
    fun 行槽publish只许装配层调_一轮一次() {
        val callers = allSources
            .filter { (_, src) -> stripComments(src).contains("ReplayRowStore.publishForRound") }
            .map { it.first }
        assertTrue(
            "publishForRound 只许在装配层（一轮一 publish；现在：$callers）",
            callers.all { it == "baopu/subconscious/ReplayStores.kt" || it == "baopu/subconscious/ReplayWiring.kt" },
        )
    }

    @Test
    fun 撤销放行只许装配层调() {
        val callers = allSources
            .filter { (_, src) -> stripComments(src).contains("ReplayRowStore.undoPublish") }
            .map { it.first }
        assertTrue(
            "undoPublish 只许在装配层的撤销路径（现在：$callers）",
            callers.all { it == "baopu/subconscious/ReplayStores.kt" || it == "baopu/subconscious/ReplayWiring.kt" },
        )
    }

    @Test
    fun 记忆读取只许loadAll() {
        pkgSources.forEach { (name, src) ->
            val memoryCalls = stripComments(src).lines().filter { it.contains("MemoryStore") }
            memoryCalls.forEach { line ->
                assertTrue(
                    "subconscious/$name 对 MemoryStore 只许 loadAll（读）：$line",
                    line.contains("memoryStore(") || line.contains("loadAll") ||
                        line.contains("MemoryStore") && line.trimStart().startsWith("*"),
                )
            }
        }
    }

    // ── 判据⑥附口径：不引向量检索（485）＋与形神不复制数据 ───────────────────

    @Test
    fun 不引向量检索_合并走中文BM25() {
        pkgSources.forEach { (name, src) ->
            assertNull("subconscious/$name 不许引向量检索（485 口径）", vectorLine(src))
        }
        val pure = pkgSources["baopu/subconscious/HippocampalReplay.kt"] ?: error("缺主文件")
        assertTrue("合并检测必须走 BM25", pure.contains("BM25"))
        assertTrue("切词必须复用 MemoryRules.tokensOf（全仓唯一切词尺）", pure.contains("MemoryRules.tokensOf"))
    }

    @Test
    fun 与形神不复制数据() {
        pkgSources.forEach { (name, src) ->
            val formTrack = stripComments(src).lines().firstOrNull {
                it.contains("Silhouette") || it.contains("CharacterTrack") || it.contains("形轨读数")
            }
            assertNull("subconscious/$name 与形神零引用（判据⑥：不复制数据）", formTrack)
        }
    }

    @Test
    fun 本包不带器官标记_器官归694() {
        // 标记名字拼出来：694 的登记闸扫源码字面量，测试里写全名会被误判成畸形标记
        //（`tools/check-bio-analog.py` 头注释的原话：改写那条字符串，别为它放宽本闸）。
        val marker = "@Bio" + "Analog"
        pkgSources.forEach { (name, src) ->
            val organ = stripComments(src).lines().firstOrNull { it.contains(marker) }
            assertNull("subconscious/$name 不许带器官标记（六体登记表是 694 的海马件，本单只消费键）", organ)
        }
    }

    // ── 694 缝（合入后回归的锚） ─────────────────────────────────────────────

    @Test
    fun 海马情景键形状与694约定一致() {
        val pure = pkgSources["baopu/subconscious/HippocampalReplay.kt"] ?: error("缺主文件")
        // G-708-2 收口（714）：键定义只此一处——本包不再自造第二把键，
        // 一律消费 bio 包的 `Hippocampus.EpisodeKey`（seq Int）；本包留自己的键类 ⇒ 红。
        assertTrue("键必须共用 bio 的 EpisodeKey（G-708-2：全仓键定义只此一处）", pure.contains("Hippocampus.EpisodeKey"))
        assertTrue("本包不许再自造键类（G-708-2）", !pure.contains("data class HippocampusKey"))
        assertTrue("seq 必须收敛 Int（G-708-2：Int/Long 双形状并存 ⇒ 红）", pure.contains("seq: Int"))
        assertTrue("键源缝必须立着（整缝交接后由装配层喂）", pure.contains("fun interface KeySource"))
        val wiring = pkgSources["baopu/subconscious/ReplayWiring.kt"] ?: error("缺装配文件")
        assertTrue("键适配必须标注 694 出处（回归项）", wiring.contains("694"))
        assertTrue("装配层键类型也要落到 EpisodeKey（G-708-2）", wiring.contains("EpisodeKey"))
    }

    @Test
    fun 缺指针即丢_纯层与构造期双道() {
        val pure = pkgSources["baopu/subconscious/HippocampalReplay.kt"] ?: error("缺主文件")
        assertTrue("候选锚不上必须在进产物前丢", pure.contains("l0Value.isNullOrBlank()"))
        assertTrue("L0Pointer 构造期必须拒空（结构保证）", pure.contains("require(value.isNotBlank())"))
    }

    @Test
    fun 撤销只走状态回prev_无删除通道() {
        val stores = pkgSources["baopu/subconscious/ReplayStores.kt"] ?: error("缺存储文件")
        assertTrue("撤销＝状态桶回 prev（无删除 API）", stores.contains("restoreToPrev"))
        assertNull("存储文件不许有删除 API（账只增不删）", memoryWriteLine(stores))
    }
}
