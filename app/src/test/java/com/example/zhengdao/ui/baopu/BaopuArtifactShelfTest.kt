package com.example.zhengdao.ui.baopu

import com.example.zhengdao.baopu.artifactCopyOf
import com.example.zhengdao.baopu.artifactViewOf
import com.example.zhengdao.baopu.selectTextArtifacts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 第三十八批 §9.2／§9.3 · 抽屉产物区（回看／删除）· 判据 C2／C3／C4／C5／C6 ＋ §9.4 红线。
 *
 * 两层打法沿用 36 批（同一族「面板＋纯函数＋一个文件操作」）：
 *   1. **行为层**：真临时目录里造出「原件／回收站／同前缀兄弟目录／已经不在了的行」，
 *      跑 [moveArtifactToTrash]，断言**盘上到底变成什么样**——C5 的「第一击不动盘」、
 *      C6 的「只清台账」、§9.4 的「不彻底删」全靠这一层，源码字符串代替不了；
 *   2. **源码层**：读剥掉注释后的四个文件（面板、抽屉、引擎、查看器），钉「N 打开抽屉重数」
 *      「记账只在真写成功那一支」「确认位靠比对不是写死 true」「UI 不调工具层」——
 *      每条「不许出现 X」都配判别器自证，防门禁空跑。
 *
 * 为什么 C1 的「同一行/另一行」不在这里测：那是 SQL 与索引的事，归
 * `BaopuArtifactLedgerTest`（一条判据一个主人，不重复咬）。
 */
class BaopuArtifactShelfTest {

    // ── 源码读取（工作目录 = app/；判据一律读剥掉注释后的文本）──────────────

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    private fun strip(path: String): String = repoFile(path).readText(Charsets.UTF_8)
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private fun shelf() = strip("src/main/java/com/example/zhengdao/ui/baopu/BaopuArtifactShelf.kt")

    private fun drawer() = strip("src/main/java/com/example/zhengdao/ui/baopu/BaopuDrawer.kt")

    private fun engine() = strip("src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt")

    private fun viewer() = strip("src/main/java/com/example/zhengdao/ui/baopu/BaopuArtifactViewer.kt")

    private fun zh() = repoFile("src/main/res/values/strings.xml").readText(Charsets.UTF_8)

    private fun en() = repoFile("src/main/res/values-en/strings.xml").readText(Charsets.UTF_8)

    private fun placeholders(text: String): List<String> =
        Regex("%\\d\\$[a-zA-Z]").findAll(text).map { it.value }.toList()

    // ── 盘上现场 ──────────────────────────────────────────────────────────

    private lateinit var root: File

