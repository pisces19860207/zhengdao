// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百三十批 · 「免打扰授权」——用户说一句"不弹卡"之后，App 自己把活干完的边界。
//
// 用户 2026-10-31（派工单原文四条）：
//   ① 用户说"不弹卡"⇒ **本任务零卡**；
//   ② 不可逆动作**不问，但必须可回退**（回收站／.bak／可退回清单三选一，一样都没有就不做）；
//   ③ 结束交三样：产物清单／验收结论／未做项；
//   ④ **≤4 小时自动退出**（到期不许续，要续得由人再说一次）。
// 免打扰页另外的口径（同批派工单，一句都不许多、也一句都不许少）：
//   触发词＝**不弹卡／别弹了／直接干／只要结果**，进来要在账上记一笔授权；
//   不弹的是**建议卡／询问卡／过程确认卡**；**外发一律不做**；全程自监控；**只有真失败打断一次**。
// 补充页（开机定策）：开机读四类历史（工作区类型／同类卡 30 天驳回·快关·忽略率／近 7 天误判／时段与节奏）
//   ⇒ 算本次会话的"弹卡三档"（必弹／可弹／不弹），输出**一行** `必弹 N 类 · 可弹 M 类 · 不弹 K 类`
//   ＋三颗按钮（全不弹／全部弹／按默认），跨会话不继承；**只调"何时问"，不改"什么必须问"**。
//
// 七条纪律（为什么长成下面这个样子）：
// 1. **本文件一个写口都没有**：授权、判卡、算到期、定策全是纯函数，落账归装配层（330 租约只含三个新文件，
//    `SelfRulingLedger` 与 `UnfinishedLedger` 归 277／290 的账，本批不抢它们的路径）；
// 2. **授权是有形状的，不是布尔**：一个 [QuietGrant] 带着任务号、触发词与到期时刻，
//    所以"免打扰"永远回答得出"哪件事、凭什么、到几点、还剩多久"——只有 true/false 的授权管不住自己不过期；
// 3. **到期不是"再提醒一次"**：[QuietState.EXPIRED] 之后 [cardAllowed] 直接回真（回到普通档），
//    本文件不许任何"自动续期"的口——负例点名"免打扰把用户永久关在门外"就犯在这儿；
// 4. **"必须问"的三档不归免打扰管**：[CardClass.lawMustAsk]（不可逆／越权／真失败）说的是
//    "我干不了活"或"这一步要你点手"这件事本身，它不是一种打扰。所以那三类压不掉——
//    其中"不可逆"这一格还有第二条路：不问**但**必须可回退，走 [decide]（派工单②），
//    所以免打扰期内它既不打扰你、也不许偷偷干。真失败"只打断一次"的**次数**不在本文件管：
//    去重键的权威在开检那本账上（`BootSinks.interruptShown`），这里只回答"这类事算不算打扰"。
//    "阻断性仍要说"这一条是本批的判读，不是派工单的字面——已按 Q1 交给用户拍板。
// 5. **外发不问也不做**：[decide] 的 `outbound` 是**必填**参数（没有默认值），
//    少传一个参数是编译错误，而默认成 false 是一封悄悄发出去的邮件——两害相权取前者。
// 6. **定策只排"哪一档"，不重算额度**：哪一次具体问不问仍由 `inspect/AllowanceLadder.kt`
//    判（那里有 219 风险档、C 类、影子一致这三条硬边界）。本文件多出来的只有两件事：
//    一类卡 30 天的下场（来自 `卡处置.jsonl`，由装配层数好喂进来）与本次会话的形状（时段／忙）。
//    再抄一份额度表＝两条链迟早打架，这条不做。
// 7. **"全程自监控"不在本文件重写**：跑偏／偷偷变简单那一路是 242 的 [com.example.zhengdao.baopu.DriftCheck]
//    ＋ `DriftStore` 已经在做的事（免打扰期照跑），本批只把它需要的"这一句算不算打扰"回给它：真失败才打断。
package com.example.zhengdao.baopu.agent

/** 免打扰授权的时长上限（派工单④：≤4 小时）。改这个数＝改授权制度，不许在调用处各写一把。 */
internal object QuietLimits {

    /** 一次授权最多多少分钟（240 ＝ 4 小时）。 */
    const val MAX_MINUTES = 240L

