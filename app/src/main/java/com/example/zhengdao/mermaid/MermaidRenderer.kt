// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百三十七批 · Mermaid 出图（Android 侧，**只做三件事**：搭离线离屏页、等页面回话、落 PNG）。
//
// 定位：它是 `OffscreenShotter` 的**调用方**，不是第二套离屏实现。263/262 验过的
// 「1dp 挂窗 → 载入 → 等视觉帧 → draw → 判白图」一个字不改，本批只加了「页面自己说
// 什么时候画完了」这一条轮询通道（见 OffscreenShotter.capture 的 readyScript 参数）。
//
// 边界（派工单【安全】）：
// - 断网：离线页＋blockNetworkLoads(true)，「断网仍能出图」是结构性的，不是"我们没写请求"；
// - 禁 JS 桥：本类不装任何 JavascriptInterface，页面也没有；
// - 禁文件访问：allowFileAccess/allow*FromFileURLs 全 false，页内脚本读不到本地文件；
// - 只写一个 PNG：直接写最终文件（同名不覆盖，追加 -2/-3），**不落中间缓存**。
package com.example.zhengdao.mermaid

import android.app.Activity
import com.example.zhengdao.browser.offscreen.OffscreenShotter
import com.example.zhengdao.browser.offscreen.ProbeWebConfig
import com.example.zhengdao.core.TimeFormats
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

/** 出图落点：`<工作区>/产物/图表/`（用户看到的 `证道/产物/图表`）。 */
object MermaidPaths {
    const val DIAGRAMS = "产物/图表"

    fun diagramsDir(root: File): File = File(root, DIAGRAMS)

    /** 文件名：`mermaid-<类型>-<年月日-时分秒>.png`——全 ASCII，任何工具链都咬得动。 */
    fun fileName(kind: MermaidKind, stamp: String): String = "mermaid-${kind.label}-$stamp.png"

    /** 时间戳口径**只有一处**：`:core` 的 [TimeFormats]（B-补-24 门禁钉着，不许自己 new 格式化器）。 */
    fun stamp(now: Long = System.currentTimeMillis()): String = TimeFormats.fileStamp(now)

    /** 同名不覆盖：已存在就 `-2`、`-3`……（与仓库既有产物口径一致）。 */
    fun unique(dir: File, name: String): File {
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "png")
        var f = File(dir, name)
        var n = 2
        while (f.exists()) {
            f = File(dir, "$base-$n.$ext")
            n++
        }
        return f
    }
}

/** 一次出图的结果。失败**必须**带一句人话（首行错误），不许静默空图。 */
sealed interface MermaidRenderResult {
    data class Ok(
        val file: File,
        val width: Int,
        val height: Int,
        val dominantRatio: Double,
    ) : MermaidRenderResult

    data class Fail(val reason: String) : MermaidRenderResult
}

/**
 * 渲染器。**调用方必须在非主线程**（内部会阻塞等主线程的离屏会话）。
 *
 * [invocationCount] 是给判据「没点按钮＝0 次离屏任务」用的计数器：只有真正走进来
 * 才会 +1，UI 那边不点就不加——这条在真机测试里被断言，不靠"看代码里没调用"。
 */
object MermaidRenderer {

    private val invocations = AtomicInteger(0)

    fun invocationCount(): Int = invocations.get()

    fun resetInvocationCount() {
        invocations.set(0)
    }

    /**
     * 出图。
     *
     * @param root 工作区根（`BaopuWorkspace.hostDir` 的结果）
     * @param code 图源码（围栏内文本，**不含** ```mermaid 标记行）
     */
    fun render(
        activity: Activity,
        root: File,
        code: String,
        kind: MermaidKind = MermaidFence.kindOf(code),
        stamp: String = MermaidPaths.stamp(),
    ): MermaidRenderResult {
        invocations.incrementAndGet()

        val bytes = code.toByteArray(StandardCharsets.UTF_8).size
        if (bytes > MermaidFence.MAX_CODE_BYTES) {
            return MermaidRenderResult.Fail(
                "代码块 ${bytes} 字节，超过 ${MermaidFence.MAX_CODE_BYTES} 字节上限（不截断，免得画出一张残图）",
            )
        }
        if (code.isBlank()) {
            return MermaidRenderResult.Fail("代码块是空的，没有可画的图")
        }

        val shotter = OffscreenShotter(
            activity = activity,
            webConfig = ProbeWebConfig(javaScriptEnabled = true),
            offlineOnly = true,
        )
        val outcome = shotter.capture(
            MermaidPage.pageUrl(),
            MermaidPage.probeScript(code),
        )

        return when (outcome) {
            is OffscreenShotter.Outcome.Ok -> {
                val dir = MermaidPaths.diagramsDir(root)
                val target = MermaidPaths.unique(dir, MermaidPaths.fileName(kind, stamp))
                runCatching {
                    if (!dir.exists()) dir.mkdirs()
                    target.writeBytes(outcome.png)
                }.map {
                    MermaidRenderResult.Ok(
                        file = target,
                        width = outcome.width,
                        height = outcome.height,
                        dominantRatio = outcome.dominantRatio,
                    )
                }.getOrElse {
                    MermaidRenderResult.Fail("出图成功但存不下来：${it.javaClass.simpleName} ${it.message}")
                }
            }

            is OffscreenShotter.Outcome.Fail -> MermaidRenderResult.Fail(readable(outcome))
        }
    }

    /** 把机器错误翻成一句人话；页面自己报的错**原样**带出去（首行已经是人话）。 */
    private fun readable(fail: OffscreenShotter.Outcome.Fail): String = when (fail.kind) {
        OffscreenShotter.Kind.PAGE_ERROR -> fail.reason
        OffscreenShotter.Kind.LOAD_ERROR -> "离线渲染页没载入：${fail.reason}"
        OffscreenShotter.Kind.PAGE_TIMEOUT -> "离线渲染页打开超时：${fail.reason}"
        OffscreenShotter.Kind.VISUAL_TIMEOUT -> "图算得太久或页面没回话：${fail.reason}"
        OffscreenShotter.Kind.SIZE_ZERO -> "画布尺寸为 0：${fail.reason}"
        OffscreenShotter.Kind.UNIFORM -> "画出来是空白：${fail.reason}"
        OffscreenShotter.Kind.EXCEPTION -> "出图出错：${fail.reason}"
    }
}
