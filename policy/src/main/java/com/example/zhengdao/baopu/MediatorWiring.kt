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
//
// 第二百九十三批（2026-10-31）：本文件随宰相一起搬进 `:policy`（白名单匣），两处跟着动、
// 一处**没**动：
//   · 动了 1——`internal object`／`internal fun` 放宽为公开（Kotlin 的 internal 以编译单元为界，
//     :app 的调用方与单测看不见 :policy 的 internal）。
//   · 动了 2——W2 落账不再直接叫 `DecisionLedger`（那是 :app 的实现），改叫 `:policy` 的
//     [LedgerPort]（293 ③）。写进去的**还是同一本 208**：`write` 逐字转发 `record`，
//     `reason`／`evidence`／`kind`／`sessionId`／那句失败日志的形状全部逐字节不变。
//   · 没动——判据、档位机、词表、`PROMPT_MARK`／`PROMPT_CONTRACT` 那两句一个字。
//   纪律 1 照旧成立：端口拿不到 `File`，落盘那套留在 :app，本文件仍然零 IO。
//
// 第五百五十批（2026-11-11）：加一条 **W5 相处模式**——左相在这里多判一件事：「用什么口吻」。
//   · 判据本体在 [CompanionJudge]（本文件只搬运，与 W1/W2/W3 同一条纪律 3）；
//   · 出口只走 system **尾部追加**（[companionSuffix]，恒常追加、固定字符串）；
//   · 落账走**路线②**：只落 `RunLog` 一行人话（[companionLine]），不进任何账本
//     ——理由写在 [companionLine] 的调用位（495/517 的行里没有可放的列）。
//   · W1–W4 一个字没动，`logLine` 的字面也没动（258 把它钉死了，并进去就是改别人的判据）。
//
// 第五百七十一批（2026-11-12）：加一个**只读观察口** [turnObserver]——判档逻辑（W5 的
// 三级顺序、一轮一次、纠正识别）一个字没动，只是判完、落完账之后把这一轮的**既成事实**
// 交给装配层注册的观察者（app 侧用它喂右相达成判定与改判账，法典 L34 的制度落点）。
//   · `null`（没人注册）⇒ 行为与接线前逐字节一致；
//   · 观察者拿到的是**快照**（[CompanionTurnFact]），不是活引用——它不许改判档，
//     只许记账、判定、落行；
//   · [resetForTest] 同步摘观察口（谁注册谁摘，进程内单例不许串测）。
package com.example.zhengdao.baopu

import com.example.zhengdao.core.RunLog

/**
 * 调解器的进程内接线（单例）。
 *
 * 为什么是 `object`（进程内单例）而不是随会话存的字段：派工单 W1 明写「档位存**进程内**单例
 * （不落盘、不跨会话）」——重启就忘是**要求**，不是缺陷：调解器管的是"这几步顺不顺"，
 * 不是用户档案（那是 [PatienceProfile] 的事，且本批不接）。
 */
object MediatorWiring {

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

    /**
     * 第五百一十八批 · 转译那一段的**固定标记**（与 [PROMPT_MARK] 并列，排在它之后）。
     *
     * 为什么要单开一个标记而不是并进 [PROMPT_MARK]：两者性质不同——[PROMPT_MARK] 是
     * 「语气怎么调」，本标记是「原话被改写过、按改写版理解」。日志分流、将来换通道的抽取
     * 都需要分开捞，混成一个键就分不出来了。
     */
    const val REFRAME_MARK = "[转译]"

    /**
     * 转译段的字数上限（含事实抽取后的结果）。
     *
     * 用户原话已经在本轮消息里一遍了，这一段是**第二份**；不设上限就等于给每次请求
     * 挂一条随输入线性增长的尾巴。超限按字符截断并加省略号——判据只要「保住事实」，
     * 不要求把整段原话复述一遍。
     */
    const val MAX_REFRAME_NOTE_CHARS = 120

