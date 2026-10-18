// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.baopu.library

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `FaceIndex.sortKey` 的接线判据（第二十七批 · 死函数清理）。
 *
 * ## 为什么这个函数值得单独一个测试文件
 *
 * 它原本是**死函数**：`fun sortKey(item: Item)`，参数 `item` 从头到尾没被用过（写错了），
 * 而真正需要排序的两处（[FaceIndex.dedup] 与 [FaceIndex.render]）各自**内联复制**了一遍
 * 同样的 `compareBy({ it.name }, { it.sha256 })`。
 *
 * 三处同源的风险不是"代码重复不好看"，而是**改一处忘两处**：排序规则一旦变
 * （比如加个 kind 前缀），索引渲染出来的字节顺序与去重时"留哪个"的判定就会不一致，
 * 而且**静默**——没有任何断言会红，只有用户看到索引文件自己变了样。
 *
 * 所以本文件钉两件事：
 * 1. 排序规则本身（name 升序、同名按 sha256）；
 * 2. 两个调用点**真的用了它**（render 的输出顺序 与 sortKey 排序结果一致；
 *    dedup 同 sha 留的就是排序后第一个）——这一条是「接上」的证据，不是重复断言。
 */
class FaceIndexSortKeyTest {

    private fun item(
        sha: String,
        name: String,
        source: String = LibrarySource.DELIVER,
        exportedAt: Long = 0L,
        isolated: Boolean = true,
        kind: String = LibraryKind.TEXT,
    ) = FaceIndex.Item(sha, name, 1L, 1L, source, exportedAt, isolated, kind)

    // ── 排序规则本体 ─────────────────────────────────────────────────────────

    @Test
    fun `按文件名升序`() {
        val sorted = listOf(item("s3", "c.txt"), item("s1", "a.txt"), item("s2", "b.txt"))
            .sortedWith(FaceIndex.sortKey)
            .map { it.name }
        assertEquals(listOf("a.txt", "b.txt", "c.txt"), sorted)
    }

    @Test
    fun `同名时按sha256升序`() {
        val sorted = listOf(
            item("sha_ccc", "same.txt"),
            item("sha_aaa", "same.txt"),
            item("sha_bbb", "same.txt"),
        ).sortedWith(FaceIndex.sortKey).map { it.sha256 }
        assertEquals(
            "同名必须再按 sha 定序——否则同批内容的渲染顺序会随输入顺序漂移",
            listOf("sha_aaa", "sha_bbb", "sha_ccc"),
            sorted,
        )
    }

    @Test
    fun `排序键是稳定的_同输入同输出`() {
        val items = listOf(item("s2", "b"), item("s1", "a"))
        val once = items.sortedWith(FaceIndex.sortKey)
        val twice = items.reversed().sortedWith(FaceIndex.sortKey)
        assertEquals(
            "同一批内容不论输入顺序如何，排完必须一样",
            once.map { it.sha256 },
            twice.map { it.sha256 },
        )
    }

    // ── 接线证据：两个调用点真的用了它 ───────────────────────────────────────

    @Test
    fun `render的条目顺序与sortKey一致`() {
        val items = listOf(
            item("s3", "c.txt"),
            item("s1", "a.txt"),
            item("s2", "b.txt"),
        )
        val text = FaceIndex.render(FaceIndex.Doc(items = items))

        // render 一行一条 item，按 `"name"` 字段出现顺序取回来比对
        val namesInText = Regex("\"name\": \"([^\"]+)\"")
            .findAll(text).map { it.groupValues[1] }.toList()
        val expected = items.sortedWith(FaceIndex.sortKey).map { it.name }

        assertEquals(
            "render 必须与 sortKey 同序（三处各写一遍时这里就红）",
            expected,
            namesInText,
        )
    }

    @Test
    fun `dedup同sha留的是排序后的第一个`() {
        // 两个同 sha 的候选，名字不同 ⇒ 排序后 "a.txt" 在前，该留它
        val candidates = listOf(
            item("same_sha", "z.txt"),
            item("same_sha", "a.txt"),
        )
        val r = FaceIndex.dedup(candidates, existing = emptyList())
        assertEquals(1, r.kept.size)
        assertEquals(
            "同 sha 留排序第一个（不是输入第一个）——这条也是稳定性的地基",
            "a.txt",
            r.kept.first().name,
        )
    }

    @Test
    fun `dedup与existing重复的不进kept`() {
        val existing = listOf(item("dup_sha", "old.txt"))
        val candidates = listOf(item("dup_sha", "new.txt"), item("new_sha", "fresh.txt"))
        val r = FaceIndex.dedup(candidates, existing)
        assertEquals(listOf("fresh.txt"), r.kept.map { it.name })
        assertEquals(1, r.duplicates.size)
    }
}
