// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百二十四批 · 判据④：24h 自清（时间注入夹具）。会话文件按会话 id（起始时刻 base36）
// 判旧；未学会条目按 createdAt 判旧。两条都走同一套注入时钟，假钟一拨即可验证。
package com.example.zhengdao.baopu.learn

import com.example.zhengdao.testkit.TempSandbox
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class ObserveTtlTest {

    private val texts = LearnCardTexts(
        qAmbiguous = "qA", qNoEvidence = "qN", qParam = "qP", qIntent = "qI",
        optParam = "p", optFixed = "f", optTeach = "t", optDrop = "d", freeText = "free",
    )

    private var fakeNow: Long = 5_000_000_000L

    private fun sessionFile(root: File, openedAtMs: Long): File {
        val f = File(root, ObserveSession.fileNameFor(openedAtMs.toString(36)))
        f.parentFile?.mkdirs()
        f.writeText("{\"k\":\"close\"}\n")
        return f
    }

    @Test
    fun `判据4_过期会话文件被清_新鲜保留`() {
        TempSandbox.use("zd624-ttl-sessions") { root ->
            val old = sessionFile(root, fakeNow - 25L * 60 * 60 * 1000) // 25h 前
            val fresh = sessionFile(root, fakeNow - 1L * 60 * 60 * 1000) // 1h 前
            ObserveCenter.install(root, texts, { fakeNow }, { _ -> null }, { _ -> null }, { false })
            assertFalse("过期会话文件已自清", old.exists())
            assertTrue("新鲜会话文件保留", fresh.exists())
            // 第二轮：时钟走到 fresh 也过期 ⇒ 再清（同一个 install 期 pruneAll）
            fakeNow += 24L * 60 * 60 * 1000 // 一整天：fresh 的 createdAt 也在界外了
            ObserveCenter.install(root, texts, { fakeNow }, { _ -> null }, { _ -> null }, { false })
            assertFalse(fresh.exists())
            assertEquals(0, ObserveCenter.dataFileCount())
        }
    }

    @Test
    fun `判据4_过期未学会条目随同一套时钟清`() {
        TempSandbox.use("zd624-ttl-items") { root ->
            val store = NotLearnedStore(File(root, NotLearnedStore.FILE_NAME))
            val mk: (String, Long) -> NotLearnedItem = { id, at ->
                NotLearnedItem(
                    id = id, type = LearnTriggerType.PARAM_UNCERTAIN, createdAt = at, sessionId = "s",
                    stepSeq = 1, url = "https://example.test/", pageName = "", kind = "type", faces = null,
                    question = "q", options = listOf("p", "f"), freeTextPrompt = "free",
                )
            }
            store.add(listOf(mk("old", fakeNow - 25L * 60 * 60 * 1000), mk("fresh", fakeNow)))
            // install 会装上同一个文件并 prune
            ObserveCenter.install(root, texts, { fakeNow }, { _ -> null }, { _ -> null }, { false })
            val items = ObserveCenter.notLearned.value.items
            assertEquals(listOf("fresh"), items.map { it.id })
        }
    }
}
