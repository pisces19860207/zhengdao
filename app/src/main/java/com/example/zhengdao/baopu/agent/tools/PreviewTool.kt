// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单 2026-10-19 第五十九批 §5（玄览本地预览：只显示、默认不跑）＋ §7（许愿式
// 编程 W6：抱朴能写程序/做页面，产物在玄览本地预览页签里**看**，App 不替它执行）。
// 用户口径逐字：「只显示、不运行」「工具要给抱朴备齐哦」「和工具渐进披露这个一起开工单吧」。
//
// 三条设计口径：
// 1. **只读**：本工具不写任何文件、不发任何网络请求——它把内容交给玄览的「本地预览」页签，
//    断网与禁 JS 由页签那一侧硬拦（`LocalPreviewPolicy` + `EngineSecurityConfig.blockAllNetwork`），
//    不是靠「别点链接」。所以权限是 [ToolPermission.FREE]（每次弹确认卡问「可以显示吗」是骚扰）。
// 2. **路径守卫复用现成的**：[GuardPipeline] 第 ② 段已按 path 参数名做过工作区内判定，
//    这里再用一次同一个纯判据 [BaopuGuardCore.checkPath] 兜底（越界直接 Rejected），
//    这样单测能不打 Android 就验到「越界拒」，且一个字没改管线的权限语义。
// 3. **接线是运行时的**（照 [PlanTool.sink] / [CapabilitiesTool.sink] 房型）：
//    显示要 Context/WebView ⇒ Android 侧实现挂在 [PreviewTool.sink]（装配层
//    [com.example.zhengdao.baopu.BaopuRuntime]），没接线就**明说**，不静默丢。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.BaopuGuardCore
import org.json.JSONObject
import java.io.File

/**
 * 本地预览的显示落点（Android 侧实现；本工具层零 Android 依赖）。
 *
 * @return null = 已交给玄览页签；非 null = 人话拒绝原因（如实回灌给模型，不静默）。
 */
internal interface PreviewSink {
    fun show(relPath: String, text: String): String?
}

/** preview_local_file（第五十九批 §5.2：工作区内文件的只显示预览）。 */
internal object PreviewTool {

    const val NAME = "preview_local_file"

    const val ARG_PATH = "path"

    /**
     * 单文件上限（与 [ReadFileTool.MAX_FILE_BYTES] 同档：1 MB）。
     *
     * 为什么要有上限还要拒而不是截断：预览页签吃的是整段内容，截一半的 HTML 会渲染出
     * 一个「看着像坏掉」的页面——那种假象比一句「太大了，先 read_file 分段看」更坑人。
     */
    const val MAX_FILE_BYTES = 2L * 1024 * 1024

    /** 显示落点（装配层注入；JVM 单测注入假件）。 */
    @Volatile
    var sink: PreviewSink? = null

    /** 写进系统提示的工具描述（≤ [ToolSpec.MAX_DESC] 字符；工具描述是注入面）。 */
    const val DESCRIPTION: String =
        "把工作区里的一个文件送到玄览的「本地预览」页签**只显示**：HTML 能看排版，" +
            "其余按源码纯文本看（有「源码／渲染」切换与复制）。" +
            "这一页签默认断网、默认不跑脚本，开了「脚本沙箱」也只在本页签跑、出不了网；" +
            "它不执行你写的程序、不接终端、不碰 rootfs。路径必须在工作区内，越界直接拒。"

