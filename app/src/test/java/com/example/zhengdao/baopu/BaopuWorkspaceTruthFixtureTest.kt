// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百一十五批（2026-11-13）· 判据②的夹具：投递 10 个文件 ⇒ App 可见 10 个。
// 起因是 569 真机那一幕（原文在 569-验机轮二 §五-2）：屏幕上说 `Download/男性`，
// IO 在 App 私有目录，人照着屏幕放素材 ⇒ 一个都进不来。
//
// ⚠️ 本文件**不碰投递扫描本身**（判据④）：`LibraryPaths.scanDeliver` 一行没改，
// 这里只是把「界面说的那条路」和「扫描读的那条路」放到一起对拍。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.library.LibraryPaths
import com.example.zhengdao.ui.baopu.realPathNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 显示的那条路 == 读写的那条路（判据①②③在夹具层面的落点）。
 *
 * 全部走真生产件：解析用 [BaopuWorkspace.resolve]（`hostDir` 里那一条判断），
 * 界面读数用 [realPathNotice]（抽屉画的那两行），扫描用 [LibraryPaths.scanDeliver]。
 * 目录一律临时沙盒，`TemporaryFolder` 自己回收（AGENTS「测试沙盒与水位」）。
 */
class BaopuWorkspaceTruthFixtureTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 判据②点名的数量。 */
    private val ten = 10

    /** 十个**内容各不相同**的文本件（内容相同会被 sha256 归成一件，那是另一码事，别在这儿混）。 */
    private fun seedTen(deliver: File): File {
        deliver.mkdirs()
        repeat(ten) { i ->
            val n = i + 1
            File(deliver, "夹具%02d.md".format(n)).writeText("# 夹具 $n\n灵潮词 $n\n")
        }
        return deliver
    }

    private fun countIn(workspace: File): Int =
        LibraryPaths.scanDeliver(LibraryPaths.deliverDirUnder(workspace)).files.size

    // ── 判据②：投递 10 个 ⇒ 可见 10 个 ────────────────────────────────────

    @Test
    fun `夹具_按界面说的那条路投十个_扫描就见到十个`() {
        val host = tmp.newFolder("实走根")
        val shownOld = tmp.newFolder("声明旧路")
        val notice = realPathNotice(shownOld.absolutePath, host.absolutePath, "（没记录）")
            ?: error("两条路不同就必须给两行（否则又回到 569 那种只字不提）")

        seedTen(LibraryPaths.deliverDirUnder(File(notice.realPath)))

        val scan = LibraryPaths.scanDeliver(LibraryPaths.deliverDirUnder(File(notice.realPath)))
        assertEquals(ten, scan.files.size)
        assertEquals("十个都要被认领成文本件，否则卡上那句只报 0", ten, scan.files.count { it.kind == LibraryPaths.FileKind.TEXT })
        assertTrue("relPath 要能回指到投递内的文件", scan.files.first().relPath.endsWith(".md"))
    }

    @Test
    fun `夹具_回落时读写根与界面读数是同一个目录`() {
        // 生产里这一支：授权不在 ⇒ hostDir 兜底给私有目录。兜底给的**那一个 File**
        // 必须同时是扫描的根和界面说的那条路，两者不许各说各的。
        val private = tmp.newFolder("AndroidData", "files", "workspace")
        val declared = "/storage/emulated/0/Download/男性"
        val host = BaopuWorkspace.resolve("default", declared, private)
        assertEquals(private, host)

        val notice = realPathNotice(declared, host.absolutePath, "（没记录）")
            ?: error("回落了却没给两行＝用户照样按旧路放素材")
        assertEquals("界面那条路必须逐字等于读写根", host.absolutePath, notice.realPath)

        seedTen(LibraryPaths.deliverDirUnder(File(notice.realPath)))
        assertEquals(ten, countIn(host))
    }

    // ── 判据③的负例：旧那条路不能再冒充 ──────────────────────────────────

    @Test
    fun `夹具_负例_按旧显示那条路放十个_实走根上一个都看不见`() {
        // 这条钉的就是 569 现场的成因：素材落在"屏幕说的那条路"里，读写根看不到。
        // 它一直该红着提醒后人——两行显示（判据②）就是为了不让用户走到这一步。
        val host = tmp.newFolder("实走根")
        val shownOld = tmp.newFolder("声明旧路")
        val notice = realPathNotice(shownOld.absolutePath, host.absolutePath, "（没记录）")!!

        seedTen(LibraryPaths.deliverDirUnder(shownOld))

        assertEquals("读写根里一个都没有（这就是「App 只见 1 个」里的那个 0）", 0, countIn(host))
        assertEquals(ten, countIn(shownOld))
    }

    @Test
    fun `夹具_授权恢复后换到新根_旧根不残留`() {
        val private = tmp.newFolder("回落期私有根")
        val back = tmp.newFolder("Download", "男性")

        // 回落期：界面与读写都在私有根，放了 10 个。
        val during = BaopuWorkspace.resolve("default", back.absolutePath, private)
        seedTen(LibraryPaths.deliverDirUnder(during))

        // 授权回来了：custom 合法 ⇒ 根换成声明那条 ⇒ 两行该收回去。
        val healed = BaopuWorkspace.resolve("custom", back.absolutePath, private)
        assertEquals(back, healed)
        assertNull("一致就不该再有第二行", realPathNotice(back.absolutePath, healed.absolutePath, "（没记录）"))

        // 旧根里那 10 个不跟过来（换根就是换根，本批不改投递行为，判据④）。
        assertEquals(0, countIn(healed))
        assertEquals(ten, countIn(during))
    }

    // ── 判据③：回落那一支必须自己出声（接线断言，源码级） ────────────────

    @Test
    fun `接线_两个解析器都把实态交给同一处留痕`() {
        // 这里的断言全是"presence"形状（字样必须在），所以直接吃原文；
        // 本文件没有 absence 断言 —— 那种要先剥注释（照 BaopuSessionTreeTest 的口径）。
        pair("app/src/main/java/com/example/zhengdao/baopu/BaopuWorkspace.kt", "投递")
        pair("core/src/main/java/com/example/zhengdao/core/ZhengdaoDirs.kt", "资料库")
    }

    private fun pair(rel: String, source: String) {
        val code = repoText(rel)
        assertTrue("$rel：hostDir 没把实态交给留痕", code.contains("WorkspaceTruth.report("))
        assertTrue("$rel：报的必须是解析后的那一个 File（不是 prefs 原值）", code.contains("realPath = host.absolutePath"))
        assertTrue("$rel：落点是 RunLog（println 与 logcat 都不算自证）", code.contains("sink = RunLog::log"))
        assertTrue("$rel：署名要跟界面/日志对得上（$source）", code.contains("source = \"$source\""))
    }

    /** 测试的工作目录可能是仓根、也可能是模块目录，两边都试一次。 */
    private fun repoText(rel: String): String = listOf(rel, "app/$rel", "../$rel", "../../$rel")
        .map(::File)
        .firstOrNull { it.isFile }
        ?.readText()?.replace("\r\n", "\n")
        ?: throw AssertionError("找不到源码：$rel")
}
