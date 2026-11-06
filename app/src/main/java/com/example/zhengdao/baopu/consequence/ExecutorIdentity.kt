// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百零六批（2026-11-07）· 476 持久身份——本机执行体的稳定 id 与它的四条规矩。
//
// 派工单 §三 那一节在这里落成的形状（判据④⑤ 的牙都在本文件）：
//   ① 首启生成、之后每次读回来都是同一个 id（**持久化**），且**模型改不动**；
//   ③ 每条账行带身份 id（盖章在 `DecisionLedger.build`，见 [ExecutorIdentity.STAMP_NOTE]）；
//   ④ 轮换＝新 id 落账 ＋ 旧 id 标「已轮换」，**不许静默覆盖**；
//   ⑤ 模型调「改身份／解除撤销」⇒ 拒。
//
// 房型照 `AllowanceLadder`／`ReflectionAutonomy`：**本文件是纯判据层**——零 IO、零时钟、
// 零随机、零 Android。id 的熵由调用方（[ConsequenceStore]）拿，时刻由调用方传。
// 这样判据可以拿手搓的行直测，不必造一台文件系统在测试里。
//
// 为什么不用 `Settings.Secure.ANDROID_ID`：那条是**设备**标识、不是**执行体**标识，
// 而且恢复出厂/换机就变、App 卸载重装也可能变；本单要的是「这台机器上这个执行体」的稳定身份，
// 首启自己生成一个 UUID 并持久化，语义正对，而且**不采集任何人的信息**（派工单 §六 边界）。
//
// 为什么不复用 `DecisionActor`：那个枚举的 `actor` 记的是**角色**（user/model/auto），
// 已经是账本里的稳定键（改它＝老账读不出来）。身份 id 是另一件正交的事，
// 所以新开一格 `executor`，两格并存——详见交付报告「口径」那节（本批唯一一处判据字面与实现有出入）。
package com.example.zhengdao.baopu.consequence

/**
 * 身份状态（**闭集**，三态；认不出就 null，不许静默当 [ACTIVE]）。
 *
 * [ROTATED_OUT] 就是派工单 ④ 里那句「旧 id 记'已轮换'」——它是一条**留下来的行**，
 * 不是被删掉的那条。删掉＝静默覆盖，账上就查不到这个 id 曾经存在过。
 */
internal enum class IdentityState(val wire: String, val cn: String) {

    /** 正在用：凭据有效，动作可以挂在这个 id 下。 */
    ACTIVE("active", "在用"),

    /** 已撤销：名下凭据**全失效**（见 [CredentialBinding]），历史账照读。 */
    REVOKED("revoked", "已撤销"),

    /** 已轮换：被新 id 顶下来的那一条，留痕不删。 */
    ROTATED_OUT("rotated_out", "已轮换"),
    ;

