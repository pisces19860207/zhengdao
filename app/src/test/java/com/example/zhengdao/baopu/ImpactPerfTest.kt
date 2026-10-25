// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百二十批 · 实测读数（报告里 p50/p95 与「300ms 降级」两个数字的来源）。
//
// 为什么真盘真文件：派工单要求的是**实测**数字，而预览的成本就在文件系统调用上
// （真机上还有 SELinux 与软链）。假件（返回固定值）测出来永远 0ms，那是自欺。
// 这里用 JUnit 的临时目录造 200 个文件、跑 200 次，报 p50/p95 并**断言 p95 < 300ms**
// （超了就红——那时该改的是实现，不是报告里的数字）。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.agent.ToolCall
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ImpactPerfTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 真盘事实源（只读：只 stat / list，不写不删）。 */
    private class FileFacts(private val rootDir: File) : LocalFacts {
        override val root: String get() = rootDir.absolutePath
        override val rawDir: String? get() = File(rootDir, "资料库/原始").absolutePath
        override fun sizeOf(path: String): Long? {
            val f = File(path)
            return if (f.isFile) f.length() else null
        }

        override fun isDir(path: String): Boolean = File(path).isDirectory
        override fun listNames(path: String): List<String>? = File(path).list()?.toList()
        override fun ledgerRowsFor(paths: List<String>): Int = 0
        override fun formState(selector: String): FormState? = null
        override fun nowMs(): Long = System.nanoTime() / 1_000_000
    }

    private fun call(name: String, json: String): ToolCall = ToolCall("c", name, JSONObject(json))

    @Test
    fun `实测_写与移动_单次预览的 p50 与 p95`() {
        tmp.newFolder("产物")
        val facts = FileFacts(tmp.root)
        val samples = (1..200).map {
            val t0 = facts.nowMs()
            ImpactPreview.preview(call("write_file", """{"path":"产物/新$it.txt","content":"x"}"""), facts)
            facts.nowMs() - t0
        }.sorted()
        val p50 = samples[samples.size / 2]
        val p95 = samples[(samples.size * 95) / 100]
        println("[220 实测] 写新文件 200 次：p50=${p50}ms p95=${p95}ms max=${samples.last()}ms")
        assertTrue("写类预览 p95=${p95}ms 超了 300ms 预算", p95 < ImpactPreview.BUDGET_MS)
    }

    @Test
    fun `实测_清空回收站两百个文件_计数正确且读数进报告`() {
        val trash = tmp.newFolder("回收站")
        repeat(200) { File(trash, "f$it.txt").writeText("x".repeat(it + 1)) }
        val facts = FileFacts(tmp.root)
        val samples = (1..60).map {
            val t0 = facts.nowMs()
            val i = ImpactPreview.preview(call("delete_path", """{"empty_trash":true}"""), facts)
            assertTrue(
                "要么数对 200 个，要么明确降级（不许第三种：少报又不说不知道）" +
                    "：count=${i.deleteCount} unknown=${i.unknown}",
                i.deleteCount == 200 || i.unknown,
            )
            facts.nowMs() - t0
        }.sorted()
        val p50 = samples[samples.size / 2]
        val p95 = samples[(samples.size * 95) / 100]
        println("[220 实测] 清空回收站 200 个文件 60 次：p50=${p50}ms p95=${p95}ms max=${samples.last()}ms")
        // 这里**不**断言墙钟：200 次 stat 已接近预算上限，机器一忙（全量单测与 lint 并行跑）
        // 读数就会飘——把"机器必须快"写成判据，等于给自己埋一条随机红。
        // 真正钉住的是**正确性**（循环里每轮都断言数出 200 个）与**降级**（下一个用例：
        // 慢事实源必须 cut 在 6 个以内）。p50/p95 只作为报告读数打印。
    }

    @Test
    fun `实测_超时降级_慢事实源被 300ms 闸切断`() {
        // 每次 stat 花 60ms 的假慢盘：200 个文件若一路数下去要 12 秒
        var now = 0L
        val slow = object : LocalFacts {
            override val root: String = "/ws"
            override val rawDir: String? = null
            override fun sizeOf(path: String): Long? {
                now += 60
                return 1L
            }

            override fun isDir(path: String): Boolean = false
            override fun listNames(path: String): List<String>? = (1..200).map { "$it.txt" }
            override fun ledgerRowsFor(paths: List<String>): Int = 0
            override fun formState(selector: String): FormState? = null
            override fun nowMs(): Long = now
        }
        val i = ImpactPreview.preview(call("delete_path", """{"empty_trash":true}"""), slow)
        println("[220 实测] 慢盘降级：unknown=${i.unknown} 已耗=${now}ms 读到=${i.deleteCount} 个")
        assertTrue("超预算必须降级", i.unknown)
        // 每次 stat 花 60ms ⇒ 300ms 预算内最多读 6 个（第 6 个读完才越过 300）；
        // 关键是**不许一路数完 200 个**（那要 12 秒）
        assertTrue("降级时不该读超过 6 个（实测 ${i.deleteCount} 个）", i.deleteCount <= 6)
        assertTrue("总耗时不许超过预算＋一次读取（实测 ${now}ms）", now <= ImpactPreview.BUDGET_MS + 60)
    }
}
