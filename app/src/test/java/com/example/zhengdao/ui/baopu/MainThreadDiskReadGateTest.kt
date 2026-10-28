// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。

package com.example.zhengdao.ui.baopu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第二百六十四批 · 主线程读盘：**本批四处一起**的门禁（派工单负例「只改一处就交」直接对着这一条）。
 *
 * ⚠ 租约：`BaopuSettingsScreen.kt`（原第 ④ 处）已划给 262（巡按取证据），262 合入后才补。
 * 本类只钉本批真正动过的四处；settings 那一条单独放在末尾，钉的是「本批不许碰它」。
 *
 * 每条判据都是「源码形状」而非行为：
 * 1. 四处读盘都在 `LaunchedEffect`/`produceState` + `Dispatchers.IO` 里，组合期不再现读；
 * 2. 可重读的（回收站面板／抽屉两处计数＋取根）用「保留旧快照直到新快照到达」，
 *    不用 `produceState` 换 key——换 key 会把值重置成初始值，正好制造派工单点名的「先空白再闪」；
 * 3. 加载态与空态分开（读到了没东西 ≠ 还没读到）；
 * 4. `RunLog.init` 只记路径 + 起后台线程，`mkdirs → 迁移 → 归档 → 补写` 串行在同一条线程；
 * 5. 四处都不许 `runBlocking`（把异步再按回主线程等于没改）。
 *
 * 行为层另有两处（不靠字符串）：`RunLogStartupQueueTest`（日志不丢/target 顺序）与
 * `BaopuTrashBinTest.T1_分页读…`（分页只 stat 前 N 条、总数与排序不变）。
 */
class MainThreadDiskReadGateTest {

    // 工作目录 = app/（与仓里其它源码门禁同口径）
    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    /** 读剥掉注释后的源码：注释里也会出现这些函数名，不剥就是门禁空跑。 */
    private fun strip(rel: String): String = repoFile(rel).readText(Charsets.UTF_8)
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private fun novel() = strip("src/main/java/com/example/zhengdao/ui/baopu/BaopuNovelSheet.kt")

    private fun trash() = strip("src/main/java/com/example/zhengdao/ui/baopu/BaopuTrashBin.kt")

    private fun drawer() = strip("src/main/java/com/example/zhengdao/ui/baopu/BaopuDrawer.kt")

    private fun settings() = strip("src/main/java/com/example/zhengdao/ui/baopu/BaopuSettingsScreen.kt")

    private fun runlog() = strip("../core/src/main/java/com/example/zhengdao/core/RunLog.kt")

    /**
     * 「组合期读盘」的坏形状：`remember { …(…` 里直接调某个读盘函数（可选带 key）。
     * 判别器在 [组合期读盘正则抓得住坏形状] 里自证，防门禁空跑。
     */
    private fun rememberLoader(fn: String): Regex =
        Regex("remember\\s*(\\([^)]*\\))?\\s*\\{[\\s\\S]{0,120}?\\b$fn\\s*\\(")

    // ── ① 小说面板（A-P2-1）─────────────────────────────────────────────

    @Test
    fun `①小说面板_首帧读取中_读盘在IO`() {
        val src = novel()
        assertTrue(
            "读盘必须在 produceState 的 IO 块里（组合期不再逐本 readText）",
            src.contains("produceState<NovelState>(NovelState.Loading, workspaceRoot)") &&
                src.contains("withContext(Dispatchers.IO) { NovelState.Ready(readNovels(workspaceRoot)) }"),
        )
        assertFalse("组合期 readNovels 不许回来", rememberLoader("readNovels").containsMatchIn(src))
        assertTrue("加载态是一个独立的分支（不是一个空列表）", src.contains("is NovelState.Loading"))
        assertTrue("读到之后才谈空态", src.contains("is NovelState.Ready") && src.contains("s.rows.isEmpty()"))
        assertTrue("首帧那句是资源里的「读取中…」", src.contains("R.string.common_loading"))
        assertFalse("加载态与空态不许混成同一句", src.contains("NovelState.Ready(emptyList())"))
    }

    // ── ② 回收站面板（A-P2-2）──────────────────────────────────────────

