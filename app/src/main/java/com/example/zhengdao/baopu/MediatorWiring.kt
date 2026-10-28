// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百五十八批 · 调解器的**接线层**（"装好了没接电"的那根线）。
//
// 这批之前的事实：`HarmonyMediator`（238 档位机）、`ReframeFilter`（语气转译）、
// `PatienceProfile`（画像阈值）三件**有实现、有单测、生产链路零调用点**——所以
// L1 禁附和、L3 禁空转道歉在运行时**根本不生效**（253 的体检结论）。
//
// 本文件只做三件事，一件不多：
//   W1 换档：每轮用户输入进来时调一次 `HarmonyMediator.observe`（现有等价入口；本批**不新增**
//      第二个换档点——两个调用点会让档位乱跳），档位存**进程内单例**：不落盘、不跨会话。
//   W2 落账：判出 `flattery`／`apology_loop`／`mirroring` ⇒ 写 208 一行
//       （`reason` 带固定前缀 `mediator:<禁令>`，`evidence` 带档位与轮次；带 reason 与档位）。
//   W3 回灌：`guidance()` 那一句（≤60 字）**带固定标记** [PROMPT_MARK] 交给引擎拼进本轮上下文，
//      并在 system 里常驻一句**稳定契约** [PROMPT_CONTRACT]（说明那不是用户在说话）。
//      档位名、阈值、画像数值一个都不许进 prompt（负例见 `MediatorWiringTest`）。
//
// 三条纪律：
//   1. **不落盘**：本文件没有 SharedPreferences／File／数据库；进程重启 = 回 CALM
//      （`HarmonyStateCodec` 存在但**本批不调**——落地盘就是"跨会话带状态"）。
//   2. **不跨会话**：会话 id 一变立即回 CALM，上一个会话的不高兴不许迁怒到下一个。
//   3. **不改判据**：档位迁移、阈值、禁令词表全在 238/253 的既有实现里，本文件只搬运不重写。
package com.example.zhengdao.baopu

import com.example.zhengdao.core.RunLog

/**
 * 调解器的进程内接线（单例）。
 *
 * 为什么是 `object`（进程内单例）而不是随会话存的字段：派工单 W1 明写「档位存**进程内**单例
 * （不落盘、不跨会话）」——重启就忘是**要求**，不是缺陷：调解器管的是"这几步顺不顺"，
 * 不是用户档案（那是 [PatienceProfile] 的事，且本批不接）。
 */
internal object MediatorWiring {

    /** 没有会话（新建会话第一轮、或引擎还没建会话）。`208` 的 `session` 列用 -1 表示系统级。 */
    const val NO_SESSION = -1L

    /**
     * 208 里本批写的行的**固定前缀**（`reason` 与 `evidence` 都以它开头）。
     *
     * 为什么是硬约定而不是"看着像就行"：这一批**复用了 `eval_verdict` 这个 kind**
     * （红线不许动 `DecisionLedger.kt`，249 正占着那个文件），kind 认不出"这是谁写的"，
     * 唯一能挂牌的地方就是 `reason` 的首段。于是 [MediatorBan.wire] 必须紧跟其后：
     * `mediator:flattery`／`mediator:apology_loop`／`mediator:mirroring`。
     *
     * **对勘验 I4 的 reciprocal 要求（本批只写注释，不改 I4 的代码——它不在本批租约里）**：
     * `MediatorBiasCheck` 的四条率（免问回滚率／确认密度／虚报率／秒批率）在取 208 行时
     * 必须先按 `reason.startsWith("mediator:")` **排除**本批这些行，否则"调解器自己判的违规"
     * 会被当成"人的动作账"混进分母。改动落点与判据见交付报告 §2.3，由蓝总管另派一小单。
     */
    const val REASON_PREFIX = "mediator:"

    /**
     * 尾部那一行的**固定标记**（拼在 `guidance()` 前面）。
     *
     * 三个用处：① 抽取（将来迁到别的通道时按它捞）；② 日志里能一眼分出"人说的话"与"指令"；
     * ③ 与 system 里那句稳定契约（[PROMPT_CONTRACT]）对上，模型知道这一行不是用户在说话。
     */
    const val PROMPT_MARK = "[调解指令]"