    /** 一分钟多少毫秒（把分钟换成时刻只在这一处乘）。 */
    const val MINUTE_MS = 60_000L

    /** 一次授权最多活多久（毫秒）。 */
    const val MAX_MS = MAX_MINUTES * MINUTE_MS

    /**
     * 定策降档要看够不够样本：少于这个数就当"没数"（30 天里只弹过两张，一张驳了就降＝拿噪声当意愿）。
     *
     * 阈值是本批初值，校准归第三百三十一批（自决率）——那一批才有真账可数。
     */
    const val MIN_HISTORY_SAMPLES = 10

    /** 30 天里这类卡被驳回／快关／忽略到几成就算"她不想看"（任一过线即降，不合成一个分）。 */
    const val NOISY_PCT = 60

    /**
     * 近 7 天这类被判错到几次就**不许降**。
     *
     * 线取 227 §0.6 那条（[com.example.zhengdao.baopu.AuditHint.MIN_EVENTS] ＝ 3）：
     * 驳回率高是"她不想问"，误判多是"机器还在猜错"——后者把卡关掉只会让她更烦。
     */
    const val MISJUDGE_MAX = 3

    /** 深夜窗口的起点（含）：22 点后开机默认不打断"可弹"那一档。 */
    const val NIGHT_FROM_HOUR = 22

    /** 深夜窗口的终点（不含）：0–6 点算深夜，7 点起不算。 */
    const val NIGHT_TO_HOUR = 7

    /** 定策给**一行**（派工单补充页"必须一行可见"）：换行＝渲染层自造了第二行，拦住。 */
    const val MAX_POLICY_LINE_CHARS = 60
}

/**
 * 开免打扰的那句话（派工单免打扰页点名的四个触发词，闭集）。
 *
 * 为什么把话术做成闭集而不是"调用方说算就算"：授权是**免问权**，
 * 谁来都能宣布一句"用户说过不弹卡"，那张授权就成了机器自己的免罪符。
 * 认不出这四个词 ⇒ [QuietMode.grantFromText] 回 null ⇒ 没有授权。
 */
internal enum class QuietTrigger(val wire: String, val cn: String) {
    NO_CARDS("no_cards", "不弹卡"),
    STOP_CARDS("stop_cards", "别弹了"),
    DO_IT("do_it", "直接干"),
    RESULT_ONLY("result_only", "只要结果"),
    ;

    companion object {
        /** 那句话里有哪个触发词就算哪个（按声明序取第一个；空句／没词 ⇒ null）。 */
        fun ofText(text: String): QuietTrigger? =
            if (text.isBlank()) null else entries.firstOrNull { text.contains(it.cn) }
    }
}

/**
 * 本次会话的弹卡档位（派工单补充页"三档固定"）。
 *
 * 档位说的是**这一轮会不会弹**，不是"这件事重不重要"：
 * [MUST_ASK] ＝ 一定弹（法条钉死，或人按了"全部弹"）；[MAY_ASK] ＝ 可能弹，
 * 具体那一次由 `AllowanceLadder` 当场判；[SILENT] ＝ 本次会话不弹（降为自决）。
 */
internal enum class CardTier(val wire: String, val cn: String) {
    MUST_ASK("must_ask", "必弹"),
    MAY_ASK("may_ask", "可弹"),
    SILENT("silent", "不弹"),
    ;

    /** 这一档会不会弹（给人按的那三颗按钮与免打扰都用得上）。 */
    val pops: Boolean get() = this != SILENT
}

/**
 * 卡的类别（闭集；派工单两张页的词都在这儿，一个不自造）。
 *
 * 免打扰页点名"不弹建议卡／询问卡／过程确认卡"，补充页点名"必弹＝不可逆／越权／真失败、
 * 不弹＝建议／进度／许可类"。两页合起来就是这八类——**不做第二套卡类**：
 * 两套类别早晚对不上，一张卡到底归哪套问就成了新的官司。
 *
 * @param quietSuppresses 免打扰期内这一类一枚都不弹
 * @param lawMustAsk 法条钉死"必须问"：定策与三颗按钮都不许降级它（只调何时问，不改什么必须问）
 * @param defaultTier 没有历史、没按按钮时的落点
 */
