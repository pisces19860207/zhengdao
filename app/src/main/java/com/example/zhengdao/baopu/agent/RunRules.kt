// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：抱朴-Agent设计-程枢版 §2 状态机（刹车数值）+ §8 错误矩阵。
// 2026-10-17（抱朴刀 3 · A 段）：从 `RunEngine.kt` 拆出——派工单 §9 行数纪律
// 「单文件新增 ≤ 400 行；超了拆文件」，且派工单 §6/§12.5 白名单本就按
// `agent/RunRules.kt` 独立文件列过（拆后与白名单对齐）。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.kernel.BaopuKernel
import com.example.zhengdao.baopu.Provider
import java.io.File
import org.json.JSONObject

/**
 * 引擎的**纯判据内核**（无 Android/Compose/SQLite，JVM 直测）——
 * 刹车数值、熔断判据、确认超时判据全在这里。
 */
internal object RunRules {

    /**
     * max_steps：v1 写 16（程枢版 §2 刹车数值）；2026-10-19 用户真机口径两次上调——
     * 「好像 16 步不够啊」→ 48，「初次探索某个网络平台的制卡过程肯定很多步」→ **120**。
     * 真源是 [com.example.zhengdao.baopu.kernel.BaopuKernel.Budget.MAX_STEPS]，这里只是转手。
     */
    const val MAX_STEPS = BaopuKernel.Budget.MAX_STEPS

    /** 连续 3 次工具失败 ⇒ 停下 ask_user，禁止硬试（§2）。 */
    const val MAX_TOOL_FAILURES = BaopuKernel.Budget.MAX_TOOL_FAILURES

    /**
     * 收尾闸给模型的自修次数（2026-10-17，工具补强第二刀；从旧内嵌版搬来）。
     *
     * 为什么只给 1 次：一次 run 里「写完说完了但产物不对」多半是漏了某步；
     * 给两三轮它会开始瞎改别的文件。修一次仍不过 ⇒ 如实报「未通过」让人看，
     * 而不是继续烧 token 假装在修。
     */
    const val MAX_VERIFY_REPAIRS = BaopuKernel.Budget.MAX_VERIFY_REPAIRS

    /** 通道熔断阈值：当前通道连续 N 次失败 ⇒ 标记不可用 + 提示切通道（§2 补录）。 */
    const val CHANNEL_BREAK_THRESHOLD = BaopuKernel.Budget.CHANNEL_BREAK_THRESHOLD

    /** 确认超时：默认 15 分钟，且**仅 App 前台计时**（§2 补录）。 */
    const val CONFIRM_TIMEOUT_MS = BaopuKernel.Auth.CONFIRM_TIMEOUT_MS

    /**
     * 一轮之内自动换通道的上限（刀 1 补丁）。
     *
     * 为什么要有：换通道是 `continue` 重跑**同一步**（不涨步数），若两个通道都坏，
     * 熔断→切换→再失败会无限循环烧 token，`MAX_STEPS` 拦不住（步数没涨）。
     * 到顶后把候选置空 ⇒ `channelDecision` 只好走 `BrokenAskUser`，停下来问人。
     */
    const val MAX_CHANNEL_SWITCHES = BaopuKernel.Budget.MAX_CHANNEL_SWITCHES

    /** 一步 = 一次模型轮次 ⇒ 步数从 1 起；满 16 必停。 */
    fun isStepExhausted(steps: Int): Boolean = steps >= MAX_STEPS

    /**
     * 坏参数（模型给的 tool_call 参数不是合法 JSON）最多连续重试几次（P1-2，2026-10-19）。
     *
     * 为什么是 1：模型偶尔把引号/转义写坏，把错误原文回灌给它，下一轮基本能自己改对；
     * 同一个错误连着来第二次，说明这一轮状态不对，再试只是烧 token ⇒ 如实收尾问人。
     *
     * 为什么不进 [com.example.zhengdao.baopu.kernel.BaopuKernel.Budget]：那是 A 内核的
     * 只读真源，引擎的容错策略不该往内核里加字段（本单红线：不碰 A 内核）。
     */
    const val MAX_BAD_ARGS_RETRIES = 1

    /** 还能再试一次坏参数吗（纯判据：`retries` 是**已经**用掉的机会）。 */
    fun shouldRetryBadArgs(retries: Int): Boolean = retries < MAX_BAD_ARGS_RETRIES

    /** 连续工具失败到线 ⇒ 停下问人。 */
    fun shouldAskUser(consecutiveToolFailures: Int): Boolean =
        consecutiveToolFailures >= MAX_TOOL_FAILURES

