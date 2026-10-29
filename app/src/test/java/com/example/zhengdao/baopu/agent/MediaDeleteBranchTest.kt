// 独立开发声明：本文件为本项目原创。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.MediaDelete
import com.example.zhengdao.baopu.MediaOwnership
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 第一百九十九批 · `delete_path` 的**相册分流**（纯判据，不碰 Android）。
 *
 * 五条：
 * ① 相册路径 ⇒ 交给注入的相册删除通道，**不当作工作区文件去 resolve**（否则会去删
 *    `<工作区>/Pictures/…` 这个根本不存在的路径，把「删掉了」说成成功）；
 * ② 「不是自己写的（Ask）」⇒ **Rejected**，绝不当成删除成功；
 * ③ 通道没接上 ⇒ Failed（不假装删掉）；
 * ④ 相册外的普通路径仍走原来的工作区删除（分流不许误伤）；
 * ⑤ 相册路径的三种写法都能认出来（绝对路径/相册相对/口语）。
 */
class MediaDeleteBranchTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun album(name: String = "证道截屏-20261025-083000.png") =
        MediaOwnership.relShotDir() + name

    @Test
    fun `相册路径_Done_回执说清相册同步消失`() {
        val root = tmp.newFolder("ws")
        val entry = MediaOwnership.Entry(
            id = 12L,
            name = "证道截屏-20261025-083000.png",
            relPath = album(),
            sha256 = "e".repeat(64),
            owner = "com.example.zhengdao",
            createdAt = 1L,
            source = MediaOwnership.SOURCE_SCREENSHOT,
            bytes = 10,
        )
        val out = DeletePathTool.run(
            path = album(),
            root = root,
            albumDelete = { MediaDelete.Done(entry) },
        )
        assertTrue("应当是成功：$out", out is ToolOutcome.Ok)
        assertTrue((out as ToolOutcome.Ok).text.contains("相册里同步消失"))
        assertFalse("相册那条不该在工作区里造出同名文件", File(root, "Pictures").exists())
    }

    @Test
    fun `相册路径_Ask_一律Rejected不当成功`() {
        val out = DeletePathTool.run(
            path = album("别人的照片.jpg"),
            root = tmp.newFolder("ws"),
            albumDelete = { MediaDelete.Ask("归属清单里没有这一条") },
        )
        assertTrue("不是自己写的必须拒绝：$out", out is ToolOutcome.Rejected)
        assertTrue((out as ToolOutcome.Rejected).reason.contains("没有删"))
    }

    @Test
    fun `相册路径_通道没接上_如实说`() {
        val out = DeletePathTool.run(
            path = album(),
            root = tmp.newFolder("ws"),
            albumDelete = null,
        )
        assertTrue(out is ToolOutcome.Failed)
        assertTrue((out as ToolOutcome.Failed).error.contains("相册删除通道没接上"))
    }

    @Test
    fun `相册路径_三种写法都分流`() {
        val abs = "/storage/emulated/0/" + album()
        var seen = mutableListOf<String>()
        for (p in listOf(album(), abs, "Screenshots/" + "证道截屏-20261025-083000.png")) {
            val out = DeletePathTool.run(
                path = p,
                root = tmp.newFolder(),
                albumDelete = { rel ->
                    seen += rel
                    MediaDelete.Ask("测试用")
                },
            )
            assertTrue("$p 应当被识别为相册路径：$out", out is ToolOutcome.Rejected)
        }
        assertEquals(
            "三条路径都要规整成同一口径（268：系统截屏夹）",
            listOf(album(), album(), album()),
            seen,
        )
    }

    @Test
    fun `相册路径_存量目录的口语写法仍分流到相册通道`() {
        // 268：旧目录 Pictures/证道/截屏/ 不再有新写入，但旧账本条目必须删得掉（判据照旧）
        var seen = mutableListOf<String>()
        val out = DeletePathTool.run(
            path = "证道/" + "截屏/证道截屏-20261025-083000.png",
            root = tmp.newFolder(),
            albumDelete = { rel ->
                seen += rel
                MediaDelete.Ask("测试用")
            },
        )
        assertTrue("存量路径也该走相册通道：$out", out is ToolOutcome.Rejected)
        assertEquals(
            listOf(MediaOwnership.relLegacyShotDir() + "证道截屏-20261025-083000.png"),
            seen,
        )
    }

    @Test
    fun `非相册路径_仍走工作区删除`() {
        val root = tmp.newFolder("ws")
        val p = File(root, "附件/a.png").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        assertTrue(p.isFile)
        val out = DeletePathTool.run(
            path = "附件/a.png",
            root = root,
            stamp = "20261025-090000",
            // 相册通道就算给了也不该被用到
            albumDelete = { error("非相册路径不该走相册通道：$it") },
        )
        assertTrue("默认进回收站应当成功：$out", out is ToolOutcome.Ok)
        assertFalse("源文件应当已被移走", p.exists())
        assertTrue("应当进回收站", File(root, "回收站/20261025-090000-a.png").isFile)
    }
}
