// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.library.FaceIndex
import com.example.zhengdao.baopu.library.LibraryPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * LibraryTools 单测（第三十一批 A/B 段；判据 L1/L2/L3/L4/L5/L6）。
 *
 * 钉死六件事：
 * 1. **library_list 纯只读**（L1）——跑完目录 mtime 不变、不添文件（另见 LibraryToolsGuardTest 源码级）；
 * 2. **截断明说**（L2）——输出封顶时写明「还有 N 条没列」，不许静默截；
 * 3. **organize 一律 CONFIRM**（L3）——权限属性直查，index 之外只动索引一个文件；
 * 4. **promote 同名拒绝不覆盖**（L4）；
 * 5. **越界一律拒且人话无堆栈**（L5）——`..`／绝对路径／盘符／指向 `原始/`；
 * 6. **`原始/` 零改动**（L6）——哨兵文件（工作区内与 App 私有两处）跑完全部动作 sha256 不变。
 */
class LibraryToolsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var deliver: File
    private lateinit var done: File
    private lateinit var rawInWorkspace: File
    private lateinit var rawPrivate: File

    private fun setup() {
        root = tmp.newFolder("工作区")
        deliver = File(root, "资料库/投递").apply { mkdirs() }
        done = File(root, "资料库/整理").apply { mkdirs() }
        rawInWorkspace = File(root, "资料库/原始").apply { mkdirs() } // 旧布局残留：也要纹丝不动
        rawPrivate = tmp.newFolder("App私有") // 工作区外：filesDir/baopu/library/原始 的替身
    }

    private fun deliverFile(name: String, text: String = "内容"): File {
        val f = File(deliver, name)
        f.parentFile?.mkdirs()
        f.writeText(text)
        return f
    }

    private fun doneFile(name: String, text: String = "产出"): File {
        val f = File(done, name)
        f.parentFile?.mkdirs()
        f.writeText(text)
        return f
    }

    private fun sha(f: File): String = FaceIndex.sha256(f)

    // ── A 段：library_list ────────────────────────────────────────────────

    @Test
    fun `list_列出投递清单_带sha前8位与扩展名`() {
        setup()
        deliverFile("a.txt", "甲")
        deliverFile("武侠/b.md", "乙")

        val out = LibraryTools.list(root)

        assertTrue(out is ToolOutcome.Ok)
        val text = (out as ToolOutcome.Ok).text
        assertTrue("要有相对路径：\n$text", text.contains("a.txt"))
        assertTrue("要有子目录路径：\n$text", text.contains("武侠/b.md"))
        assertTrue("要有字节数：\n$text", text.contains("3 B"))
        assertTrue("要有扩展名：\n$text", text.contains("\ttxt\t"))
        val sha8 = sha(File(deliver, "a.txt")).take(8)
        assertTrue("sha256 前 8 位要对：\n$text", text.contains(sha8))
        assertTrue("要说明只读：\n$text", text.contains("只读"))
    }

    @Test
    fun `list_done目录与pattern过滤`() {
        setup()
        doneFile("斗罗.md")
        doneFile("红楼梦.md")
        deliverFile("投递里的.txt")

        val outDone = LibraryTools.list(root, dir = "done")
        assertTrue((outDone as ToolOutcome.Ok).text.contains("斗罗.md"))
        assertFalse((outDone).text.contains("投递里的.txt"))

        val outPat = LibraryTools.list(root, dir = "done", pattern = "红楼")
        assertTrue((outPat as ToolOutcome.Ok).text.contains("红楼梦.md"))
        assertFalse((outPat).text.contains("斗罗.md"))
        assertTrue((outPat).text.contains("pattern「红楼」命中 1 条"))
    }

    @Test
    fun `list_目录不存在给空清单不炸`() {
        setup()
        File(root, "资料库").deleteRecursively()
        val out = LibraryTools.list(root)
        assertTrue(out is ToolOutcome.Ok)
        assertTrue((out as ToolOutcome.Ok).text.contains("空清单"))
    }

    @Test
    fun `list_输出封顶明说还有多少条`() {
        setup()
        // 每条约 40 字符 ⇒ 80 条就超 2000 字符封顶（40 条亲测差一点，别凭感觉估）
        repeat(80) { deliverFile("第${it}卷-一个很长的文件名用来撑爆输出字符数.txt", "内容") }

        val out = LibraryTools.list(root)
        val text = (out as ToolOutcome.Ok).text

        assertTrue("封顶必须明说：\n$text", text.contains("还有"))
        assertTrue("要给出条数：\n$text", text.contains("没列"))
        assertTrue("要教怎么收窄：\n$text", text.contains("pattern"))
        val listed = text.lines().count { it.contains(" B\t") }
        assertTrue("列出的应远少于 80（实际 $listed）", listed in 1..79)
    }

    @Test
    fun `list_只读_目录mtime不变且不添文件`() {
        setup()
        deliverFile("a.txt", "甲")
        deliverFile("b.txt", "乙")
        val mtimeBefore = deliver.lastModified()
        val namesBefore = deliver.walkTopDown().map { it.name }.sorted().toList()

        LibraryTools.list(root)
        LibraryTools.list(root, dir = "done", pattern = "x")

        assertEquals("跑完 list 投递目录 mtime 不许变", mtimeBefore, deliver.lastModified())
        assertEquals(
            "跑完 list 不许添/删文件",
            namesBefore,
            deliver.walkTopDown().map { it.name }.sorted().toList(),
        )
    }

    // ── B 段：library_organize ────────────────────────────────────────────

    @Test
    fun `organize_权限是CONFIRM_list是FREE`() {
        val listSpec = LibraryTools.listSpec { root }
        val organizeSpec = LibraryTools.organizeSpec { root }
        assertEquals(ToolPermission.FREE, listSpec.permission)
        assertEquals("写动作一律过确认卡（L3）", ToolPermission.CONFIRM, organizeSpec.permission)
    }

    @Test
    fun `promote_移到整理并报字节数`() {
        setup()
        deliverFile("a.txt", "甲乙丙")

        val out = LibraryTools.organize(root, "promote", "a.txt", null)

        assertTrue(out is ToolOutcome.Ok)
        assertTrue((out as ToolOutcome.Ok).text.contains("已移到 整理/a.txt"))
        assertTrue((out).text.contains("9 B"))
        assertFalse("投递里那份要不在", File(deliver, "a.txt").exists())
        assertEquals("甲乙丙", File(done, "a.txt").readText())
    }

    @Test
    fun `promote_工作区全路径形式也认`() {
        setup()
        deliverFile("a.txt", "甲")
        val out = LibraryTools.organize(root, "promote", "资料库/投递/a.txt", null)
        assertTrue(out is ToolOutcome.Ok)
        assertTrue(File(done, "a.txt").isFile)
    }

    @Test
    fun `promote_子目录路径_整理侧自动建目录`() {
        setup()
        deliverFile("武侠/a.txt", "甲")
        val out = LibraryTools.organize(root, "promote", "武侠/a.txt", null)
        assertTrue(out is ToolOutcome.Ok)
        assertTrue(File(done, "武侠/a.txt").isFile)
    }

    @Test
    fun `promote_同名已存在_拒绝且不覆盖`() {
        setup()
        deliverFile("a.txt", "新内容")
        doneFile("a.txt", "旧产出")

        val out = LibraryTools.organize(root, "promote", "a.txt", null)

        assertTrue("同名必须拒绝：$out", out is ToolOutcome.Failed)
        assertTrue((out as ToolOutcome.Failed).error.contains("不覆盖"))
        assertEquals("旧产出不许被碰", "旧产出", File(done, "a.txt").readText())
        assertEquals("投递那份要还在", "新内容", File(deliver, "a.txt").readText())
    }

    @Test
    fun `promote_文件不存在与目录_都说清`() {
        setup()
        val missing = LibraryTools.organize(root, "promote", "没有这本书.txt", null)
        assertTrue(missing is ToolOutcome.Failed)
        assertTrue((missing as ToolOutcome.Failed).error.contains("没有这个文件"))

        File(deliver, "子目录").mkdirs()
        val dir = LibraryTools.organize(root, "promote", "子目录", null)
        assertTrue(dir is ToolOutcome.Failed)
        assertTrue((dir as ToolOutcome.Failed).error.contains("目录"))
    }

    @Test
    fun `rename_同目录改名_两个目录都能改`() {
        setup()
        deliverFile("a.txt", "甲")
        val out = LibraryTools.organize(root, "rename", "a.txt", "新名字.txt")
        assertTrue(out is ToolOutcome.Ok)
        assertTrue(File(deliver, "新名字.txt").isFile)
        assertFalse(File(deliver, "a.txt").exists())

        doneFile("b.md", "乙")
        val out2 = LibraryTools.organize(root, "rename", "b.md", "c.md")
        assertTrue(out2 is ToolOutcome.Ok)
        assertTrue(File(done, "c.md").isFile)
    }

    @Test
    fun `rename_新名不合法_拒绝并给净化后的建议`() {
        setup()
        deliverFile("a.txt", "甲")

        val slash = LibraryTools.organize(root, "rename", "a.txt", "子/目录.txt")
        assertTrue(slash is ToolOutcome.Failed)
        assertTrue((slash as ToolOutcome.Failed).error.contains("子_目录.txt"))

        val ctrl = LibraryTools.organize(root, "rename", "a.txt", "坏\u0001名.txt")
        assertTrue(ctrl is ToolOutcome.Failed)

        val dot = LibraryTools.organize(root, "rename", "a.txt", ".hidden")
        assertTrue(dot is ToolOutcome.Failed)

        val same = LibraryTools.organize(root, "rename", "a.txt", "a.txt")
        assertTrue(same is ToolOutcome.Failed)
        assertTrue((same as ToolOutcome.Failed).error.contains("一模一样") || (same).error.contains("一样"))

        // 原文件一个字节都没动
        assertEquals("甲", File(deliver, "a.txt").readText())
    }

    @Test
    fun `rename_目标已存在_拒绝`() {
        setup()
        deliverFile("a.txt", "甲")
        deliverFile("b.txt", "乙")
        val out = LibraryTools.organize(root, "rename", "a.txt", "b.txt")
        assertTrue(out is ToolOutcome.Failed)
        assertTrue((out as ToolOutcome.Failed).error.contains("不覆盖"))
        assertEquals("乙", File(deliver, "b.txt").readText())
    }

    @Test
    fun `index_只覆写索引一个文件_内容一行一条`() {
        setup()
        doneFile("斗罗.md", "甲")
        doneFile("武侠/红楼梦.md", "乙")
        val before = snapshot(root)

        val out = LibraryTools.organize(root, "index", null, null, now = 1700000000000L)

        assertTrue(out is ToolOutcome.Ok)
        val indexFile = File(root, "资料库/索引.md")
        assertTrue("索引文件要在：$indexFile", indexFile.isFile)
        val text = indexFile.readText()
        assertTrue(text.contains("- 斗罗.md"))
        assertTrue(text.contains("- 武侠/红楼梦.md"))
        assertTrue(text.contains("# 资料库索引"))
        // 除了索引文件，整棵树一个字节都不许变（L3：index 之外不许写别的文件）
        val after = snapshot(root)
        val changed = LinkedHashSet<String>()
        before.keys.forEach { if (after[it] != before[it]) changed += it }
        after.keys.forEach { if (before[it] != after[it]) changed += it }
        assertEquals("只许 资料库/索引.md 变化，实际：$changed", listOf("资料库/索引.md"), changed.sorted())
    }

    @Test
    fun `index_两次生成字节一致且整理为空有说明`() {
        setup()
        val out1 = LibraryTools.organize(root, "index", null, null, now = 1L)
        val out2 = LibraryTools.organize(root, "index", null, null, now = 1L)
        assertTrue(out1 is ToolOutcome.Ok && out2 is ToolOutcome.Ok)
        val text = File(root, "资料库/索引.md").readText()
        assertTrue("空整理要有说明：\n$text", text.contains("整理/ 还是空的"))
    }

    @Test
    fun `organize_未知动作_说清`() {
        setup()
        val out = LibraryTools.organize(root, "删库跑路", null, null)
        assertTrue(out is ToolOutcome.Failed)
        assertTrue((out as ToolOutcome.Failed).error.contains("不认识的动作"))
    }

    // ── 红线：越界与原始/ ────────────────────────────────────────────────

    private val hostilePaths = listOf(
        "../逃逸.txt",
        "../../etc/passwd",
        "/tmp/绝对路径.txt",
        "C:\\Windows\\x.txt",
        "资料库/原始/不许动.txt",
        "资料库/../资料库/原始/不许动.txt",
    )

    @Test
    fun `越界路径_一律拒且错误信息没有堆栈`() {
        setup()
        for (p in hostilePaths) {
            val promote = LibraryTools.organize(root, "promote", p, null)
            assertTrue("$p 要被拒（promote）", promote is ToolOutcome.Rejected || promote is ToolOutcome.Failed)
            val msg = when (promote) {
                is ToolOutcome.Rejected -> promote.reason
                is ToolOutcome.Failed -> promote.error
                else -> ""
            }
            assertFalse("$p 的错误信息不许带堆栈：$msg", msg.contains("at com.example"))
            assertTrue("$p 的错误信息要看得懂：$msg", msg.isNotBlank() && msg.length > 4)

            val rename = LibraryTools.organize(root, "rename", p, "新名.txt")
            assertTrue("$p 要被拒（rename）", rename is ToolOutcome.Rejected || rename is ToolOutcome.Failed)
        }
        // 这一轮没有任何文件被写出来：原始/（工作区内旧布局）依旧空的
        assertEquals("越界试闹不许落任何文件", 0, rawInWorkspace.listFiles()!!.size)
    }

    @Test
    fun `原始目录_跑完全部动作_两个哨兵sha都不变`() {
        setup()
        val sentinelWs = File(rawInWorkspace, "哨兵.txt").apply { writeText("私有原件，不许动") }
        val sentinelPrivate = File(rawPrivate, "library/原始").apply { mkdirs() }
            .let { File(it, "真原始.txt").apply { writeText("App 私有原件，更不许动") } }
        val shaWs = sha(sentinelWs)
        val shaPrivate = sha(sentinelPrivate)
        deliverFile("a.txt", "甲")
        doneFile("旧.md", "旧")

        LibraryTools.organize(root, "promote", "a.txt", null)
        LibraryTools.organize(root, "rename", "旧.md", "新.md")
        LibraryTools.organize(root, "index", null, null, now = 2L)
        LibraryTools.list(root)
        LibraryTools.list(root, dir = "done")

        assertEquals("工作区 资料库/原始/ 哨兵不许动（L6）", shaWs, sha(sentinelWs))
        assertEquals("App 私有 原始/ 哨兵不许动（L6）", shaPrivate, sha(sentinelPrivate))
        assertEquals("私有目录不许添文件", 1, File(rawPrivate, "library/原始").listFiles()!!.size)
        assertEquals(
            "资料库/原始/ 连新文件都不许添（L6：一个字节都不动）",
            listOf("哨兵.txt"),
            rawInWorkspace.listFiles()!!.map { it.name }.sorted(),
        )
    }

    @Test
    fun `rename_投递整理都有同名_说清让指哪个`() {
        setup()
        deliverFile("a.txt", "甲")
        doneFile("a.txt", "乙")
        val out = LibraryTools.organize(root, "rename", "a.txt", "c.txt")
        assertTrue(out is ToolOutcome.Failed)
        assertTrue((out as ToolOutcome.Failed).error.contains("都有"))
        assertEquals("甲", File(deliver, "a.txt").readText())
        assertEquals("乙", File(done, "a.txt").readText())
    }

    @Test
    fun `sanitizeName_口径与附件一致`() {
        assertEquals("a_b.txt", LibraryTools.sanitizeName("a/b.txt"))
        assertEquals("a_b.txt", LibraryTools.sanitizeName("a\\b.txt"))
        assertEquals("坏_名.txt", LibraryTools.sanitizeName("坏\u0001名.txt"))
        assertEquals("abc", LibraryTools.sanitizeName(".abc"))
        assertNotNull(LibraryTools.sanitizeName("ok.txt"))
        // 长名封顶且扩展名永远保留（与附件口径的刻意差异：资料库场景不能削掉 .txt）
        val long = LibraryTools.sanitizeName("x".repeat(80) + ".txt")
        assertTrue("长度要封顶：${long?.length}", long != null && long.length <= LibraryTools.MAX_NAME_CHARS)
        assertTrue("扩展名要保留：$long", long!!.endsWith(".txt"))
        val longNoExt = LibraryTools.sanitizeName("y".repeat(80))
        assertEquals(LibraryTools.MAX_NAME_CHARS, longNoExt!!.length)
    }

    // ── 夹具 ──────────────────────────────────────────────────────────────

    /** 全树快照（相对路径 → sha256）；索引.md 的生成时间戳会让它每次都变，故内容也归一化进 key。 */
    private fun snapshot(base: File): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        base.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = f.relativeTo(base).path.replace('\\', '/')
            val bytes = if (rel == "资料库/索引.md") {
                // 时间戳行会变：比对其余部分
                f.readText().lines().filterNot { it.contains("生成于") }.joinToString("\n")
            } else {
                f.readText()
            }
            out[rel] = sha256Of(bytes)
        }
        return out
    }

    private fun sha256Of(text: String): String =
        FaceIndex.sha256Bytes(text.toByteArray(Charsets.UTF_8))
}