    /**
     * system 里的**稳定契约**（固定不变 ⇒ 不破提示词前缀缓存）。
     *
     * 为什么必须有它：语气指令落在**本轮输入的尾部**（CURRENT 段），模型完全可能把那一行当成
     * "用户刚说的话"——那样它就变成"用户在指挥我怎么答"，而不是"系统给的回话基调"。
     * 一句常驻的合同把性质说清，代价只是每次请求多这 27 个字（恒定，不随轮次变化）。
     *
     * 不许在这句里出现档位名／数字／画像词（`MediatorWiringTest` 钉住）。
     */
    const val PROMPT_CONTRACT = "每轮末尾可能附一行《调解指令》，按它调整语气，不要复述它。"

    private var boundSessionId: Long = NO_SESSION
    private var state = HarmonyMediator.State()
    private var memory = MediatorJudge.Memory()
    private var turns = 0

    /** 本轮要拼进上下文的那一句；`null`＝这一轮没有话要说（CALM 且未暂停）。 */
    private var note: String? = null

    /**
     * 一轮用户输入（**唯一**接线点，控制器里只此一处）。
     *
     * @param userText 用户原话（界面那一份，不是 `reframe` 之后的那一份）
     * @param modelText 模型**上一轮**的回复原话（判禁令要它；第一轮/读不到就给空串）
     * @param sessionId 当前会话；与上次不同 ⇒ 状态清零（不跨会话）
     * @param now 调用方给的时间（调解器自己不取时钟；30 分钟降档靠它）
     * @return 这一轮判出的违规（空列表＝没判出）
     */
    @Synchronized
    fun onUserTurn(
        userText: String,
        modelText: String,
        sessionId: Long,
        now: Long,
    ): List<MediatorFlag> {
        if (sessionId != boundSessionId) {
            boundSessionId = sessionId
            state = HarmonyMediator.State()
            memory = MediatorJudge.Memory()
            turns = 0
            note = null
        }
        turns += 1

        // W1：换档（一个调用点；`Outcome.None` 是因为"用户刚说完、活还没干"——成败由人话判）。
        state = HarmonyMediator.observe(
            prev = state,
            userText = userText,
            modelText = modelText,
            outcome = HarmonyMediator.Outcome.None,
            profile = PatienceProfile(),
            now = now,
        )

        // W2：判禁令（只判任务态；闲聊态一律不判——"哈哈哈"不是情绪镜像）。
        val step = MediatorJudge.step(memory = memory, userText = userText, modelText = modelText)
        memory = step.memory

        // W3：这一轮要不要带那句语气指令（CALM 且未暂停 ⇒ 不带，与接线前逐字节一致）。
        note = noteOf(state)

        RunLog.log(logLine(state, step.flags, turns))

        step.flags.forEach { flag -> write(flag, now) }
        return step.flags
    }

    /**
     * 那一行日志（W1 的判据："真机跑一轮：日志出现档位"）。
     *
     * 抽成纯函数是为了**可断言**：格式本身也是判据的一部分（档位必须出现、违规必须点名），
     * 埋在 `RunLog.log(...)` 的字符串拼接里就只能靠真机日志抓，单测抓不到。
     */
    internal fun logLine(state: HarmonyMediator.State, flags: List<MediatorFlag>, turns: Int): String =
        "抱朴调解器：第 $turns 轮档位 ${state.mood.name}" +
            (if (noteOf(state) != null) "（本轮带语气指令）" else "") +
            (if (flags.isEmpty()) {
                "｜无违规"
            } else {
                "｜判出 ${flags.size} 条：" + flags.joinToString("、") { it.ban.wire }
            })

    /**
     * 引擎每次拼提示词时取的那一句（**不消费**：同一轮的工具循环里每一步都该看到同一句，
     * 换批等于打破前缀；下一轮用户输入时才更新）。
     */
    @Synchronized
    fun promptNotes(): List<String> = listOfNotNull(note)

