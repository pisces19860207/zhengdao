// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.ui.baopu

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第二十四批 B 段判据（B1–B5）。
 *
 * ## 钉的是什么
 *
 * B 段做四件事：附件落 `part.kind='image'`、Composer 传结构化附件、气泡渲缩略图、
 * 文件缺失要说。其中**只有 B1 有纯函数可测**（[AttachmentParts.imageRelPaths]）；
 * 其余三条是"渲染/接线有没有这件事"，只能源码级钉住——`git grep` 式的断言不是偷懒，
 * 而是：缩略图长什么样、点开是什么，JVM 里根本跑不了 Compose。
 *
 * 所以本文件分两层：
 * - **行为层**（B1）：路径过滤的每条规则，喂脏数据进去看它能不能漏；
 * - **源码层**（B2/B3/B4/B5）：hint 还在、96dp 与长按在、缺失文案在、中英对称。
 */
class BaopuAttachmentPartsTest {

    private fun img(rel: String, size: Long = 1024L) =
        BaopuAttachments.Saved(relPath = rel, sizeBytes = size, displayName = "x.jpg", isImage = true)

    private fun file(rel: String) =
        BaopuAttachments.Saved(relPath = rel, sizeBytes = 10L, displayName = "x.txt", isImage = false)

    private fun appFile(rel: String): File =
        File(rel).let { if (it.exists()) it else File("app/$rel") }

    private fun read(rel: String): String = appFile(rel).readText()

    private val composer: String
        get() = read("src/main/java/com/example/zhengdao/ui/baopu/BaopuComposer.kt")

    private val list: String
        get() = read("src/main/java/com/example/zhengdao/ui/baopu/BaopuMessageList.kt")

    private val controller: String
        get() = read("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt")

    private val zh: String get() = read("src/main/res/values/strings.xml")
    private val en: String get() = read("src/main/res/values-en/strings.xml")

    // ── B1：只挑图片 ＋ 只收相对路径 ─────────────────────────────────────────

    @Test
    fun `B1_只挑图片_文件附件不落image_part`() {
        val out = AttachmentParts.imageRelPaths(
            listOf(
                file("附件/说明.txt"),
                img("附件/photo.jpg"),
                file("附件/报告.pdf"),
            ),
        )
        assertEquals(listOf("附件/photo.jpg"), out)
    }

    @Test
    fun `B1_多张图保持原顺序`() {
        val out = AttachmentParts.imageRelPaths(
            listOf(img("附件/a.jpg"), img("附件/b.jpg"), img("附件/c.jpg")),
        )
        assertEquals(listOf("附件/a.jpg", "附件/b.jpg", "附件/c.jpg"), out)
    }

    @Test
    fun `B1_绝对路径一律不收`() {
        // 落库的 payload 只能是相对工作区的路径：换工作区/换机后绝对路径全废，
        // 而且本机路径不该进数据库（会被导出、被备份）。
        listOf(
            "/storage/emulated/0/DCIM/a.jpg",
            "/data/user/0/com.example.zhengdao/files/a.jpg",
            "/sdcard/a.jpg",
        ).forEach { abs ->
            assertTrue("绝对路径必须被过滤：$abs", AttachmentParts.imageRelPaths(listOf(img(abs))).isEmpty())
        }
    }

    @Test
    fun `B1_data_base64与其他scheme一律不收`() {
        listOf(
            "data:image/jpeg;base64,AAAA",
            "file:///storage/emulated/0/a.jpg",
            "content://media/external/images/1",
            "http://example.com/a.jpg",
            "https://example.com/a.jpg",
        ).forEach { bad ->
            assertTrue("非相对路径必须被过滤：$bad", AttachmentParts.imageRelPaths(listOf(img(bad))).isEmpty())
        }
    }

    @Test
    fun `B1_上跳与反斜杠与盘符不收`() {
        listOf("../outside.jpg", "附件/../../etc/passwd.jpg", "附件\\win.jpg", "C:/x/a.jpg").forEach { bad ->
            assertTrue("越界路径必须被过滤：$bad", AttachmentParts.imageRelPaths(listOf(img(bad))).isEmpty())
        }
    }

    @Test
    fun `B1_空白路径不收`() {
        assertEquals(emptyList<String>(), AttachmentParts.imageRelPaths(listOf(img("   "), img(""))))
    }

