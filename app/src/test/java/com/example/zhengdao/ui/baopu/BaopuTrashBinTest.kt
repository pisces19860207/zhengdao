// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单第三十六批 §1 A 段＋§4 判据 T1/T2/T3（抽屉「回收站」入口）。
//
// 两层打法沿用 32/34 批的惯例（本批同样只动界面那一侧）：
//   1. **行为层**：真临时目录里造出「回收站／子目录／撞名占位者」，跑 trashRows／restoreTrash／
//      deleteTrash／emptyTrash，断言**盘上到底变成什么样**——T1 的「只列文件」、T2 的「一个字节不覆盖」、
//      T3 的「没确认就不许动」全靠这一层，源码字符串代替不了；
//   2. **源码层**：读剥掉注释后的两个文件（纯函数＋面板同一个文件、抽屉），钉「入口常驻」「确认位靠比对
//      不是写死 true」「UI 没调工具层」——每条「不许出现 X」都配判别器自证，防门禁空跑。
//
// 三条负例（派工单 §5）由脚本按字节造红，读数记在交付报告：
//   N1 恢复改成同名直接覆盖 ⇒ T2 红（本文件 `T2_恢复撞名…`）；
//   N2 清空去掉二次确认 ⇒ T3 红（本文件 `T3_彻底删除没二次确认…`／`T3_界面那条闸…`）；
//   N3 分类写进 SKILL.md 头 ⇒ T5 红（那条在 BaopuSkillCategoryTest 里）。
//
// 环境说明（证据等级②，与 GuardTest 同一处口径）：本机沙盒里 `Files.createSymbolicLink`
// 可能因权限失败。软链那条用例因此是**两分支**的：能建就断言「不列」，建不了就钉源码
// 「过滤器必须问 BaopuGuardCore 要判定」。两种读法都不许静默通过——分支里都有 assertTrue。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.baopu.agent.DeletePathTool
import com.example.zhengdao.rootfs.PathGuard
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 第三十六批 A 段 · 回收站（抽屉入口＋恢复／彻底删除／清空）。
 *
 * 为什么能在 JVM 里真跑：这一步的全部输入输出是「一个目录＋几个文件」，
 * 不碰 Android API（`LocalContext`/`Toast` 只出现在面板那一层，判据不依赖它）。
 * 真机要验的是排版与手感，列在交付报告 §7。
 */
class BaopuTrashBinTest {

    // ── 源码读取（工作目录 = app/；判据一律读剥掉注释后的文本）──────────────

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    private fun strip(path: String): String = repoFile(path).readText(Charsets.UTF_8)
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private fun bin() = strip("src/main/java/com/example/zhengdao/ui/baopu/BaopuTrashBin.kt")

    private fun drawer() = strip("src/main/java/com/example/zhengdao/ui/baopu/BaopuDrawer.kt")

    private fun screen() = strip("src/main/java/com/example/zhengdao/ui/baopu/BaopuScreen.kt")

    private fun zh() = repoFile("src/main/res/values/strings.xml").readText(Charsets.UTF_8)

    private fun en() = repoFile("src/main/res/values-en/strings.xml").readText(Charsets.UTF_8)

    private fun value(fileText: String, key: String): String {
        // 134 批：部分键改成 <plurals>（lint PluralsCandidate），两种形态都要能取到原文。
        Regex("name=\"$key\">([^<]*)</string>").find(fileText)?.let { m ->
            if (m.groupValues[1].isNotBlank()) return m.groupValues[1]
        }
        return Regex("name=\"$key\">([\\s\\S]*?)</plurals>").find(fileText)?.groupValues?.get(1)
            ?: throw AssertionError("找不到键 $key（被删了？）")
    }

    private fun placeholders(text: String): List<String> =
        Regex("%\\d\\$[a-zA-Z]").findAll(text).map { it.value }.distinct().toList()

    // ── 盘上现场 ──────────────────────────────────────────────────────────

