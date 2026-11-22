// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 798 判据③：只增不改（改 = 新增 + supersedes 指旧条），链可双向追；
// 目录投放是唯一录入路径，一条命令分流主库/待验证池/拒收账。
package com.example.zhengdao.baopu.textbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TextbookAppendOnlyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `目录投放一条命令分流三账`() {
        val drop = tmp.newFolder("drop")
        val lib = tmp.newFolder("lib")
        File(drop, "batch.jsonl").writeText(
            listOf(
                Fixtures.base(id = "TB-OK").toString(),
                Fixtures.base(id = "TB-PENDING", sourceType = "经验总结", scope = "口述无样本量").toString(),
                Fixtures.base(id = "TB-BAD", content = "一段读书笔记，没有引用").toString(),
            ).joinToString("\n") + "\n",
            Charsets.UTF_8,
        )

        val report = TextbookLibrary.ingest(drop, lib)

        assertEquals(listOf("TB-OK"), report.accepted)
        assertEquals(listOf("TB-PENDING"), report.pending)
        assertEquals(1, report.rejects.size)
        assertEquals(listOf("batch.jsonl"), report.files)
        assertTrue(File(lib, "entries.jsonl").isFile)
        assertTrue(File(lib, "待验证池.jsonl").isFile)
        assertTrue(File(lib, "rejects.jsonl").isFile)
        assertTrue("读书笔记应另存", File(lib, "笔记.jsonl").isFile)
        assertTrue("笔记文件应含被拒的原文",
            File(lib, "笔记.jsonl").readText(Charsets.UTF_8).contains("一段读书笔记"))
    }

    @Test
    fun `同一id二次投放拒收且原条目一字未改`() {
        val drop = tmp.newFolder("drop")
        val lib = tmp.newFolder("lib")
        File(drop, "one.json").writeText(Fixtures.base(id = "TB-1").toString(), Charsets.UTF_8)
        TextbookLibrary.ingest(drop, lib)

        val second = TextbookLibrary.ingest(drop, lib)
        assertTrue("重复 id 应拒收：${second.rejects}", second.accepted.isEmpty())
        assertEquals("DUPLICATE_ID", second.rejects.first().codes)
        assertEquals(1, AppendOnlyStore(lib).all().size)
        assertEquals(1, File(lib, "entries.jsonl").readLines(Charsets.UTF_8).size)
    }

    @Test
    fun `修改既有条目等于新增一条_supersedes链双向可追`() {
        val lib = tmp.newFolder("lib")
        val store = AppendOnlyStore(lib)
        val v1 = Fixtures.entry(id = "TB-1")
        assertEquals(AddResult.Ok("TB-1"), store.add(v1))

        val duplicate = store.add(Fixtures.entry(id = "TB-1", content = "字段 [字段:source] 改写了内容。"))
        assertTrue("同 id 改写必须红：${duplicate}", duplicate is AddResult.Duplicate)
        assertEquals("原条目不许被改", v1, store.get("TB-1"))

        val v2 = Fixtures.entry(id = "TB-2", supersedes = "TB-1", content = "字段 [字段:source] 新版本。")
        assertEquals(AddResult.Ok("TB-2"), store.add(v2))

        assertEquals(listOf("TB-1", "TB-2"), store.chainOf("TB-1"))
        assertEquals(listOf("TB-2", "TB-1"), store.lineageOf("TB-2"))
        assertEquals(2, File(lib, "entries.jsonl").readLines(Charsets.UTF_8).size)
        assertEquals("旧条目正文保持原样", v1.content, store.get("TB-1")?.content)
    }

    @Test
    fun `规范域标准不许被经验类教材取代`() {
        val store = AppendOnlyStore(tmp.newFolder("lib"))
        assertEquals(AddResult.Ok("TB-NORM"),
            store.add(Fixtures.entry(id = "TB-NORM", sourceType = SourceType.标准规范)))

        val override = store.add(
            Fixtures.entry(id = "TB-EXP", sourceType = SourceType.经验总结,
                scope = "样本 n=24", supersedes = "TB-NORM"),
        )
        assertTrue("规范域不得被经验类覆盖：${override}", override is AddResult.NormativeOverride)
        assertEquals(1, store.all().size)
        assertEquals("旧标准仍在", "TB-NORM", store.get("TB-NORM")?.id)
    }

    @Test
    fun `supersedes指向不存在的旧条拒收`() {
        val store = AppendOnlyStore(tmp.newFolder("lib"))
        val orphan = store.add(Fixtures.entry(id = "TB-X", supersedes = "TB-GHOST"))
        assertTrue("孤儿链必须红：${orphan}", orphan is AddResult.BrokenSupersedes)
        assertTrue(store.all().isEmpty())
    }
}
