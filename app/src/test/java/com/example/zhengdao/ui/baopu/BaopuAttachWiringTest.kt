// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.ui.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 附件（第二十二批 A 段）接线守卫 —— 用户 2026-10-18：「入口要两个（拍照＋相册）还是要三个
 * （再加「选文件」），一个大+号就可以是这三个的入口了吧？」
 *
 * 六组判据（源码/清单文本级，先剥注释再断言）：
 * ① **一个「＋」开三个口**：拍照／相册／选文件都在同一个 bottom sheet 里，不摆三个常驻键；
 * ② 清单有 `CAMERA` 且**运行时请求**（声明不要权限＝起相机直接崩）；
 * ③ FileProvider **只**暴露相机临时目录（不许 `<root-path>` / `<external-path>` 这种全盘口子）；
 * ④ 附件一律落**工作区 `附件/`**，消息里只带相对路径（不把绝对路径、也不把 base64 塞进对话）；
 * ⑤ 文件名消毒：`../`、`/`、`\`、控制字符都被换掉（防路径穿越）；
 * ⑥ 图片与文件**分开说**：图片那条必须明写「当前通道不能看图」（不许假装模型看过）。
 */
class BaopuAttachWiringTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    private fun code(rel: String): String = repoFile(rel).readText()
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private fun composer() = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuComposer.kt")

    private fun attach() = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuAttachments.kt")

    private fun manifest() = repoFile("src/main/AndroidManifest.xml").readText()

    @Test
    fun `一个加号开三个口`() {
        val src = composer()
        assertTrue("拍照入口", src.contains("pickImage.launch") || src.contains("requestCamera.launch"))
        assertTrue("相机权限走后 RequestPermission", src.contains("ActivityResultContracts.RequestPermission()"))
        assertTrue("相册走系统照片选择器多选（不申请存储读权限）", src.contains("PickMultipleVisualMedia(MAX_ATTACH)"))
        assertTrue("选文件走 OpenMultipleDocuments（多选）", src.contains("ActivityResultContracts.OpenMultipleDocuments()"))
        assertTrue("三个入口在同一个 sheet 里", src.contains("ModalBottomSheet"))
        // 入口图标：2026-10-18 用户「+号太大了，做个小回形针」⇒ 自绘回形针、无边框小键
        assertTrue("入口应是自绘回形针", src.contains("PaperclipGlyph"))
        assertTrue("回形针要有读屏名（自绘图形没有文字）", src.contains("contentDescription = attachDesc"))
        assertFalse("不许再回到一个大「＋」按钮", src.contains("\"＋\""))
        assertFalse("不许 import 图标库", src.contains("material.icons"))
    }

    @Test
    fun `清单有相机权限_并且运行时请求`() {
        val m = manifest()
        assertTrue("清单缺 CAMERA", m.contains("android.permission.CAMERA"))
        val src = composer()
        assertTrue("声明了就得运行时请求", src.contains("android.Manifest.permission.CAMERA"))
        assertFalse("拒绝要有话说（不许静默）", !src.contains("baopu_attach_camera_denied"))
    }

    @Test
    fun `FileProvider 只暴露相机临时目录`() {
        val m = manifest()
        assertTrue("清单缺 FileProvider", m.contains("androidx.core.content.FileProvider"))
        assertTrue("authority 必须带 applicationId 前缀", m.contains("\${applicationId}.baopu.files"))
        val paths = repoFile("src/main/res/xml/baopu_file_paths.xml").readText()
        assertTrue("只给 cache-path", paths.contains("<cache-path"))
        assertFalse("不许 root-path（等于把私有目录全开了）", paths.contains("<root-path"))
        assertFalse("不许 external-path", paths.contains("<external-path"))
        // 代码里的 authority 拼接必须与清单一致
        assertTrue(composer().contains(".baopu.files\""))
    }

    @Test
    fun `先落工作区_消息只带相对路径`() {
        val a = attach()
        assertTrue("落点是工作区下的 附件/", a.contains("const val DIR = \"附件\""))
        assertTrue("用 Workspace.hostDir 解析工作区（单一真源）", a.contains("Workspace.hostDir(ctx)"))
        assertTrue("返回相对路径", a.contains("relPath = \"\$DIR/\${target.name}\""))
        val src = composer()
        assertTrue("消息里拼的是相对路径 + 字节数", src.contains("withAttachmentHint"))
        assertFalse("不许把绝对路径塞进对话", src.contains("absolutePath"))
        assertFalse("不许把图片 base64 塞进对话", src.contains("base64") || src.contains("Base64"))
    }

    @Test
    fun `文件名消毒挡住路径穿越`() {
        val a = attach()
        assertTrue("换掉 /", a.contains(".replace('/', '_')"))
        assertTrue("换掉 \\\\", a.contains(".replace('\\\\', '_')"))
        assertTrue("控制字符压平", a.contains("\\\\p{Cntrl}"))
        assertTrue("去前导点（不许隐藏文件）", a.contains(".removePrefix(\".\")"))
        assertTrue("截 60 字", a.contains("60"))
    }

    @Test
    fun `图片与文件分开说_图片明说看不了`() {
        val zh = repoFile("src/main/res/values/strings.xml").readText()
        val en = repoFile("src/main/res/values-en/strings.xml").readText()
        listOf(
            "baopu_attach_title", "baopu_attach_camera", "baopu_attach_gallery", "baopu_attach_file",
            "baopu_attach_chip", "baopu_attach_failed", "baopu_attach_camera_denied",
            "baopu_attach_hint_files", "baopu_attach_hint_images", "baopu_attach_too_many",
        ).forEach { key ->
            assertTrue("中文缺 $key", zh.contains("name=\"$key\""))
            assertTrue("英文缺 $key", en.contains("name=\"$key\""))
        }
        assertTrue("图片提示必须写明「不能看图」", zh.contains("不能看图"))
        assertTrue("文件提示要指向 read_file", zh.contains("read_file"))
    }
}
