// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百二十四批 · 判据⑦：未学会.json 数字与清单一致；教完减一、归零隐藏的**数据半边**。
// 加 N＝N、答/教各减一、全消归零、文件↔计数恒等、prune、清空、resolved 封顶、坏文件不猜。
package com.example.zhengdao.baopu.learn

import com.example.zhengdao.testkit.TempSandbox
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class NotLearnedStoreTest {

    private fun item(id: String, createdAt: Long = 1_000L) = NotLearnedItem(
        id = id,
        type = LearnTriggerType.PARAM_UNCERTAIN,
        createdAt = createdAt,
        sessionId = "s1",
        stepSeq = 1,
        url = "https://example.test/list",
        pageName = "列表页",
        kind = "type",
        faces = null,
        question = "第 1 步「单号」填的内容每次都不同吗？",
        options = listOf("是参数（每次会变）", "固定值（每次一样）"),
        freeTextPrompt = "或者直接说你的想法",
    )

    private fun storeIn(tmp: File) = NotLearnedStore(File(tmp, "未学会.json"))

    @Test
    fun `判据7_加两条计数就是二_文件与计数一致`() {
        TempSandbox.use("zd624-store-add") { tmp ->
            val store = storeIn(tmp)
            store.add(listOf(item("a"), item("b")))
            val state = store.state()
            assertEquals("count 恒等于 items.size（判据⑦）", state.items.size, state.count)
            assertEquals(2, state.count)
            // 重开一个壳：文件↔清单一致（不是内存假象）
            assertEquals(2, storeIn(tmp).state().count)
        }
    }

    @Test
    fun `答问消项_计数减一_答案进已消`() {
        TempSandbox.use("zd624-store-answer") { tmp ->
            val store = storeIn(tmp)
            store.add(listOf(item("a"), item("b")))
            store.answer("a", "是参数", atMs = 2_000L)
            val state = store.state()
            assertEquals(1, state.count)
            assertEquals("b", state.items.single().id)
            assertEquals("answered", state.resolved.single().resolution)
            assertEquals("是参数", state.resolved.single().detail)
        }
    }

    @Test
    fun `教完消项_两条教完归零`() {
        TempSandbox.use("zd624-store-teach") { tmp ->
            val store = storeIn(tmp)
            store.add(listOf(item("a"), item("b")))
            store.taught("a", "click @ https://example.test/list", atMs = 2_000L)
            assertEquals(1, store.state().count)
            store.taught("b", "type @ https://example.test/list", atMs = 3_000L)
            val state = store.state()
            assertEquals("归零（隐藏的判定输入）", 0, state.count)
            assertTrue(state.items.isEmpty())
            assertEquals(2, state.resolved.size)
            // 重开：归零也持久（不是内存态）
            assertEquals(0, storeIn(tmp).state().count)
        }
    }

    @Test
    fun `同id不重复出卡`() {
        TempSandbox.use("zd624-store-dup") { tmp ->
            val store = storeIn(tmp)
            store.add(listOf(item("a")))
            store.add(listOf(item("a")))
            assertEquals(1, store.state().count)
        }
    }

    @Test
    fun `判据4_清单条目24h过期整条移出`() {
        TempSandbox.use("zd624-store-ttl") { tmp ->
            val store = storeIn(tmp)
            store.add(listOf(item("old", createdAt = 1_000L), item("fresh", createdAt = 90_000_000L)))
            store.prune(nowMs = 86_500_000L) // 24h = 86_400_000；old 在界外，fresh 在界内
            val state = store.state()
            assertEquals(listOf("fresh"), state.items.map { it.id })
        }
    }

    @Test
    fun `已消记录封顶50条`() {
        TempSandbox.use("zd624-store-cap") { tmp ->
            val store = storeIn(tmp)
            store.add((1..60).map { item("x$it") })
            (1..60).forEach { store.taught("x$it", "d$it", atMs = it.toLong()) }
            assertEquals(0, store.state().count)
            assertEquals(NotLearnedStore.MAX_RESOLVED, store.state().resolved.size)
        }
    }

    @Test
    fun `清空两口一起清`() {
        TempSandbox.use("zd624-store-clear") { tmp ->
            val store = storeIn(tmp)
            store.add(listOf(item("a")))
            store.answer("a", "固定值", atMs = 2_000L)
            store.clear()
            val state = store.state()
            assertTrue(state.items.isEmpty())
            assertTrue(state.resolved.isEmpty())
            assertEquals(0, storeIn(tmp).state().count)
        }
    }

    @Test
    fun `坏文件按空算不猜`() {
        TempSandbox.use("zd624-store-bad") { tmp ->
            val file = File(tmp, "未学会.json")
            file.writeText("{这不是json")
            assertEquals(0, NotLearnedStore(file).state().count)
        }
    }
}