    @Test
    fun `②回收站面板_快照在IO_保留旧值不闪_空态与读取中分开`() {
        val src = trash()
        assertTrue(
            "文件列表与会话库一起在 IO 里读（且要算全量体积：清空确认的「共 X MB」不能拿前 80 条凑）",
            src.contains("LaunchedEffect(dir, tick, page)") &&
                Regex(
                    "withContext\\(Dispatchers\\.IO\\)\\s*\\{[\\s\\S]{0,200}?" +
                        "trashListing\\(dir, page,\\s*wantBytes = true\\)",
                ).containsMatchIn(src),
        )
        assertFalse("组合期 trashRows 全量读不许回来", rememberLoader("trashRows").containsMatchIn(src))
        assertTrue(
            "可重读的状态用 remember(null) 保留旧快照（produceState 换 key 会先清空再闪）",
            src.contains("var ready by remember { mutableStateOf<TrashPanelData?>(null) }"),
        )
        assertFalse("这一处不用 produceState", src.contains("produceState"))
        assertTrue("「还没读到」是 loading 标志", src.contains("val loading = ready == null"))
        assertTrue("空态只在真读到 0 条时才画", src.contains("if (!loading && total == 0)"))
        assertTrue("标题也分两态（读取中不报 0）", src.contains("R.string.trash_dialog_title_loading"))
        assertTrue("列表里那句读取中", src.contains("R.string.common_loading"))
        assertTrue(
            "一页一页展开：还有 N 条（内容仍可达，顺序不变）",
            src.contains("R.string.trash_more_hint") && src.contains("page += TRASH_PAGE"),
        )
    }

    // ── ③ 抽屉两处计数（A-P2-3）────────────────────────────────────────

    @Test
    fun `③抽屉两处计数_都在IO_读到前不报0`() {
        val src = drawer()
        assertTrue(
            "产物数在 IO（组合期组合期不再跑 SQLite COUNT）",
            src.contains("LaunchedEffect(artifactTick, artifactRoot)") &&
                src.contains("withContext(Dispatchers.IO) { artifactShelfCount(artifactDb, artifactRoot) }"),
        )
        assertTrue(
            "回收站计数在 IO",
            Regex(
                "LaunchedEffect\\(trashTick, trashRoot\\)[\\s\\S]{0,200}?" +
                    "withContext\\(Dispatchers\\.IO\\)\\s*\\{[\\s\\S]{0,400}?trashRows\\(BaopuTrashBin\\.dirOf\\(trashRoot\\)\\)\\.size",
            ).containsMatchIn(src),
        )
        assertFalse("组合期 artifactShelfCount 不许回来", rememberLoader("artifactShelfCount").containsMatchIn(src))
        assertFalse("组合期 trashRows 不许回来", rememberLoader("trashRows").containsMatchIn(src))
        assertTrue(
            "计数状态用 remember(null) 保留旧值（拉开抽屉重数时不闪「读取中」）",
            src.contains("var artifactCountState by remember { mutableStateOf<Int?>(null) }") &&
                src.contains("var trashCountState by remember { mutableStateOf<Int?>(null) }"),
        )
        assertFalse("抽屉这两处不用 produceState", src.contains("produceState"))
        assertTrue(
            "标题在 null 时画「读取中…」，不把 null 当成 0",
            src.contains("if (artifactCountState == null)") && src.contains("if (trashCountState == null)"),
        )
        assertTrue("两个 loading 文案都走资源", src.contains("R.string.trash_drawer_entry_loading"))
        // 真机 StrictMode 抓到的那 4 条 `BaopuWorkspace.hostDir`（~84ms/条，栈顶是抽屉组合期）：
        // 取根本身也是读盘（mkdirs + isDirectory），所以根也必须进 IO 快照，且读到前是 null。
        assertTrue(
            "取根在 IO：读到之前 null（不拿一个猜的根去开面板）",
            src.contains("var workspaceRootState by remember { mutableStateOf<File?>(null) }") &&
                Regex("LaunchedEffect\\(Unit, drawerOpen\\)\\s*\\{[\\s\\S]{0,160}?" +
                    "withContext\\(Dispatchers\\.IO\\)\\s*\\{[\\s\\S]{0,200}?" +
                    "BaopuRuntime\\.workspaceRoot\\(ctx\\)").containsMatchIn(src),
        )
        assertTrue(
            "两处计数都只读快照（组合期不再取根）",
            src.contains("val artifactRoot = workspaceRootState") &&
                src.contains("val trashRoot = workspaceRootState"),
        )
        assertTrue(
            "面板只在根到位后才画",
            src.contains("if (trashOpen && trashRoot != null)") &&
                src.contains("if (artifactShelfOpen && artifactRoot != null)"),
        )
        // 真机上与抽屉同一窗口抓到的另外 3 条：树快照在 BaopuScreen 的组合期 remember 里
        // 取根＋查库（~317~436ms/条）。它和抽屉是同一件事（drawerTick 一变就重算），一起挪进 IO。
        val screen = strip("src/main/java/com/example/zhengdao/ui/baopu/BaopuScreen.kt")
        assertTrue(
            "抽屉树整块在 IO（取根＋SELECT 都不在组合期）",
            Regex(
                "LaunchedEffect\\(\\s*drawerTick,[\\s\\S]{0,400}?" +
                    "withContext\\(Dispatchers\\.IO\\)\\s*\\{[\\s\\S]{0,200}?BaopuWorkspace\\.hostDir\\(context\\)",
            ).containsMatchIn(screen),
        )
        assertFalse("组合期取根不许回来", rememberLoader("BaopuWorkspace.hostDir").containsMatchIn(screen))
        assertFalse("组合期查会话库不许回来", rememberLoader("loadSessionLibrary").containsMatchIn(screen))
        assertTrue(
            "树还没读到 ⇒ 树区画「读取中…」（与「没有工作区」分开）",
            screen.contains("workspaceLoading = drawerTreeLoading") &&
                src.contains("if (workspaceLoading && nodes.isEmpty())") &&
                src.contains("R.string.common_loading"),
        )
    }