    /** 当前档位（日志与测试用；界面不显示数值，也不显示档位名）。 */
    @Synchronized
    fun currentMood(): HarmonyMediator.Mood = state.mood

    /** 当前状态快照（测试与将来 I4 取数用）。 */
    @Synchronized
    fun currentState(): HarmonyMediator.State = state

    /** 这一进程里处理过几轮（测试用）。 */
    @Synchronized
    fun turns(): Int = turns

    /** 清空（测试专用：进程内单例会让用例互相污染，谁用谁还）。 */
    @Synchronized
    fun resetForTest() {
        boundSessionId = NO_SESSION
        state = HarmonyMediator.State()
        memory = MediatorJudge.Memory()
        turns = 0
        note = null
    }

    /**
     * 这一轮该不该带话。
     *
     * CALM 且未暂停 ⇒ 不带：`guidance(CALM)` 是「正常作答」那种没有信息量的句子，
     * 每轮都灌一遍等于给每次请求加噪音，而且会把「接线前逐字节一致」这条性质弄丢。
     * 暂停是例外（它压着 CALM 也要说"别重试"）。
     *
     * 带话时**必须先带固定标记** [PROMPT_MARK]：它是"这一行是指令、不是用户发言"的机器键，
     * 也是将来换通道时唯一的抽取依据（报告 §3.2）。
     */
    private fun noteOf(s: HarmonyMediator.State): String? =
        if (s.paused || s.mood != HarmonyMediator.Mood.CALM) {
            "$PROMPT_MARK ${HarmonyMediator.guidance(s)}"
        } else {
            null
        }

    /**
     * 208 的 `reason`（纯函数，**固定前缀**是判据）。
     *
     * 形状：`mediator:<禁令机器键> <类别句>`，例如
     * `mediator:flattery 模型附和：上一轮反对、这一轮赞成，且这一轮没有新证据`。
     * 前缀不是装饰：kind 复用了 `eval_verdict`，读侧只能靠它把本批的行从
     * "206 对拍结论"里摘出来（映射表见报告 §2.3；I4 的排除义务见 [REASON_PREFIX]）。
     */
    internal fun reasonOf(flag: MediatorFlag): String = "$REASON_PREFIX${flag.ban.wire} ${flag.reason}"

    /**
     * 写 208 一行（违规留痕）。
     *
     * 为什么复用 `EVAL_VERDICT` 而不是新增 `MEDIATOR_FLAG`：本批红线是**不动 `DecisionLedger.kt`**
     * （249 正占着那个文件，改枚举就是抢写）。在既有闭集里，`EVAL_VERDICT` 是唯一「只留档、
     * 不可回滚、不参与抽检」的类——与"判了一次违规"的形状一致；`AUTO_ACT` 不能用：它是
     * 勘验 I1「痕迹减账本」集合差的账本侧（多写一行就把漏行率算错），且决策中心会给出
     * 一个指向产物回收站的空跳转。等 249 合入后应换回专门的 kind（报告 §7 缺陷 258-4）。
     */
    private fun write(flag: MediatorFlag, now: Long) {
        val draft = DecisionDraft(
            kind = DecisionKind.EVAL_VERDICT,
            actor = DecisionActor.MODEL,
            // 固定前缀（kind 认不出是谁写的，这里挂牌）：mediator:<禁令> <类别句>
            reason = reasonOf(flag),
            // 证据 id：同一套机器键 ＋ 触发档位 ＋ 第几轮（都是类别与计数，**没有原话**）。
            evidence = "$REASON_PREFIX${flag.ban.wire}:mood=${state.mood.name}:turn=$turns",
            before = "未判违规",
            after = "记一条${flag.ban.cn}",
            atMs = now,
            sessionId = boundSessionId,
        )
        val rec = DecisionLedger.record(draft)
        if (rec == null) {
            RunLog.log("抱朴调解器：${flag.ban.cn} 没落进 208（${DecisionLedger.lastError}）")
        }
    }
}