internal enum class CardClass(
    val wire: String,
    val cn: String,
    val quietSuppresses: Boolean,
    val lawMustAsk: Boolean,
    val defaultTier: CardTier,
) {
    // ── 不弹档（派工单：建议／进度／许可类；免打扰页也压） ──────────────────
    SUGGESTION("suggestion", "建议卡", true, false, CardTier.SILENT),
    PROGRESS("progress", "进度卡", true, false, CardTier.SILENT),
    PERMISSION("permission", "许可卡", true, false, CardTier.SILENT),

    // ── 可弹档（依 30 天的下场可降为自决） ─────────────────────────────────
    QUESTION("question", "询问卡", true, false, CardTier.MAY_ASK),
    PROCESS_CONFIRM("process_confirm", "过程确认卡", true, false, CardTier.MAY_ASK),

    // ── 必弹档（法条钉死，任何按钮与任何历史都不许降） ──────────────────────
    IRREVERSIBLE("irreversible", "不可逆确认", false, true, CardTier.MUST_ASK),
    OVER_REACH("over_reach", "越权请求", false, true, CardTier.MUST_ASK),
    REAL_FAILURE("real_failure", "真失败陈述", false, true, CardTier.MUST_ASK),
    ;

    /** 这一类是不是"天生不弹"（没有历史时的落点，与 [lawMustAsk] 互斥）。 */
    val silentByDefault: Boolean get() = defaultTier == CardTier.SILENT && !lawMustAsk

    companion object {
        /** 认不出就 null（照本仓闭集解析纪律：自造值不许静默当别的类）。 */
        fun ofWire(wire: String): CardClass? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * 一类卡这 30 天的下场（数由装配层从 `卡处置.jsonl` 算好交进来，本层不读盘）。
 *
 * 为什么三个率分开、不合成一个"打扰分"：310 那条判据写着禁标量分——
 * 一个分把"她驳了"和"她没看见"糊成一团，事后要复的人问"为什么这类不弹了"就答不上来。
 *
 * @param samples 这一类 30 天里一共有几张卡；少于 [QuietLimits.MIN_HISTORY_SAMPLES] ⇒ 当没数
 * @param rejectPct 驳回率（`CardDisposition.Choice.REJECT`）
 * @param quickClosePct 快关率（关卡即"不用了"，`DISMISS` ＋ actor=用户）
 * @param ignorePct 忽略率（没人表态、计时器收走，`DISMISS` ＋ actor=时钟）
 * @param misjudge7d 近 7 天这一类被判错的次数（`抽检结论.jsonl`）
 */
internal data class ClassHistory(
    val cardClass: CardClass,
    val samples: Int,
    val rejectPct: Int,
    val quickClosePct: Int,
    val ignorePct: Int,
    val misjudge7d: Int,
) {
    init {
        listOf(rejectPct, quickClosePct, ignorePct).forEach {
            require(it in 0..100) { "比率必须是百分数 0..100，交进来的是 $it" }
        }
        require(samples >= 0 && misjudge7d >= 0) { "计数不许为负：${cardClass.wire}" }
    }

    /** 数够不够下"她不想看"这个结论（没数 ⇒ 一律不许降级，照 291「取不到数不许拿 0 顶替」）。 */
    val known: Boolean get() = samples >= QuietLimits.MIN_HISTORY_SAMPLES

    /** 近 7 天还在判错 ⇒ 这一类更该问，历史再吵也不许降。 */
    val misjudging: Boolean get() = misjudge7d >= QuietLimits.MISJUDGE_MAX

    /** 三个率里有没有一个过线（任一过线即"吵"，不取平均）。 */
    val noisy: Boolean
        get() = rejectPct >= QuietLimits.NOISY_PCT ||
            quickClosePct >= QuietLimits.NOISY_PCT ||
            ignorePct >= QuietLimits.NOISY_PCT

    /** 一句为什么（进策略的 `why`，事后看得懂是谁的意思）。 */
    fun noisyWhy(): String = "${cardClass.cn} 30 天：驳回 $rejectPct%、快关 $quickClosePct%、" +
        "忽略 $ignorePct%（$samples 张）⇒ 降为自决"
}

/**
 * 本次会话的形状（派工单补充页"工作区类型／时段与节奏"那一格的读数）。
 *
 * 缺的那格一律 null／false（＝不据此降级）：拿"看起来正常"的默认值顶上去，
 * 就等于机器自己给自己批了一次"现在是深夜，别弹了"。
 *
 * @param workspaceType 工作区类型（照装配层给的字面，本层不解释；空串 ⇒ 没报）
 * @param hourOfDay 开机那一刻的钟点 0..23（调用方给的，本层不读时钟）；null ⇒ 没报
 * @param busy 节奏：她正在连着发指令／正在手机上打字 ⇒ 忙
 */
internal data class SessionShape(
    val workspaceType: String = "",
    val hourOfDay: Int? = null,
    val busy: Boolean = false,
) {
    init {
        hourOfDay?.let { require(it in 0..23) { "钟点必须在 0..23，给的是 $it" } }
    }

    /** 深夜窗口（[QuietLimits.NIGHT_FROM_HOUR] 起、到 [QuietLimits.NIGHT_TO_HOUR] 前）；没报 ⇒ 不算深夜。 */
    val atNight: Boolean
        get() = hourOfDay != null &&
            (hourOfDay >= QuietLimits.NIGHT_FROM_HOUR || hourOfDay < QuietLimits.NIGHT_TO_HOUR)

    /** 这一段别打断（时段与节奏只影响"可弹"那一档，必弹档不看它）。 */
    val hush: Boolean get() = atNight || busy

    /** 一句形状（策略行的补充，不参与判定）。 */
    fun describe(): String = "${if (workspaceType.isBlank()) "工作区类型未报" else workspaceType}" +
        "·${if (atNight) "深夜" else "日间"}·${if (busy) "正忙" else "不忙"}"
}

/**
 * 三颗按钮（派工单补充页"全不弹／全部弹／按默认"）。
 *
 * 只有 [ASK_NONE] 与 [ASK_ALL] 算"人改过"：所以策略行上要把当前落点写出来，
 * 不然下一眼看到"必弹 3 类"没人知道那是默认还是被按出来的。
 */
internal enum class PolicyButton(val wire: String, val cn: String, val clicked: Boolean) {
    DEFAULT("default", "按默认", false),
    ASK_NONE("ask_none", "全不弹", true),
    ASK_ALL("ask_all", "全部弹", true),
    ;

    companion object {
        fun ofWire(wire: String): PolicyButton? = entries.firstOrNull { it.wire == wire }
    }
}

/** 一个类别在本次会话的落点（带为什么——判据"不许随机"的另一半：随机给不出 why）。 */
internal data class ClassDecision(
    val cardClass: CardClass,
    val tier: CardTier,
    /** 一句为什么（法条／按钮／没数／历史／时段——五选一，不许空）。 */
    val why: String,
) {
    init {
        require(why.isNotBlank()) { "定策每一档都要说清为什么：${cardClass.wire}" }
    }
}


/**
 * 一次不可逆动作的**回退办法**（闭集；派工单②点名三种，第四种是"没有"）。
 *
 * 为什么把"回退办法"做成闭集而不是一个 `rollback: Boolean`：
 * 布尔只回答"能不能退"，不回答**退到哪**——而事后要复的人问的永远是第二句。
 * 三种都算可回退；[NONE] 是唯一把静默执行关掉的那一格。
 */
internal enum class Rollback(val wire: String, val cn: String) {
    /** 移入应用内回收站（原物还在，只是换目录）。 */
    RECYCLE_BIN("recycle_bin", "回收站"),

    /** 改之前先留 `.bak` 原件（写覆盖类动作的标准做法）。 */
    BACKUP_FILE("backup_file", ".bak 原件"),

    /** 写进可退回清单：按清单逐条撤得回去。 */
    UNDO_LIST("undo_list", "可退回清单"),

    /** 没有任何回退办法（真删、外发消息、覆盖不留底）。 */
    NONE("none", "不可回退"),
    ;

    /** 有没有回退办法（[NONE] 之外都算有）。 */
    val reversible: Boolean get() = this != NONE

    companion object {
        /** 认不出就 null（账本自造值不静默当别的档，照斥候/未办成单的解析纪律）。 */
        fun ofWire(wire: String): Rollback? = entries.firstOrNull { it.wire == wire }
    }
}

/** 一次免打扰授权（**本任务**、到 [expiresAtMs] 为止；两个时刻都在构造时定死）。 */
internal data class QuietGrant(
    /** 哪件事免打扰（任务号；授权不覆盖别的任务，所以它必填）。 */
    val taskId: String,
    val grantedAtMs: Long,
    /** 授权的分钟数（1..[QuietLimits.MAX_MINUTES]，超不了）。 */
    val minutes: Long,
    /** 是哪句话开的（派工单点名四个触发词；账上要答得出"凭什么算免打扰"）。 */
    val trigger: QuietTrigger,
    /** 是谁说的（"user" ＝ 用户亲口；别的来源本批不认，写在这里是为了留痕能追）。 */
    val grantedBy: String = "user",
) {
    init {
        require(taskId.isNotBlank()) { "免打扰授权必须写明是哪件事" }
        require(minutes in 1L..QuietLimits.MAX_MINUTES) {
            "免打扰时长必须在 1..${QuietLimits.MAX_MINUTES} 分钟（派工单④：≤4 小时自动退出）"
        }
        require(grantedBy.isNotBlank()) { "授权必须写明是谁说的（没人说的授权＝机器自己批的）" }
    }

    /** 到期时刻（到点即 [QuietState.EXPIRED]，不续）。 */
    val expiresAtMs: Long get() = grantedAtMs + minutes * QuietLimits.MINUTE_MS
}

/** 授权的三态（闭集）：没授权／正授权／已到期。 */
internal enum class QuietState(val wire: String, val cn: String) {
    OFF("off", "未授权"),
    ACTIVE("active", "免打扰中"),
    EXPIRED("expired", "已自动退出"),
}

/** 一件动作在当下该走哪条路（闭集；免打扰不许出现"既不问也不做也不记"的第四格）。 */
internal enum class QuietVerdict(val wire: String, val cn: String) {
    /** 没有授权 ⇒ 照普通档问。 */
    ASK_AS_USUAL("ask_as_usual", "照常规问"),

    /** 免打扰中且动作可回退 ⇒ 不问、做、留痕。 */
    RUN_SILENTLY("run_silently", "静默执行"),

    /**
     * 免打扰中但这一件不许静默干 ⇒ **不做**，改记一条。
     *
     * 两种来历共用这一格（都不问、都不干、都要留痕，所以不必再开一格）：
     * 动作没有任何回退办法（派工单②）、动作是**外发**（免打扰页"外发一律不做"）。
     */
    REFUSE_AND_RECORD("refuse_and_record", "不做并留痕"),
}

/**
 * 免打扰授权的判定口（纯函数，没有进程状态：同一份授权＋同一个时刻 ⇒ 同一个结论）。
 *
 * 为什么不做成单例里的可变状态：可变授权在单测里会串味（上一条用例的授权漏到下一条），
 * 在生产里更糟——两个任务共用一张"免打扰"就是越权。授权由装配层拿着，本文件只算。
 */
internal object QuietMode {

    /** 开一张授权（[QuietGrant] 的构造把时长钉在 1..240 分钟；要更久得由人再说一次）。 */
    fun grant(
        taskId: String,
        minutes: Long = QuietLimits.MAX_MINUTES,
        nowMs: Long,
        trigger: QuietTrigger = QuietTrigger.NO_CARDS,
    ): QuietGrant = QuietGrant(taskId = taskId, grantedAtMs = nowMs, minutes = minutes, trigger = trigger)

    /**
     * 从**那句话**开授权（派工单免打扰页：触发词＝不弹卡／别弹了／直接干／只要结果）。
     *
     * 认不出触发词 ⇒ null：**没有那句话就没有授权**。这一格是"免问权只能由人给"的机器依据——
     * 装配层若图省事直接调 [grant]（默认触发词），至少账上写的是"不弹卡"这一句，
     * 而不是把来源抹平；而拿文本进来的这条路必须过触发词，谁也不许替用户说这句话。
     */
    fun grantFromText(
        text: String,
        taskId: String,
        nowMs: Long,
        minutes: Long = QuietLimits.MAX_MINUTES,
    ): QuietGrant? = QuietTrigger.ofText(text)?.let { grant(taskId, minutes, nowMs, it) }

    /**
     * 授权落账那一笔（派工单免打扰页："进入时在账上记一笔授权"）。
     *
     * 句子在这儿定、写出归装配层（本文件一个写口都没有，纪律 1）。
     * 五样都在：**哪件事／凭什么话／多久／到什么时候／外发一律不做**——
     * 事后有人问"这张免打扰是谁给的、给了多大"，这一行就是答案，不用猜。
     */
    fun authRecord(grant: QuietGrant, nowMs: Long): String =
        "免打扰授权｜任务 ${grant.taskId}｜由「${grant.trigger.cn}」开了 ${grant.minutes} 分钟" +
            "（${grant.grantedBy}说）｜到期 ms ${grant.expiresAtMs}｜" +
            "期间不弹建议／进度／许可／询问／过程确认五类，外发一律不做｜此刻=${stateOf(grant, nowMs).cn}"

    /** 此刻这份授权处在哪个态（`null` ⇒ 从没授权过）。 */
    fun stateOf(grant: QuietGrant?, nowMs: Long): QuietState = when {
        grant == null -> QuietState.OFF
        // 到期时刻本身算"已退出"：满 4 小时那一毫秒起就不再免打扰。
        nowMs >= grant.expiresAtMs -> QuietState.EXPIRED
        nowMs < grant.grantedAtMs -> QuietState.OFF
        else -> QuietState.ACTIVE
    }

    /** 还在免打扰期内吗（[QuietState.ACTIVE] 才是；到期不是"再提醒一次"，是退出）。 */
    fun active(grant: QuietGrant?, nowMs: Long): Boolean = stateOf(grant, nowMs) == QuietState.ACTIVE

    /** 还剩多少毫秒（未授权或已到期 ⇒ 0，不写负数）。 */
    fun remainingMs(grant: QuietGrant?, nowMs: Long): Long = when (stateOf(grant, nowMs)) {
        QuietState.ACTIVE -> grant!!.expiresAtMs - nowMs
        else -> 0L
    }

    /**
     * 这一刻**这一类卡**能不能弹。
     *
     * 两条规则，顺序不许调：
     * - [cls] 不在可压的五类里（不可逆／越权／真失败）⇒ 一律放行：免打扰压不到"什么必须问"，
     *   而"真失败"就是派工单免打扰页那句**只有它打断一次**（次数另由去重键管，见纪律 4）。
     * - 否则：免打扰期内 ⇒ **零卡**（判据④断言的就是这一格）。
     */
    fun cardAllowed(grant: QuietGrant?, nowMs: Long, cls: CardClass): Boolean {
        if (!cls.quietSuppresses) return true
        return !active(grant, nowMs)
    }

    /**
     * 一件动作在当下走哪条路（派工单②：不问，但必须可回退；免打扰页：外发一律不做）。
     *
     * 没授权时不冒充授权：回 [QuietVerdict.ASK_AS_USUAL]，常规问法照走。
     * `outbound` **故意没有默认值**：默认成 false 就是一次可以漏传的外发，漏传一次少一封邮件、
     * 但多一次"它替我发了东西"——编译期要人写清楚比事后翻账便宜。
     */
    fun decide(
        grant: QuietGrant?,
        nowMs: Long,
        rollback: Rollback,
        outbound: Boolean,
    ): QuietVerdict = when {
        !active(grant, nowMs) -> QuietVerdict.ASK_AS_USUAL
        // 外发排在可回退之前：留着"撤回"按钮也不算可回退——收件人看见过就是看见过。
        outbound -> QuietVerdict.REFUSE_AND_RECORD
        rollback.reversible -> QuietVerdict.RUN_SILENTLY
        else -> QuietVerdict.REFUSE_AND_RECORD
    }

    /**
     * 收工三样（派工单③）：产物清单／验收结论／未做项。
     *
     * 空手交不得：验收结论为空 ⇒ 构造期拦住。
     * 负例点名"结束只说做完了"——只说一句"做完了"既不是结论也不是清单。
     */
    fun closeOut(
        grant: QuietGrant?,
        nowMs: Long,
        artifacts: List<String>,
        acceptance: String,
        undone: List<String>,
    ): QuietHandback {
        val text = acceptance.trim()
        require(text.isNotBlank()) { "收工必须交验收结论（派工单③第三样不许空手）" }
        require(artifacts.isNotEmpty() || undone.isNotEmpty()) {
            "产物与未做项不能同时为空：一件事既没产物也没未做项＝没干过"
        }
        return QuietHandback(
            taskId = grant?.taskId ?: "",
            state = stateOf(grant, nowMs),
            artifacts = artifacts.map { it.trim() }.filter { it.isNotEmpty() },
            acceptance = text,
            undone = undone.map { it.trim() }.filter { it.isNotEmpty() },
        )
    }

    /**
     * 到期时该不该由装配层补一句"免打扰已自动退出"（只在**跨过边界那一次**为真）。
     *
     * 为什么要单独问一句：到期是时刻算出来的，不是事件驱动的——App 可能早就没在跑了。
     * 这一格给的是"上次还在免打扰、现在不了"这个**边沿**，落账归装配层。
     */
    fun justExpired(grant: QuietGrant?, lastSeenMs: Long, nowMs: Long): Boolean =
        grant != null && lastSeenMs < grant.expiresAtMs && nowMs >= grant.expiresAtMs
}

/**
 * 收工三样（派工单③）。三样都在类型里，所以"漏交"编译不过，而不是运行时没人发现。
 *
 * @param state 交这份单子时授权还在不在（[QuietState.EXPIRED] ⇒ 是自动退出交的，不是人叫停的）
 */
internal data class QuietHandback(
    val taskId: String,
    val state: QuietState,
    /** 第一样：产物清单（一行一件，人能找到东西在哪）。 */
    val artifacts: List<String>,
    /** 第二样：验收结论（一句事实，不许写成"做完了"）。 */
    val acceptance: String,
    /** 第三样：未做项（没做的照实列，空表＝一件没落下）。 */
    val undone: List<String>,
) {
    init {
        require(acceptance.isNotBlank()) { "验收结论不许为空" }
    }

    /** 给人看的三行（产物／验收／未做）；授权到期那一次多一行说明。 */
    fun lines(): List<String> = listOf(
        "产物 ${artifacts.size} 件：${artifacts.joinToString("、").ifEmpty { "无" }}",
        "验收：$acceptance",
        "未做 ${undone.size} 项：${undone.joinToString("、").ifEmpty { "无" }}",
    ) + if (state == QuietState.EXPIRED) listOf("免打扰已自动退出（满 ${QuietLimits.MAX_MINUTES / 60} 小时）") else emptyList()
}

/**
 * 本次会话的弹卡策略（派工单补充页：开机算一次、一行可见、点击可改、跨会话不继承）。
 *
 * 三样都必须留在类型里，不然"一行"与"必弹不许降"就没人管：
 * [decisions] 恒按 [CardClass] 声明序一条一类（缺类＝有人在别处补了默认档），
 * [histories] 与 [shape] 一起存着，所以 [click] 重算时不必回头再读盘。
 */
internal data class BootStrategy(
    val shape: SessionShape,
    /** 交进来算策略的那几段历史（只为"点击可改"能原地重算而留，不参与行的渲染）。 */
    val histories: List<ClassHistory>,
    val button: PolicyButton,
    /** 恒 [CardClass.entries.size] 条，按声明序（判据⑩"不许随机"的形状依据）。 */
    val decisions: List<ClassDecision>,
) {
    init {
        require(decisions.map { it.cardClass } == CardClass.entries.toList()) {
            "定策必须一类一条、按闭集声明序：现在排的是 ${decisions.joinToString("、") { it.cardClass.wire }}"
        }
    }

    /** 某一类的落点（装配层在真要弹卡那一刻查这一格）。 */
    fun tierOf(cls: CardClass): CardTier = decisions.first { it.cardClass == cls }.tier

    /** 某一类为什么落到这一档（事后复的入口：不许有人只能看到档、看不到理由）。 */
    fun whyOf(cls: CardClass): String = decisions.first { it.cardClass == cls }.why

    /** 这一档有几类。 */
    fun countOf(tier: CardTier): Int = decisions.count { it.tier == tier }

    /**
     * 那一行（派工单原文格式 `必弹 N 类 · 可弹 M 类 · 不弹 K 类`，加当前落点）。
     *
     * 行长度与"只许一行"在这儿钉死：超了不是截断，是 [check] 红——
     * 截断会把"必弹 3 类"截成"必弹 3 "，那比没有这行更坏。
     */
    fun line(): String {
        val text = "弹卡策略：必弹 ${countOf(CardTier.MUST_ASK)} 类 · " +
            "可弹 ${countOf(CardTier.MAY_ASK)} 类 · 不弹 ${countOf(CardTier.SILENT)} 类（${button.cn}）"
        check(!text.contains('\n')) { "定策必须一行可见，现在出了换行" }
        check(text.length <= QuietLimits.MAX_POLICY_LINE_CHARS) {
            "定策那一行 ${text.length} 字，超了 ${QuietLimits.MAX_POLICY_LINE_CHARS} 字上限"
        }
        return text
    }

    /**
     * 人按了哪颗按钮 ⇒ 当场重算（判据⑨"点击可改"）。
     *
     * 重算走同一个纯函数，所以**必弹那一档一类都不会少**——这是"只调何时问，不改什么必须问"
     * 唯一的机器依据；把它写成 `decisions.map { ... }` 那种就地改档，降级就没了刹车。
     */
    fun click(button: PolicyButton): BootStrategy = BootPolicy.of(shape, histories, button)
}

/**
 * 开机定策的算式（纯函数：同工作区同历史 ⇒ 同一行，判据⑩）。
 *
 * 为什么不做成"读盘的 object"：`卡处置.jsonl`／`抽检结论.jsonl` 的读法各自归 292 与 208 的账，
 * 本层拿数就行。把读盘搬进来＝330 抢了别人的路径，而且随机性正是从"顺手读一下当前进程状态"来的。
 */
internal object BootPolicy {

    /**
     * 算一次策略。
     *
     * 历史按类别逐条 `firstOrNull` 取 ⇒ **同一段历史换个次序交进来也照样算出同一行**
     * （盘上行序不该反过来影响策略；判据⑩的另一半钉的就是这个）。
     */
    fun of(
        shape: SessionShape,
        histories: List<ClassHistory>,
        button: PolicyButton = PolicyButton.DEFAULT,
    ): BootStrategy = BootStrategy(
        shape = shape,
        histories = histories,
        button = button,
        decisions = CardClass.entries.map { cls ->
            tierOf(cls, histories.firstOrNull { it.cardClass == cls }, shape, button)
        },
    )

    /**
     * 一类落到哪一档。
     *
     * 顺序＝优先级，五条不许调：
     * 1. 法条永远在按钮前面（按钮是人改"何时问"的手，不是改"什么必须问"的手）；
     * 2. 按钮永远在时段前面（按出来的意图比这一刻忙不忙更近）；
     * 3. 时段排在历史与"没数"前面——它说的是**这一段**别打断，与"这一类值不值得问"是两回事，
     *    所以新机器上没历史也照样按得住；但它按不住必弹档，也不给闭集加格；
     * 4. 历史排在默认档后面（天生不弹的那三类不必去数账）。
     */
    private fun tierOf(
        cls: CardClass,
        h: ClassHistory?,
        shape: SessionShape,
        button: PolicyButton,
    ): ClassDecision = when {
        cls.lawMustAsk -> ClassDecision(
            cls, CardTier.MUST_ASK, "法条钉死必须问（不可逆／越权／真失败）：定策与按钮都不许降"
        )
        button == PolicyButton.ASK_NONE -> ClassDecision(cls, CardTier.SILENT, "人按了「全不弹」：本次会话这一类不问")
        button == PolicyButton.ASK_ALL -> ClassDecision(cls, CardTier.MUST_ASK, "人按了「全部弹」：本次会话这一类必问")
        cls.silentByDefault -> ClassDecision(cls, CardTier.SILENT, "${cls.cn}排在派工单的「不弹」档：不值得打断")
        shape.hush -> ClassDecision(cls, CardTier.SILENT, "${shape.describe()}：这一段只让必弹的弹")
        h == null || !h.known -> ClassDecision(cls, CardTier.MAY_ASK, noDataWhy(cls, h))
        h.misjudging -> ClassDecision(
            cls, CardTier.MAY_ASK, "${cls.cn} 近 7 天判错 ${h.misjudge7d} 次：还在猜错，更该问"
        )
        h.noisy -> ClassDecision(cls, CardTier.SILENT, h.noisyWhy())
        else -> ClassDecision(cls, CardTier.MAY_ASK, "${cls.cn} 按默认档：那一次问不问由额度表（AllowanceLadder）判")
    }

    /** 没数为什么不许降（照 291「取不到数就回 null，绝不拿 0 顶替」）。 */
    private fun noDataWhy(cls: CardClass, h: ClassHistory?): String = if (h == null) {
        "${cls.cn} 30 天一段数都没有：没数就照默认档，不许当成「她不想看」"
    } else {
        "${cls.cn} 30 天只有 ${h.samples} 张（不足 ${QuietLimits.MIN_HISTORY_SAMPLES} 张）：样本不够，不许降"
    }
}