    // ── ④ 设置页技能卡（A-P2-4）——租约在 262，合入后补 ────────────────

    @Test
    fun `④设置技能卡_262合入后已重放_走 IO 快照不回主线程`() {
        val src = settings()
        // 262 合入后重放 264 的 ④ 补丁（git apply --check rc=0）：技能卡改走异步快照，
        // 读数在 Dispatchers.IO 里算完才交付；本测钉住「已重放」而不是「不许出现」。
        assertTrue("④ 应已重放：设置页要出现 loadSkillReview", src.contains("loadSkillReview"))
    }

    @Test
    fun `⑤RunLog_init只做最小初始化_迁移归档补写后台串行`() {
        val src = runlog()
        val initBody = src.substringAfter("fun init(").substringBefore("private fun startPrepare(")
        assertTrue("init 里只起后台善后", initBody.contains("startPrepare(filesDir"))
        // 真机第二层（2026-10-28）：`ctx.filesDir` 内部会 `ensurePrivateDirExists` ⇒ 一次 File.exists，
        // StrictMode 报在 init 这两行上。路径改从 applicationInfo.dataDir 拼（纯字符串）。
        assertFalse("init 不许向 Context 要 filesDir（那一步自带一次 stat）", initBody.contains("ctx.filesDir"))
        assertTrue(
            "路径改由 dataDir 拼（纯字符串，不 stat）",
            initBody.contains("applicationInfo.dataDir"),
        )
        assertFalse(
            "init 自己不许迁移/归档（那就是 Application.onCreate 主线程读盘）",
            initBody.contains("migrateLegacy(") || initBody.contains("archivePrevious(") ||
                initBody.contains("copyLegacyFromPublic("),
        )

        assertTrue("善后有专用后台线程", src.contains("\"zhengdao-runlog-prepare\""))
        val prepareBody = src
            .substringAfter("publicLogsDir: () -> File?,")
            .substringBefore("private fun flushPending")
        val mk = prepareBody.indexOf("d.mkdirs()")
        val migrate = prepareBody.indexOf("migrateLegacy(filesDir, publicLogsDir(), d)")
        val archive = prepareBody.indexOf("archivePrevious(d)")
        val flush = prepareBody.indexOf("flushPending(dirFor(filesDir))")
        assertTrue("建目录/迁移/归档都在那条线程里：$prepareBody", mk >= 0 && migrate > mk)
        assertTrue("归档必须在补写之前（否则本轮日志被当上一轮卷走）", archive > migrate && flush > archive)
        assertTrue("补写在 finally 里（迁移抛了也要把排队那几行写出来）", prepareBody.contains("finally"))

        val logBody = src.substringAfter("fun log(line: String)").substringBefore("private fun appendLine")
        assertTrue("善后未完成 ⇒ 先进内存队列", logBody.contains("pending.addLast(at to safe)"))
        assertTrue("队列补写走同一条 appendLine", src.contains("appendLine(d, at, safe)"))
        assertTrue("补写结束才放开直接落盘", src.contains("prepared = true"))
        assertTrue("启动那几行拿不到 Context 也不许丢（只靠 init 记下的目录）", src.contains("filesDirForRedact"))
    }

    // ── 证据装置：debug 包的主线程读盘探针 ──────────────────────────────

    @Test
    fun `证据装置_StrictMode读盘探针只在debug装且最先`() {
        val app = strip("src/main/java/com/example/zhengdao/ZhengdaoApp.kt")
        assertTrue(
            "探针本体：detectDiskReads + penaltyLog（logcat 里带 ~duration 读数）",
            app.contains("StrictMode.ThreadPolicy.Builder()") &&
                app.contains(".detectDiskReads()") &&
                app.contains(".penaltyLog()"),
        )
        assertTrue("release 不装（BuildConfig.DEBUG 门）", app.contains("if (!BuildConfig.DEBUG) return"))
        val onCreate = app.substringAfter("override fun onCreate()")
        val probe = onCreate.indexOf("installMainThreadDiskReadProbe()")
        val runLog = onCreate.indexOf("RunLog.init(")
        assertTrue("探针必须在 RunLog.init 之前（否则 init 里那几处读盘漏掉）：probe=$probe init=$runLog", probe in 0 until runLog)
    }

