// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：抱朴-Agent设计-程枢版 §2 状态机（刹车数值）+ §8 错误矩阵。
// 2026-10-17（抱朴刀 3 · A 段）：从 `RunEngine.kt` 拆出——派工单 §9 行数纪律
// 「单文件新增 ≤ 400 行；超了拆文件」，且派工单 §6/§12.5 白名单本就按
// `agent/RunRules.kt` 独立文件列过（拆后与白名单对齐）。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.Provider
import com.example.zhengdao.baopu.FilePolicy
import com.example.zhengdao.baopu.kernel.BaopuKernel
import java.io.File
import org.json.JSONObject

/**
 * 引擎的**纯判据内核**（无 Android/Compose/SQLite，JVM 直测）——
 * 刹车数值、熔断判据、确认超时判据全在这里。
 */
internal object RunRules {

    /**
     * max_steps：v1 写 16（程枢版 §2 刹车数值）；2026-10-19 用户真机口径两次上调——
     * 「好像 16 步不够啊」→ 48，「初次探索某个网络平台的制卡过程肯定很多步」→ **120**；
     * 189 再抬到 **300**（＝`STEP_SEGMENTS` 3 × `STEPS_PER_SEGMENT` 100，抬之前先装重复调用熔断）。
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

    /**
     * 熔断/失败后递给引擎的**候选**（第一百三十一批 ②；纯数据，判定全在装配层）。
     *
     * 为什么要有这个壳而不是继续返回 [Provider]：②「同一 provider 可填多把 Key，遇
     * 429/403/额度耗尽切下一个」与「整条通道坏了换一条」在引擎里必须走**同一个写入口**
     * （`ChannelSwitchTruthGateTest` 钉死 provider 字段只许两处赋值）。壳就是为此：
     * 装配层把"下一把 Key"包成 next = 当前 Provider 换掉 apiKey 的副本 并置
     * [keySwap]，引擎在同一个分支里换掉 provider，只是话说得不一样、计数器不一样。
     *
     * 为什么带 [notice]：② 要求"全部不可用 ⇒ 可读提示，不静默重试"。那句提示的原料
     * （几把在冷却、最快多久回来）只有拿着 Key 池的装配层知道，引擎猜不出来；
     * 而它**不是 Key**（KeyPool.mask 那关都过不了的东西根本不进这里），所以可以上界面。
     *
     * 怎么区分"换 Key"与"换通道"而不用第四个信号：看 [next] 的 `id` 还等不等于当前 provider
     * 的 `id`。这条判据是安全的，因为候选名单的来源只有两处——
     * `ProviderPrefs.routeCandidates` **显式排除当前 id**（第九十三批起就这样），
     * 而 Key 池永远挂在当前 id 名下 ⇒ 同 id 只能是换 Key。**新加候选来源时必须守住这一点。**
     *
     * @param next 要切过去的那个 Provider（含 Key）；null = 没得切，只剩 [notice] 要说
     * @param keySwap true = 同一条模型换一把 Key（不花换通道次数、不涨步数）
     * @param notice 要贴给用户的一句话；空串 = 没有额外的话要说
     */
    data class ChannelCandidate(
        val next: Provider?,
        val keySwap: Boolean = false,
        val notice: String = "",
    ) {
        /** 什么都没带（引擎按"没候选"处理，与 null 同义，只是调用方少写一个 `?.`）。 */
        val isEmpty: Boolean get() = next == null && notice.isEmpty()
    }

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

    /**
     * 坏参数回灌文案（纯函数，第九十五批）。
     *
     * **只改前缀，错误原文逐字保留**——原文里有字段名与允许范围，是模型能改对的前提。
     *
     * @param kind 坏在哪（JSON 语法 / Schema）。两者共用一句话的后果见 [ToolCallCodec.BadArgsKind]。
     * @param error 校验/解析给出的错误原文（不许改写、不许截断）
     */
    fun badArgsFeed(kind: ToolCallCodec.BadArgsKind, error: String): String {
        val prefix = when (kind) {
            ToolCallCodec.BadArgsKind.JSON_SYNTAX -> "工具调用参数不是合法 JSON"
            ToolCallCodec.BadArgsKind.SCHEMA -> "工具调用参数不符合 schema"
        }
        return "$prefix：$error；请修正参数后重试"
    }

