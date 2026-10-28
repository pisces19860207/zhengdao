// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 259 批 B 段 #9：`BaopuDb` 把「落库失败」吞成一行日志、调用方仍当成功。
// 改前表现：
// · `SessionCompressionStore.insert` 写失败只有 RunLog 一行，`CompressionRunner` 照旧 log
//   「…已落库」（本文件用例 2 钉这条）；
// · `DiaryIndexStore.deleteById` 失败返回 0，与「本来就没有」同一个值——真删收尾时
//   索引残留没人知道（SQLite 进不了 JVM，这半边按仓库惯例钉源码接线）。
// 为什么 insert 这半边能真跑：`CompressionStore` 本来就是为 JVM 直测抽的接口，
// 假件把「写失败」报出来即可；被测的是**调用方拿不拿得到区别**。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.CompletionResult
import com.example.zhengdao.baopu.CompressionRecord
import com.example.zhengdao.baopu.HistoryMessage
import com.example.zhengdao.baopu.LlmProtocol
import com.example.zhengdao.baopu.Provider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompressionInsertOutcomeTest {

    private val summary =
        "目标：整理资料\n已定的决定：三格式\n已排除的路：无\n未决：无\n涉及的文件路径：资料库/整理/x.txt"

    /** [written] = false ⇒ 模拟「摘要有了，SQLite 那一步炸了」（真实现的落库结果口子）。 */
    private class FakeStore(val written: Boolean) : CompressionStore {
        val rows = mutableListOf<CompressionRecord>()
        override var lastInsertOk: Boolean = true
            private set

        override fun insert(
            sessionId: Long,
            record: CompressionRecord,
            sourceTokens: Int,
            summaryTokens: Int,
        ) {
            if (written) rows += record
            lastInsertOk = written
        }

        override fun latest(sessionId: Long): CompressionRecord? = rows.lastOrNull()
        override fun lastFailedStep(sessionId: Long): Int? = null
        override fun markFailed(sessionId: Long, step: Int, code: Int, message: String) = Unit
        override fun markSkipped(sessionId: Long, step: Int, reason: String) = Unit
    }

    /** 什么都不覆盖的假件：接口口子必须默认「落库成功」，否则老实现要一起改（越租）。 */
    private class BareStore : CompressionStore {
        var inserted = 0
        override fun insert(sessionId: Long, record: CompressionRecord, sourceTokens: Int, summaryTokens: Int) {
            inserted++
        }

        override fun latest(sessionId: Long): CompressionRecord? = null
        override fun lastFailedStep(sessionId: Long): Int? = null
        override fun markFailed(sessionId: Long, step: Int, code: Int, message: String) = Unit
        override fun markSkipped(sessionId: Long, step: Int, reason: String) = Unit
    }

    private fun provider() = Provider(
        id = "t",
        label = "测试通道",
        protocol = LlmProtocol.OPENAI_COMPAT,
        baseUrl = "https://x/v1",
        model = "test-model",
    )

    private fun history(): List<HistoryMessage> =
        (1..8).map { HistoryMessage(id = "h$it", role = HistoryMessage.ROLE_USER, text = "第 $it 条的正文内容") }

    /** 跑一次成功压缩，返回引擎落的日志行。 */
    private fun runOk(store: CompressionStore): List<String> {
        val logs = mutableListOf<String>()
        val r = CompressionRunner(
            complete = { _, _, _ -> CompletionResult.Ok(summary, "test-model") },
            store = store,
            log = { logs += it },
            freeAvailable = { true },
            now = { 1_700_000_000_000L },
        ).runOnce(sessionId = 1L, history = history(), provider = provider(), step = 3)
        assertTrue("压缩本身应当成功：$r", r is CompressionRunner.Outcome.Ok)
        return logs
    }

    // ── 调用方拿得到区别 ──────────────────────────────────────────────────

    /** 改前：这一句恒说「已落库」——写失败只活在 BaopuDb 的日志里。 */
    @Test
    fun `落库失败_引擎不许再说已落库`() {
        val logs = runOk(FakeStore(written = false))
        assertTrue("要说出没落库：$logs", logs.any { it.contains("没落库") })
        assertFalse("不许谎报已落库：$logs", logs.any { it.contains("），已落库") })
        assertTrue("仍要说清压了几条：$logs", logs.any { it.contains("4 条压成摘要") })
    }

    /** 成功路径的话术是第十三批 J3 钉过的，本批一个字不许动。 */
    @Test
    fun `落库成功_话术一字未改`() {
        val logs = runOk(FakeStore(written = true))
        assertTrue(
            "成功那句必须逐字还在：$logs",
            logs.contains("抱朴压缩：4 条压成摘要（模型 test-model），已落库"),
        )
        assertFalse(logs.any { it.contains("没落库") })
    }

    /** 口子是**带默认值**的：不覆盖也编得过、按成功算（否则得改清单外的两个假件）。 */
    @Test
    fun `不覆盖口子的假件_默认按落库成功`() {
        val store = BareStore()
        val logs = runOk(store)
        assertEquals("假件确实被调了一次 insert", 1, store.inserted)
        assertTrue("默认值必须是 true", store.lastInsertOk)
        assertTrue(logs.contains("抱朴压缩：4 条压成摘要（模型 test-model），已落库"))
    }

    // ── SQLite 半边：按仓库惯例钉源码接线 ────────────────────────────────

    private fun src(rel: String): String = File("src/main/java/com/example/zhengdao/$rel").readText()

    @Test
    fun `闸_压缩实现把写结果交出去`() {
        val db = src("baopu/BaopuDb.kt")
        val body = db.substringAfter("internal class SessionCompressionStore").substringBefore("override fun latest")
        assertTrue(body.contains("override var lastInsertOk: Boolean = true"))
        assertTrue("写成功与否必须落到返回值：$body", body.contains("lastInsertOk = wrote.isSuccess"))
        assertTrue("@Volatile 让读的一方看得见", body.contains("@Volatile"))
        // 负例3「为了通过用例把错误分支删掉」：日志那句还在，且没改成往上抛（J6 不许炸整轮）。
        assertTrue(body.contains("抱朴压缩：落库失败"))
        assertFalse("失败分支不许改成抛异常：$body", body.contains("throw"))
    }

    @Test
    fun `闸_日记索引删除失败不再冒充本来就没有`() {
        val db = src("baopu/BaopuDb.kt")
        val idx = db.substringAfter("internal class DiaryIndexStore").substringBefore("fun count()")
        assertTrue("失败的 id 要有地方存", idx.contains("failedIds += id"))
        assertTrue("调用方读得到残留清单", idx.contains("fun deleteFailures(): List<String>"))
        // 208 批门禁钉过的字面量：签名与表名一个字没动。
        assertTrue(idx.contains("fun deleteById(id: String): Int"))
        assertTrue(idx.contains("""d.delete("diary_index""""))

        val diary = src("baopu/DiaryStore.kt")
        val realDelete = diary.substringAfter("fun realDelete(plan: DiaryDeletionPlan)").substringBefore("DecisionLedger.record")
        assertTrue("208 钉的接线还在", realDelete.contains("idx.deleteById(it)"))
        assertTrue("真删收尾必须读残留", realDelete.contains("idx.deleteFailures()"))
        assertTrue("有残留必须留话", realDelete.contains("residue.isNotEmpty()"))
    }

    /** 压缩选区口径（h1–h4）本批没动——这条防「顺手改选区」。 */
    @Test
    fun `闸_选区与压缩口径没被动`() {
        val store = FakeStore(written = true)
        runOk(store)
        assertEquals("选区仍是最近 4 条之外", listOf("h1", "h2", "h3", "h4"), store.rows.single().compressedIds)
        assertTrue("落库的是解析后的摘要", store.rows.single().summary.contains("目标：整理资料"))
    }
}
