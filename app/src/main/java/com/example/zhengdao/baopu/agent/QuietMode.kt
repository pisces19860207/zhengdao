// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百三十批 · 「免打扰授权」——用户说一句"不弹卡"之后，App 自己把活干完的边界。
//
// 用户 2026-10-31（派工单原文四条）：
//   ① 用户说"不弹卡"⇒ **本任务零卡**；
//   ② 不可逆动作**不问，但必须可回退**（回收站／.bak／可退回清单三选一，一样都没有就不做）；
//   ③ 结束交三样：产物清单／验收结论／未做项；
//   ④ **≤4 小时自动退出**（到期不许续，要续得由人再说一次）。
//
// 四条纪律（为什么长成下面这个样子）：
// 1. **本文件一个写口都没有**：授权、判卡、算到期全是纯函数，落账归装配层（330 租约只含三个新文件，
//    `SelfRulingLedger` 与 `UnfinishedLedger` 归 277／290 的账，本批不抢它们的路径）；
// 2. **授权是有形状的，不是布尔**：一个 `QuietGrant` 带着任务号与到期时刻，
//    所以"免打扰"永远回答得出"哪件事、到几点、还剩多久"——只有 true/false 的授权管不住自己不过期；
// 3. **到期不是"再提醒一次"**：`EXPIRED` 之后 `cardsAllowed` 直接回真（回到普通档），
//    本文件不许任何"自动续期"的口——负例点名"免打扰把用户永久关在门外"就犯在这儿；
// 4. **阻断性那一格不归免打扰管**：派工单第 ④ 格写的是"唯一例外"，说的是"我干不了活"这件事本身，
//    它不是一种打扰，是一次失败陈述。所以 [cardsAllowed] 的 `blocking` 参数为真时不看授权。
//    这条是本批的判读，不是派工单的字面——已按 Q1 交给用户拍板。
package com.example.zhengdao.baopu.agent

/** 免打扰授权的时长上限（派工单④：≤4 小时）。改这个数＝改授权制度，不许在调用处各写一把。 */
internal object QuietLimits {

    /** 一次授权最多多少分钟（240 ＝ 4 小时）。 */
    const val MAX_MINUTES = 240L

    /** 一分钟多少毫秒（把分钟换成时刻只在这一处乘）。 */
    const val MINUTE_MS = 60_000L

    /** 一次授权最多活多久（毫秒）。 */
    const val MAX_MS = MAX_MINUTES * MINUTE_MS
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
    /** 是谁说的（"user" ＝ 用户亲口；别的来源本批不认，写在这里是为了留痕能追）。 */
    val grantedBy: String = "user",
) {
    init {
        require(taskId.isNotBlank()) { "免打扰授权必须写明是哪件事" }
        require(minutes in 1L..QuietLimits.MAX_MINUTES) {
            "免打扰时长必须在 1..${QuietLimits.MAX_MINUTES} 分钟（派工单④：≤4 小时自动退出）"
        }
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

    /** 免打扰中但动作没有回退办法 ⇒ **不做**，改记一条（不许把"不问"读成"什么都可以干"）。 */
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
    fun grant(taskId: String, minutes: Long = QuietLimits.MAX_MINUTES, nowMs: Long): QuietGrant =
        QuietGrant(taskId = taskId, grantedAtMs = nowMs, minutes = minutes)

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
     * 这一刻**能不能弹卡**。
     *
     * 两条规则，顺序不许调：
     * - [blocking] 为真 ⇒ 一律放行（派工单④"唯一例外"：阻断性要说清"我干不了活，因为 X"）；
     *   弹几次由开检那边按事件键去重（[QuietMode] 不管次数，只管"这类事算不算打扰"）。
     * - 否则：免打扰期内 ⇒ **零卡**（判据④断言的就是这一格）。
     */
    fun cardsAllowed(grant: QuietGrant?, nowMs: Long, blocking: Boolean): Boolean {
        if (blocking) return true
        return !active(grant, nowMs)
    }

    /**
     * 一件动作在当下走哪条路（派工单②：不问，但必须可回退）。
     *
     * 没授权时不冒充授权：回 [QuietVerdict.ASK_AS_USUAL]，常规问法照走。
     */
    fun decide(grant: QuietGrant?, nowMs: Long, rollback: Rollback): QuietVerdict = when {
        !active(grant, nowMs) -> QuietVerdict.ASK_AS_USUAL
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
