// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.memoryprune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第七百五十二批 · 记忆修剪的**源码门禁**（判据③「反向清单不在自动清理白名单」
 * ＋ 边界「不删用户数据/不动物理文件/只在空闲窗口跑」的文本形态不变量）。
 *
 * 为什么按源码钉而不是只按行为钉：行为用例能证明「现在没做」，证明不了
 * 「谁也做不了」——白名单一旦长出 LESSON，就是坑表被机器淡忘；本门禁让那一步先红。
 * 每条判据都带**变异负例**（判据本身能被绕过它就是假的）。
 */
class PruneSourceGateTest {

    private companion object {
        /** 本包的**主源码**目录（新增文件自动进扫描；门禁扫的是代码、注释剥掉）。 */
        val PACKAGE_DIR = "app/src/main/java/com/example/zhengdao/baopu/memoryprune"
    }

    /** 从测试工作目录回仓库根找文件/目录（SubconsciousGateTest 同房型；目录也认）。 */
    private fun repoFile(rel: String): File {
        for (f in listOf(File(rel), File("app/$rel"), File("../$rel"))) {
            if (f.isFile || f.isDirectory) return f
        }
        error("找不到源文件：$rel（模块搬家了？门禁跟着改）")
    }

    /** 读源码并剥掉注释（注释里提到禁词不算数，只看代码）。 */
    private fun code(rel: String): String = repoFile(rel).readText(Charsets.UTF_8)
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \t]*//.*$"), "")

    private fun packageSources(): List<Pair<String, String>> =
        repoFile(PACKAGE_DIR).listFiles().orEmpty()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.name to code("$PACKAGE_DIR/${it.name}") }
            .sortedBy { it.first }

    private fun packageCode(): String = packageSources().joinToString("\n") { it.second }

    // ── 判据③：反向清单不在自动清理白名单（源码断言） ────────────────────────

    /** 白名单字面量抽取（setOf( .. ) 的内容；判据③的唯一住处）。 */
    private fun whitelistLiteral(): String {
        val src = code("$PACKAGE_DIR/PruneRules.kt")
        val key = "PRUNE_ELIGIBLE_KINDS: Set<MemoryKind> = setOf("
        val start = src.indexOf(key)
        assertTrue("白名单必须还在 PruneRules.kt（挪走了要改门禁）", start >= 0)
        val end = src.indexOf(')', start)
        return src.substring(start + key.length, end)
    }

    @Test
    fun 门禁_白名单只有事实与术语_坑表规矩偏好画像不在其中() {
        val literal = whitelistLiteral()
        assertTrue("事实要在白名单里", literal.contains("MemoryKind.FACT"))
        assertTrue("术语要在白名单里", literal.contains("MemoryKind.TERM"))
        // 反向清单（判据③）：这些类名在白名单字面量里出现一个就是红。
        assertFalse("坑表（LESSON）不在白名单——永不自动退役", literal.contains("LESSON"))
        assertFalse("规矩（RULE）不在白名单——只能人改", literal.contains("RULE"))
        assertFalse("偏好（PREFERENCE）不在白名单——机器不许替人淡忘", literal.contains("PREFERENCE"))
        assertFalse("画像（PROFILE）不在白名单", literal.contains("PROFILE"))
    }

    @Test
    fun 负例_白名单塞进坑表_断言必红() {
        val literal = whitelistLiteral()
        // 「不含 LESSON」的检查在原字面量上成立……
        assertFalse(literal.contains("LESSON"))
        // ……把 LESSON 拼进去后必不成立（判据不是恒真的：变异能被咬住）。
        val mutated = "$literal, MemoryKind.LESSON"
        assertTrue(mutated.contains("LESSON"))
    }

    @Test
    fun 门禁_合并与退役的池子都过白名单闸() {
        val src = code("$PACKAGE_DIR/PruneRules.kt")
        assertTrue("pruneEligible 判定本体必须在（一处说了算）", src.contains("fun pruneEligible"))
        assertTrue(
            "合并池出自白名单条目（filter { pruneEligible(it) } 恰这一处，无旁路）",
            Regex("filter \\{ pruneEligible\\(it\\) \\}").findAll(src).count() == 1,
        )
        assertTrue("合并聚簇吃的池子＝eligible", src.contains("clusterNearDuplicates(eligible"))
        assertTrue("退役池也出自同一批白名单条目", src.contains("val retires = eligible"))
    }

    // ── 边界：不删用户数据／不动物理文件 ─────────────────────────────────────

    @Test
    fun 门禁_本包零物理删零改名() {
        val forbidden = listOf(".delete(", "deleteRecursively", ".renameTo(", "deleteOnExit")
        packageSources().forEach { (name, src) ->
            val hits = forbidden.filter { src.contains(it) }
            assertTrue("$name 出现了删除/改名口 $hits——修剪只标状态，物理文件一个不动", hits.isEmpty())
        }
    }

    @Test
    fun 负例_往包里塞一个删除调用_门禁必须红() {
        val clean = packageSources().first { it.first == "PruneRules.kt" }.second
        assertTrue("前提自证：纯层本来干净", forbiddenAbsent(clean))
        assertFalse(
            "负例：加一行 File.delete ⇒ 门禁必红",
            forbiddenAbsent(clean + "\nprivate fun z(f: java.io.File) = f.delete()\n"),
        )
    }

    private fun forbiddenAbsent(src: String): Boolean =
        listOf(".delete(", "deleteRecursively", ".renameTo(").none { src.contains(it) }

    @Test
    fun 门禁_记忆条目零构造_零编解码_写回只经update与撤销原像() {
        val all = packageCode()
        assertFalse("本包不许构造 MemoryEntry（全部动作是 .copy——不造新条目）", all.contains("MemoryEntry("))
        assertFalse("本包不许碰 EntryCodec（条目字节只经 MemoryStore 读/update 与撤销原像写回）", all.contains("EntryCodec"))
        assertTrue("记忆写口＝MemoryStore.update（装配层唯一动作）", all.contains("store.update("))
        // writeText 的面：PruneStores（自身三样）＋ PruneWiring 的 restoreOne（原像写回）。
        val wiringWriteText = Regex("writeText").findAll(code("$PACKAGE_DIR/PruneWiring.kt")).count()
        assertEquals("装配层 writeText 恰一处＝撤销原像写回（多一处＝有旁路）", 1, wiringWriteText)
        val rulesWriteText = Regex("writeText").findAll(code("$PACKAGE_DIR/PruneRules.kt")).count()
        assertEquals("纯层零写盘", 0, rulesWriteText)
    }

    @Test
    fun 负例_往纯层塞MemoryEntry构造_门禁必须红() {
        val clean = packageSources().first { it.first == "PruneRules.kt" }.second
        assertFalse("前提自证：纯层不构造条目", clean.contains("MemoryEntry("))
        assertTrue(
            "负例：塞一个 MemoryEntry 构造 ⇒ 门禁必红",
            (clean + "\nprivate fun w() = MemoryEntry(id=\"x\", scope=null, kind=null, title=\"\", body=\"\")\n")
                .contains("MemoryEntry("),
        )
    }

    // ── 边界：零调度（判据⑤「只在空闲窗口跑」的另一半——不许自己给自己定时） ────

    @Test
    fun 门禁_本包零调度构造() {
        val forbidden = listOf(
            "Timer(", "Handler(", "Executors.", "ScheduledExecutor",
            "Thread(", "CoroutineScope(", "newFixedThreadPool", "setInterval",
        )
        packageSources().forEach { (name, src) ->
            val hits = forbidden.filter { src.contains(it) }
            assertTrue("$name 出现了调度/线程构造 $hits——触发线程归调用方（676/677/678 同款）", hits.isEmpty())
        }
        assertTrue("空闲窗口判据复用 678 的 idleVerdict（不另立第二把尺）", packageCode().contains("idleVerdict("))
        assertTrue("天闸复用 PatrolScheduler.dayBucket（676/677/678 同一把日历尺）", packageCode().contains("PatrolScheduler.dayBucket("))
    }

    // ── 边界：不引向量检索（485 口径：相似度只用既有词面尺） ────────────────────

    @Test
    fun 门禁_不引向量检索_相似度只复用MemoryRules那把尺() {
        val forbidden = listOf("embedding", "cosine", "ONNX", "onnx", "tflite", "vector", "Vector")
        packageSources().forEach { (name, src) ->
            val hits = forbidden.filter { src.contains(it) }
            assertTrue("$name 出现了向量检索字样 $hits——语义近复用 MemoryRules.bodySimilarity（一把尺）", hits.isEmpty())
        }
        assertTrue(packageCode().contains("MemoryRules.bodySimilarity"))
        assertTrue("语义近阈值复用写入闸的 DUPLICATE_SIMILARITY（不另立第二份相似度真相）", packageCode().contains("MemoryRules.DUPLICATE_SIMILARITY"))
    }

    @Test
    fun 门禁_自身产出只写自己的三样() {
        // 产物目录唯一入口＝BaopuKernel.Data.DIR_PRODUCTS；不许另开工作区路径。
        val stores = code("$PACKAGE_DIR/PruneStores.kt")
        assertTrue("账/状态落点走 BaopuKernel.Data（内核单一真相源）", stores.contains("BaopuKernel.Data"))
        val all = packageCode()
        assertTrue("回滚档根从全局记忆根派生（App 私有侧，与备份同隐私口径）", all.contains("PruneRollback.dirOf"))
        assertFalse("本包不写工作区其他目录（产物/之外一个字节都不写）", all.contains("DIR_BACKUP") || all.contains("DIR_DELIVER"))
    }
}
