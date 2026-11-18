// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 702 派工单判据⑦：**不调模型（源码断言）· 不引向量库（依赖断言）**。
// 为什么扫源码：算法层是纯 Kotlin、薄壳只碰 SQLite——「不调模型」这件事在单测里唯一能钉死
// 的位置就是包内源码文本（房型照 `AdoptPreviewWiringGateTest`：源码位置就是判据本身）。
// 「分词只有一套」（派单 §①「分词复用 tokensOf／不新造第二套」）也在这里钉。
package com.example.zhengdao.baopu.msgsearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SessionSearchGateTest {

    private val pkgDir = File("src/main/java/com/example/zhengdao/baopu/msgsearch")

    private fun sources(): Map<String, String> {
        val files = pkgDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("msgsearch 包不见了（门禁按它找源码）", files.isNotEmpty())
        return files.associate { it.name to it.readText(Charsets.UTF_8) }
    }

    @Test
    fun `判据7_不调模型_包内源码没有任何模型调用口`() {
        listOf(
            "OkHttp", "okhttp3", "HttpURLConnection", "Retrofit", "retrofit2",
            "LlmClient", "ChatModel", "runCompletion", "chatCompletion", "generateText",
            "OpenAI", "openai", "anthropic", "gemini",
        ).forEach { banned ->
            sources().forEach { (name, src) ->
                assertFalse("$name 出现 $banned——会话搜索不许调模型（判据⑦）", src.contains(banned))
            }
        }
    }

    @Test
    fun `判据7_不引向量库_依赖清单里没有向量与嵌入式检索的账`() {
        listOf(
            "faiss", "hnsw", "jvector", "milvus", "chroma", "weaviate", "qdrant",
            "vespa", "elasticsearch", "lucene", "sqlite-jdbc", "androidx.room",
        ).forEach { banned ->
            assertFalse(
                "app 依赖清单出现 $banned——702 派工单「不上向量」直接红",
                File("build.gradle.kts").readText(Charsets.UTF_8).contains(banned, ignoreCase = true),
            )
        }
    }

    @Test
    fun `判据7_检索与摘要层零Android依赖_算法可进全量单测`() {
        // 引擎是纯函数层（判据①—⑥能 JVM 直测的前提），连 android.* 都不许 import
        val engine = sources().getValue("SessionSearchEngine.kt")
        assertFalse("算法层不许 import android.*（纯 Kotlin 判据的前提）", engine.contains("import android."))
        val sql = sources().getValue("SessionSearchSql.kt")
        assertFalse("SQL 常量层也不许 import android.*（照 SearchSql 的房型）", sql.contains("import android."))
    }

    @Test
    fun `分词只有一套_包内不许自造tokenizer_薄壳必须走MemoryRules`() {
        sources().forEach { (name, src) ->
            assertFalse(
                "$name 自定义了 tokensOf/tokenCountsOf/bigram——第二套分词，702 派工单直接红",
                Regex("fun\\s+(tokensOf|tokenCountsOf|bigrams|tokenize)\\s*\\(").containsMatchIn(src),
            )
        }
        val store = sources().getValue("SessionSearchStore.kt")
        assertTrue("入库切词必须走 MemoryRules.tokenCountsOf（与记忆/手机检索同一台分词机）", store.contains("MemoryRules.tokenCountsOf("))
        val engine = sources().getValue("SessionSearchEngine.kt")
        assertTrue("查询切词必须走 MemoryRules.tokensOf", engine.contains("MemoryRules.tokensOf("))
    }

    @Test
    fun `不越界_不引用KbRetriever与DiaryRecall_不碰SearchDb的DDL`() {
        sources().forEach { (name, src) ->
            assertFalse("$name 引用了 KbRetriever（判据⑨：一行不改）", src.contains("KbRetriever"))
            assertFalse("$name 引用了 DiaryRecall（判据⑨：一行不改）", src.contains("DiaryRecall"))
            assertFalse("$name 出现 DROP TABLE（索引坏了走「删文件⇒表自愈」，不写代码删）", src.contains("DROP TABLE"))
        }
    }

    @Test
    fun `给540的接口_三件齐备`() {
        // 派单 §④：search / digest ＋ 索引状态读数 {messages, tokens, lastIndexedAt}
        val store = sources().getValue("SessionSearchStore.kt")
        listOf("fun search(", "fun digest(", "fun status()").forEach { fn ->
            assertTrue("给 540 的接口缺 $fn", store.contains(fn))
        }
        assertTrue("状态读数三件套", store.contains("SessionIndexStatus("))
    }

    @Test
    fun `判据9_KbRetriever与DiaryRecall原文件仍在原位_本批不接管它们`() {
        // 存在性＋职责钉法：这两个文件必须还只服务知识库/日记检索（本批零改动由 git diff 保证，
        // 这里钉的是「它们没被挪进会话搜索的依赖里」的另一半）
        val kb = File("src/main/java/com/example/zhengdao/baopu/kb/KbRetriever.kt")
        val diary = File("src/main/java/com/example/zhengdao/baopu/DiaryRecall.kt")
        assertTrue("KbRetriever.kt 不见了", kb.isFile)
        assertTrue("DiaryRecall.kt 不见了", diary.isFile)
        assertEquals(
            "KbRetriever 仍是知识库检索（搜的是 KbIndex），没有被改成会话搜索",
            true,
            kb.readText(Charsets.UTF_8).contains("KbIndex"),
        )
    }
}