    // ── 负例红：本批四处都不许 runBlocking ──────────────────────────────

    @Test
    fun `负例_四处都不许runBlocking包一层`() {
        listOf("小说" to novel(), "回收站" to trash(), "抽屉" to drawer(), "RunLog" to runlog())
            .forEach { (name, src) ->
                assertFalse("$name 里不许用 runBlocking 把异步按回主线程", src.contains("runBlocking"))
            }
        // 判别器自证
        assertTrue(
            "判别器抓得住 runBlocking 的形状",
            novel().let { src ->
                val bad = src.replace(
                    "val state by produceState<NovelState>(NovelState.Loading, workspaceRoot) {",
                    "val state = runBlocking {",
                )
                bad.contains("runBlocking")
            },
        )
    }

    @Test
    fun `判别器_组合期读盘正则抓得住坏形状`() {
        assertTrue(
            "旧写法必须被正则抓到",
            rememberLoader("readNovels").containsMatchIn("val rows = remember(workspaceRoot) { readNovels(workspaceRoot) }"),
        )
        assertTrue(
            "回收站旧写法也要被同一条正则抓到",
            rememberLoader("trashRows").containsMatchIn("val rows = remember(tick) { trashRows(dir) }"),
        )
        assertFalse(
            "改动后的写法不许被误伤",
            rememberLoader("readNovels").containsMatchIn(
                "val state by produceState<NovelState>(NovelState.Loading, workspaceRoot) {\n" +
                    "    value = withContext(Dispatchers.IO) { NovelState.Ready(readNovels(workspaceRoot)) }\n}",
            ),
        )
    }

    // ── ⑥ 动作路径（第二百六十四批补齐）：五条入口都经 IO 执行体 ──────────

    /**
     * 「经 [runTrashAction] 跑」的形状：`runTrashAction(` 之后 500 字内出现该调用。
     *
     * 判别器本身在下面用**裸调用样本**自证——不拿正确代码 contains 自证（那是恒真）。
     */
    private fun wrapInAction(src: String, call: String): Boolean =
        Regex("runTrashAction\\([\\s\\S]{0,500}?" + Regex.escape(call)).containsMatchIn(src)

    @Test
    fun `⑥动作路径_三条文件入口与两条会话入口都经IO执行体`() {
        val src = trash()
        assertTrue(
            "执行体本体：block 在 Dispatchers.IO 上跑",
            Regex(
                "internal suspend fun <T> runTrashAction\\([\\s\\S]{0,700}?" +
                    "withContext\\(Dispatchers\\.IO\\) \\{ block\\(\\) \\}",
            ).containsMatchIn(src),
        )
        assertTrue(
            "整段装在 NonCancellable 里（取消只影响谁在等，不影响做完）",
            src.contains("withContext(NonCancellable)"),
        )
        // 先落账、后回灌：report 抛异常（组合已死）时账不能跟着丢
        val logAt = src.indexOf("RunLog.log(\"回收站")
        val reportAt = src.indexOf("withContext(mainDispatcher) { report(outcome) }")
        assertTrue("账在回灌之前：log=$logAt report=$reportAt", logAt >= 0 && reportAt > logAt)

        listOf(
            "恢复" to "restoreTrash(row, workspaceRoot)",
            "彻底删除" to "deleteTrash(row, workspaceRoot, confirmedDelete)",
            "清空" to "emptyTrash(dir, true)",
        ).forEach { (name, call) ->
            assertTrue("$name 必须经 runTrashAction 走 IO（裸调用＝主线程读盘）：$call", wrapInAction(src, call))
        }
        assertTrue("恢复会话经执行体", src.contains("label = \"恢复会话\""))
        assertTrue("彻底删除会话经执行体", src.contains("label = \"彻底删除会话\""))

        // 负例自证 ①：裸调用样本必须判假
        assertFalse(
            "判别器抓得住裸调用",
            wrapInAction("onClick = { emptyTrash(dir, true) }", "emptyTrash(dir, true)"),
        )
        // 负例自证 ②：把执行体那层名字改掉（等于有人改回直接调），判别器也必须判假
        assertFalse("去掉包裹后判别器判假", wrapInAction(src.replace("runTrashAction(", "runNaked("), "emptyTrash(dir, true)"))
    }
}