    private lateinit var root: File
    private lateinit var trash: File

    @Before
    fun setUp() {
        root = File.createTempFile("zd36-ws", "").let { it.delete(); it.mkdirs(); it }
        trash = BaopuTrashBin.dirOf(root)
        assertTrue("回收站目录建不出来", trash.mkdirs() || trash.isDirectory)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    /** 在回收站里造一个文件（名字就是列表里看到的那串）。 */
    private fun makeTrashFile(name: String, content: String = "x"): File =
        File(trash, name).apply { writeText(content, Charsets.UTF_8) }

    private fun rowOf(name: String): TrashRow = trashRows(trash).first { it.name == name }

    // ── T1：只列文件、N 与磁盘一致、目录不存在不算错 ──────────────────────

    @Test
    fun `T1_只列回收站下的文件_子目录一个都不列`() {
        val a = makeTrashFile("20261019-100000-a.txt", "AAA")
        makeTrashFile("20261019-100001-b.txt", "BBBB")
        val sub = File(trash, "里层目录")
        sub.mkdirs()
        File(sub, "埋在目录里的.txt").writeText("不该被列出来", Charsets.UTF_8)

        val rows = trashRows(trash)

        assertEquals("两个文件 ⇒ N=2（子目录不算，与磁盘一致）", 2, rows.size)
        assertEquals(
            "列表按名字排（稳定，断言才有确定值）",
            listOf("20261019-100000-a.txt", "20261019-100001-b.txt"),
            rows.map { it.name },
        )
        assertEquals("大小就是盘上那个字节数", 3L, rows.first().bytes)
        assertEquals("目录里那一个没混进列表", false, rows.any { it.name == "埋在目录里的.txt" })

        // 时间：把 mtime 钉成一个整秒值再读回来（列表那一列显示的就是它）
        val stamp = 1_700_000_000_000L
        a.setLastModified(stamp)
        assertEquals("时间取盘上 mtime", stamp, rowOf(a.name).modifiedAt)
    }

    @Test
    fun `T1_回收站还不存在时是空表_不是错误也不许抛`() {
        val never = File(root, "从来没有这个目录")

        val rows = trashRows(never)

        assertTrue("没这个目录 ⇒ 空表", rows.isEmpty())
        assertFalse("也不许把根自己当成一个文件", rows.any { it.name == root.name })
    }

    @Test
    fun `T1_软链不列_跟着 GuardCore 那一处判定`() {
        val real = File(root, "真身.txt").apply { writeText("外面那份", Charsets.UTF_8) }
        val link = File(trash, "链进来.txt")
        val made = runCatching { Files.createSymbolicLink(link.toPath(), real.toPath()) }.isSuccess
        makeTrashFile("普通文件.txt", "内容")

        val rows = trashRows(trash)

        if (made) {
            assertEquals("软链不算一个文件：只列普通那个 ⇒ N=1", 1, rows.size)
            assertEquals("列出来的就是普通文件", "普通文件.txt", rows.single().name)
        } else {
            // 建不了软链（沙盒权限）：那就钉源码——过滤器里必须真的调那一处判定，
            // 不许自己另写一份 isSymbolicLink，也不许没有这一道。
            assertTrue("过滤器必须问 GuardCore 要软链判定", bin().contains("!BaopuGuardCore.isSymlink(it)"))
            assertFalse("不许自己再写一份 Files.isSymbolicLink", bin().contains("Files.isSymbolicLink"))
        }
    }

    @Test
    fun `T1_入口常驻_N等于零只关 enabled 不许整条消失`() {
        val src = drawer()
        assertTrue("抽屉要有这一行", src.contains("R.string.trash_drawer_entry"))
        assertTrue("常驻的形状＝只把 enabled 关掉", src.contains("enabled = trashCount > 0"))
        assertFalse("不许用 if 把整行包起来（那就是消失）", Regex("if \\(trashCount > 0\\)").containsMatchIn(src))
        // 判别器自证：那种"消失"的坏形状必须被同一条正则抓到
        assertTrue(
            "判别器抓得住消失形状",
            Regex("if \\(trashCount > 0\\)").containsMatchIn("if (trashCount > 0) { DrawerItem(...) }"),
        )
        assertTrue("N 现读盘：数的是 trashRows", src.contains("trashRows(BaopuTrashBin.dirOf(trashRoot)).size"))
        assertTrue("取根走全仓那一处", src.contains("BaopuRuntime.workspaceRoot(ctx)"))
        // E-163（真机 2026-10-19 抓到）：`ModalNavigationDrawer` 的 drawerContent 一直处于组合中，
        // 光靠 remember(tick) 不会因为「把抽屉拉开」而重算 ⇒ 抱朴刚删完文件，抽屉里仍显示旧 N；
        // 而旧 N=0 又把整行 enabled 关掉，连面板都打不开（只能重启 App）。
        assertTrue("打开抽屉要重数一次", src.contains("LaunchedEffect(drawerOpen)"))
        assertTrue("抽屉自己要看得到打开状态", src.contains("drawerOpen: Boolean"))
        assertTrue("页面必须把 drawerState.isOpen 传进来", screen().contains("drawerOpen = drawerState.isOpen"))
    }

    // ── T2：恢复 ──────────────────────────────────────────────────────────

    @Test
    fun `T2_恢复成功_文件真的回到工作区根且回收站里不再有`() {
        makeTrashFile("20261019-120000-shot.jpg", "IMG")
        val row = rowOf("20261019-120000-shot.jpg")

        val o = restoreTrash(row, root)

        assertTrue("应成功：$o", o is TrashOutcome.Restored)
        val back = File(root, row.name)
        assertTrue("工作区根上有它", back.isFile)
        assertEquals("内容原样搬回，没重写一个字节", "IMG", back.readText(Charsets.UTF_8))
        assertFalse("回收站里不再有", row.file.exists())
        assertEquals("列表跟着少一条", 0, trashRows(trash).size)
    }

    @Test
    fun `T2_恢复撞名必须拒绝并指名占位者_占位内容一字不动`() {
        makeTrashFile("20261019-120000-dup.txt", "回收站里的那份")
        val occupier = File(root, "20261019-120000-dup.txt").apply { writeText("别人正用的那份", Charsets.UTF_8) }
        val row = trashRows(trash).single()

        val o = restoreTrash(row, root)

        assertTrue("必须拒：$o", o is TrashOutcome.Occupied)
        assertEquals("指名指到那个占位者的路径", occupier.absolutePath, (o as TrashOutcome.Occupied).target)
        assertEquals("占位内容一个字不许动", "别人正用的那份", occupier.readText(Charsets.UTF_8))
        assertTrue("回收站里那份还在（没搬走也没删）", row.file.isFile)
        assertEquals("回收站里还是那 1 个文件", 1, trashRows(trash).size)
        assertEquals("工作区根上还是那 1 个占位者（没被换掉也没多出一个）", 1, root.listFiles()?.count { it.isFile })
        assertEquals("两个文件都还在盘上", 2, (listOf(occupier, row.file)).count { it.isFile })
    }

    @Test
    fun `T2_恢复撞上的是目录也照样拒`() {
        makeTrashFile("附件", "这是一个文件名")
        val occupier = File(root, "附件").apply { mkdirs() }
        val row = trashRows(trash).single()

        val o = restoreTrash(row, root)

        assertTrue("落点是目录也算占位：$o", o is TrashOutcome.Occupied)
        assertTrue("那个目录没被动", occupier.isDirectory)
    }

    @Test
    fun `T2_名字不合法或越界的恢复一律拒绝`() {
        // ① 名字里被人塞了路径分隔（列目录本来不该列出这种名字，但判定不靠「应该不会」）
        val sneakyFile = makeTrashFile("a.txt", "真身在回收站里")
        val sneaky = TrashRow(sneakyFile, "../逃出去.txt", 1L, 0L)
        assertTrue("带 / 的名字要拒：${restoreTrash(sneaky, root)}", restoreTrash(sneaky, root) is TrashOutcome.Rejected)
        assertNull("落点纯函数也不给落点", restoreTargetOf(root, sneaky))
        assertTrue("越界那一次原文件不许被动", sneakyFile.isFile)
        assertFalse("工作区根外面没被凭空造出东西", File(root.parentFile, "逃出去.txt").exists())

        // ② 这一行根本不在回收站里（别人塞进来的一个越界路径 ⇒ PathGuard 那条闸）
        val outside = File(root, "工作区里的正常文件.txt").apply { writeText("别动我", Charsets.UTF_8) }
        val outsideRow = TrashRow(outside, outside.name, 9L, 0L)
        assertTrue("回收站外的一律拒：${restoreTrash(outsideRow, root)}", restoreTrash(outsideRow, root) is TrashOutcome.Rejected)
        assertTrue("原件还在", outside.isFile)

        // ③ 文件已经被别的进程删了 ⇒ IoFailed 并且要指名是哪个
        val gone = makeTrashFile("20261019-130000-gone.txt", "要被删掉")
        val goneRow = rowOf("20261019-130000-gone.txt")
        gone.delete()
        val late = restoreTrash(goneRow, root)
        assertTrue("没了要说清楚：$late", late is TrashOutcome.IoFailed)
    }

    @Test
    fun `T2_落点默认就是工作区根_原目录信息不猜`() {
        makeTrashFile("20261019-140000-附件.txt", "A")
        val row = trashRows(trash).single()

        val target = restoreTargetOf(root, row)

        assertEquals(
            "落点＝工作区根＋那个名字（不剥时间戳前缀、不猜原目录）",
            File(root, row.name).absolutePath,
            target?.absolutePath,
        )
        assertTrue("落点仍在工作区内（PathGuard 兜边界）", PathGuard.isInside(root, target!!))
    }

    // ── T3：二次确认 ──────────────────────────────────────────────────────

    @Test
    fun `T3_彻底删除没二次确认时一个字节都不许动`() {
        val f = makeTrashFile("20261019-150000-keep.txt", "还想留着")
        val row = trashRows(trash).single()

        val o = deleteTrash(row, root, confirmed = false)

        assertTrue("必须回 NeedsConfirm：$o", o is TrashOutcome.NeedsConfirm)
        assertEquals("报的数量给确认文案用", 1, (o as TrashOutcome.NeedsConfirm).count)
        assertEquals("报的体积给确认文案用", "还想留着".toByteArray(Charsets.UTF_8).size.toLong(), o.bytes)
        assertTrue("文件还在原地", f.isFile && row.file.exists())

        val yes = deleteTrash(row, root, confirmed = true)
        assertTrue("第二击才真删：$yes", yes is TrashOutcome.Deleted)
        assertFalse("这才没了", row.file.exists())
    }

    @Test
    fun `T3_回收站外的东西 deleteTrash 也不许删`() {
        val outside = File(root, "还在用的.txt").apply { writeText("别删我", Charsets.UTF_8) }
        val row = TrashRow(outside, outside.name, outside.length(), 0L)

        val o = deleteTrash(row, root, confirmed = true)

        assertTrue("越界就算带了 confirmed 也要拒：$o", o is TrashOutcome.Rejected)
        assertTrue("原件一个字没动", outside.isFile)
    }

    @Test
    fun `T3_清空也要二次确认_确认后只删文件留子目录`() {
        makeTrashFile("20261019-160000-one.txt", "1")
        makeTrashFile("20261019-160001-two.txt", "22")
        val sub = File(trash, "留着这个目录").apply { mkdirs() }
        val insideSub = File(sub, "里面的.txt").apply { writeText("不该被删", Charsets.UTF_8) }

        val first = emptyTrash(trash, confirmed = false)

        assertTrue("第一击只回话不动手：$first", first is TrashOutcome.NeedsConfirm)
        assertEquals("确认文案要的数量＝2", 2, (first as TrashOutcome.NeedsConfirm).count)
        assertEquals("确认文案要的体积＝3 字节", 3L, first.bytes)
        assertEquals("两个文件都还在", 2, trashRows(trash).size)

        val second = emptyTrash(trash, confirmed = true)

        assertTrue("第二击才清：$second", second is TrashOutcome.Deleted)
        val done = second as TrashOutcome.Deleted
        assertEquals("清掉 2 个", 2, done.count)
        assertEquals("3 字节", 3L, done.bytes)
        assertEquals("没有失败的", 0, done.failed)
        assertEquals("回收站里没文件了", 0, trashRows(trash).size)
        assertTrue("子目录留着（与工具层清空同一口径）", sub.isDirectory)
        assertTrue("子目录里的东西没被顺手删", insideSub.isFile)
    }

    @Test
    fun `T3_界面那条闸靠的是比对路径_不是写死 true`() {
        val src = bin()
        assertTrue("单条删除的确认位由比对得出", src.contains("deleteTrash(row, workspaceRoot, confirmingFile == row.file.absolutePath)"))
        assertTrue("清空的确认位由 confirmingEmpty 得出", src.contains("emptyTrash(dir, confirmingEmpty)"))
        assertTrue("关掉那一行/换一条就得重新确认", src.contains("confirmingFile = null"))
        assertFalse("不许出现无条件删除的写法", src.contains("deleteTrash(row, workspaceRoot, true)"))
        assertFalse("清空同理", src.contains("emptyTrash(dir, true)"))
        // 判别器自证：坏形状（写死 true）确实被上面那条抓到
        assertTrue(
            "判别器抓得住写死 true",
            Regex("emptyTrash\\(dir, true\\)").containsMatchIn("emptyTrash(dir, true)"),
        )
    }

    @Test
    fun `T3_确认那句文案必须写清数量与恢复不了`() {
        val src = bin()
        assertTrue("单条那句红字只在确认态出现", src.contains("if (isConfirming) {"))
        assertTrue("确认态是这一行自己的", src.contains("val isConfirming = confirmingFile == row.file.absolutePath"))
        assertTrue("单条那句查的是单条文案", src.contains("R.string.trash_confirm_one"))
        assertTrue("清空那句查的是批量文案", src.contains("R.plurals.trash_confirm_empty") || src.contains("R.string.trash_confirm_empty"))
        val zhEmpty = value(zh(), "trash_confirm_empty")
        assertTrue("清空那句要数量", zhEmpty.contains("%1\$d"))
        assertTrue("清空那句要体积", zhEmpty.contains("%2\$s"))
        assertTrue("清空那句要说恢复不了", zhEmpty.contains("恢复不了"))
        assertTrue("单条那句也要说恢复不了", value(zh(), "trash_confirm_one").contains("恢复不了"))
    }

    // ── 口径与体积 ────────────────────────────────────────────────────────

    @Test
    fun `口径_目录名与工具层同一个字_但界面不调工具层`() {
        // 常量对齐（谁改了哪一边就红）；这是**读常量**，不是调用那个工具。
        assertEquals("两边必须同名", DeletePathTool.TRASH_DIR, BaopuTrashBin.TRASH_DIR)
        val both = bin() + "\n" + drawer()
        assertFalse("UI 不许出现那个工具的名字", both.contains("DeletePathTool"))
        assertFalse("也不许 import 工具层那个包（import 才是调用入口）", both.contains("import com.example.zhengdao.baopu.agent"))
        assertFalse("不许自己另拼回收站路径", Regex("File\\((root|workspaceRoot), \"回收站\"\\)").containsMatchIn(both))
        assertTrue("判别器正例：路径只有 dirOf 一处", bin().contains("fun dirOf(workspaceRoot: File): File"))
        assertEquals("dirOf 只定义一次", 1, Regex("fun dirOf").findAll(bin()).count())
    }

    @Test
    fun `口径_体积说成人话是整数运算_不看区域设置`() {
        assertEquals("0 B", humanBytes(0L))
        assertEquals("512 B", humanBytes(512L))
        assertEquals("1023 B 还在 B 档", "1023 B", humanBytes(1023L))
        assertEquals("1 KB", humanBytes(1024L))
        assertEquals("1.5 MB", humanBytes(1024L * 1024 + 512 * 1024))
        assertEquals("整档不写 .0", "2 MB", humanBytes(2L * 1024 * 1024))
        assertEquals("1 GB", humanBytes(1024L * 1024 * 1024))
        assertEquals("负数当 0，不许出现 -1 B", "0 B", humanBytes(-1L))
    }

    @Test
    fun `口径_报给用户的是相对路径不是长串`() {
        makeTrashFile("20261019-170000-rel.txt", "R")
        val row = trashRows(trash).single()

        val rel = trashRelOf(root, row.file)

        assertEquals("回收站/xxx —— 列表与回执都用这一串", "回收站/20261019-170000-rel.txt", rel)
        assertFalse("开头不许留分隔符（Windows 上剥前缀会剩一个反斜杠）", rel.startsWith("/") || rel.startsWith("\\"))
        assertFalse("不许出现平台反斜杠", rel.contains("\\"))
        assertFalse("不许把根那一长串念出来", rel.contains(root.absolutePath))

        // 越界那份（不在根下面）不许把 `..\..\` 那种串交给用户 ⇒ 退回只报文件名
        val outside = File(root.parentFile, "外头那个.txt")
        assertEquals("算不出相对路径就只报名字", "外头那个.txt", trashRelOf(root, outside))
    }

    // ── T6：中英对称 ──────────────────────────────────────────────────────

    @Test
    fun `T6_回收站这批新键中英都在且占位符一致`() {
        val keys = listOf(
            "trash_drawer_entry", "trash_drawer_desc", "trash_dialog_title", "trash_none",
            "trash_row_meta", "trash_restore", "trash_delete", "trash_empty", "trash_close",
            "trash_confirm_one", "trash_confirm_empty", "trash_restored", "trash_occupied",
            "trash_deleted_one", "trash_emptied", "trash_emptied_partial", "trash_rejected", "trash_io_failed",
        )
        keys.forEach { key ->
            assertTrue("$key 中文不许空", value(zh(), key).isNotBlank())
            assertTrue("$key 英文不许空", value(en(), key).isNotBlank())
            assertEquals(
                "$key 的占位符两边必须一致（否则切英文直接崩）",
                placeholders(value(zh(), key)),
                placeholders(value(en(), key)),
            )
        }
        assertEquals("A 段共 18 个键", 18, keys.size)
    }

    @Test
    fun `T6_界面那几句全从strings取_代码里没有硬编码按钮文案`() {
        val src = bin() + drawer()
        listOf("\"彻底删除\"", "\"清空回收站\"", "\"恢复\"", "\"关闭\"", "\"回收站（\"").forEach { bad ->
            assertFalse("按钮/标题文案不许写死在代码里：$bad", src.contains(bad))
        }
        // 判别器自证：写死的那种形状确实会被上面那条抓到
        assertTrue("判别器抓得住硬编码按钮", Regex("\"彻底删除\"").containsMatchIn("Text(\"彻底删除\")"))
        assertTrue("面板每一句走资源", src.contains("stringResource(R.string.trash_restore)"))
        assertTrue("抽屉那一行也走资源并带 N", drawer().contains("stringResource(R.string.trash_drawer_entry, trashCount)"))
    }
}
