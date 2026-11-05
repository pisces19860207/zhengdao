// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：492 工单判据 A/B 的**真机取证**（工单：报告含真机取证——截图＋命令＋产物）。
// 房型照 `PhoneSearchColdScanInstrumentedTest`：判据的纯函数半边在 JVM 单测里钉死
// （WorkspaceRootsTest／DocSummaryRulesTest／IndexBrowsePureTest／SearchScopePlanExtrasTest／
// GuardMultiRootTest），本文件在机把每条判据跑一遍并落读数，写进
// `files/zd-ws-reach-492.txt`（`adb exec-out run-as … cat` 拉回）。
//
// ## 怎么跑（她的规矩：`adb install -r` ＋ `am instrument`，绝不 `connectedDebugAndroidTest`）
// ```
// adb install -r app/build/outputs/apk/debug/app-debug.apk
// adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
// adb shell appops set com.example.zhengdao MANAGE_EXTERNAL_STORAGE allow
// adb shell am instrument -w -e class com.example.zhengdao.baopu.search.WorkspaceReach492InstrumentedTest \
//   com.example.zhengdao.test/androidx.test.runner.AndroidJUnitRunner
// adb exec-out run-as com.example.zhengdao cat files/zd-ws-reach-492.txt
// ```
// ⚠️ 前提：所有文件访问已授。测试开头**清自己的场**（只删 492- 前缀夹具与自己的清单/索引），
// 判据用「样例目录账行数＝50」「摘要与账行 1:1」这类**免漂移口径**——工作区是活目录，
// App 自己会动文件，绝对数对不上是环境噪音不是判据。
package com.example.zhengdao.baopu.search

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.zhengdao.baopu.BaopuWorkspace
import com.example.zhengdao.baopu.RootMode
import com.example.zhengdao.baopu.WsRoot
import com.example.zhengdao.baopu.WorkspaceRoots
import com.example.zhengdao.baopu.agent.FindFilesTool
import com.example.zhengdao.baopu.agent.GuardPipeline
import com.example.zhengdao.baopu.agent.ToolGroup
import com.example.zhengdao.baopu.agent.IndexTools
import com.example.zhengdao.baopu.agent.ListDirTool
import com.example.zhengdao.baopu.agent.ReadFileTool
import com.example.zhengdao.baopu.agent.SearchInFilesTool
import com.example.zhengdao.baopu.agent.ToolCall
import com.example.zhengdao.baopu.agent.ToolRegistry
import com.example.zhengdao.baopu.agent.ToolRunner
import com.example.zhengdao.baopu.agent.WriteFileTool
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkspaceReach492InstrumentedTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val out = StringBuilder()
    private val evidence = StringBuilder()

    private fun say(s: String) {
        Log.i(TAG, s)
        out.append(s).append('\n')
    }

    /** 判据一条一段：段落头带线程名（「不在主线程构建」的取证之一），正文进证据文件。 */
    private fun section(title: String, body: () -> Unit) {
        out.setLength(0)
        say("════ $title ════（线程 ${Thread.currentThread().name}）")
        body()
        evidence.append(out).append('\n')
    }

    // ── 夹具 ────────────────────────────────────────────────────────────────

    /** 清自己上一次跑留下的场（只删 492- 前缀夹具、自己的清单与索引；不碰工作区其他文件）。 */
    private fun cleanup(host: File) {
        val ro = File(RO_ROOT)
        listOf(
            File(host, "492-样例"), File(host, "492-超限"), File(host, "492-隐藏"),
            File(host, "歧义目录"), ro,
        ).forEach { it.deleteRecursively() }
        WorkspaceRoots.file(ctx).delete()
        // 索引从零建（v2 起本来就要重建；删库也把上一次跑的 RO 账一起带走）
        val db = SearchDb.dbPathUnder(ctx.filesDir)
        assertTrue("要删的必须是 search.db 这一颗（${db.absolutePath}）", db.absolutePath.startsWith(ctx.filesDir.absolutePath))
        listOf(db, File(db.path + "-wal"), File(db.path + "-shm"), File(db.path + "-journal")).forEach { f ->
            if (f.isFile) assertTrue("旧索引删不掉（${f.absolutePath}）", f.delete())
        }
        PhoneSearch.syncNow(ctx)
    }

    /** RO 根 99_速记 两篇 + 写附加根清单 + 账本同步。 */
    private fun buildRoRoot(): File {
        val ro = File(RO_ROOT)
        val shorthand = File(ro, "99_速记")
        shorthand.mkdirs()
        File(shorthand, "速记一.md").writeText("# 速记一\n只读根里的速记正文，暗流词甲。\n")
        File(shorthand, "速记二.md").writeText("## 速记二\n暗流词乙，只读根的第二篇。\n")
        WorkspaceRoots.file(ctx).parentFile?.mkdirs()
        WorkspaceRoots.file(ctx).writeText("""[{"path": "$ro", "mode": "RO", "label": "资料库"}]""")
        PhoneSearch.syncNow(ctx)
        return ro
    }

    /** 主根（工作区）里 50 篇样例。 */
    private fun buildSamples(host: File): File {
        val sample = File(host, "492-样例")
        sample.mkdirs()
        (1..50).forEach { i ->
            File(sample, "样例%02d.md".format(i))
                .writeText("# 样例$i\n灵潮词$i 这是一篇给模型看懂工作区的样例，第 $i 篇。\n正文$i 行。\n")
        }
        return sample
    }

    /** 真实形状的管线（② 段多根解析 + RO 写拒全在守卫）。 */
    private fun pipeline(host: File): GuardPipeline = GuardPipeline(
        registry = ToolRegistry().apply {
            register(ReadFileTool.spec())
            register(WriteFileTool.spec { host })
            register(ListDirTool.spec { host })
            register(SearchInFilesTool.spec { host })
            register(FindFilesTool.spec { host })
            register(IndexTools.browseSpec { host })
            register(IndexTools.searchSpec { host })
            // index_* 归 FILES 组（默认不可见）：本文件验的是守卫与工具语义，给一个全解锁的舞台
            currentSessionId = 1L
            unlockStore().unlock(1L, ToolGroup.entries.toList())
        },
        workspaceRoot = { host },
        rawDir = { null },
        extraRoots = { WorkspaceRoots.extras(ctx) },
        runner = ToolRunner(ToolRunner.toolExecutor),
        log = { },
    )

    private fun exec(p: GuardPipeline, name: String, vararg pairs: Pair<String, Any>): GuardPipeline.Result =
        p.execute(ToolCall("e492", name, JSONObject().apply { pairs.forEach { (k, v) -> put(k, v) } }), { _, _ -> null })

    // ── 判据 ────────────────────────────────────────────────────────────────

    @Test
    fun `真机取证_492_A段B段一次记全`() {
        val st = SearchScope.status(ctx)
        assertTrue(
            "前提未满足（档位=" + st.access + "）：先 appops 授「所有文件访问」再跑，不许交 0 根的读数",
            st.access == PhoneAccess.GRANTED,
        )
        val host = BaopuWorkspace.hostDir(ctx)
        cleanup(host)

        section("B 前置 基线扫描（search.db 从零建）") {
            PhoneSearch.syncNow(ctx)
            val report = PhoneSearch.scan(ctx, forceFull = true)
            val s = IndexBrowse.status(ctx)
            say("基线读数：根 ${report.roots}、片 ${report.shards}、入库 ${report.docsWritten}、停因 ${report.stopped}、用时 ${report.elapsedMs}ms")
            say("基线状态 = ${s.line()}")
            say("主根 = $host")
        }

        val ro = buildRoRoot()

        section("A① 根清单（附加根读自 workspace_roots.json；最多 5 条写死）") {
            say("清单文件 = ${WorkspaceRoots.file(ctx).absolutePath}")
            say("解析出附加根 = " + WorkspaceRoots.extras(ctx).joinToString { "${it.label}(${it.mode.tag}) ${it.dir}" })
            say("主根（工作区，恒 RW 恒第 0 条）= $host")
            assertTrue("附加根清单应解析出 1 条 RO 根", WorkspaceRoots.extras(ctx).size == 1)
        }

        section("A 判据 RO 根能列 99_速记（list_dir 相对路径经管线解析到 RO 根）") {
            val r = exec(pipeline(host), "list_dir", "path" to "99_速记")
            say("list_dir 99_速记 ⇒ ${r.toString().take(300)}")
            assertTrue("RO 根能列 99_速记", r is GuardPipeline.Result.Done && r.toString().contains("速记一.md"))
        }

        section("A 判据 RO 根能搜到（search_in_files 相对路径命中）") {
            val r = exec(
                pipeline(host), "search_in_files",
                "path" to "99_速记", "query" to "暗流词甲",
            )
            say("search_in_files 99_速记 暗流词甲 ⇒ ${r.toString().take(300)}")
            assertTrue("RO 根内容搜得到", r is GuardPipeline.Result.Done && r.toString().contains("暗流词甲"))
        }

        section("A② 歧义报错列候选（两个根里都建同名目录 ⇒ 不许猜）") {
            File(host, "歧义目录").mkdirs()
            File(ro, "歧义目录").mkdirs()
            val r = exec(pipeline(host), "list_dir", "path" to "歧义目录")
            say("list_dir 歧义目录 ⇒ ${r::class.simpleName}")
            assertTrue("同名歧义 ⇒ Failed（可重试，不是 Rejected）", r is GuardPipeline.Result.Failed)
            val msg = (r as GuardPipeline.Result.Failed).error
            say(msg)
            assertTrue("候选要列两个根", msg.contains(host.absolutePath) && msg.contains(ro.absolutePath))
        }

        section("A③ includeHidden 两态（搜盘 + 按名找；用自家子目录免歧义）") {
            val hidden = File(host, "492-隐藏")
            File(hidden, ".obsidian").mkdirs()
            File(hidden, ".obsidian/插件.md").writeText("隐藏词在这儿。\n")
            val off = exec(pipeline(host), "search_in_files", "path" to hidden.absolutePath, "query" to "隐藏词")
            say("默认（false）⇒ ${off.toString().take(160)}")
            assertTrue("默认搜不到点开头目录", off.toString().contains("没有命中"))
            val on = exec(
                pipeline(host), "search_in_files",
                "path" to hidden.absolutePath, "query" to "隐藏词", "include_hidden" to true,
            )
            say("true ⇒ ${on.toString().take(200)}")
            assertTrue("includeHidden=true 能搜到 .obsidian", on is GuardPipeline.Result.Done && on.toString().contains("隐藏词"))
            val findOn = FindFilesTool.find(host, hidden, "插件", includeHidden = true)
            val findOff = FindFilesTool.find(host, hidden, "插件")
            say("find_files true ⇒ ${findOn.toString().take(120)}；默认 ⇒ ${findOff.toString().take(60)}…")
            assertTrue(findOn.toString().contains("插件.md"))
            assertTrue("默认连 .obsidian 目录都不进", findOff.toString().contains("没找到"))
        }

        section("RO 写拒（write_file 落 RO 根的子目录，② 段拦在确认卡之前）") {
            val r = exec(pipeline(host), "write_file", "path" to "99_速记/新.md", "content" to "x")
            say("write_file 99_速记/新.md ⇒ ${r::class.simpleName}")
            assertTrue("RO 写拒（Rejected）", r is GuardPipeline.Result.Rejected)
            say((r as GuardPipeline.Result.Rejected).reason)
        }

        section("A④ 单根 0 回归（清掉附加根清单 ⇒ 相对路径按旧算式折回主根）") {
            WorkspaceRoots.file(ctx).delete()
            say("清掉清单后 extras = ${WorkspaceRoots.extras(ctx).size} 条")
            val prepared = pipeline(host).prepare(
                ToolCall("e492", "read_file", JSONObject().put("path", "99_速记/速记一.md")),
            )
            assertTrue("单根：相对路径解析成主根下的绝对路径（Ready）", prepared is GuardPipeline.Prepared.Ready)
            say("改写后的 path = " + (prepared as GuardPipeline.Prepared.Ready).call.arguments.optString("path"))
            assertTrue("落点是主根", prepared.call.arguments.optString("path").startsWith(host.absolutePath))
            // 恢复清单（后面的 B 段还要用）
            WorkspaceRoots.file(ctx).writeText("""[{"path": "$ro", "mode": "RO", "label": "资料库"}]""")
            PhoneSearch.syncNow(ctx)
        }

        // ── B 段 ────────────────────────────────────────────────────────────

        section("B 判据 50 文件 ⇒ 摘要同条数（样例目录 50 篇全有账行 ＋ 全局 1:1）") {
            val sample = buildSamples(host)
            val report = PhoneSearch.scan(ctx, forceFull = true)
            say("扫描读数：入库 ${report.docsWritten}、复用 ${report.reused}、停因 ${report.stopped}、用时 ${report.elapsedMs}ms")
            val s = IndexBrowse.status(ctx)
            say("状态 = ${s.line()}")
            val browse = IndexBrowse.dirBrowse(ctx, sample, 500)
            say("index_browse 492-样例 ⇒ 文件 ${browse?.directFiles?.size}、子目录 ${browse?.dirNames?.size}、${s.line()}")
            assertTrue("样例目录 50 篇全有账行", browse?.directFiles?.size == 50)
            assertTrue("摘要与账行 1:1（50 文件 ⇒ 摘要 50 条）", s.summaries == s.homeDocs)
        }

        section("B 判据 抽 5 词命中（index_search 复用既有检索）＋ index_browse 摘要卡") {
            IndexTools.ctx = { ctx }
            listOf("灵潮词7" to "样例7", "正文23" to "样例23", "暗流词甲" to "速记一", "灵潮词41" to "样例41", "正文9" to "样例9")
                .forEach { (word, want) ->
                    val r = exec(pipeline(host), "index_search", "query" to word)
                    say("index_search $word ⇒ ${r.toString().take(130)}")
                    assertTrue("「$word」应命中（回执含 $want）", r.toString().contains(want))
                }
            val browse = exec(pipeline(host), "index_browse", "path" to "492-样例/样例07.md")
            say("index_browse 样例07 ⇒ ${browse.toString().take(420)}")
            assertTrue(browse.toString().contains("[索引摘要卡]"))
            assertTrue("结构块带 文件:行", browse.toString().contains("492-样例/样例07.md:1"))
        }

        section("B 判据 增删改只更新该条（增量走 size+mtime 快路）") {
            File(File(host, "492-样例"), "样例07.md").appendText("改过的第 7 篇：改词新词。\n")
            assertTrue("样例23 删掉", File(File(host, "492-样例"), "样例23.md").delete())
            File(File(host, "492-样例"), "样例51.md").writeText("# 样例51\n新词51。\n")
            val report = PhoneSearch.scan(ctx)
            say("增量读数：入库 ${report.docsWritten}、复用 ${report.reused}、删 ${report.goneDeleted}" +
                "（同一样例在全盘档 PHONE 下还有一份索引——HOME/PHONE 双账是既有设计，" +
                "所以入库是 2..4、删是 1..2；判据看的是「没动的篇零重读」）")
            assertTrue("没动的篇一个字节不重读（复用 ≥ 50）", report.reused >= 50)
            assertTrue("删的只删该删的（该样例在 HOME/PHONE 两本账各删一行）", report.goneDeleted in 1..2)
            assertTrue("入库只动动过的（改 1 + 新 1，双账翻倍 ≤ 4）", report.docsWritten in 2..4)
            val s = IndexBrowse.status(ctx)
            say("状态 = ${s.line()}")
            assertTrue("删 1 加 1 摘要仍与账行 1:1", s.summaries == s.homeDocs)
        }

        section("B 判据 超限标「部分索引 N/M」（顶满 ${DocSummaryRules.MAX_ROWS} 后不再新建摘要）") {
            val cap = File(host, "492-超限")
            cap.mkdirs()
            (1..2010).forEach { i -> File(cap, "t%04d.md".format(i)).writeText("# 超限$i\n词$i。\n") }
            var report = PhoneSearch.scan(ctx, forceFull = true)
            say("重建读数：入库 ${report.docsWritten}、停因 ${report.stopped}")
            if (report.stopped != StopReason.DONE) {
                report = PhoneSearch.scan(ctx, forceFull = true)
                say("续扫读数：入库 ${report.docsWritten}、停因 ${report.stopped}")
            }
            val s = IndexBrowse.status(ctx)
            say("状态 = ${s.line()}")
            assertTrue("摘要顶满硬上限", s.summaries == DocSummaryRules.MAX_ROWS)
            assertTrue("账行超过上限 ⇒ 部分索引", s.partial)
        }

        // 落盘（App 私有 files/，adb exec-out run-as cat 拉回）
        val f = File(ctx.filesDir, EVIDENCE_FILE)
        f.writeText("492 工作区可及性 · 真机取证\n$evidence")
        Log.i(TAG, "evidence -> ${f.absolutePath} (${f.length()} B)")
    }

    private companion object {
        const val TAG = "ws492"
        const val EVIDENCE_FILE = "zd-ws-reach-492.txt"
        const val RO_ROOT = "/storage/emulated/0/492-只读根"
    }
}