    companion object {
        /** 线值 ⇒ 状态；认不出 ⇒ null（自造状态一律不渲染，也不冒充 [ACTIVE]）。 */
        fun ofWire(wire: String): IdentityState? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * 身份 id 的形状纪律。
 *
 * 前缀 `zdx-` 是**机器键**的一部分：账行、凭据行、轮换行都按整串比对，
 * 前缀写进串里，一眼能认出哪一格是身份、哪一格是会话号或 opId（负例「拿 sessionId 冒充身份」）。
 */
internal object IdentityFormat {

    /** 身份 id 前缀（稳定键：改它＝老账里的 executor 一格全部读不出来）。 */
    const val PREFIX: String = "zdx-"

    private val SHAPE = Regex(
        "^" + PREFIX + "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
    )

    /** 这串像不像一个身份 id（**只判形状**，不判它有没有落过账）。 */
    fun isWellFormed(id: String): Boolean = SHAPE.matches(id)

    /**
     * 归一并校验：调用方交来的熵串（一般是 `UUID.randomUUID().toString()`）⇒ 身份 id。
     *
     * @return 合格就返回带前缀的小写串；不合格就 null（装配层据此拒绝落盘，不写半条）
     */
    fun normalize(raw: String): String? {
        val trimmed = raw.trim().lowercase()
        if (trimmed.isEmpty()) return null
        val candidate = if (trimmed.startsWith(PREFIX)) trimmed else PREFIX + trimmed
        return candidate.takeIf(::isWellFormed)
    }
}

/**
 * 一行身份账（`执行体身份.json` 里的一条）。
 *
 * @param rotatedFrom 只由**新**行携带：我是从哪个 id 轮换下来的（派工单 ④「新 id ＋ 旧 id 记已轮换」）
 * @param revokedAtMs 只由 [IdentityState.REVOKED] 行携带；0＝没被撤销过
 */
internal data class IdentityRow(
    val id: String,
    val state: IdentityState,
    val createdAtMs: Long,
    val rotatedFrom: String = "",
    val revokedAtMs: Long = 0L,
) {
    /** 这一行还能不能拿来挂新凭据（撤销与已轮换都不行）。 */
    val usable: Boolean get() = state == IdentityState.ACTIVE
}

/** 一次改身份的请求（`actor` 用线值：`user`／`auto`／`model`）。 */
internal data class IdentityMutation(
    val actor: String,
    val op: String,
    val targetId: String = "",
)

/**
 * 身份判据（谁能动、能不能动、动完该留哪几行）。
 *
 * 三条硬规矩都写成**会拒绝**的函数，不是文档里的形容词：
 * 装配层拿 [refusalOf]／[rotateRows]／[revokeRows] 的返回值决定写不写盘，
 * 所以「模型改身份」这条路在代码里没有出口，而不是「暂时没有模型这么干」。
 */
internal object IdentityRules {

    const val OP_ROTATE: String = "rotate"
    const val OP_REVOKE: String = "revoke"
    const val OP_UNREVOKE: String = "unrevoke"

    /** 认得的改身份动作（闭集；不在表里的动作一律拒，不认自造动词）。 */
    val MUTATING_OPS: Set<String> = setOf(OP_ROTATE, OP_REVOKE, OP_UNREVOKE)

    /**
     * 这次改身份的请求该不该拒（null＝放行）。
     *
     * 派工单判据⑤：模型调「改身份／解除撤销」⇒ 拒。
     * 这里把**所有**改身份动作一并挡在 `actor=model` 外面，不只是那两条点名——
     * 点名的是「改 id」和「解除撤销」，但「撤销」本身同样能作废掉一切授权；
     * 只钉两条等于给第三条留门。
     */
    fun refusalOf(m: IdentityMutation): String? = when {
        m.actor == ACTOR_MODEL -> "身份只能由人或系统装配层改，模型不许改（actor=model／op=${m.op}）"
        m.op !in MUTATING_OPS -> "不认识的改身份动作：${m.op}"
        m.op == OP_UNREVOKE && m.actor != ACTOR_USER -> "解除撤销只许本人做（actor=${m.actor}）"
        else -> null
    }

    /** 这个 actor 是不是模型（`model` 线值与 `DecisionActor.MODEL.wire` 逐字相同，一处口径）。 */
    const val ACTOR_MODEL: String = "model"

    /** 只有本人（御批）能做的动作才用这个线值。 */
    const val ACTOR_USER: String = "user"

    /**
     * 首启生成（派工单 ①）：表里一条都没有 ⇒ 用调用方给的熵生成一行 [ACTIVE]。
     *
     * 已经有在用行就**原样返回 null**——「首启生成」不是「每次启动都生成」，
     * 后者会把身份变成每次重启都换的进程代号（那正是 476 要替掉的旧样）。
     *
     * @param entropy 调用方取的一次随机串（本层零随机）
     * @param nowMs 调用方给的时钟
     * @return 新生成的行；不该再生成 ⇒ null
     */
    fun firstRunRow(rows: List<IdentityRow>, entropy: String, nowMs: Long): IdentityRow? {
        if (rows.any { it.state == IdentityState.ACTIVE }) return null
        val id = IdentityFormat.normalize(entropy) ?: return null
        return IdentityRow(id = id, state = IdentityState.ACTIVE, createdAtMs = nowMs)
    }

    /**
     * 轮换（派工单 ④）：返回**换完之后**那张表；拒则返回 null 并给原因。
     *
     * 两条牙：
     *   · 旧行不删，改标 [IdentityState.ROTATED_OUT] ⇒ 账上查得到这个 id 曾经在用；
     *   · 新 id 必须与旧 id 不同、且形状合格 ⇒ 否则就是「静默覆盖」或伪造。
     */
    fun rotateOutcome(rows: List<IdentityRow>, m: IdentityMutation, newId: String, nowMs: Long): Rotation {
        val refuse = refusalOf(m)
        if (refuse != null) return Rotation.Rejected(refuse)
        val current = rows.lastOrNull { it.state == IdentityState.ACTIVE }
            ?: return Rotation.Rejected("没有在用的身份，轮换无从谈起")
        val fresh = IdentityFormat.normalize(newId)
            ?: return Rotation.Rejected("新身份 id 形状不合格")
        if (fresh == current.id) return Rotation.Rejected("新 id 与旧 id 相同——那不是轮换")
        val kept = rows.filterNot { it.id == current.id }
        val old = current.copy(state = IdentityState.ROTATED_OUT)
        val news = IdentityRow(id = fresh, state = IdentityState.ACTIVE, createdAtMs = nowMs, rotatedFrom = current.id)
        return Rotation.Accepted(rows = kept + old + news, previousId = current.id, newId = fresh)
    }

    /** 轮换的两种结果。 */
    sealed interface Rotation {
        /** 换成了：新表（旧行仍在）＋ 新旧两个 id。 */
        data class Accepted(val rows: List<IdentityRow>, val previousId: String, val newId: String) : Rotation

        /** 没换成：一句为什么。 */
        data class Rejected(val reason: String) : Rotation
    }

    /**
     * 撤销（派工单 ②）：在用行改标 [IdentityState.REVOKED]，**一个字节的历史都不删**。
     *
     * 撤销之后：名下凭据全失效由 [CredentialBinding] 判；
     * 历史账照读由账本自己的只增不改保证——这里不碰账本，只改身份状态。
     *
     * @return null＝撤销成功；非 null＝拒绝理由
     */
    fun revokeRows(rows: List<IdentityRow>, m: IdentityMutation, nowMs: Long): Pair<List<IdentityRow>, String?> {
        val refuse = refusalOf(m)
        if (refuse != null) return rows to refuse
        val current = rows.lastOrNull { it.state == IdentityState.ACTIVE }
            ?: return rows to "没有在用的身份可撤销"
        val updated = current.copy(state = IdentityState.REVOKED, revokedAtMs = nowMs)
        return rows.filterNot { it.id == current.id } + updated to null
    }

    /**
     * 解除撤销（只有本人能做，见 [refusalOf]）。
     *
     * 撤销过的行改回 [ACTIVE] 之前必须仍是**同一行**（同一个 id），不许借这条路塞一个新 id 进来——
     * 「解除撤销」变成「换个身份」就是判据⑤点名的那个洞。
     */
    fun unrevokeRows(rows: List<IdentityRow>, m: IdentityMutation): Pair<List<IdentityRow>, String?> {
        val refuse = refusalOf(m)
        if (refuse != null) return rows to refuse
        val target = rows.lastOrNull { it.state == IdentityState.REVOKED }
            ?: return rows to "没有被撤销的身份"
        if (m.targetId.isNotBlank() && m.targetId != target.id) {
            return rows to "解除撤销不许换成别的 id（要新身份请走轮换）"
        }
        if (rows.any { it.state == IdentityState.ACTIVE }) {
            return rows to "已经有一个在用的身份，先轮换再谈解除"
        }
        val updated = target.copy(state = IdentityState.ACTIVE, revokedAtMs = 0L)
        return rows.filterNot { it.id == target.id } + updated to null
    }

    /** 当前在用的 id（没有 ⇒ 空串；撤销／已轮换都读不出来）。 */
    fun activeId(rows: List<IdentityRow>): String =
        rows.lastOrNull { it.state == IdentityState.ACTIVE }?.id ?: ""

    /** 这串是不是**曾经存在过**的身份（撤销与已轮换都算曾经存在 ⇒ 历史账还能按它筛）。 */
    fun wasKnown(rows: List<IdentityRow>, id: String): Boolean = rows.any { it.id == id }
}