    /**
     * 等一轮模型流式的总上限（P2-5，2026-10-19 第五十六批）。
     *
     * 为什么必须有：`latch.await()` 无参 ⇒ 通道只连上不收数据时，引擎那条**唯一**的
     * run 线程会挂死在这里，后面每个工具都排队。旧实现靠 readTimeout（60s）兜底，
     * 但那是「读一小段」的超时，不是「整轮」的。
     *
     * 取值 90s：比 readTimeout（60s）宽一档，给「慢但活着」的流留余地；
     * 又不至于让用户干等太久。到顶按失败处理并取消 Call。
     */
    const val STREAM_WAIT_TIMEOUT_MS = 90_000L

    /** 通道熔断判据。 */
    fun shouldBreakChannel(consecutiveChannelFailures: Int): Boolean =
        consecutiveChannelFailures >= CHANNEL_BREAK_THRESHOLD

    /**
     * 熔断后怎么处置（纯判据，JVM 直测；真正的"切"在引擎里做）。
     *
     * 三条出路：没到线就留在原通道；到线且有候选就切过去；到线且没候选就停下问人
     * （**不抛异常**——"可报告地失败"是设计口径）。
     */
    sealed interface ChannelDecision {
        /** 还没到熔断线：留在原通道（重试由上层决定）。 */
        data object KeepCurrent : ChannelDecision

        /** 到线且 [fallback] 给了候选：自动切过去。 */
        data class SwitchTo(val next: Provider) : ChannelDecision

        /** 到线且没有候选（或换够次数了）：停下问人。 */
        data object BrokenAskUser : ChannelDecision
    }

    fun channelDecision(consecutiveChannelFailures: Int, fallback: Provider?): ChannelDecision = when {
        !shouldBreakChannel(consecutiveChannelFailures) -> ChannelDecision.KeepCurrent
        fallback != null -> ChannelDecision.SwitchTo(fallback)
        else -> ChannelDecision.BrokenAskUser
    }

    /**
     * 确认超时判据（**仅前台计时**：后台期间不计时，回前台才继续计）。
     * @param foregroundElapsedMs 前台累计毫秒（后台时间由调用方剔除）
     * @return true = 已超时（按拒绝处理）
     */
    fun confirmExpired(foregroundElapsedMs: Long): Boolean = foregroundElapsedMs >= CONFIRM_TIMEOUT_MS

    /**
     * 确认等待的**前台累计毫秒**（2026-10-18 真机 T4 修正）：
     * 已结账的段落 ＋ **当前仍在计的前台段落**。
     *
     * 原接线只读已结账值（`confirmForegroundMs`），而结账发生在「切后台」那一刻——
     * 于是一直观在前台不动的确认**永远不到线**（真机实测：15 分钟过去 `pending_tool`
     * 还在库里，四步等待没人点就无限等）。当前段落必须按 `now - since` 现算。
     *
     * @param committed 已结账的前台毫秒（切后台时累加）
     * @param since 本次回前台的时刻（null = 当前在后台，不累计）
     * @param now 现时刻（单测注入，生产用 `System.currentTimeMillis()`）
     */
    fun confirmElapsedMs(committed: Long, since: Long?, now: Long): Long =
        committed.coerceAtLeast(0L) +
            (since?.let { (now - it).coerceAtLeast(0L) } ?: 0L)

    // ── 确认制纯判据（刀 3 A 段；JVM 直测） ──────────────────────────────

    /** 确认等待的轮询间隔（前台计时判据的检查粒度；100ms 对人眼/CPU 都无感）。 */
    const val CONFIRM_POLL_MS = BaopuKernel.Auth.CONFIRM_POLL_MS

    /** argsJson 落库上限（派工单 §3.5 红线 4：超了截断并标注，防大参数撑爆表）。 */
    const val CONFIRM_ARGS_MAX_CHARS = BaopuKernel.Auth.CONFIRM_ARGS_MAX_CHARS

    /**
     * 超时回灌的人话（与 strings 的 `baopu_confirm_timeout` 同文）。
     *
     * 为什么这里有一份中文：引擎要把这句话**落进会话历史**（模型要看得见），
     * 而 `strings.xml` 只有 UI 层（Compose）够得着——引擎无 Context。
     * 与引擎既有的 AskUser 通知同款口径（那些也早就是中文）；**确认卡界面上的
     * 文案仍全走 strings.xml**（派工单 §3.1 管的是界面）。
     */
    const val CONFIRM_TIMEOUT_NOTE = "等了 15 分钟没收到批准，本轮先停"