    fun spec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = NAME,
        description = DESCRIPTION,
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_PATH to jsonObjectOf(
                    "type" to "string",
                    "description" to "相对工作区根的文件路径（如 产物/首页.html）",
                ),
            ),
            "required" to jsonArrayOf(ARG_PATH),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.FREE,
        timeoutMs = ToolTimeout.FILE_MS,
        // 第九十二批：只读盘（不改文件），但会**切玄览页签** ⇒ 不许和别的读并行
        readOnly = true,
    ) { args -> run(workspaceRoot(), args) }

    /**
     * 判据本体（**纯函数**：root 与 file 已定，无 Android 依赖，JVM 直测）。
     *
     * 三条纪律与 read_file 同规：「不存在」与「不是文件」分开说（合成一句模型会反复重试）；
     * 超限/二进制**拒并说清**，不硬塞进界面；越界一律 Rejected（终止本轮那条工具，
     * 不是 Failed 重试——撞墙重试第二次还是墙）。
     */
    fun preview(root: File, file: File): ToolOutcome {
        val verdict = BaopuGuardCore.checkPath(root, file.absoluteFile)
        if (verdict is BaopuGuardCore.PathVerdict.Outside) {
            return ToolOutcome.Rejected(
                "越界：${file.name} 不在工作区里（本地预览只显示工作区内的文件，" +
                    "也不提供别的显示通道）。",
            )
        }
        if (!file.exists()) {
            return ToolOutcome.Failed(
                "不存在：${file.name}（工作区里没有这个文件；先 write_file 写出来，或用 list_dir 看结构）",
            )
        }
        if (!file.isFile) {
            return ToolOutcome.Failed("不是一个文件：${file.name}（预览只吃单个文件；目录用 list_dir 看）")
        }
        if (file.length() > MAX_FILE_BYTES) {
            return ToolOutcome.Failed(
                "文件太大不预览：${file.name} 有 ${file.length()} B，超过 ${MAX_FILE_BYTES} B 上限" +
                    "（这类文件先 read_file 分段看内容，别整塞界面）。",
            )
        }
        val bytes = runCatching { file.readBytes() }
            .getOrElse { return ToolOutcome.Failed("读不了这个文件（权限或 IO 错误）：${file.name}") }
        if (bytes.isEmpty()) return ToolOutcome.Failed("空文件：${file.name}（没东西可显示）")
        val decoded = TextDecode.decode(file, bytes)
        val text = decoded.text ?: return ToolOutcome.Failed(
            decoded.error ?: "读不了：${file.name}（看起来不是文本文件；本工具不把二进制塞进预览）",
        )
        val rel = runCatching { root.toPath().relativize(file.absoluteFile.normalize().toPath()).toString() }
            .getOrDefault(file.name)
            .replace('\\', '/')
        // ⚠️ 两档 null 必须分开判：[PreviewSink.show] **回 null 是"成功"**（已交给页签），
        // 而 sink 本身为 null 才是"没接线"。写成 `sink?.show(...) ?: "没接线"` 会把成功
        // 吞成没接线（elvis 分不清这两种 null）——这里显式分两步，别改回一行。
        val previewSink = sink
            ?: return ToolOutcome.Failed(
                "本地预览没接线（玄览未装配）：本次调用不生效。" +
                    "先切到「玄览」页打开一次浏览器再让我重发，不是我这边没这个本事。",
            )
        val reason = previewSink.show(rel, text)
        return if (reason == null) {
            ToolOutcome.Ok(
                "已送到玄览「本地预览」页签：$rel（${decoded.encoding}，${text.length} 字）。" +
                    "**切到「玄览」就能看到**（这一句别说漏：登记与显示是两步，" +
                    "用户不在玄览时不会立刻切过去，抱朴预览卡那条 B 路径同样如此）。" +
                    "只显示：默认断网、默认不跑脚本，App 不执行它。" +
                    "改过这个文件再调一次就是新内容（不留旧缓存）。",
            )
        } else {
            ToolOutcome.Failed("$reason（文件没改、也没换个方式显示）")
        }
    }

    /** executor：arguments 里的 path 已被管线守过（绝对路径）；本函数再兜一次越界。 */
    private fun run(root: File, args: JSONObject): ToolOutcome {
        val path = args.optString(ARG_PATH).trim()
        if (path.isBlank()) return ToolOutcome.Failed("缺少必填参数「$ARG_PATH」")
        return preview(root, File(path))
    }
}