    @Before
    fun setUp() {
        root = File.createTempFile("zd38-ws", "").let { it.delete(); it.mkdirs(); it }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    /** 在工作区里造一个文件（返回它的相对路径，就是台账里那一列）。 */
    private fun make(rel: String, content: String = "甲\n"): String {
        val f = File(root, rel)
        f.parentFile?.mkdirs()
        f.writeText(content, Charsets.UTF_8)
        return rel
    }

    private fun trashDir(): File = BaopuTrashBin.dirOf(root)

    /** 回收站里现在有几个文件（不列子目录，与 36 批同一口径）。 */
    private fun trashFiles(): List<String> =
        trashDir().listFiles()?.filter { it.isFile }?.map { it.name }?.sorted() ?: emptyList()

    // ── C5：删除是两击，第一击盘上一个字节都不动 ────────────────────────────

    @Test
    fun `C5_第一击只回话_盘上一个字节都不动`() {
        val rel = make("报告/甲.md", "第一行\n第二行\n")
        val before = File(root, rel).length()
        trashDir().mkdirs()

        val o = moveArtifactToTrash(root, rel, nowMs = 1_700_000_000_000L, confirmed = false)

        assertTrue("第一击必须只拿到 NeedsConfirm：$o", o is ArtifactMoveOutcome.NeedsConfirm)
        assertEquals("问的是这一条", rel, (o as ArtifactMoveOutcome.NeedsConfirm).relPath)
        assertTrue("原件还在原位", File(root, rel).isFile)
        assertEquals("内容一个字节都没变", before, File(root, rel).length())
        assertTrue("确认那句要说「盘上还有东西」这一型", o.onDisk)
        assertEquals("回收站里不许多出东西", emptyList<String>(), trashFiles())
    }

    @Test
    fun `C5_第二击才进回收站_原件不在原位`() {
        val rel = make("输出/清单.txt", "甲\n乙\n")
        val bytes = File(root, rel).length()

        val first = moveArtifactToTrash(root, rel, nowMs = 1_700_000_000_999L, confirmed = false)
        assertTrue("第一击仍然是 NeedsConfirm：$first", first is ArtifactMoveOutcome.NeedsConfirm)

        val second = moveArtifactToTrash(root, rel, nowMs = 1_700_000_000_999L, confirmed = true)
        assertTrue("第二击才真搬：$second", second is ArtifactMoveOutcome.Moved)
        assertFalse("原位置已经没有它了", File(root, rel).exists())
        val name = "1700000000999-清单.txt"
        assertTrue("回收站里就是这一份：${trashFiles()}", File(trashDir(), name).isFile)
        assertEquals(
            "报给用户的是相对路径（不是一长串 /storage/…）",
            "回收站/$name",
            (second as ArtifactMoveOutcome.Moved).trashRelPath,
        )
        assertEquals("搬过去的字节数与原来一模一样", bytes, File(trashDir(), name).length())
        assertEquals(
            "中文内容也一个字符都不差",
            "甲\n乙\n",
            File(trashDir(), name).readText(Charsets.UTF_8),
        )
    }

    @Test
    fun `C5_确认位靠比对相对路径_不是写死的true`() {
        val src = shelf()
        assertTrue(
            "第二击的 confirmed 必须是「这一条是不是正在等确认」的比对结果",
            src.contains("confirmed = confirmingRel == row.relPath"),
        )
        assertTrue(
            "只有 NeedsConfirm 那一支才把这一条标成「等第二击」",
            src.contains("confirmingRel = if (o is ArtifactMoveOutcome.NeedsConfirm) row.relPath else null"),
        )
        assertFalse("界面不许直接写死 true（那就是没有二次确认）", src.contains("confirmed = true"))
        // 判别器自证：写死那种形状确实会被上面那条抓到
        assertTrue("判别器抓得住写死的形状", Regex("confirmed = true").containsMatchIn("val o = moveArtifactToTrash(confirmed = true)"))
    }

    // ── C1 的形状侧：落名与撞名（唯一索引那一半在台账测试里）─────────────────

    @Test
    fun `C1_落名带时间戳_撞名让路_绝不覆盖回收站里那一份`() {
        trashDir().mkdirs()
        val t1 = artifactTrashTargetOf(trashDir(), "x.txt", 1700L)
        assertEquals("空回收站里就是 <时间戳>-<原名>", "1700-x.txt", t1.name)
        // 把这一位占住（模拟「同一毫秒已经移进来一个同名文件」）
        t1.writeText("旧的一份", Charsets.UTF_8)
        val t2 = artifactTrashTargetOf(trashDir(), "x.txt", 1700L)
        assertFalse("第二次不许指着同一位（那就是覆盖）", t2.absolutePath == t1.absolutePath)
        assertEquals("-2 让路", "1700-x.txt-2", t2.name)
        val t3 = artifactTrashTargetOf(trashDir(), "x.txt", 1701L)
        assertEquals("换了时间戳就不是同一位", "1701-x.txt", t3.name)
        assertEquals("回收站里那一份原样未动", "旧的一份", t1.readText(Charsets.UTF_8))
    }

    @Test
    fun `C1_移进回收站的形状与工具层同一个字_但没调它`() {
        val rel = make("回收站2/顺手起的目录里.txt", "x\n")
        // 同前缀兄弟目录不是回收站里面（加固-1 那个洞：字符串前缀比较会把 回收站2 当成 回收站/）
        val o = moveArtifactToTrash(root, rel, nowMs = 42L, confirmed = true)
        assertTrue("兄弟目录里的文件照搬：$o", o is ArtifactMoveOutcome.Moved)
        assertFalse("原位空了", File(root, rel).exists())
        assertEquals("落的还是回收站那一格", listOf("42-顺手起的目录里.txt"), trashFiles())
    }

    // ── C6：台账指向一个已经不在的文件 ───────────────────────────────────────

    @Test
    fun `C6_盘上已经没有的行_两击只清台账_回收站不许多出东西`() {
        val rel = "报告/被用户自己删掉了.md"
        assertFalse("前置：盘上确实没有它", File(root, rel).exists())

        val first = moveArtifactToTrash(root, rel, nowMs = 77L, confirmed = false)
        assertTrue("第一击仍然只问（$first）", first is ArtifactMoveOutcome.NeedsConfirm)
        assertFalse("问的那一型是「清掉这一行」：盘上没有 ⇒ onDisk=false", (first as ArtifactMoveOutcome.NeedsConfirm).onDisk)

        val second = moveArtifactToTrash(root, rel, nowMs = 77L, confirmed = true)
        assertTrue("第二击 ⇒ 只清台账：$second", second is ArtifactMoveOutcome.LedgerOnly)
        assertEquals("回收站里一个字节都没多", emptyList<String>(), trashFiles())
        assertFalse("也不许凭空造出那个文件", File(root, rel).exists())
    }

    @Test
    fun `C6_徽标与只清台账的分工在界面上都看得见`() {
        val src = shelf()
        assertTrue("行上有「已不在盘上」徽标（走资源，不硬编码）", src.contains("R.string.baopu_artifact_shelf_offdisk"))
        assertTrue("徽标的条件就是 onDisk", src.contains("if (!row.onDisk)"))
        assertTrue(
            "确认那句分两型：盘上有 ⇒ 问移进回收站；没有 ⇒ 问清这一行",
            src.contains("if (row.onDisk) R.string.baopu_artifact_shelf_confirm") &&
                src.contains("else R.string.baopu_artifact_shelf_confirm_gone"),
        )
        assertTrue("LedgerOnly 也要有它自己那句（不许静默）", src.contains("R.string.baopu_artifact_shelf_ledger_cleared"))
    }

    // ── C2：不该动的东西一律拒，并且盘上不变 ────────────────────────────────

    @Test
    fun `C2_越界_回收站自己_目录 三类都拒且盘上不变`() {
        val inTrash = make("回收站/已经在里面的.txt", "x")
        val dir = File(root, "一个目录").apply { mkdirs() }
        assertTrue("前置：目录建出来了", dir.isDirectory)
        val before = root.walkTopDown().filter { it.isFile }.map { it.absolutePath }.sorted().toList()

        val cases = listOf(
            "../逃出去.txt" to "bad_path",
            "/absolute/逃出去.txt" to "bad_path",
            "报告/../../逃出去.txt" to "bad_path",
            inTrash to "already_in_trash",
            "一个目录" to "not_a_file",
        )
        cases.forEach { (rel, why) ->
            val o = moveArtifactToTrash(root, rel, nowMs = 9L, confirmed = true)
            assertTrue("「$rel」必须被拒（拿到 $o）", o is ArtifactMoveOutcome.Rejected)
            assertEquals("拒的原因要说清（用户看得懂的那句由界面拼）", why, (o as ArtifactMoveOutcome.Rejected).why)
        }
        val after = root.walkTopDown().filter { it.isFile }.map { it.absolutePath }.sorted().toList()
        assertEquals("五拒之后盘上一个文件都没变", before, after)
    }

    @Test
    fun `C2_记账只发生在真写成功那一支`() {
        val src = engine()
        val done = src.indexOf("is GuardPipeline.Result.Done ->")
        val failed = src.indexOf("is GuardPipeline.Result.Failed ->")
        val rejected = src.indexOf("is GuardPipeline.Result.Rejected ->")
        val call = src.indexOf("persistArtifactLedger(sessionId, touchedPaths)")
        assertTrue("Done 分支找得到", done > 0)
        assertTrue("调用点找得到", call > 0)
        assertTrue("调用必须落在 Done 那一支里（done=$done call=$call failed=$failed）", call > done && call < failed)
        assertTrue("Failed 分支之后还有 Rejected（说明上面那个窗口不是巧合）", rejected > failed)
        assertEquals("全仓只有一个记账调用点", 1, Regex("persistArtifactLedger\\(sessionId,").findAll(src).count())
        assertTrue("定义也只有一处", src.contains("private fun persistArtifactLedger(sessionId: Long, touched: List<String>)"))
    }

    @Test
    fun `C2_记账用的就是同一份收集逻辑_而且自己不写盘`() {
        val src = engine()
        assertTrue(
            "收集口径与 §8 顶栏同一份（selectTextArtifacts：白名单＋512KB＋无 NUL）",
            src.contains("com.example.zhengdao.baopu.selectTextArtifacts(root, touched)"),
        )
        assertTrue("字节数与查看器同一份（artifactEntries）", src.contains("artifactEntries(root, kept)"))
        assertTrue("落库语句只认 BaopuSql 那一条", src.contains("BaopuSql.upsertArtifact(sessionId, workspace, e.relPath, e.fileBytes, now)"))
        assertFalse("引擎不许自己拼 SQL（第二真源）", src.contains("INSERT INTO artifact"))
        // 「不动盘」只管记账这一步：引擎别处（快照、日志）本来就要写盘，全文扫会把它们算成红。
        // 所以三条禁令圈在 persistArtifactLedger 的函数体里，并配判别器自证「圈真的圈到了东西」。
        val body = src
            .substringAfter("private fun persistArtifactLedger(sessionId: Long, touched: List<String>) {")
            .substringBefore("\n    }\n")
        assertTrue("判别器：函数体真切到了（切不到＝作用域是白圈的）", body.contains("selectTextArtifacts(root, touched)"))
        listOf("writeText(", "createNewFile(", "deleteRecursively", ".delete(", "copyTo(").forEach { bad ->
            assertFalse("记账这一步不动盘：$bad", body.contains(bad))
        }
        assertTrue("判别器：圈外确实有写盘形状（证明切的是函数体不是整份文件）", src.contains("writeText("))
        // 判别器自证：真源在 BaopuSql 那边，所以那边必须有这条 INSERT
        val db = strip("src/main/java/com/example/zhengdao/baopu/BaopuDb.kt")
        assertTrue("判别器：SQL 真源在 BaopuSql", db.contains("INSERT INTO artifact"))
        // 工作区那一列取的就是引擎的落点根（spillRoot），与 session.workspace 同一个值
        assertTrue("workspace 取 spillRoot 的绝对路径", src.contains("val workspace = root.absolutePath"))
    }

    // ── C3：入口的 N 现读库，而且永远可点 ───────────────────────────────────

    @Test
    fun `C3_打开抽屉重数一次N_而且那一行永远可点`() {
        val src = drawer()
        assertTrue("取根走全仓同一处", src.contains("com.example.zhengdao.baopu.BaopuRuntime.workspaceRoot(ctx)"))
        assertTrue("数的是台账（现读库）", src.contains("remember(artifactTick, artifactRoot) { artifactShelfCount(artifactDb, artifactRoot) }"))
        // E-163 那条教训：drawerContent 常驻组合 ⇒ 必须有「打开」这个信号
        assertTrue("打开抽屉要重数一次", src.contains("LaunchedEffect(drawerOpen) { if (drawerOpen) artifactTick++ }"))
        assertEquals("抽屉里两次「打开就重数」（回收站一次、产物一次）", 2, Regex("LaunchedEffect\\(drawerOpen\\)").findAll(src).count())
        assertTrue("标题带 N 且走资源", src.contains("stringResource(R.string.baopu_artifact_shelf_title, artifactCount)"))
        assertFalse("产物那一行不置灰（空态也得进得去）", src.contains("enabled = artifactCount > 0"))
        assertFalse("更不许用 if 把整行包起来（那是消失）", Regex("if \\(artifactCount > 0\\)").containsMatchIn(src))
        // 判别器自证：那种坏形状必须被同一条正则抓到
        assertTrue(
            "判别器抓得住消失形状",
            Regex("if \\(artifactCount > 0\\)").containsMatchIn("if (artifactCount > 0) { DrawerItem(...) }"),
        )
    }

    @Test
    fun `C3_产物那一行排在回收站上面`() {
        val src = drawer()
        val mine = src.indexOf("R.string.baopu_artifact_shelf_title")
        val trash = src.indexOf("R.string.trash_drawer_entry")
        assertTrue("两行都在（36 批那一行没被动）", mine > 0 && trash > 0)
        assertTrue("派工单 §9.2：产物在回收站**上面**一行（mine=$mine trash=$trash）", mine < trash)
    }

    @Test
    fun `C4_删完这条当场消失_两笔账一起重数`() {
        val src = shelf()
        val gate = src.indexOf("o is ArtifactMoveOutcome.Moved || o is ArtifactMoveOutcome.LedgerOnly")
        val del = src.indexOf("deleteArtifactRow(db, workspaceRoot, row.relPath)")
        val refresh = src.indexOf("tick++")
        assertTrue("先判「搬成了没有」（$gate）", gate > 0)
        assertTrue("只有搬成／本来就没才清台账行（del=$del）", del > gate)
        assertTrue("清完才重新查库（refresh=$refresh）", refresh > del)
        val block = drawer().substringAfter("ArtifactShelfDialog(").substringBefore("onDismiss = {")
        assertTrue("onChanged 要同时重数产物 N 与回收站 N：$block", block.contains("artifactTick++") && block.contains("trashTick++"))
    }

    @Test
    fun `C4_点文件名开的是同一份只读查看器_内容与盘上逐字一致`() {
        val rel = make("输出/中文与制表.txt", "第一行：甲\n\t缩进的乙\n尾巴—破折号\n")
        val text = File(root, rel).readText(Charsets.UTF_8)
        val view = artifactViewOf(root, rel)
        assertNotNull("能读到", view)
        assertEquals("查看器内容与盘上逐字一致", text, view!!.text)
        assertFalse("这么短不该截断", view.truncated)
        assertEquals("换行数认得", 3, view.shownLines)
        val copy = artifactCopyOf(root, rel)
        assertNotNull(copy)
        assertTrue("复制那条路也是全文", copy!!.complete)
        assertEquals("一字不差", text, copy.text)

        // 接线侧：面板点文件名就是把这一条交给查看器（渲染与复制都归 B 段那一处）
        val src = shelf()
        assertTrue("文件名可点 ⇒ viewingRel", src.contains("Modifier.clickable { viewingRel = row.relPath }"))
        assertTrue("开的是那一份查看器", src.contains("BaopuArtifactViewer("))
        assertEquals("查看器调用点只有一个", 1, Regex("BaopuArtifactViewer\\(").findAll(src).count())
    }

    // ── §9.4 红线 ─────────────────────────────────────────────────────────

    @Test
    fun `红线_界面不调工具层也不许彻底删`() {
        val both = shelf() + "\n" + drawer()
        assertFalse("不许出现那个工具的名字", both.contains("DeletePathTool"))
        assertFalse("不许 import 工具层那个包", both.contains("import com.example.zhengdao.baopu.agent"))
        assertFalse("本区没有彻底删除：不许 .delete()", Regex("""\.delete\(\)""").containsMatchIn(both))
        assertFalse("不许删目录", both.contains("deleteRecursively"))
        assertFalse("不许调 36 批的彻底删除/清空/恢复", both.contains("deleteTrash") || both.contains("emptyTrash") || both.contains("restoreTrash"))
        assertFalse("回收站目录名不在这里另拼一次", Regex("""File\((root|workspaceRoot), "回收站"\)""").containsMatchIn(both))
        // 判别器自证：上面这几条正则抓得住真代码里的坏形状
        assertTrue("判别器：.delete() 抓得住", Regex("""\.delete\(\)""").containsMatchIn("File(\"x\").delete()"))
        assertTrue("判别器：另拼路径抓得住", Regex("""File\((root|workspaceRoot), "回收站"\)""").containsMatchIn("File(root, \"回收站\")"))
        // 取回收站目录仍走 36 批那一个函数（不是调用它的删除逻辑，只是同一个路径口径）
        assertTrue("目录口径只有一处", shelf().contains("BaopuTrashBin.dirOf(root)"))
    }

    @Test
    fun `红线_查看器与复制只有一份_面板不另开一套`() {
        val src = shelf()
        listOf("setPrimaryClip", "ClipData", "ClipboardManager", "artifactViewOf", "artifactCopyOf").forEach { bad ->
            assertFalse("面板里不许出现「复制／读正文」的第二份实现：$bad", src.contains(bad))
        }
        assertEquals("面板自己只开一层窗口（第二层是调用查看器）", 1, Regex("Dialog\\(onDismissRequest").findAll(src).count())
        assertFalse("查看器不在这份文件里重定义", src.contains("internal fun BaopuArtifactViewer"))
        // 判别器：复制与查看那两件事确实各自只有一个主人
        assertEquals("复制全文只有一处落地", 1, Regex("private suspend fun copyArtifactToClipboard\\(").findAll(viewer()).count())
        assertTrue("顶栏产物条用的也是那一份", viewer().contains("copyArtifactToClipboard(context, root, relPath)"))
    }

    @Test
    fun `红线_文案全在strings_代码里没有硬编码中文`() {
        val src = shelf()
        val hits = Regex("\"[^\"]*[\\u4E00-\\u9FFF][^\"]*\"").findAll(src).map { it.value }.toList()
        assertTrue("面板代码里不许出现中文字面量（中英不对称就是这么来的）：$hits", hits.isEmpty())
        // 判别器自证：硬编码那种形状必须被抓到
        assertTrue("判别器抓得住硬编码文案", Regex("\"[^\"]*[\\u4E00-\\u9FFF][^\"]*\"").containsMatchIn("Text(\"还没有产物\")"))
        assertTrue("每一句都查键", src.contains("stringResource(R.string.baopu_artifact_shelf_none"))
    }

    @Test
    fun `G8_中英两边都有产物区那17个键且占位符成对`() {
        val keys = listOf(
            "baopu_artifact_shelf_title",
            "baopu_artifact_shelf_desc",
            "baopu_artifact_shelf_row_meta",
            "baopu_artifact_shelf_row_session",
            "baopu_artifact_shelf_offdisk",
            "baopu_artifact_shelf_open",
            "baopu_artifact_shelf_delete",
            "baopu_artifact_shelf_confirm",
            "baopu_artifact_shelf_confirm_gone",
            "baopu_artifact_shelf_moved",
            "baopu_artifact_shelf_ledger_cleared",
            "baopu_artifact_shelf_rejected",
            "baopu_artifact_shelf_io_failed",
            "baopu_artifact_shelf_ledger_failed",
            "baopu_artifact_shelf_none",
            "baopu_artifact_shelf_none_hint",
            "baopu_artifact_shelf_close",
        )
        keys.forEach { key ->
            val z = Regex("name=\"$key\">([^<]*)<").find(zh())
            val e = Regex("name=\"$key\">([^<]*)<").find(en())
            assertNotNull("中文少了这一句：$key", z)
            assertNotNull("英文少了这一句：$key", e)
            assertEquals(
                "$key 两边占位符必须成对（拼句子的参数对不上就是崩）",
                placeholders(z!!.groupValues[1]),
                placeholders(e!!.groupValues[1]),
            )
        }
        assertEquals("17 个键", 17, keys.size)
        // 每条句子都必须真的被引用（写了不用的键是下一批的坑）
        val src = shelf() + drawer()
        keys.forEach { key ->
            assertTrue("键没被引用：$key", src.contains("R.string.$key"))
        }
    }

    @Test
    fun `口径_收集与查看两个入口共用一份`() {
        // §8 顶栏与 §9 抽屉必须同一份逻辑：收集在纯函数里、查看在查看器里，两页都只是调用方。
        val src = shelf()
        assertTrue("面板的行数据来自台账（listArtifacts）", shelfDbUsesList())
        assertTrue("面板读盘用同一份 isFile 口径", src.contains("onDisk = File(root, rel).isFile"))
        assertFalse("面板不自己列目录找产物（那是引擎的口径）", src.contains("walkTopDown") || src.contains("listFiles()"))
        // 判别器：收集那一份的真源在纯函数里，且被两边共用
        assertTrue("判别器：selectTextArtifacts 只定义一处", 1 == Regex("fun selectTextArtifacts").findAll(pure()).count())
        assertTrue("顶栏产物条也走同一份 entries", viewer().contains("artifactEntries(root, artifacts)"))
    }

    private fun shelfDbUsesList(): Boolean = shelf().contains("BaopuSql.listArtifacts(root.absolutePath)")

    private fun pure(): String = strip("src/main/java/com/example/zhengdao/baopu/BaopuCostAndArtifacts.kt")
}