    /**
     * 确认卡摘要（纯函数：工具名 + 参数 → 一句话人话）。
     *
     * 在线出卡与重启重建**共用这一个函数**（派工单 §3.3：summary 由工具名 ＋
     * 参数重新生成，不落库）⇒ 两处永远一致，不会「重启后摘要变了」。
     * 不声称旧文件行数——那要读文件，摘要函数不碰 IO（③ 不知道的不编）。
     */
    fun confirmSummary(tool: String, args: JSONObject): String {
        val path = args.optString("path").trim().ifBlank { null }
        return when (tool) {
            "write_file" -> {
                val lines = args.optString("content").lines().size
                val verb = if (args.optBoolean("overwrite", false)) "覆盖" else "新建"
                "$verb ${path ?: "（未给路径）"}（$lines 行）"
            }

            "edit_file" -> "编辑 ${path ?: "（未给路径）"}"
            "move_path" -> "移动 ${args.optString("from").trim().ifBlank { "?" }} → " +
                args.optString("to").trim().ifBlank { "?" }
            else -> "$tool（${args.toString().take(60)}）"
        }
    }

    /**
     * argsJson 落库前截断（≤ [CONFIRM_ARGS_MAX_CHARS]；超了截断**并标注**，
     * 不静默——派工单 §3.5 红线 4）。
     *
     * 截断的 JSON 是坏的：恢复执行时 SchemaCheck/JSON 解析会挡住它 ⇒ 工具
     * Failed（可重试），模型会改用分次写。**不会**拿着半截参数真去写文件。
     */
    fun capArgsJson(json: String): String =
        if (json.length <= CONFIRM_ARGS_MAX_CHARS) {
            json
        } else {
            json.take(CONFIRM_ARGS_MAX_CHARS) + "\n…（参数过长已截断：原文 ${json.length} 字符，请分次给）"
        }

    /** 拒绝回灌（历史里多这一条；备注非空时附上——用户为什么拒，模型该知道）。 */
    fun rejectNote(summary: String, note: String?): String =
        if (note.isNullOrBlank()) "用户拒绝：$summary" else "用户拒绝：$summary；备注：$note"

    /**
     * 展示用相对路径（纯函数：绝对路径在工作区内 ⇒ 剥成相对；不在 ⇒ 原样）。
     * 确认卡标题用（`要动文件：<路径>`——绝对路径又长又泄目录结构）。
     */
    fun relativizePath(absolutePath: String, root: File): String {
        val prefix = root.absolutePath.trimEnd('/', '\\') + File.separator
        return if (absolutePath.startsWith(prefix)) absolutePath.removePrefix(prefix) else absolutePath
    }

    /** 中断标记值：会话被标「中断于第 N 步」时落这个数；-1 = 未中断。 */
    const val NOT_INTERRUPTED = -1

    // ── 压缩触发口径（第十五批 · 2026-10-18） ─────────────────────────────

    /**
     * 压缩触发的**绝对 token 上限**（第十五批 A 段）。
     *
     * 为什么要有：第十三批真机 T1 实证——触发线＝窗口×0.85，deepseek 窗口 1M ⇒
     * 触发线 891,289 token，手机会话永远够不着，「压不动的压缩」。改后触发线＝
     * min(窗口×0.85, 本值)：1M 窗口 ⇒ 120k（原来 891k）；8k 窗口 ⇒ 仍按比例
     * 6,963（不变）；未声明窗口（回落 1M）⇒ 同 1M。
     * 不改 [com.example.zhengdao.baopu.ContextAssembler.COMPRESS_THRESHOLD] 的
     * 语义与取值——只加一道封顶。
     */
    const val COMPRESS_ABS_MAX_TOKENS = BaopuKernel.Budget.COMPRESS_ABS_MAX_TOKENS

    /**
     * 第二条触发线：**可压旧消息条数下限**（第十五批 B 段）。
     *
     * 「可压旧消息」条数由 `ContextAssembler.pendingOldMessageCount` 算——
     * 直接复用 `Compressor.selectCompressionRange` 的选区（最近 KEEP_RECENT 条不动）
     * 再扣除已压进摘要的消息，**不另写一套口径**。两条触发任一满足就压；
     * 都满足不了就一定不压（C 段反向保证）。
     */
    const val COMPRESS_OLD_MESSAGE_MIN = BaopuKernel.Budget.COMPRESS_OLD_MESSAGE_MIN

    /**
     * 压缩**单次**处理的消息条数上限（第十六批第二件）。
     *
     * 为什么要有：`runOnce` 的选区扣掉已压消息（skipIds）之后仍可能很大（长会话
     * 一次攒几百条），一次全塞给压缩模型必然撞它的窗口——压缩永久失败、失败后
     * 按 10 步冷却反复重试白烧请求。上限之内一次压完；超了**只压最旧的 200 条**
     * （不许整批拒绝——整批拒绝会让长会话永远压不动，等于把压缩锁死），剩下的
     * 留给下一轮：条数触发线（[COMPRESS_OLD_MESSAGE_MIN]）仍满足，会接着压。
     */
    const val COMPRESS_MAX_MESSAGES_PER_RUN = BaopuKernel.Budget.COMPRESS_MAX_MESSAGES_PER_RUN
}