    /** 还能再试一次坏参数吗（纯判据：`retries` 是**已经**用掉的机会）。 */
    fun shouldRetryBadArgs(retries: Int): Boolean =
        // 第八十七批：判据本体搬到统一策略表（[FailurePolicy]），这里是**同语义**的薄适配器
        // —— `retries` 是 0-based「已用掉几次」，策略表吃 1-based「这是第几次失败」⇒ +1。
        // 保留这个函数是为了不惊动既有调用点与判据（P1EngineGateTest 钉着它必须出现）。
        FailurePolicy.decide(FailureKind.BAD_JSON, retries + 1, fallbackAvailable = false) is FailureAction.Retry

    /**
     * 省钱闸的**可读错误**（第一百八十八批 §1.6，2026-10-24）。
     *
     * 病灶：坏参数到线（同一轮连续第 [attempts] 次仍不合法）时旧文案只有一句
     * 「模型连续给的参数都不合法，这轮停下来（错误已记日志）」——不说法通道、
     * 不说法工具、不说法服务端原话，排障得翻日志猜。本函数把「哪家／哪字段／
     * 服务端原话」合成一行，进 run_log.err（工程取证列）。
     *
     * @param providerId 通道 id（哪家）
     * @param protocol 协议名（哪家第二重：同 id 也可能切协议）
     * @param toolName 出错的工具（哪字段的载体；Schema 错的字段名在 [rawError] 原文里）
     * @param rawError 错误原文（逐字，不许截断——「服务端原话」就是它）
     * @param attempts 这一轮第几次仍不合法（1-based；到线那次）
     */
    fun badArgsStopSummary(
        providerId: String,
        protocol: String,
        toolName: String,
        rawError: String,
        attempts: Int,
    ): String = "坏参数到线已停（同一轮第 $attempts 次仍不合法）：" +
        "通道 $providerId（$protocol）；" +
        "工具 ${toolName.ifBlank { "（没给出名字）" }}；" +
        "原话：$rawError"

    /** 连续工具失败到线 ⇒ 停下问人。 */
    fun shouldAskUser(consecutiveToolFailures: Int): Boolean =
        // 第八十七批：同 [shouldRetryBadArgs]——判据本体在统一策略表，这里保持原签名与语义
        // （「还能再让模型试一次」＝策略给 Retry；到线必然是 AskUser）。
        FailurePolicy.decide(
            FailureKind.TOOL_ERROR,
            consecutiveToolFailures,
            fallbackAvailable = false,
        ) !is FailureAction.Retry

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

    fun channelDecision(consecutiveChannelFailures: Int, fallback: Provider?): ChannelDecision =
        // 第八十七批：判据本体搬到统一策略表（[FailurePolicy]），本函数退成**物理映射器**
        // ——「连续失败 N 次」在没有重试预算的口径下问策略，再把四种语义翻成三条老出路。
        // 用 [FailurePolicyConfig.LEGACY_NO_RETRY] 是刻意的：这个签名的语义里**没有重试**
        // （「重试」是第八十七批新加的一层，压缩在 [FailurePolicyConfig.retries] 里，由引擎
        // 先问一次策略、没被判成重试才走到这里）⇒ 旧口径逐条相等，ProviderSwitchTest 不动。
        channelDecision(
            FailurePolicy.decide(
                FailureKind.NETWORK,
                consecutiveChannelFailures,
                fallbackAvailable = fallback != null,
                config = FailurePolicyConfig.LEGACY_NO_RETRY,
            ),
            fallback,
        )

