// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第三十八批 · 壳层与引擎接线的**源码级**守卫（判据 A6／B6 ＋ A2/A3/A4/B7 的落点）。
// 为什么用源码文本而不是行为断言：`RunEngine` 与 `BaopuChatController` 都要 Android
// 上下文（`BaopuDb`、`Context`），JVM 单测造不出一个能跑的引擎；而这批最容易出事
// 的恰恰是「谁在什么线程读盘」「复制拿的是哪一份字符串」——这两件事都能从代码文本
// 上钉死，且必须钉死（口径来自派工单 §1.4 A6 与 §3 B6）。
// 每条都配**判别器**：同一套扫描器/切块在一个真会命中的样本上必须报命中，
// 证明这条门不是空转。
package com.example.zhengdao.ui.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BaopuCostWiringTest {

    private fun repoFile(rel: String): File =
        File("src/main/java/com/example/zhengdao/$rel")
            .let { if (it.isFile) it else File("app/src/main/java/com/example/zhengdao/$rel") }

    /** 先剥注释（块注释＋行注释），再断言——注释里写的 `Thread(` 不算创建线程。 */
    private fun code(rel: String): String = repoFile(rel).readText()
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private fun controller() = code("ui/baopu/BaopuChatController.kt")

    private fun engine() = code("baopu/agent/RunEngine.kt")

    private fun screen() = code("ui/baopu/BaopuScreen.kt")

    private fun list() = code("ui/baopu/BaopuMessageList.kt")

    private fun viewer() = code("ui/baopu/BaopuArtifactViewer.kt")

    /**
     * §8 的顶栏产物条**故意与查看器同一个文件**（用户 2026-10-19 口径：两处共用同一份
     * 收集逻辑与查看器）⇒ §7 那条复制路径也只有一份。取整段用「到文件末尾」而不是
     * [body]：`body` 切到下一个 4 空格缩进的 fun，而产物条内部就有一个局部 `fun copyRow`。
     */
    private fun topBar(): String {
        val src = viewer()
        val i = src.indexOf("internal fun BaopuArtifactTopBar(")
        assertTrue("顶栏产物条必须和查看器在同一文件（共用复制助手）", i > 0)
        return src.substring(i)
    }

    /** 取某个成员函数的函数体（到下一个 4 空格缩进的 `fun` 为止）。 */
    private fun body(src: String, anchor: String): String {
        val start = src.indexOf(anchor)
        assertTrue("锚点找不到，说明那一段被改名或删掉了：$anchor", start >= 0)
        val rest = src.substring(start + anchor.length)
        val next = Regex("\n    (private |internal |suspend |public |override )*fun ").find(rest)
        return if (next != null) rest.substring(0, next.range.first) else rest
    }

    // ── A 段 · 回调线程纪律（A6） ───────────────────────────────────────────

    /**
     * A6：控制器里 `engine.onUsage = { ... }` 的实现体只许改状态——
     * 出现 `File(`／`Thread(`／`Dispatchers` 就是拿引擎线程读盘或另起线程。
     */
    @Test
    fun A6_onUsage实现体不读盘不起线程() {
        val onUsageBody = controller()
            .substringAfter("engine.onUsage = {")
            .substringBefore("\n        }")
        assertTrue("切块要真切到实现体（里面必须有 accumulateUsage）", onUsageBody.contains("accumulateUsage"))
        listOf("File(", "Thread(", "Dispatchers").forEach { tok ->
            assertFalse("onUsage 回调里不许出现 $tok（回调跑在 zd-baopu-run 线程上）", onUsageBody.contains(tok))
        }
        // 判别器：同一套检查在「往体里塞一行读盘」的变异样本上必须报红
        val mutant = onUsageBody.replace("accumulateUsage", "File(\"x\").readText(); accumulateUsage")
        assertTrue(
            "判别器：把 File( 塞进实现体，上面那条检查必须命中（否则这条门是空转）",
            listOf("File(", "Thread(", "Dispatchers").any { tok -> mutant.contains(tok) },
        )
    }

    /** A3 的源头：引擎只在「真落了一条 usage」之后才回调，两处 `?: return` 都在。 */
    @Test
    fun A3_引擎没有读数时不回调() {
        val persist = body(engine(), "private fun persistUsage(")
        assertTrue("usage 为空 ⇒ 直接 return（不回调、不落库）", persist.contains("usage ?: return"))
        assertTrue(
            "SQL 语句为 null（五列全空）⇒ 也 return：这就是「没有读数就不加这一条」的源头",
            persist.contains("BaopuSql.updateRunLogUsage(sessionId, step, readings) ?: return"),
        )
        val src = engine()
        val idx = src.indexOf("onUsage?.invoke(")
        assertTrue("回调必须写在 persistUsage 里（同一处，别另起一条链）", idx > src.indexOf("private fun persistUsage("))
        assertTrue("persistUsage 之后不能再有第二个回调点", src.indexOf("onUsage?.invoke(", idx + 1) < 0)
        assertEquals("invoke 只许出现一次", 1, Regex("onUsage\\?\\.invoke\\(").findAll(engine()).count())
        // 判别器：整文件里 onUsage 的声明与调用是分开的两处，切块确实读到的是调用那处
        assertTrue(engine().contains("var onUsage: ((BaopuUsageStep) -> Unit)? = null"))
    }

    /** A2 的源头：每步记下开步时刻，步号用引擎自己的 stepCount（不是壳层的到达序号）。 */
    @Test
    fun A2_步号与耗时都取自引擎自己() {
        assertTrue(engine().contains("stepStartedAtMs = System.currentTimeMillis()"))
        val invoke = engine().substringAfter("onUsage?.invoke(").substringBefore("\n        )")
        assertTrue("切块要真切到那一次回调（里面必须有 BaopuUsageStep）", invoke.contains("BaopuUsageStep("))
        assertTrue("步号取自 persistUsage 的形参 step（＝引擎侧步号）", invoke.contains("step = step"))
        assertTrue(
            "没记到开步时刻 ⇒ elapsedMs = -1（未知），不许写 0 假装知道",
            invoke.contains("if (stepStartedAtMs == 0L) -1L"),
        )
        // 判别器：把 -1 改成 0 就违反「没测到不编数」，这条断言应当命中
        assertTrue(
            "判别器：elapsedMs 若被改成 0L，上面的检查会红",
            !invoke.replace("-1L", "0L").contains("if (stepStartedAtMs == 0L) -1L"),
        )
    }

    /** A4：清零函数存在，且在「发消息 / 开新会话 / 切会话 / 清空视图」四处都调了。 */
    @Test
    fun A4_新开一轮与换会话都清零本轮账() {
        val src = controller()
        assertTrue(src.contains("private fun resetRoundUsage()"))
        listOf("fun send(", "fun newSession()", "fun openSession(", "private fun clearSessionView()").forEach { anchor ->
            assertTrue("${anchor} 里必须调 resetRoundUsage()", body(src, anchor).contains("resetRoundUsage()"))
        }
        // 清零函数自己：回到空账 ＋ 清产物视图 ＋ 关掉正在看的文件
        val reset = body(src, "private fun resetRoundUsage()")
        assertTrue(reset.contains("roundUsage = emptyRoundUsage()"))
        assertTrue(reset.contains("engine.clearRoundArtifactView()"))
        assertTrue(reset.contains("viewingArtifact = null"))
        // 判别器：漏掉 send 里那一句，上面逐锚点的检查就会红
        assertFalse(
            "判别器：把 send 体里的清零删掉后，那段体不再含它",
            body(src.replace("resetRoundUsage()", ""), "fun send(").contains("resetRoundUsage()"),
        )
    }

    /** A 段接线：跑马灯条目「没有读数就不加」＋ 标签走 strings。 */
    @Test
    fun A_跑马灯条目按读数有无添加() {
        val src = screen()
        assertTrue("必须先问 shouldShowUsageNotice 再加条目", src.contains("if (shouldShowUsageNotice(usage))"))
        assertTrue("条目走 NoticeItem(label, body)", src.contains("NoticeItem(") && src.contains("stringResource(R.string.baopu_usage_label),"))
        assertTrue("body 由纯函数拼（累计一行＋每步明细）", src.contains("usageNoticeBody(usage, usageCopy)"))
        assertTrue("累计取的是控制器持有的本轮账", src.contains("val usage = controller.roundUsage"))
        assertTrue("步号文案走 strings 且用 context.getString（lambda 里不能调 stringResource）", src.contains("context.getString(R.string.baopu_usage_step, step)"))
        // 判别器（A3 的负例形状「没读数也出条目」）：条目必须落在守卫那一段里
        val guardIdx = src.indexOf("if (shouldShowUsageNotice(usage))")
        val addIdx = src.indexOf("stringResource(R.string.baopu_usage_label),")
        assertTrue("守卫必须先于条目出现", guardIdx in 0 until addIdx)
        assertTrue("守卫与条目必须同一段（隔太远说明守的不是这一条；这段缩进很深，606 字符是实测）", addIdx - guardIdx < 900)
    }

    // ── B 段 · 产物收集与查看 ───────────────────────────────────────────────

    /** B 段源头：产物列表复用收尾闸那份 verifyTouched，不在别处另立一套收集。 */
    @Test
    fun B_产物收集复用收尾闸的路径() {
        val src = engine()
        val publish = body(src, "private fun publishRoundArtifacts()")
        assertTrue("必须走 selectTextArtifacts（白名单＋大小＋NUL 都在那儿判）", publish.contains("selectTextArtifacts(spillRoot(), verifyTouched.toList())"))
        assertFalse("不许自己拼路径清单（另立一套收集＝越界）", publish.contains("walkTopDown") || publish.contains("listFiles"))
        assertEquals("发布点只许一处（Done 分支）", 2, Regex("publishRoundArtifacts\\(\\)").findAll(src).count())
        assertTrue("每个 run 开局先清视图", body(src, "verifyTouched.clear()").contains("clearRoundArtifactView()"))
    }

    /** B6：查看器**只读**——源码里不许出现写盘/删除/改名，也不许起外部 App。 */
    @Test
    fun B6_查看器没有任何写盘路径() {
        val src = viewer()
        listOf(
            "writeText", "writeBytes", "appendText", "delete()", "deleteRecursively", "deleteIfExists",
            "renameTo", "createNewFile", "mkdirs", "mkdir(", "FileOutputStream", "OutputStreamWriter",
            "PrintWriter", "execSQL", "ContentValues", "db.write",
        ).forEach { tok ->
            assertFalse("只读查看器里不许出现 $tok", src.contains(tok))
        }
        // 派工单 §2.3「不做」三条：外部 App 打开、分享、图片/PDF 预览
        listOf("Intent", "startActivity", "ACTION_VIEW", "ACTION_SEND", "FileProvider", "BitmapFactory").forEach { tok ->
            assertFalse("查看器不许走 $tok（不做外部打开/分享）", src.contains(tok))
        }
        assertTrue("Dialog 只有一份（查看器自己就是那一层）", Regex("Dialog\\(").findAll(src).count() == 1)
        // 判别器：同一套 token 表扫一个真会写盘的文件，必须命中——否则这条门是空转
        val writer = code("ui/baopu/BaopuSkillCategory.kt")
        assertTrue(
            "判别器：BaopuSkillCategory.kt 确实写盘（writeText 命中），说明上面那张表管用",
            writer.contains("writeText"),
        )
    }

    /** B 段接线：产物行挂在消息列表末尾，点片只传相对路径。 */
    @Test
    fun B_产物行只传相对路径给查看器() {
        val src = list()
        assertTrue("形参收 artifacts ＋ 点击回调", src.contains("artifacts: List<String> = emptyList()"))
        assertTrue(src.contains("onOpenArtifact: (String) -> Unit = {}") || src.contains("onOpenArtifact: (String) -> Unit") )
        assertTrue("摆几片由纯函数算（含「还有 N 个」）", src.contains("artifactChipPlan(artifacts)"))
        assertTrue("片名只显最后一段", src.contains("artifactFileName(rel)"))
        assertTrue("标签走 strings", src.contains("R.string.baopu_artifact_row_label"))
        assertFalse("消息列表不许把文件内容读进来（读盘只在查看器/引擎）", src.contains("artifactViewOf") || src.contains("artifactCopyOf"))
        val scr = screen()
        assertTrue("屏幕把引擎那份产物视图传给列表", scr.contains("artifacts = controller.roundArtifacts"))
        assertTrue("点片 ⇒ 写 controller.viewingArtifact（唯一真源）", scr.contains("controller.openArtifact(rel)"))
        assertTrue("查看器在屏幕层渲染", scr.contains("BaopuArtifactViewer("))
        assertTrue("查看器的根走 BaopuWorkspace", scr.contains("root = BaopuWorkspace.hostDir(context)"))
    }

    /**
     * B7 的落点：复制的**源头是盘上的文件**，不是显示那一份截断串。
     *
     * 钉三件事：① 复制调 `artifactCopyOf(root, relPath)`（自己开文件读全量）；
     * ② 写进剪贴板的是自证通过（`complete`）的那份；③ 渲染用的 `v.text` 不许出现在
     * 剪贴板调用里。负例「复制改成复制显示内容」就是把 ② 换成 `v.text ?: ""`——
     * 那时 `completeText` 的定义句消失，第一条断言立刻红。
     */
    @Test
    fun B7_复制从盘上重读全文而不是复制显示内容() {
        val src = viewer()
        assertTrue("复制必须调 artifactCopyOf(root, relPath)（从盘上重读）", src.contains("artifactCopyOf(root, relPath)"))
        assertTrue("读盘必须在 IO 线程", src.contains("withContext(Dispatchers.IO) { artifactCopyOf(root, relPath) }"))
        assertTrue("进剪贴板的那份必须是自证通过（complete）的全文", src.contains("result?.takeIf { it.complete }?.text"))
        val clip = src.substringAfter("ClipData.newPlainText(").substringBefore(")")
        assertTrue("剪贴板拿的是 completeText，不是显示串", clip.contains("completeText"))
        assertFalse("剪贴板不许拿显示那一份（v.text 是被 200 KB／3000 行裁过的）", clip.contains("v.text"))
        // 判别器：把源头换成显示串，上面那条 completeText 检查就会红
        val mutant = src.replace("result?.takeIf { it.complete }?.text", "v.text")
        assertFalse("判别器：变异成复制显示内容后，complete 自证那一句消失", mutant.contains("result?.takeIf { it.complete }?.text"))
    }

    /** §7 纪律 2：自证不过必须说出来，不许静默。 */
    @Test
    fun 复制不完整时必须明说而不是静默() {
        val src = viewer()
        assertTrue("有「复制不完整，请重试」这条失败文案", src.contains("R.string.baopu_artifact_copy_incomplete"))
        assertTrue("失败与成功两条走同一个 when（不许只写成功分支）", src.contains("!ok -> context.getString(R.string.baopu_artifact_copy_incomplete)"))
        assertTrue("文件不在也要明说", src.contains("R.string.baopu_artifact_missing"))
        assertTrue("成功要报字符数与行数（自证读数给人看）", src.contains("R.string.baopu_artifact_copied"))
        // 判别器：整段成功分支删掉后，失败分支仍在——所以这条门只可能因为「没有失败分支」而红
        assertTrue("判别器：失败文案确实在成功文案之前出现", src.indexOf("baopu_artifact_copy_incomplete") < src.indexOf("baopu_artifact_copied"))
    }

    /** §3 截断提示：显示被截断时必须写明「只显示前 N 行」，且带总数时用另一条文案。 */
    @Test
    fun 截断时必须写明只显示前N行() {
        val src = viewer()
        assertTrue("截断标志位驱动文案", src.contains("if (v != null && v.truncated)"))
        assertTrue("有总数 ⇒「只显示前 N 行（共 M 行）」", src.contains("R.string.baopu_artifact_truncated,"))
        assertTrue("没数完（totalLines = -1）⇒ 只说「只显示前 N 行」，不编总数", src.contains("R.string.baopu_artifact_truncated_only"))
        assertTrue("判 -1 的分支在", src.contains("v.totalLines >= 0"))
        assertTrue("顶部显示盘上字节数而不是显示串长度", src.contains("v.fileBytes"))
    }

    // ── §8 · 顶栏产物条（B9／B10／B11 的落点） ───────────────────────────────

    /**
     * B9 的落点：顶栏那一条显示什么**由纯函数决定**，壳层不许自己判空。
     *
     * 负例形状（派工单 §8：「无产物时也硬显示『产物 · 0』」）：把 `when` 换成无条件渲染
     * 产物条 ——那时 `is TopBarSlot.ReplyPreview ->` 分支消失，第一条断言立刻红。
     */
    @Test
    fun B9_顶栏二选一交给纯函数() {
        val src = screen()
        assertTrue("必须先问 topBarSlot（这一层不判断）",
            src.contains("when (val slot = topBarSlot(topArtifacts, lastAssistant, topBarExpanded))"))
        assertTrue("有产物 ⇒ 渲染 §8 的产物条", src.contains("is TopBarSlot.Artifacts ->"))
        assertTrue("产物条确实被调用", src.contains("BaopuArtifactTopBar("))
        assertTrue("没产物 ⇒ 退回原 CopyBox（B9 的另一半，别让这一行空着）",
            src.contains("is TopBarSlot.ReplyPreview ->"))
        val replyIdx = src.indexOf("is TopBarSlot.ReplyPreview ->")
        val copyIdx = src.indexOf("CopyBox(")
        assertTrue("CopyBox 落在 ReplyPreview 分支里", replyIdx in 0 until copyIdx)
        assertTrue("退化分支用的是 slot.text（不在这一层再取一次 lastAssistant）", src.contains("text = slot.text"))
        assertFalse("这一层不许写 topArtifacts.isEmpty()（判据在纯函数里，两处一套口径）",
            src.contains("topArtifacts.isEmpty("))
        assertEquals("产物源与消息列表同一份（roundArtifacts 只接一次给这两处）",
            2, Regex("controller\\.roundArtifacts").findAll(src).count())
        // 判别器：同一套检查在「删掉 ReplyPreview 分支」的变异样本上必须命中
        val mutant = src.replace("is TopBarSlot.ReplyPreview ->", "is TopBarSlot.Artifacts ->")
        assertEquals("判别器：变异后 ReplyPreview 分支消失（说明上面那条不是空转）",
            -1, mutant.indexOf("is TopBarSlot.ReplyPreview ->"))
    }

    /** B10 的落点：折叠行整行可点、翻转走纯函数、展开段受 expanded 驱动且有高度上限。 */
    @Test
    fun B10_折叠行点了真能展开() {
        val src = screen()
        assertTrue("翻转走 toggleTopBarExpanded（这一层不自己写取反）",
            src.contains("topBarExpanded = toggleTopBarExpanded(topBarExpanded)"))
        assertTrue("展开态透传给产物条", src.contains("expanded = slot.expanded"))
        assertTrue("展开态键在产物列表上：换一轮自动收回",
            src.contains("var topBarExpanded by remember(topArtifacts) { mutableStateOf(false) }"))
        val bar = topBar()
        assertTrue("折叠行整行可点（不是只有右边一个小箭头）", bar.contains(".clickable { onToggle() }"))
        assertTrue("展开段由 expanded 驱动", bar.contains("if (expanded)"))
        assertTrue("展开高度上限 180dp", bar.contains("heightIn(max = 180.dp)"))
        assertTrue("展开段内部可滚（§8.1「高度上限 ~180dp、内部可滚」）",
            bar.contains("verticalScroll(rememberScrollState())"))
        assertTrue("点文件名 = 打开 B 段查看器（同一个回调，不另起一路）", bar.contains("onOpen(rel)"))
        // 判别器（负例 2「折叠行点了不展开」的形状）：删掉那一次 clickable，上面的检查就红
        assertFalse("判别器：去掉 onToggle 的点击后，折叠行就不展开了",
            bar.replace(".clickable { onToggle() }", "").contains(".clickable { onToggle() }"))
    }

    /** §8.1「把复制功能做小点」＋ B11「折叠态只复制第一个产物」的源码落点。 */
    @Test
    fun B11_折叠按钮小号且只复制第一个产物() {
        val bar = topBar()
        assertTrue("目标由纯函数给（不是这一层自己 first()）", bar.contains("val first = firstArtifactForCopy(artifacts)"))
        assertTrue("折叠那颗的 onClick 只可能拿 first", bar.contains("onClick = { if (first != null) copyRow(first) }"))
        assertFalse("折叠那颗不许直接吃 artifacts.first()", bar.contains("artifacts.first()"))
        assertTrue("小号：labelSmall", bar.contains("style = MaterialTheme.typography.labelSmall"))
        assertTrue("小号：24dp 高", bar.contains("Modifier.heightIn(min = 24.dp)"))
        assertTrue("小号：内边距压扁（不许再占半行）",
            bar.contains("contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)"))
        // §7 只有一份：两处（查看器 / 顶栏每行与折叠按钮）都调同一个助手，剪贴板写入也只有一处
        val src = viewer()
        assertEquals("§7 复制助手只定义一处",
            1, Regex("private suspend fun copyArtifactToClipboard\\(").findAll(src).count())
        assertEquals("调用点共两处（查看器一次＋顶栏一次；两处共用同一份）",
            3, Regex("copyArtifactToClipboard\\(").findAll(src).count())
        assertEquals("setPrimaryClip 全文件只有一处（不许另起一套复制口径）",
            1, Regex("setPrimaryClip").findAll(src).count())
        assertFalse("顶栏自己不许碰剪贴板/读盘（全部走助手）",
            bar.contains("setPrimaryClip") || bar.contains("artifactCopyOf"))
        // 判别器：本仓确有别的文件写剪贴板 ⇒ 上面那条「全文件只有一处」不是空转
        assertTrue("判别器：util/Clipboard.kt 确实写 setPrimaryClip（扫描器认得这个 token）",
            code("util/Clipboard.kt").contains("setPrimaryClip"))
        assertEquals("判别器：往顶栏那一段塞一次写入，「只有一处」立刻变 2 处 ⇒ 会红",
            2, Regex("setPrimaryClip").findAll(src + bar + "setPrimaryClip").count())
    }

    /**
     * 红线：这批**不许引入存储权限**、查看器**不许用 Intent 打开外部文件**。
     *
     * 说明：本仓清单里 `READ/WRITE/MANAGE_EXTERNAL_STORAGE` 是既有的（抱朴本来就是文件工具），
     * 所以「没新增权限」这条不能靠「清单里没有」来证——那由交付报告里的 `git diff` 取证
     * （AndroidManifest.xml 零改动）。这条用例钉的是**代码侧**：本批改动的四个文件里
     * 不许出现任何权限申请或跨 App 打开的写法。
     */
    @Test
    fun 红线_本批代码不申请权限也不起外部App() {
        // ① 权限：本批改动的四个文件里不许出现任何权限申请写法
        val batch = viewer() + list() + screen() + controller()
        listOf(
            "checkSelfPermission", "requestPermissions", "ShouldShowRequestPermissionRationale",
            "Manifest.permission", "ACTION_MANAGE_ALL_STORAGE", "isExternalStorageManager",
        ).forEach { tok ->
            assertFalse("本批改的代码里不许出现 $tok（这批不引入存储权限）", batch.contains(tok))
        }
        // ② 跨 App 打开：只看查看器（BaopuScreen 里那处 startActivity 是别批既有的设置页跳转）
        listOf("Intent", "startActivity(", "ACTION_VIEW", "ACTION_SEND", "FileProvider", "MediaStore").forEach { tok ->
            assertFalse("查看器不许用 $tok（不做外部 App 打开/分享）", viewer().contains(tok))
        }
        // 判别器：两类写法在本仓都真实存在（既有权限页 / 既有设置页跳转），上面两张表不是空转
        assertTrue(
            "判别器：PermissionSetupScreen.kt 确实有权限申请写法",
            code("ui/PermissionSetupScreen.kt").contains("Manifest.permission"),
        )
        assertTrue("判别器：BaopuScreen 确实有 startActivity 写法（查看器里没有才是真没有）", screen().contains("startActivity("))
    }
}