    @Test
    fun `B1_相对路径照收_带子目录也收`() {
        listOf(
            "附件/20261018-173000-照片.jpg",
            "附件/子目录/深一点.png",
            "shot.jpg",
        ).forEach { ok ->
            assertEquals("相对路径不该被误杀：$ok", listOf(ok), AttachmentParts.imageRelPaths(listOf(img(ok))))
        }
    }

    @Test
    fun `B1_落库处用的是这个判据_不是另一套`() {
        // 接线证据：controller 必须走 AttachmentParts.imageRelPaths，
        // 而不是自己 map 一遍 relPath（那样过滤就漏了）
        assertTrue(
            "controller 必须用 AttachmentParts.imageRelPaths 取路径",
            controller.contains("AttachmentParts.imageRelPaths(attachments)"),
        )
        assertTrue(
            "落库语句必须是 kind='image' 且 payload 是相对路径变量",
            controller.contains("""BaopuSql.insertPart(messageId, i + 1, "image", rel, now)"""),
        )
    }

    // ── B2：文本 hint 仍在 ───────────────────────────────────────────────────

    @Test
    fun `B2_文本hint没被删_仍拼进要发出去的话`() {
        assertTrue(
            "Composer 必须继续调 withAttachmentHint —— 有了 image part 不等于模型看得到图",
            composer.contains("onSend(withAttachmentHint(ctx, body, list), list)"),
        )
        // withAttachmentHint 本体也必须在（不是被改成空实现）
        assertTrue(composer.contains("internal fun withAttachmentHint("))
        assertTrue(
            "hint 内容要区隔图片与文件（原来的两条文案还在）",
            composer.contains("baopu_attach_hint_images"),
        )
    }

    // ── B3：96dp 缩略图 ＋ 点开全屏 ＋ 长按复制路径 ──────────────────────────

    @Test
    fun `B3_缩略图是96dp`() {
        assertTrue("派工单指定 96dp", list.contains("IMAGE_THUMB_DP = 96.dp"))
    }

    @Test
    fun `B3_点开全屏且长按复制路径`() {
        assertTrue("点击要打开全屏 Dialog", list.contains("combinedClickable("))
        assertTrue("要有全屏 Dialog", list.contains("DialogProperties(usePlatformDefaultWidth = false)"))
        assertTrue(
            "长按复制的是**相对路径**（不是文件、不是绝对路径）",
            list.contains("ClipData.newPlainText(\"baopu-image-path\", relPath)"),
        )
    }

    @Test
    fun `B3_只读文件_不复制不新落盘`() {
        // 图从工作区直读；代码里不许出现任何"往别处写一份"的动作
        assertTrue("读的是工作区根 + 相对路径", list.contains("File(Workspace.hostDir(ctx), relPath)"))
        listOf("copyTo(", "copyRecursively(", "FileOutputStream(").forEach { writeApi ->
            assertFalse("渲染缩略图不该写盘：出现了 $writeApi", list.contains(writeApi))
        }
    }

    @Test
    fun `B3_图片part不进SelectionContainer_否则长按被选字抢走`() {
        assertTrue(
            "图片 part 必须从文字区里分出来",
            list.contains("""val imageParts = msg.parts.filter { it.kind == "image" }"""),
        )
    }

    // ── B4：缺失要说，不许空白方块 ───────────────────────────────────────────

    @Test
    fun `B4_文件缺失显示明确文字`() {
        assertTrue(
            "要有 baopu_image_missing 这条分支",
            list.contains("R.string.baopu_image_missing"),
        )
        assertTrue("要真的判断文件在不在", list.contains("file.isFile && file.length() > 0"))
    }

    @Test
    fun `B4_缺失文案带路径占位`() {
        val line = zh.lines().first { it.contains("baopu_image_missing") }
        assertTrue("要把路径写出来：$line", line.contains("%1\$s"))
    }

    // ── B5：中英对称 ─────────────────────────────────────────────────────────

    @Test
    fun `B5_三个新键中英对称`() {
        listOf("baopu_image_missing", "baopu_image_open", "baopu_image_path_copied").forEach { key ->
            assertTrue("中文缺键：$key", zh.contains("""name="$key""""))
            assertTrue("英文缺键：$key", en.contains("""name="$key""""))
        }
    }
}