    private var boundSessionId: Long = NO_SESSION
    private var state = HarmonyMediator.State()
    private var memory = MediatorJudge.Memory()
    private var turns = 0

    /** 本轮要拼进上下文的那一句；`null`＝这一轮没有话要说（CALM 且未暂停）。 */
    private var note: String? = null

    /**
     * 本轮要拼进上下文的**转译段**（第五百一十八批）；`null`＝原话不用改写（逐字节不变）。
     *
     * 排位：恒在 [note] 之后（尾部追加）。两段都是「这一轮新增的行」——前缀（人设/system）
     * 一个字节都不动，提示词缓存的键因此不变。
     */
    private var reframeNote: String? = null

    /**
     * 本回合已弹出的问卡数（406 弹卡闸的计数；「回合」＝两次用户输入之间，
     * [turns] 变了就归零——耐心阈值的口径是一回合，不是一会话）。
     */
    private var askedThisTurn = 0

    /**
     * 本轮的相处模式判档（第五百五十批）；`null`＝这一进程还没经过 [onUserTurn]
     * （引擎直跑工具、没有用户输入的那种轮次，与 [consultAsk] 的 `NO_SESSION` 同一口径）。
     *
     * 读取口 [companionSuffix] 对 `null` 落**默认工作档**而不是省略这一段：派工单 §一③
     * 「判不出就用它」，加上模式段是**恒常追加**的（用户 2026-11-11 裁定：省略＝没信号，
     * 模型分不清"这次没判"与"判成工作"，上一轮学习档的行为会残留到这一轮）。
     */
    private var companion: CompanionDecision? = null

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
            reframeNote = null
            askedThisTurn = 0
            companion = null
        }
        turns += 1
        askedThisTurn = 0

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

        // W4（第五百一十八批）：原话的双向改写真接进生产。这一批之前 `ReframeFilter.reframe`
        // 只在 `HarmonyMediator` 里有个转手，而它在 `app/src/main` 零真实引用 ⇒ 防线运行时不存在。
        // 产出只走尾部（[promptNotes]），不替换消息本体：替换历史字节＝破前缀缓存。
        reframeNote = reframeNoteOf(userText = userText, modelText = modelText, repeats = state.taskFailures)

        // W5（第五百五十批）：相处模式判档。**一轮只判一次**是结构性质，不是计数纪律——
        // [onUserTurn] 是控制器里唯一的接线点（258 负例6 已把它钉成一处），
        // [CompanionJudge.judge] 全仓也只在这一行出现；引擎每步拼提示词走的是 [companionSuffix]
        // 那个**非消费读取口**，读多少次都不重判（同轮每一步必须看到同一份，换批＝打破前缀）。
        // 上一轮的档位在上面"换会话即清零"之后才取，所以纠正只认**同一会话内**的前后两轮。
        val prevCompanion = companion
        val decided = CompanionJudge.judge(userText)
        companion = decided
        // 派工单 §四⑥：用户点名纠正（学习档下说一句「别这么啰嗦」）⇒ 本轮立即切回工作档
        // ——切换本身由 [CompanionJudge] 的三级顺序保证，这里只负责把"这是一次纠正"认出来，
        // 它是 §六 的进化样本（自动信号换档不算纠正，理由见 [CompanionDecision.explicit]）。
        val correction = CompanionJudge.correctionOf(prevCompanion, decided)

        RunLog.log(logLine(state, step.flags, turns))
        // 判据④的落账走**路线②**：只落 RunLog 一行人话（带证据）。为什么不进账本：
        // 495/517 既有的行里没有可放的列——`进化深度.jsonl` 的 `depth` 是闭集枚举（浅/中/深），
        // 把 work/study 写进去会被 `EvolutionDepth.current()` 当成当前**深度**档读走（污染 517）；
        // 495 那几个 reason 码是 verify 路径里的常量，不是可追加的行。扩容单见交付报告。
        RunLog.log(companionLine(decided, correction, turns, boundSessionId))

        // 第五百七十一批：判档与落账都已结成**既成事实**，交给观察口（装配层注册；
        // null＝没人看，行为与接线前逐字节一致）。放在最后一步：观察者看见的永远是
        // "这一轮已经判完"的快照，不存在"判到一半被旁观"的中间态。
        turnObserver?.invoke(
            CompanionTurnFact(
                turn = turns,
                sessionId = boundSessionId,
                prevMode = prevCompanion?.mode,
                decided = decided,
                correction = correction,
                userText = userText,
            ),
        )

        step.flags.forEach { flag -> write(flag, now) }
        return step.flags
    }

    /**
     * 相处模式那一行日志（判据④落账路线②：**一行**人话，带档位＋依据＋是不是纠正）。
     *
     * 抽成纯函数与 [logLine] 同一条理由：格式本身是判据（三样都必须在场），
     * 埋在 `RunLog.log(...)` 的拼接里就只能靠真机日志抓，单测抓不到。
     * 与 [logLine] **分两行**而不是并成一行：258 那一条判据把调解器行的字面钉死了
     * （`MediatorWiringTest.W1_日志行带档位…`），并进去就是改别人的判据。
     */
    fun companionLine(
        d: CompanionDecision,
        correction: CompanionDecision?,
        turns: Int,
        sessionId: Long,
    ): String = "抱朴相处模式：第 $turns 轮判成 ${d.mode.cn}（${d.mode.wire}）｜依据：${d.reason}" +
        "｜${if (d.explicit) "用户点名" else "自动判定"}｜session=$sessionId" +
        (if (correction != null) "｜用户纠正上一轮 ⇒ 进化样本" else "")

    /**
     * 那一行日志（W1 的判据："真机跑一轮：日志出现档位"）。
     *
     * 抽成纯函数是为了**可断言**：格式本身也是判据的一部分（档位必须出现、违规必须点名），
     * 埋在 `RunLog.log(...)` 的字符串拼接里就只能靠真机日志抓，单测抓不到。
     */
    fun logLine(state: HarmonyMediator.State, flags: List<MediatorFlag>, turns: Int): String =
        "抱朴调解器：第 $turns 轮档位 ${state.mood.name}" +
            (if (noteOf(state) != null) "（本轮带语气指令）" else "") +
            (if (flags.isEmpty()) {
                "｜无违规"
            } else {
                "｜判出 ${flags.size} 条：" + flags.joinToString("、") { it.ban.wire }
            })

    /** 引擎每次拼提示词时取的那几句（**不消费**：同一轮的工具循环里每一步都该看到同一份，
     * 换批等于打破前缀；下一轮用户输入时才更新）。顺序恒定：语气指令在前，转译段在后。 */
    @Synchronized
    fun promptNotes(): List<String> = listOfNotNull(note, reframeNote)

    /** 当前档位（日志与测试用；界面不显示数值，也不显示档位名）。 */
    @Synchronized
    fun currentMood(): HarmonyMediator.Mood = state.mood

    /** 当前状态快照（测试与将来 I4 取数用）。 */
    @Synchronized
    fun currentState(): HarmonyMediator.State = state

    /** 这一进程里处理过几轮（测试用）。 */
    @Synchronized
    fun turns(): Int = turns

    // ── 第五百五十批：相处模式（左相判"用什么口吻"；W1 那句"要不要打扰"一个字没改）──

    /** 当前相处模式（没判过 ⇒ 默认工作档）。日志与测试用；界面不显示，也不新增任何入口。 */
    @Synchronized
    fun currentCompanion(): CompanionMode = companion?.mode ?: CompanionMode.DEFAULT

    /**
     * system 尾部那一段（引擎每次拼提示词时取；**不消费**、同轮每次逐字节相同）。
     *
     * 恒常追加 ⇒ **永不返回空串**：返回空串就是"没信号"，模型分不清"这次没判"与"判成工作"。
     * 内容全是固定字符串（两档一共两种字节），所以前缀缓存照常命中，只有"切换那一次"失效——
     * 这是用户 2026-11-11 裁定里"代价最小"的那一条。
     *
     * 排位恒在最后（引擎里接在 258 契约与 436-A 运行时包之后）⇒ 只追加，
     * 前面每一字节都不动（判据④的前缀判据）。
     */
    @Synchronized
    fun companionSuffix(): String = "\n\n" + CompanionJudge.segment(currentCompanion())

    // ── 第五百七十一批：一轮的只读观察口（法典 L34 的制度落点在 app 侧，这里只递事实）──

    /**
     * 一轮判档的**既成事实**快照（[onUserTurn] 判完、落完账之后交给 [turnObserver]）。
     *
     * @param turn 第几轮（1 起）
     * @param sessionId 会话 id（纠正只认同一会话内的前后两轮，与判档同一把尺）
     * @param prevMode 上一轮判的档；`null`＝第一轮（没有上一轮，也就没有可纠的判）
     * @param decided 本轮判档结论（左相的既成结论，观察者不许改）
     * @param correction 非空＝用户点名改判（[CompanionJudge.correctionOf] 认出的那一次，
     *   误判纠正的账源——改判行的原判/新判/依据三列全从这里来）
     * @param userText 本轮用户原话（学习档收口时它是达成判定的证据，右相只读这一份）
     */
    data class CompanionTurnFact(
        val turn: Int,
        val sessionId: Long,
        val prevMode: CompanionMode?,
        val decided: CompanionDecision,
        val correction: CompanionDecision?,
        val userText: String,
    )

    /**
     * 观察口（装配层注册；`null`＝没人看 ⇒ [onUserTurn] 行为与接线前逐字节一致）。
     *
     * 生产由 `BaopuRuntime` 注册到 app 侧的右相达成判定与改判账（法典 L34）；进程内单例
     * ⇒ 测试用完必须经 [resetForTest] 摘掉，不许把上一单的观察者漏进下一单。
     */
    @Volatile
    var turnObserver: ((CompanionTurnFact) -> Unit)? = null

    // ── 第四百零六批：弹卡闸的咨询口（shouldAsk/budgetFor/decide 的生产读数）────

    /**
     * 左相弹卡前问调解器（[MediatorDecide.shouldAsk] 的进程内接线版）。
     *
     * · 计数归这里：放行且非必问 ⇒ 本回合问卡数 +1（压掉的不占耐心——它没问出去）；
     * · 画像照 W1 同一份缺省（`PatienceProfile()`，画像接线归后续批）；
     * · 铁律的让步格在判据里：[mustAsk]（法条必问／用户点名）恒放行，也不占预算；
     * · **调解器只对它看过的会话发言**：一次用户输入都没进过（引擎直跑工具，没经过
     *   [onUserTurn]）⇒ 不参与也不计数——行为与接线前逐字一致（同 400 的
     *   `effective=false` 口径），进程内单例也不会漏账污染下一个会话。
     *
     * @param mustAsk 必问档（确认卡根本不走这里；走这里的必问只有「用户点名叫你问」一种）
     */
    @Synchronized
    fun consultAsk(mustAsk: Boolean = false): MediatorDecide.AskAdvice {
        if (boundSessionId == NO_SESSION) {
            return MediatorDecide.shouldAsk(state, PatienceProfile(), 0, mustAsk)
        }
        val advice = MediatorDecide.shouldAsk(state, PatienceProfile(), askedThisTurn, mustAsk)
        if (advice.allowed && !mustAsk) askedThisTurn += 1
        return advice
    }

    /**
     * 一组问卡的排序建议（[MediatorDecide.decide] 的进程内接线版）。
     *
     * [weights] 由调用方按问题形状给（0=点一下的，1=数字，2=要打字的）；
     * 这里只搬运状态与判据，不碰问题内容。采纳与否归调用方（只给排序建议）。
     */
    @Synchronized
    fun orderAdvice(weights: List<Int>): MediatorDecide.OrderAdvice =
        MediatorDecide.decide(weights, state)

    /** 清空（测试专用：进程内单例会让用例互相污染，谁用谁还）。 */
    @Synchronized
    fun resetForTest() {
        boundSessionId = NO_SESSION
        state = HarmonyMediator.State()
        memory = MediatorJudge.Memory()
        turns = 0
        note = null
        reframeNote = null
        askedThisTurn = 0
        companion = null
        turnObserver = null
    }

    /**
     * 第五百一十八批 · 转译段（**纯函数**；两边都不需要改写时返回 `null`，与接线前逐字节一致）。
     *
     * 判据全在 238，本文件只搬运：
     *   · 要不要改写 → [ReframeFilter.needsReframe]（同一份词表，不另立第二套）；
     *   · 怎么改写 → [HarmonyMediator.reframe]（不许自己写一套"洗白规则"）。
     *
     * 两段并成**一行**输出（用户侧在前、模型侧在后，用「｜」分隔）：一行 = 一条 `extraNotes`，
     * 行数恒定，模型也不会把它当成两条互相冲突的指令。事实抽取的结果可能很长，按
     * [MAX_REFRAME_NOTE_CHARS] 截断——这一段的判据是「保住事实」，不是「复述原话」。
     */
    private fun reframeNoteOf(userText: String, modelText: String, repeats: Int): String? {
        val user = if (ReframeFilter.needsReframe(userText, Side.USER)) {
            "用户原话按「保事实、去人格评价」改写：" +
                clip(HarmonyMediator.reframe(userText, Side.USER, repeats.coerceAtLeast(1)))
        } else {
            null
        }
        val model = if (ReframeFilter.needsReframe(modelText, Side.MODEL)) {
            "上一轮回复里的自贬按「认这一步没做成」改写：" +
                clip(HarmonyMediator.reframe(modelText, Side.MODEL))
        } else {
            null
        }
        val body = listOfNotNull(user, model).joinToString("｜")
        if (body.isEmpty()) return null
        return "$REFRAME_MARK $body（按改写版理解，不要复述本行）"
    }

    /** 超过 [MAX_REFRAME_NOTE_CHARS] 就截断加省略号（判据只要事实在，不要求全文）。 */
    private fun clip(text: String): String =
        if (text.length <= MAX_REFRAME_NOTE_CHARS) text else text.take(MAX_REFRAME_NOTE_CHARS) + "…"

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
    fun reasonOf(flag: MediatorFlag): String = "$REASON_PREFIX${flag.ban.wire} ${flag.reason}"

    /**
     * 写 208 一行（违规留痕）。
     *
     * 为什么复用 `EVAL_VERDICT` 而不是新增 `MEDIATOR_FLAG`：本批红线是**不动 `DecisionLedger.kt`**
     * （249 正占着那个文件，改枚举就是抢写）。在既有闭集里，`EVAL_VERDICT` 是唯一「只留档、
     * 不可回滚、不参与抽检」的类——与"判了一次违规"的形状一致；`AUTO_ACT` 不能用：它是
     * 勘验 I1「痕迹减账本」集合差的账本侧（多写一行就把漏行率算错），且决策中心会给出
     * 一个指向产物回收站的空跳转。等 249 合入后应换回专门的 kind（报告 §7 缺陷 258-4）。
     *
     * 第二百九十三批：本文件搬进 `:policy` 后够不着 :app 的 `DecisionLedger`，于是只认 [LedgerPort]。
     * **上面这套复用 `EVAL_VERDICT` 的判断一个字没改**，换的只是"用什么名字找到那本账"；
     * 写进去的仍是同一个对象、同一次落盘、同一行 JSONL。
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
        val port = LedgerPorts.ledger
        if (port == null || !port.write(draft)) {
            RunLog.log("抱朴调解器：${flag.ban.cn} 没落进 208（${port?.lastError ?: "账本端口未接线"}）")
        }
    }
}