    /**
     * 策略结论 → 物理出路（第八十七批；纯函数）。
     *
     * 为什么还要这一层：物理动作（切通道 / 停下问人 / 留原通道）是引擎既有三件套，
     * 策略只回答「该不该切、该不该问」。两者分开，策略表才能被单测单独喂假数据。
     */
    fun channelDecision(action: FailureAction, fallback: Provider?): ChannelDecision = when {
        // 策略说换，但装配层没给出候选（或换够次数了）⇒ 只能问人，绝不「切到 null」。
        action is FailureAction.Fallback && fallback != null -> ChannelDecision.SwitchTo(fallback)
        action is FailureAction.Fallback -> ChannelDecision.BrokenAskUser
        action is FailureAction.AskUser -> ChannelDecision.BrokenAskUser
        else -> ChannelDecision.KeepCurrent
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
     * 超时回灌的人话（引擎侧唯一真源；strings.xml 里那条同文资源已在第 129 批 lint 清债中删除）。
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
     *
     * @param projectRoot 工作区根（第一百四十九批 组5）：给了 ⇒ 摘要里的路径剥成
     *   **相对工作区**的显示形式；不给（null）⇒ 逐字节保持旧行为（印参数原样）。
     *   只影响**显示**：`argsJson` 落库、批准后执行的仍是绝对路径原参数。
     */
    fun confirmSummary(tool: String, args: JSONObject, projectRoot: File? = null): String {
        val path = showPath(args.optString("path").trim().ifBlank { null }, projectRoot)
        return when (tool) {
            "write_file" -> {
                val lines = args.optString("content").lines().size
                val overwrite = args.optBoolean("overwrite", false)
                val verb = if (overwrite) "覆盖" else "新建"
                val tail = if (overwrite) overwriteTail(args) else ""
                "$verb ${path ?: "（未给路径）"}（$lines 行）$tail"
            }

            // 第二百八十七批：改文件前必留旧版（>5MB 除外）——卡上要说清"能不能回"。
            "edit_file" -> "编辑 ${path ?: "（未给路径）"}${overwriteTail(args)}"
            "move_path" -> "移动 ${showPath(args.optString("from").trim().ifBlank { "?" }, projectRoot)} → " +
                showPath(args.optString("to").trim().ifBlank { "?" }, projectRoot) +
                "（搬走后原件不在原处）"
            // 第二百八十七批：删/移必确认，卡面第一句就要说清「删什么、能不能回」。
            "delete_path" -> deleteSummary(args, path)
            // 第一百五十八批：生图会烧 Gemini 额度，卡面第一句就把「几张、什么比例」说清；
            // 提示词只摘 40 字（卡面是标题，不是日志）。
            "generate_image" -> {
                val count = args.optInt("count", 1).coerceIn(1, GenerateImage.MAX_COUNT)
                val size = args.optString("size").trim().ifBlank { GenerateImage.DEFAULT_SIZE }
                val prompt = args.optString("prompt").trim()
                val brief = if (prompt.length > 40) prompt.take(40) + "…" else prompt
                val ref = args.optString("reference").trim().ifBlank { null }
                val refText = if (ref == null) "" else "（参考图：${showPath(ref, projectRoot)}）"
                "用 Gemini 画 $count 张 $size 的图（会烧额度）：「$brief」$refText"
            }
            else -> "$tool（${args.toString().take(60)}）"
        }
    }

    /**
     * 覆盖/编辑已有文件时的"能不能回"（287）：默认「旧版先备份」，超过 5MB 就说「不备份」。
     *
     * 为什么这里允许一次 `length()`：卡面必须说真话，而"这文件多大"只有盘上知道；
     * 只 stat 不读内容，代价可以忽略（与"卡面不许读文件"那条纪律不冲突——那条禁的是读正文）。
     */
    private fun overwriteTail(args: JSONObject): String {
        val raw = args.optString("path").trim()
        if (raw.isEmpty()) return ""
        val f = File(raw)
        if (!f.isFile) return ""
        return if (f.length() > FilePolicy.BACKUP_MAX_BYTES) {
            "（旧文件 ${f.length() / 1024 / 1024} MB，超过 5MB 上限：**不备份**，覆盖后不可恢复）"
        } else {
            "（旧版先备份到 备份/）"
        }
    }

    /** 删除卡摘要（287）：删什么、进回收站还是彻底删、能不能回——一句话说全。 */
    private fun deleteSummary(args: JSONObject, path: String?): String {
        if (args.optBoolean("empty_trash", false)) {
            return "清空回收站（里面的文件彻底删除，不可恢复）"
        }
        val shown = path ?: "（未给路径）"
        return if (args.optBoolean("permanent", false)) {
            "彻底删除 $shown（不进回收站，删了就没了）"
        } else {
            "删除 $shown（先进回收站，可以捞回来）"
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
     *
     * 第一百四十九批 组5 的回程：`9cda8e31` 删过它（当时生产零调用、只有测试在读），
     * 这一批把它接上确认卡。为什么现在接得住：管线第②段 `guardPath` 会把工作区内路径
     * **改写成绝对路径**（[GuardPipeline.prepare] 的 `Guarded.Yes(call.copy(arguments = newArgs))`），
     * 第③段才出确认卡 ⇒ 卡面拿到的永远是 `C:\…\资料库\x.txt` 这种长串，
     * 而工作区根就在引擎手里（`spillRoot`）。
     */
    fun relativizePath(absolutePath: String, root: File): String {
        val prefix = root.absolutePath.trimEnd('/', '\\') + File.separator
        return if (absolutePath.startsWith(prefix)) absolutePath.removePrefix(prefix) else absolutePath
    }

    /** 摘要里的路径显示口径：没给根 ⇒ 原样（旧行为，一处判据）。 */
    private fun showPath(raw: String?, projectRoot: File?): String? =
        if (raw == null || projectRoot == null) raw else relativizePath(raw, projectRoot)

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
    // ── 步数分段 ＋ 打转/无进展熔断（第一百八十九批 · 2026-10-24） ──────────────
    //
    // 派工单「先装刹车，再抬上限」：总量抬到 300 的同一次变更里，主循环必须同时拿到
    // 子 Agent 已真机验证的两道熔断（§1.1 重复调用、§1.3 无进展）与分段记账（§1.2）。
    // 判据全在本文件（JVM 直测）；引擎只接线（RunEngine）。

    /** 同工具同参重复调用熔断阈值（§1.1；真源在 [BaopuKernel.Budget.REPEAT_CALL_LIMIT]）。 */
    const val REPEAT_CALL_LIMIT = BaopuKernel.Budget.REPEAT_CALL_LIMIT

    /**
     * 一批最多执行几个调用（第二百一十四批 · 183-a；设计《Harness编排形态》§3.2）。
     *
     * 与 [MAX_STEPS]/[REPEAT_CALL_LIMIT] 同址，单测直接引用。**超批不整批拒**：
     * 前 8 个照常排段执行，第 9 个起一个都不执行、只回灌「未执行：本批超过 8 个上限」，
     * 且**不计入打转计数**（没执行过的调用不该为模型的超发背一次重复）。
     *
     * 为什么是 8：手机上一批 8 个读已经能在一轮里把「看目录＋读三份笔记＋查两处引用」
     * 这类并行动作收完；再多，单批时限与 1.5k 回灌预算都装不下。
     */
    const val MAX_BATCH_CALLS = 8

    /** 连续无新工具结果步数熔断阈值（§1.3；真源在 [BaopuKernel.Budget.NO_PROGRESS_STEPS]）。 */
    const val NO_PROGRESS_STEPS = BaopuKernel.Budget.NO_PROGRESS_STEPS

    /** 分段数（§1.2；真源在 [BaopuKernel.Budget.STEP_SEGMENTS]）。 */
    const val STEP_SEGMENTS = BaopuKernel.Budget.STEP_SEGMENTS

    /** 每段步数（§1.2；真源在 [BaopuKernel.Budget.STEPS_PER_SEGMENT]）。 */
    const val STEPS_PER_SEGMENT = BaopuKernel.Budget.STEPS_PER_SEGMENT

    /** 重复熔断文案里参数摘要的上限（§1.1「参数摘要 ≤200 字」）。 */
    const val REPEAT_ARGS_BRIEF_MAX = 200

    /**
     * 第几段（§1.2 纯判据）：0 步算第 1 段（run 从段起点开跑）；
     * 100 步进第 2 段；到 [MAX_STEPS] 整（300）不再有下一段——那是步数刹车的地盘。
     */
    fun segmentOf(step: Int): Int =
        if (step >= MAX_STEPS) STEP_SEGMENTS else step / STEPS_PER_SEGMENT + 1

    /**
     * 重复调用判据的 key（§1.1）：`工具名|规范化参数`。
     *
     * 规范化 = 递归键排序＋去空白（[canonicalArgs]）——「同一参数不同键序算同一次」；
     * 参数差一个字符 ⇒ JSON 解析出不同的值 ⇒ key 必不同（判据原文）。
     */
    fun repeatCallKey(tool: String, args: JSONObject): String = tool + "|" + canonicalArgs(args)

    /**
     * 参数规范化（§1.1）：递归键排序、无空白；字符串用 org.json 的转义口径。
     * 解析失败的参数在这里**不存在**——ToolCall.arguments 已是解析后的 JSONObject
     * （坏参数走 BadArgs 分支，根本到不了重复计数）。
     */
    fun canonicalArgs(args: JSONObject): String = canonicalValue(args)

    private fun canonicalValue(v: Any?): String = when (v) {
        is JSONObject -> v.keys().asSequence().sorted()
            .joinToString(",", "{", "}") { k -> JSONObject.quote(k) + ":" + canonicalValue(v.opt(k)) }
        is org.json.JSONArray ->
            (0 until v.length()).joinToString(",", "[", "]") { i -> canonicalValue(v.opt(i)) }
        is String -> JSONObject.quote(v)
        is Number ->
            // org.json 对同一数值可能给出 Integer/Double/BigDecimal（1 与 1.0 是同一个值）
            // ⇒ 统一整型口径；小数与超大数原样（双精整数化只在安全范围内做）。
            if (!v.toDouble().isInfinite() &&
                v.toDouble() == Math.floor(v.toDouble()) &&
                Math.abs(v.toDouble()) < 9.007199254740992E15
            ) {
                v.toLong().toString()
            } else {
                v.toString()
            }
        else -> v.toString()
    }

    /** 参数摘要（≤ [REPEAT_ARGS_BRIEF_MAX] 字；超了截断并标注，进熔断文案）。 */
    fun argsBrief(args: JSONObject, max: Int = REPEAT_ARGS_BRIEF_MAX): String {
        val raw = args.toString()
        return if (raw.length <= max) raw else raw.take(max) + "…（已截断）"
    }

    /**
     * 重复熔断的问人文案（§1.1：必须带「调了几次/工具名/参数摘要/换路建议」，
     * 不是干巴巴一句「重复了」）。
     */
    fun repeatCallNotice(times: Int, tool: String, argsBrief: String): String =
        "同一个工具用同样的参数已调 $times 次（$tool，参数摘要：$argsBrief）——" +
            "同样的调用不会有新结果，先停下：换条路径、换个工具，或把任务拆小后重试。"

    /**
     * 段复盘日志行（§1.2）：一行结构化日志，段号/已用步数/已调工具数/token/当前目标
     * 全在读数里。落到引擎的 run 日志（`log`），**不是** UI 步行——段边界只是记账点。
     */
    fun segmentCheckpointLine(
        segment: Int,
        stepsUsed: Int,
        toolCalls: Int,
        tokens: Long,
        goal: String,
    ): String =
        "段复盘：第 $segment/$STEP_SEGMENTS 段起点，已用 $stepsUsed/$MAX_STEPS 步，" +
            "已调工具 $toolCalls 次，累计 token $tokens，当前目标：${goal.take(80)}"

    /**
     * 段边界提示文案（§1.2「可选卡」的引擎侧形态）。
     *
     * 为什么不是带按钮的卡：段边界**不许**做阻塞等待（§1.4.3），而带「继续/停下」
     * 两个按钮的卡要等人点（那是 171 范围授权＋176 模式入口的 UI 面）；引擎侧按
     * 「不点也继续跑」的语义出**非阻塞**通知——想停的人随时有停止键（一个心跳内生效），
     * 不停的人一个字都不用回。整轮最多一次（引擎侧有旗标）。
     */
    fun segmentCardNotice(segment: Int, stepsUsed: Int): String =
        "已到第 $segment/$STEP_SEGMENTS 段起点（已用 $stepsUsed/$MAX_STEPS 步）：抱朴继续跑，" +
            "不用回应；想停随时点停止。"

    /**
     * 无进展熔断判据（§1.3）：连续 [steps] 步没有新工具结果 ⇒ 停。
     * 「没有新结果」的计数口径（空步也算、重跑同一步不算）在引擎接线处，这里只判阈值。
     */
    fun noProgressExceeded(steps: Int): Boolean = steps >= NO_PROGRESS_STEPS

    /** 无进展熔断的问人文案（§1.3：说清「没有新信息」＋三条出路）。 */
    fun noProgressNotice(steps: Int): String =
        "连续 $steps 步工具结果与之前重复，没有新信息，先停下。" +
            "出路：把目标缩小、换个工具或路径，或直接问我。"

    /**
     * 一「步」的工具结果内容哈希（§1.3）：各结果的稳定内容（[GuardPipeline.Result] 的
     * `contentHash`，围栏前算出——围栏头带 ts、spill 文件名带时刻，直接哈希回灌文本
     * 永远不同，熔断会静默失效）按序拼接再取 sha256。空列表返空串 ⇒ 接线处按
     * 「本步没有工具结果」计（不进集合，否则 12 个空步只数出 11 次重复，永远差 1）。
     */
    fun stepResultHash(parts: List<String>): String =
        if (parts.isEmpty()) "" else sha256Hex(parts.joinToString(","))

    /** sha256 的十六进制小写（§1.3 哈希比对的唯一出处）。 */
    fun sha256Hex(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

}
