// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.baopu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第二十四批 C 段（组装半段）· **接线门禁**。
 *
 * ## 为什么非要源码级钉住
 *
 * 「图片到不了模型眼前」这件事，在 24-A／24-B 之后的失败形状**不是崩，是静默**：
 * 图存在库里、气泡里也看得见缩略图，可请求体里一张都没有，模型照样编内容。
 * 纯函数单测（[VisionFilesTest]）测不到这种断线——断点在**两处调用**之间：
 * 控制器把路径交给引擎、引擎把 payload 交给组装器。
 *
 * 所以这里钉四条接线，每条都对应一个真实断过的可能：
 * 1. 控制器**传了路径**（不传 ⇒ 引擎永远拿不到图）；
 * 2. 引擎**真去读了**（读的根必须是工作区 `spillRoot()`，不是别处）；
 * 3. 引擎把 `visionSupported` **取自 `provider.vision`**（V5：组装层不许自己猜模型名）；
 * 4. 续跑**不带图**（否则上一轮的图会被重发一遍，白烧 token）。
 */
class VisionWiringTest {

    private fun src(rel: String): String =
        File(rel).let { if (it.isFile) it else File("app/$rel") }.readText()

    private val engine: String
        get() = src("src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt")

    private val controller: String
        get() = src("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt")

    private val asm: String
        get() = src("src/main/java/com/example/zhengdao/baopu/ContextAssembler.kt")

    @Test
    fun `C1_控制器把相对路径交给引擎`() {
        assertTrue(
            "send 必须把本批图片路径带进 engine.send（不传 ⇒ 引擎永远拿不到图）",
            controller.contains("engine.send(sid, text, imageRelPaths)"),
        )
        assertTrue(
            "路径来源必须是 B 段那个判据（同一批判据，不许另起一套）",
            controller.contains("val imageRelPaths = AttachmentParts.imageRelPaths(attachments)"),
        )
        // 第一百一十七批：路径必须**随消息**走。控制器里若还有共享的 pendingImagePaths，
        // 排队的两条带图消息会串图 —— 这条负例就是「背景任务丢/串消息」的守门员。
        assertFalse(
            "控制器不许再用单个 pendingImagePaths 格子（队列会串图）",
            controller.contains("pendingImagePaths"),
        )
        assertTrue(
            "引擎事件必须带回这条消息自己的图路径",
            engine.contains("val imageRelPaths: List<String> = emptyList()"),
        )
        assertTrue(
            "控制器按事件的路径补 image part（不是从共享格子里取）",
            controller.contains("attachImageParts(ev.messageId, ev.imageRelPaths)"),
        )
    }

    @Test
    fun `C2_引擎真去读了_且读的根是工作区`() {
        // 2026-10-18 改口径：读图入口从 payloadsOf 换成 load —— 前者会把「超 2 MB 跳过」
        // 的原因吞掉，真机上表现为「一张 3 MB 照片 = 模型看不见图」。load 必须带缩图实现。
        assertTrue(
            "引擎必须用 VisionFiles.load(spillRoot(), paths, …) 读图",
            engine.contains("VisionFiles.load(spillRoot(), turnImagePaths"),
        )
        assertTrue(
            "读图必须带上缩图实现（不带 ⇒ 超 2 MB 的图照旧全丢）",
            engine.contains("shrink = shrinkImage"),
        )
        assertTrue(
            "读图失败不许让整轮崩（要有 runCatching 兜底）",
            engine.contains("读图失败"),
        )
        // 读盘必须在引擎线程（send 是主线程），所以 send 只许存路径、不许自己读
        val sendBody = engine.substringAfter("fun send(").substringBefore("fun stop(")
        assertFalse(
            "send 里不许出现读盘（主线程不许读 2 MB 的图）",
            sendBody.contains("VisionFiles.load("),
        )
    }

    @Test
    fun `C3_视觉能力只许来自 provider 点 vision`() {
        assertTrue(
            "visionSupported 必须取自 provider.vision（V5：组装层不许自己猜模型名）",
            engine.contains("visionSupported = provider.vision"),
        )
        assertTrue(
            "payload 必须真的交给组装器",
            engine.contains("images = images,"),
        )
        assertTrue(
            "组装器里仍是唯一一次 VisionRules.select（不许在引擎里再裁一遍）",
            // 第一百二十四批改判：select 随签名平移多了一个带默认值的 userAuthorized；
            // 「唯一一次裁图」这条判据本身一个字没松（同批先例：105 批 81618595）。
            asm.contains("VisionRules.select(images, visionSupported, visionAuthorized)"),
        )
    }

    @Test
    fun `C4_续跑不带图_不重发上一轮的图`() {
        assertTrue(
            "runLoop 开跑时必须按 resumed 决定要不要读图",
            engine.contains("turnImages = if (resumed)"),
        )
        assertTrue(
            "本轮结束要清掉（否则下一次 resume 会把上一轮的图再发一遍）",
            engine.contains("turnImages = emptyList()"),
        )
    }

    @Test
    fun `C5_提示词不再断言通道不能看图`() {
        // C 段之后「能不能看图」取决于通道，硬写「当前通道不能看图」就是在请求里自我矛盾
        val zh = src("src/main/res/values/strings.xml")
        val line = zh.lines().first { it.contains("baopu_attach_hint_images") }
        assertFalse("中文提示不许再断言看不了图：$line", line.contains("当前通道不能看图"))
        val en = src("src/main/res/values-en/strings.xml")
        val enLine = en.lines().first { it.contains("baopu_attach_hint_images") }
        assertFalse("英文提示不许再断言看不了图：$enLine", enLine.contains("cannot see images"))
    }
}
