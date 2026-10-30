// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百八十七批（2026-10-30）**真机验收**：全盘可读写那五条判据，在真手机上逐条打一遍。
//
// ## 为什么必须在设备上跑
// JVM 单测里 `File("/system/x")` 会被 JDK 解释成 `C:\system\x`（测试宿主的语义），
// `MANAGE_EXTERNAL_STORAGE` 也不存在——「能不能列出 `/storage/emulated/0/Download/小说` 的
// Obsidian 库」这件事，只有真的 Android 上跑才算数。判据本体（纯函数）由 `FilePolicyTest`
// 钉着，本文件只负责**真机上真的通不通**。
//
// ## 怎么跑（她的规矩：`adb install -r` ＋ `am instrument`，绝不 `connectedDebugAndroidTest`）
// ```
// ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --offline
// adb install -r app/build/outputs/apk/debug/app-debug.apk
// adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
// adb shell am instrument -w -e class com.example.zhengdao.baopu.FullDiskAcceptanceInstrumentedTest \
//   com.example.zhengdao.test/androidx.test.runner.AndroidJUnitRunner
// ```
// ⚠️ 不用 `connectedDebugAndroidTest`：AGP 跑完会**卸载 App**，而 `files/` 里是整台手机的
// Linux（rootfs／hermes／抱朴 opencode，见 `PhoneSearchColdScanInstrumentedTest` 文件头那次事故）。
package com.example.zhengdao.baopu

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.zhengdao.baopu.agent.DeletePathTool
import com.example.zhengdao.baopu.agent.FindFilesTool
import com.example.zhengdao.baopu.agent.GuardPipeline
import com.example.zhengdao.baopu.agent.ReadFileTool
import com.example.zhengdao.baopu.agent.RunRules
import com.example.zhengdao.baopu.agent.ToolCall
import com.example.zhengdao.baopu.agent.ToolGroup
import com.example.zhengdao.baopu.agent.ToolOutcome
import com.example.zhengdao.baopu.agent.ToolRegistry
import com.example.zhengdao.baopu.agent.ToolRunner
import com.example.zhengdao.baopu.agent.WriteFileTool
import com.example.zhengdao.core.EnvironmentScope
import java.io.File
import java.util.concurrent.Executors
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FullDiskAcceptanceInstrumentedTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    /** 用户那台手机上的 Obsidian 库（287 判据里 `D:\小说和人物资料` 的对应物）。 */
    private val vault = File("/storage/emulated/0/Download/小说")

    private val root: File get() = BaopuWorkspace.hostDir(ctx)

    private fun text(o: ToolOutcome): String = when (o) {
        is ToolOutcome.Ok -> o.text
        is ToolOutcome.Failed -> o.error
        is ToolOutcome.Rejected -> o.reason
    }

    // ── 前提：系统给了「所有文件访问」 ────────────────────────────────────

    @Test
    fun 前提_所有文件访问已授予() {
        assertTrue(
            "系统没给「所有文件访问」（MANAGE_EXTERNAL_STORAGE）⇒ 下面几条都不用看，先去设置里给权限",
            EnvironmentScope.hasAllFilesAccess(ctx),
        )
        assertTrue("工作区要存在：${root.absolutePath}", root.isDirectory)
    }

    // ── 判据①：不开任何授权，直接列出 Obsidian 库 ────────────────────────

    @Test
    fun 判据一_不开任何授权_列出Obsidian库() {
        assertTrue("库不在：${vault.absolutePath}（换一台手机要改这个路径）", vault.isDirectory)
        assertNull("全盘可列：不该被 FilePolicy 拒", FilePolicy.denyReason(vault))
        val out = com.example.zhengdao.baopu.agent.ListDirTool.list(vault, 1, 200)
        assertTrue("列目录要成功：${text(out)}", out is ToolOutcome.Ok)
        // 判据必须是「真的列到了东西」，不能是「文案里恰好有这俩字」：
        // 「（空目录）」也含 `.md` 之外的一切噪音，所以先排空、再数条目。
        assertFalse("列出来是空的 ⇒ 没真读到库：${text(out)}", text(out).contains("（空目录）"))
        val lines = text(out).lines().count { it.contains(".md") || it.contains(".obsidian") }
        assertTrue("要看见库里的东西（至少 1 条）：${text(out).take(200)}", lines >= 1)
    }

    @Test
    fun 判据一b_按名找_从共享存储根找得到库里的笔记() {
        val out = FindFilesTool.find(root, File("/storage/emulated/0"), "索引", limit = 20, maxDepth = 4)
        assertTrue("全盘可找：${text(out)}", out is ToolOutcome.Ok)
        // 陷阱：找不到时它也返回 Ok，文案是「**没找到**文件名含「索引」的文件」——
        // 只判 contains("索引") 恒真。这里必须排除空结果，并见到真实的 .md 命中行。
        assertFalse("空结果不算通过：${text(out).take(200)}", text(out).contains("没找到文件名含"))
        assertTrue("要命中 .md 文件：${text(out).take(300)}", text(out).lines().any { it.contains(".md") })
    }

    // ── 判据②：能读那篇笔记 ──────────────────────────────────────────────

    @Test
    fun 判据二_能读笔记() {
        val note = firstMarkdown(vault)
        assertNotNull("库里没有 .md，换个库再验", note)
        val out = ReadFileTool.read(note!!, 0, 20)
        assertTrue("全盘可读：${note.absolutePath} → ${text(out)}", out is ToolOutcome.Ok)
    }

    // ── 判据③：能写回工作区（相对路径落工作区） ──────────────────────────

    @Test
    fun 判据三_写回工作区_覆盖先备份() {
        val rel = "产物/287验收-${System.currentTimeMillis()}.md"
        val backupDir = File(root, WriteFileTool.BACKUP_DIR_NAME)
        // 备份区是跨运行累积的：只判「非空」会被上次跑剩的旧备份假绿 ⇒ 先记本次基线。
        val before = backupDir.listFiles().orEmpty().count { it.name.contains("287验收") }
        val first = WriteFileTool.write(root, rel, "# 287 验收\n第一版\n", overwrite = false)
        assertTrue("相对路径要落在工作区：${text(first)}", first is ToolOutcome.Ok)
        val target = File(root, rel)
        assertTrue("文件真的在工作区里：${target.absolutePath}", target.isFile)

        val second = WriteFileTool.write(root, rel, "# 287 验收\n第二版\n", overwrite = true)
        assertTrue("覆盖要成功：${text(second)}", second is ToolOutcome.Ok)
        assertTrue("回执要写清备份：${text(second)}", text(second).contains("备份"))
        val backups = backupDir.listFiles().orEmpty()
        val after = backups.count { it.name.contains("287验收") }
        assertTrue("这次覆盖要真的多出一份旧版备份（跑前 $before → 跑后 $after）", after > before)
        assertEquals("第二版要真的落盘：${target.absolutePath}", "# 287 验收\n第二版\n", target.readText())

        // 清场（测试自己删，不走 delete_path：那条要确认卡，不该在自动化里绕过）
        runCatching { target.delete() }
        backups.filter { it.name.contains("287验收") }.forEach { runCatching { it.delete() } }
    }

    // ── 判据④：删笔记必弹确认（卡面写清删什么、能不能回） ────────────────

    @Test
    fun 判据四_删文件必弹确认_卡面写清能不能回() {
        val registry = ToolRegistry().apply { register(DeletePathTool.spec { root }) }
        val pipeline = GuardPipeline(
            registry, { root }, { null }, ToolRunner(Executors.newSingleThreadExecutor()), log = {},
        )
        val victim = File("/storage/emulated/0/Download/287-验收-待删-${System.currentTimeMillis()}.md")
        victim.writeText("待删\n")
        try {
            val prepared = pipeline.prepare(
                ToolCall("c-del", "delete_path", JSONObject().put("path", victim.absolutePath)),
            )
            assertTrue(
                "删文件必须先问（不能静默执行）：$prepared",
                prepared is GuardPipeline.Prepared.Settled &&
                    (prepared as GuardPipeline.Prepared.Settled).result is GuardPipeline.Result.NeedsConfirm,
            )
            assertTrue("没批准之前文件必须原样在", victim.isFile)

            val summary = RunRules.confirmSummary(
                "delete_path",
                JSONObject().put("path", victim.absolutePath),
                root,
            )
            assertTrue("卡面要写清删什么：$summary", summary.contains(victim.name))
            assertTrue("卡面要写清能不能回：$summary", summary.contains("回收站") && summary.contains("捞回来"))
        } finally {
            runCatching { victim.delete() }
        }
    }

    // ── 判据⑤：系统与凭据目录一律拒（真机的 POSIX 路径） ─────────────────

    @Test
    fun 判据五_系统与凭据目录一律拒() {
        assertNotNull("真机上 /system 必须被拒", FilePolicy.denyReason(File("/system/build.prop")))
        assertNotNull(
            "别的应用的数据目录必须被拒",
            FilePolicy.denyReason(File("/data/data/com.tencent.mm/shared_prefs/x.xml")),
        )
        assertNotNull(
            "别的应用的 Android/data 必须被拒",
            FilePolicy.denyReason(File("/storage/emulated/0/Android/data/com.tencent.mm/files/x")),
        )
        assertNotNull(
            "凭据目录必须被拒",
            FilePolicy.denyReason(File("/storage/emulated/0/.ssh/id_rsa")),
        )
        assertNull(
            "本应用自己的私有目录要放行",
            FilePolicy.denyReason(File(ctx.filesDir, "baopu/persona.md")),
        )
        // 真机上这条读必须被管线拒（不是"读到了再报错"）
        val registry = ToolRegistry().apply { register(ReadFileTool.spec()) }
        val pipeline = GuardPipeline(
            registry, { root }, { null }, ToolRunner(Executors.newSingleThreadExecutor()), log = {},
        )
        val r = pipeline.execute(
            ToolCall("c-sys", "read_file", JSONObject().put("path", "/system/build.prop")),
        ) { _, _ -> null }
        assertTrue("读系统文件必须 Rejected：$r", r is GuardPipeline.Result.Rejected)
    }

    // ── 判据⑥：全盘操作入账（真库 v20，路径＋用途） ──────────────────────

    @Test
    fun 判据六_全盘操作入账_真库v20() {
        FileOpLog.resetForTest()
        val db = BaopuRuntime.db(ctx)
        FileOpLog.install(SessionFileOpStore(db))
        FileOpLog.enterSession(287L)

        val note = firstMarkdown(vault) ?: vault
        // 用途写成「验收」，不是装好看：真库这张表就是用户眼前那张卡的内容源，
        // 验收自己写进去的行必须一眼能认出来是验收，不然将来没人分得清哪条是它干的。
        val purpose = "验收 · 读文件"
        val e = FileOpLog.record(
            tool = "read_file",
            kind = FileOpKind.READ,
            path = FilePolicy.normalize(note),
            purpose = purpose,
        )
        assertEquals(287L, e.sessionId)
        val rows = FileOpLog.recent(5)
        assertTrue("刚记的一笔要读得回来：${rows.size} 行", rows.any { it.path == e.path && it.purpose == purpose })
        assertTrue("30 天窗口常量", FileOpSql.RETENTION_DAYS == 30)
        FileOpLog.resetForTest()
    }

    // ── 判据⑥b：用户没批的删除，真库一行都不许多 ─────────────────────────

    @Test
    fun 判据六b_用户没批的删除不入真库() {
        // 287 真机验收抓到的真 bug：记账原本在 ② 段（出卡之前）⇒ 点「拒绝」也记一行"删过"。
        // 这条用**真库**钉住修好之后的行为：只出卡、没执行 ⇒ 账不涨。
        val db = BaopuRuntime.db(ctx)
        FileOpLog.install(SessionFileOpStore(db))
        FileOpLog.enterSession(2871L)

        val victim = File("/storage/emulated/0/Download/287-未批-${System.currentTimeMillis()}.md")
        victim.writeText("x\n")
        val before = FileOpLog.count()
        try {
            val registry = ToolRegistry().apply { register(DeletePathTool.spec { root }) }
            val pipeline = GuardPipeline(
                registry, { root }, { null }, ToolRunner(Executors.newSingleThreadExecutor()), log = {},
            )
            val prepared = pipeline.prepare(
                ToolCall("c-del2", "delete_path", JSONObject().put("path", victim.absolutePath)),
            )
            assertTrue(
                "必须先出卡：$prepared",
                prepared is GuardPipeline.Prepared.Settled &&
                    (prepared as GuardPipeline.Prepared.Settled).result is GuardPipeline.Result.NeedsConfirm,
            )
            assertEquals("没执行 ⇒ 账一行都不许多", before, FileOpLog.count())
            assertTrue("文件原样在", victim.isFile)
        } finally {
            runCatching { victim.delete() }
        }
    }

    // ── 判据⑥c：批准之后——真删了，且账上**恰好**多一行（反向的一半） ──────

    @Test
    fun 判据六c_批准之后真的删了并且账上多一行() {
        // ⑥b 只钉「没批不记」。只钉这一半有个更糟的假绿：记账整个坏掉（一行都不写）
        // 也能让 ⑥b 全绿。这条从反面钉住：批准 → 文件真没了 → 账上 +1 且就是这次删除。
        //
        // 为什么用内存账本而不是真库：这一条**必然新增一行**，写进真库就会在用户眼前那张
        // 「文件操作记录」卡上留一条 `删 · 287-批后-…` 的测试假行——验收本身去污染被验收的
        // 东西，说不通。真库接线由 判据六（读得回）＋判据六b（真库行数不变）两处钉着。
        FileOpLog.install(InMemoryFileOpStore())
        FileOpLog.enterSession(2872L)

        val victim = File("/storage/emulated/0/Download/287-批后-${System.currentTimeMillis()}.md")
        victim.writeText("x\n")
        assertTrue("先造一个待删文件", victim.isFile)
        val before = FileOpLog.count()
        try {
            val registry = ToolRegistry().apply { register(DeletePathTool.spec { root }) }
            // delete_path 在 FILES 组，组默认不可见（要用户御批）⇒ 测试里显式解锁本会话，
            // 否则 executeApproved 会在组锁桩上就 Rejected，删不到东西、也就验不到「批准后记账」。
            registry.unlockStore().unlock(registry.currentSessionId, listOf(ToolGroup.FILES))
            val pipeline = GuardPipeline(
                registry, { root }, { null }, ToolRunner(Executors.newSingleThreadExecutor()), log = {},
            )
            val call = ToolCall("c-del3", "delete_path", JSONObject().put("path", victim.absolutePath))
            val prepared = pipeline.prepare(call)
            assertTrue(
                "必须先出卡：$prepared",
                prepared is GuardPipeline.Prepared.Settled &&
                    (prepared as GuardPipeline.Prepared.Settled).result is GuardPipeline.Result.NeedsConfirm,
            )
            val done = pipeline.executeApproved(call) { _, _ -> null }
            assertTrue("批准后要真的执行成功：$done", done is GuardPipeline.Result.Done)
            assertFalse("文件必须真的不在了：${victim.absolutePath}", victim.isFile)
            assertEquals("执行成功 ⇒ 账上恰好 +1（跑前 $before）", before + 1, FileOpLog.count())
            val top = FileOpLog.recent(1).firstOrNull()
            assertNotNull("要读得回刚记的那一行", top)
            assertTrue("记的是这次删除的路径：${top?.path}", top!!.path.contains("287-批后-"))
            assertEquals("记的种类是「删」", FileOpKind.DELETE, top.kind)
        } finally {
            runCatching { victim.delete() }
            FileOpLog.resetForTest() // 别把内存账本留给下一条用例
        }
    }

    // ── 工具 ────────────────────────────────────────────────────────────

    /** 库里随便找一个 .md（深度 4 层，够覆盖 Obsidian 的目录结构）。 */
    private fun firstMarkdown(dir: File): File? {
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        var visited = 0
        while (stack.isNotEmpty() && visited < 2000) {
            val cur = stack.removeLast()
            val children = runCatching { cur.listFiles() }.getOrNull() ?: continue
            for (f in children) {
                visited++
                if (f.isDirectory && f.name != ".obsidian" && f.name != ".git") stack.addLast(f)
                if (f.isFile && f.name.endsWith(".md") && f.length() > 0) return f
            }
        }
        return null
    }
}
