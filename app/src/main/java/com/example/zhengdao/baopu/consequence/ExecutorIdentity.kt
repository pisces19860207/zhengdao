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
// 第五百一十七批改两条口径（Q2 并入本单的追加节，原话为准）：
//   · **U2 撤销持久不可逆**：506 的「解除撤销」整条路拆掉——撤销后重启仍失效，
//     恢复只能**铸新身份**（人工），旧身份的行与历史账原样保留、**永不复用**。
//     「解除撤销只许本人做」是 506 的旧答案；517 的答案是「本人也不行」：
//     能被撤销再复活的身份不是稳定身份，撤销的分量就在它回不来。
//   · **U3 铸新身份仅人工**：轮换与铸新两个动词都收成 `actor=user` 才放行——
//     装配层（auto）不再有铸新口。首启生成不受这条管：那是**制度性首装**，
//     不是「铸新」（表里一条都没有时才允许，见 [IdentityRules.firstRunRow] 的新前提）。
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

    /**
     * 铸新身份（U3）：**没有在用身份时**（被撤销／表被清）由**人**开一个新的。
     *
     * 与 [OP_ROTATE] 的分界：轮换的前提是有一个在用身份可换（旧 id 记「已轮换」）；
     * 铸新的前提是**没有**在用身份（旧 id 保持「已撤销」原样，历史保留不可用）。
     * 两个动词都只认人工（[refusalOf]），模型与装配层都调不动。
     */
    const val OP_MINT: String = "mint"

    /** 撤销后**不得再认**的老动词（U2：留着常量是为了拒因说得清，不是说它还通）。 */
    const val OP_UNREVOKE: String = "unrevoke"

    /** 认得的改身份动作（闭集；不在表里的动作一律拒，不认自造动词）。 */
    val MUTATING_OPS: Set<String> = setOf(OP_ROTATE, OP_REVOKE, OP_MINT)

    /**
     * 这次改身份的请求该不该拒（null＝放行）。
     *
     * 517 两条新牙：
     *   · U3——轮换与铸新都是**人工动词**：`actor != user` 一律拒（模型拒、装配层也拒）；
     *   · U2——撤销不可逆：`unrevoke` 不在 [MUTATING_OPS] 里，落到这里就是一句明话拒因，
     *     不再存在「本人可以解除」的那条路。
     */
    fun refusalOf(m: IdentityMutation): String? = when {
        m.actor == ACTOR_MODEL -> "身份只能由人或系统装配层改，模型不许改（actor=model／op=${m.op}）"
        m.op == OP_UNREVOKE -> "撤销不可逆（U2）：解除撤销这条路已拆，恢复只能铸新身份（人工）"
        (m.op == OP_ROTATE || m.op == OP_MINT) && m.actor != ACTOR_USER ->
            "铸新身份只许人工（U3）：actor=${m.actor} 不行，op=${m.op}"
        m.op !in MUTATING_OPS -> "不认识的改身份动作：${m.op}"
        else -> null
    }

    /** 这个 actor 是不是模型（`model` 线值与 `DecisionActor.MODEL.wire` 逐字相同，一处口径）。 */
    const val ACTOR_MODEL: String = "model"

    /** 只有本人（御批）能做的动作才用这个线值。 */
    const val ACTOR_USER: String = "user"

    /**
     * 首启生成（派工单 ①）：表里**一条都没有** ⇒ 用调用方给的熵生成一行 [ACTIVE]。
     *
     * 517 收紧前提（U2 的另一面）：506 的条件是「没有在用行就生成」——那意味着
     * 撤销后下一次重启会**静默长出一个新身份**，撤销的「不可逆」当场失效。
     * 新前提是**整张表为空**（真·首装／清数据）；有历史（撤销／已轮换）但没在用行
     * ⇒ 不许生成，恢复只有人工铸新（[mintOutcome]）那一条路。
     *
     * @param entropy 调用方取的一次随机串（本层零随机）
     * @param nowMs 调用方给的时钟
     * @return 新生成的行；不该再生成 ⇒ null
     */
    fun firstRunRow(rows: List<IdentityRow>, entropy: String, nowMs: Long): IdentityRow? {
        if (rows.isNotEmpty()) return null
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
     * 铸新身份（U3，517）：没有在用身份时由**人**开一个新的。
     *
     * 三条牙：
     *   · 前提是**没有**在用身份——有在用行就该走轮换（[rotateOutcome]），铸新不越过它；
     *   · 新 id 必须形状合格，且**不是这张表里任何一条曾存在过的 id**——复用旧 id
     *     ＝把撤销过的身份借壳复活，U2 在这里补第二道闩；
     *   · 旧行**原样保留**：撤销的还标撤销（不改成已轮换——它不是被换下去的，是被废掉的），
     *     [wasKnown] 查历史照常，只是再也不会有 [ACTIVE] 指向它们。
     */
    fun mintOutcome(rows: List<IdentityRow>, m: IdentityMutation, newId: String, nowMs: Long): Rotation {
        refusalOf(m)?.let { return Rotation.Rejected(it) }
        if (rows.any { it.state == IdentityState.ACTIVE }) {
            return Rotation.Rejected("已有在用的身份——换身份请走轮换，铸新只在没有在用身份时可用")
        }
        val fresh = IdentityFormat.normalize(newId)
            ?: return Rotation.Rejected("新身份 id 形状不合格")
        if (wasKnown(rows, fresh)) {
            return Rotation.Rejected("新 id 与历史身份重合——撤销过的身份不许复用（U2）")
        }
        val news = IdentityRow(id = fresh, state = IdentityState.ACTIVE, createdAtMs = nowMs)
        return Rotation.Accepted(rows = rows + news, previousId = "", newId = fresh)
    }

    /** 当前在用的 id（没有 ⇒ 空串；撤销／已轮换都读不出来）。 */
    fun activeId(rows: List<IdentityRow>): String =
        rows.lastOrNull { it.state == IdentityState.ACTIVE }?.id ?: ""

    /** 这串是不是**曾经存在过**的身份（撤销与已轮换都算曾经存在 ⇒ 历史账还能按它筛）。 */
    fun wasKnown(rows: List<IdentityRow>, id: String): Boolean = rows.any { it.id == id }
}
