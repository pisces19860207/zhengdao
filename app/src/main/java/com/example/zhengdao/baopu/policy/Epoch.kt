// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百六十四批 · 纪元（Epoch）**实体化**：把「第几纪、什么时候起的、装的哪版判据、凭什么切的」
// 从人的回忆变成一个查得到的对象。
//
// 310 批写死了纪元的**算法**（一周一纪／纪内钉死／边界换表／缺陷回滚），但那套东西只有
// 「进程内当前表」，没有**历史实体**：`EpochRuntime` 只留 current 与 previous，第三张就查不到了；
// 纪元账只有计数（候选／通过／否决），没有内容。四时闭环表里「进化时」缺的正是这一件。
//
// 分工跟 310 划清，**不建第二套纪元**：
//   · 310 = 「此刻生效的是哪张表」（日历算纪＋换表写口）；本文件 = 「历次纪元的身份证与账」；
//   · 快照沿用 [CriteriaSnapshot]，不可进化清单沿用 [ImmutableCriteria]，这里一条都不重定义；
//   · 接线（谁来调用本文件）归第三百八十批。本批照 310 的口径只出**对象＋闸＋测试＋账**，
//     在线路径一个字不读这里——那条禁令由 310 的 `tools/check-criteria-gates.py` 尺一钉着。
//
// 四条规矩，每条都有会红的负例：
//   1. **切纪元必须写原因**。[Epoch.reason] 空白 ⇒ 构造期就拒（是「建不出来」，不是提示）。
//      跨纪元那一跳还必须有**依据裁定编号**：没有编号＝没有御批，凭什么叫新纪。
//   2. **纪内不许改不可进化条**。[EpochRegistry.record] 先拿锚点撞 [ImmutableCriteria]，命中即拒；
//      **空锚点同样拒**——否则「不写锚点」就成了那张清单的逃逸口（310 的锚点归一化同口径）。
//   3. **指纹绑对象**。[Epoch.criteriaHash] 只认 [Epoch.criteriaHashOf] 从判据集合算出来的那串，
//      而且落账时再与实际装进来的快照比一次：纪元说的与账上装的不是同一件事 ⇒ 拒。
//   4. **变更账每行归属一个纪元**。写的时候必须有 `epochId` 且等于当前纪；读的时候允许 `null`——
//      本批之前的老行压根没有这一列，一律标「未归属」。宁可承认追不到，也不替它补一个当前纪（那是假账）。
//
// 零 Android／零 IO／零时钟：`startedAt` 与 `atMs` 都由调用方给（同 310——自己读一次时钟，
// 同一张表就会在午夜前后给出两个纪元号）。[CriteriaChange.wire]／[CriteriaChange.rows] 只出与读
// **文本形态**，落不落盘、落在哪个文件都不归本文件管。
package com.example.zhengdao.baopu.policy

import java.security.MessageDigest

/**
 * 一个纪元：派工单那五个字段（id／label／startedAt／criteriaHash／reason），不加不减。
 *
 * 构造期自检（任何一条不满足**根本建不出来**）：
 * - id、label、reason 三样空白都拒——`reason` 就是判据①「原因必填，空拒」落的地方；
 * - `startedAt` 不许为负，且由调用方给（本包零时钟）；
 * - `criteriaHash` 必须是 64 位小写十六进制——只认 [criteriaHashOf] 算出来的形态，
 *   手填一串「abc」想在账上冒充某版判据，是建不出对象的。
 */
internal data class Epoch(
    val id: String,
    /** 给人看的那一句（账与卡面上出现的就是它，id 是给机器寻址的）。 */
    val label: String,
    /** 本纪起点（毫秒，调用方给）。 */
    val startedAt: Long,
    /** 本纪判据集合的指纹：见 [criteriaHashOf]。 */
    val criteriaHash: String,
    /** 凭什么切这一纪（必填）。 */
    val reason: String,
) {
    init {
        require(id.isNotBlank()) { "纪元 id 不能空——快照与变更账都按它寻址，空 id 等于查不到" }
        require(label.isNotBlank()) { "纪元 label 不能空——账是给人读的" }
        require(startedAt >= 0L) { "纪元起始时刻由调用方给（本包零时钟），且不许为负" }
        require(HASH_HEX.matches(criteriaHash)) {
            "criteriaHash 必须是 64 位小写十六进制 sha256，实测「$criteriaHash」——" +
                "要建纪元请用 Epoch.of(...)，指纹由判据集合算，不许手填"
        }
        require(reason.isNotBlank()) { "切纪元必须写原因（第三百六十四批判据①：空原因拒）" }
    }

    companion object {
        /** sha256 的十六进制形态：整串都要，缺一位就不是那版判据。 */
        private val HASH_HEX = Regex("^[0-9a-f]{64}$")

        /**
         * 建纪元唯一正路：指纹从传进来的判据集合算出来，不给手填的口。
         *
         * 这里刻意不收 [CriteriaSnapshot] 而是收它的 `criteria`——310 的快照构造期会替每条判据
         * 再核一遍不可进化清单，所以「先建表、再拿表的条目建纪元」这条顺序本身就带那道闸。
         */
        fun of(
            id: String,
            label: String,
            startedAt: Long,
            criteria: List<CriterionEntry>,
            reason: String,
        ): Epoch = Epoch(id, label, startedAt, criteriaHashOf(criteria), reason)

        /**
         * 判据集合的指纹。
         *
         * 两个口径是刻意选的：
         * - **按 id 排序**：同一批判据换个写法顺序不算换版（否则「调整排列」会在账上变成一次进化）；
         * - **每个字段带长度前缀**：分隔符与换行都不许成为逃逸口——`id="a|b"` 加 `description="c"`
         *   与 `id="a"` 加 `description="b|c"` 必须算出两个指纹（这条有负例钉着，不是顺手写的）。
         */
        fun criteriaHashOf(criteria: List<CriterionEntry>): String =
            sha256Hex(canonical(criteria).toByteArray(Charsets.UTF_8))

        /** 规范化文本：逐条 `长度:值` 拼字段，条与条按 id 升序，条内按 id／描述／锚点／准入纪。 */
        private fun canonical(criteria: List<CriterionEntry>): String =
            criteria.sortedBy { it.id }.joinToString("\n") { entry ->
                listOf(entry.id, entry.description, entry.target, entry.admittedInEpoch.toString())
                    .joinToString("|") { field -> "${field.length}:$field" }
            }

        /**
         * sha256 小写十六进制。
         *
         * 没有复用同包的 [AnchorAsset.sha256Hex]：那个函数是「出厂资产的完整性」那一职责的
         * 一部分（与禁词、条数、签字四层一起），本文件的指纹是「历次判据集合」的标识；
         * 算法是两行，职责不该合家。
         */
        private fun sha256Hex(raw: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
    }
}

/**
 * 跨纪元的一跳（派工单②）：旧 id → 新 id ＋ 原因 ＋ 依据裁定编号。
 *
 * 只增不改——它是一次**事件**，不是可以随时重算的读数。`reason` 取自新纪元的 `reason`，
 * 不在这里再收一遍，免得出现「同一跳两个原因」。
 */
internal data class EpochTransition(
    val fromId: String,
    val toId: String,
    val reason: String,
    /** 依据的裁定编号（御案／派工单上那句「谁批准的」）。 */
    val rulingRef: String,
    val atMs: Long,
) {
    init {
        require(fromId.isNotBlank()) { "transition 缺旧纪元 id：不知道从哪一跳来的，就不叫跨纪元" }
        require(toId.isNotBlank()) { "transition 缺新纪元 id" }
        require(fromId != toId) { "transition 的旧新 id 相同（$fromId）：原地踏步不叫切纪元" }
        require(reason.isNotBlank()) { "切纪元必须写原因（判据①：空原因拒）" }
        require(rulingRef.isNotBlank()) { "跨纪元必须带依据裁定编号：没有编号＝没有御批" }
        require(atMs >= 0L) { "跳转时刻由调用方给（本包零时钟），且不许为负" }
    }

    /** 落盘形态：`旧id→新id|原因|裁定编号|时刻`。 */
    fun wire(): String = "$fromId→$toId|$reason|$rulingRef|$atMs"
}

/**
 * 变更账的一行（判据④）：每行带 `epochId`，老数据允许 `null` ＝未归属。
 *
 * 写口只有一条：[EpochRegistry.record]（那里 `epochId` 为空 ⇒ 拒）。
 * 本类型的 [decode]／[rows] 是**读口**：把已经落在账上的文本读回来。两条不许串——
 * 「读时允许 null」是给老数据的出路，不是给新数据开的逃逸口。
 */
internal data class CriteriaChange(
    val changeId: String,
    /** 归属的纪元；`null` ＝这笔变更追不到纪元（老账行）。 */
    val epochId: String?,
    /** 被改的判据 id。 */
    val criterionId: String,
    /** 它动的生产锚点：`路径#符号`（与 310 的清单同一形态）。 */
    val target: String,
    val atMs: Long,
) {
    /** 归属有没有追得到。 */
    fun attributed(): Boolean = epochId?.isNotBlank() == true

    /** 给人读的归属：追不到就明写「未归属」，不假装属于当前纪。 */
    fun epochLabel(): String = epochId?.takeIf { it.isNotBlank() } ?: UNATTRIBUTED

    /** 落盘形态：`变更id|纪元id|判据id|锚点|时刻`；没归属时第二列留空。 */
    fun wire(): String = "$changeId|${epochId ?: ""}|$criterionId|$target|$atMs"

    companion object {
        /** 判据④那句话：老数据不猜、不补，标出来。 */
        const val UNATTRIBUTED = "未归属"

        private const val COLUMNS = 5
        private const val LEGACY_COLUMNS = 4

        /**
         * 读一行账；读不回一个合法行就返回 null（坏行跳过不崩，与 343 变更账同口径）。
         *
         * 两种形态都要认：
         * - **本批之后**：五列 `变更id|纪元id|判据id|锚点|时刻`，第二列可以空着＝没归属；
         * - **本批之前**（老数据）：四列 `变更id|判据id|锚点|时刻`，压根没有纪元列 ⇒ `epochId = null`。
         */
        fun decode(line: String): CriteriaChange? {
            val cols = line.trim().split("|")
            val legacy = cols.size == LEGACY_COLUMNS
            if (cols.size != COLUMNS && !legacy) {
                return null
            }
            val atMs = cols.last().toLongOrNull() ?: return null
            val changeId = cols[0]
            val epochId = if (legacy) null else cols[1]
            val criterionId = if (legacy) cols[1] else cols[2]
            val target = if (legacy) cols[2] else cols[3]
            if (changeId.isBlank() || criterionId.isBlank() || target.isBlank()) {
                return null
            }
            return CriteriaChange(
                changeId = changeId,
                epochId = if (epochId.isNullOrBlank()) null else epochId,
                criterionId = criterionId,
                target = target,
                atMs = atMs,
            )
        }

        /** 整本账（按文本顺序）。坏行跳过——但跳过的条数由 [unreadable] 出读数，不静默吞。 */
        fun rows(text: String): List<CriteriaChange> =
            text.lineSequence().mapNotNull { decode(it) }.toList()

        /** 读不回的行数（账上有行、这里读不出＝格式变了，不许当成「没写过」）。 */
        fun unreadable(text: String): Int =
            text.lineSequence().filter { it.isNotBlank() }.count { decode(it) == null }
    }
}

/** 一道闸的读数：过／拒 ＋ 一句人读的原因。**没有分数，也没有第四态**（照 310 口径）。 */
internal data class EpochResult(
    val accepted: Boolean,
    val reason: String,
)

/**
 * 纪元册：历次纪元、跨纪元的那一跳、每纪的快照、以及归属到纪的变更账。
 *
 * 三个写口各管一件事，互不代替：
 * - [open] 开第一纪（此后不许再开，只能切）；
 * - [transition] 切纪：旧→新，带原因与裁定编号；
 * - [record] 记一条判据变更：必须有归属，且撞不可进化清单即拒。
 *
 * 与 310 的 [EpochRuntime] 不是两条权力链：那里管「当前生效表」，这里只管「实体与账」，
 * 既不读也不写 `EpochRuntime` 的状态；两边都由调用方驱动，本文件零时钟。
 */
internal object EpochRegistry {

    /** 按开纪顺序存的实体（key＝id；顺序即「第几纪」）。 */
    private val epochs = LinkedHashMap<String, Epoch>()

    /** 每纪装过的快照（按序号升序）——判据③「给定 epochId 可列快照」就是这张表。 */
    private val snapshots = LinkedHashMap<String, MutableList<CriteriaSnapshot>>()

    private val transitions = ArrayList<EpochTransition>()

    private val changes = ArrayList<CriteriaChange>()

    @Volatile
    private var currentId: String? = null

    fun current(): Epoch? = currentId?.let { epochs[it] }

    /** 历次纪元（开纪顺序）。 */
    fun epochs(): List<Epoch> = epochs.values.toList()

    fun epochOf(id: String): Epoch? = epochs[id]

    /** 跨纪元的事件序列（只增不改）。 */
    fun epochTransitions(): List<EpochTransition> = ArrayList(transitions)

    /** 账上已归属的变更行（全部经 [record] 落进来；未归属的老行只在读口 [CriteriaChange.rows] 出现）。 */
    fun changeRows(): List<CriteriaChange> = ArrayList(changes)

    /**
     * 判据③：给定 epochId 列出该纪装过的快照（按序号升序）。
     *
     * 查不到就返回空表——「没有这一纪」与「这一纪没装判据」是两件事，
     * 前者由 [epochOf] 答，这里不替它编结论。
     */
    fun snapshotsOf(epochId: String): List<CriteriaSnapshot> = snapshots[epochId]?.toList() ?: emptyList()

    /** 这一纪改了哪几条（派工单开头那句「换了哪几条」的落点）。 */
    fun changesOf(epochId: String): List<CriteriaChange> = changes.filter { it.epochId == epochId }

    /** 开第一纪。**只许开一次**：此后动判据是切纪，不是再开一纪。 */
    fun open(epoch: Epoch, snapshot: CriteriaSnapshot, atMs: Long): EpochResult = synchronized(this) {
        if (epochs.isNotEmpty()) {
            return@synchronized refused("已经开过纪 ${epochs.keys.first()}：此后动判据走 transition，不许再开一纪")
        }
        val blocked = shapeReject(epoch, snapshot, atMs)
        if (blocked != null) {
            return@synchronized refused(blocked)
        }
        install(epoch, snapshot)
        EpochResult(true, "开纪 ${epoch.id}（${epoch.label}）：${snapshot.criteria.size} 条判据")
    }

    /**
     * 切纪：从当前纪跳到 [to]，并记一行 [EpochTransition]。
     *
     * 拒绝有六处，任一命中都不落账、不改当前纪：没开过纪／缺裁定编号／形状不符（空表、指纹不符、
     * 时刻早于起点、日历纪与时刻不符）／纪元 id 重复／起点不递增／快照日历纪倒退。
     */
    fun transition(to: Epoch, snapshot: CriteriaSnapshot, rulingRef: String, atMs: Long): EpochResult =
        synchronized(this) {
            val from = current() ?: return@synchronized refused("还没开纪：第一纪走 open，transition 只负责旧纪→新纪")
            if (rulingRef.isBlank()) {
                return@synchronized refused("跨纪元必须带依据裁定编号（派工单②）：没有编号＝没有御批")
            }
            val blocked = shapeReject(to, snapshot, atMs)
            if (blocked != null) {
                return@synchronized refused(blocked)
            }
            if (epochs.containsKey(to.id)) {
                return@synchronized refused("纪元 id 已存在：${to.id}（一个 id 只许一张身份证；换判据是切一纪，不是修历史）")
            }
            if (to.startedAt <= from.startedAt) {
                return@synchronized refused(
                    "新纪 ${to.id} 的起点 ${to.startedAt} 不晚于当前纪 ${from.id} 的 ${from.startedAt}：纪要往后切。" +
                        "把旧表装回去是**回滚**（310 的 EpochRuntime.rollbackToPrevious：即时、不走审批），不是新纪元"
                )
            }
            // 起点递增管不住日历纪：开纪时把 `atMs` 报得很远、切纪时只往前挪一点，两张表的日历纪照样能倒着走。
            val last = snapshots.getValue(from.id).last()
            if (snapshot.epoch.ordinal < last.epoch.ordinal) {
                return@synchronized refused(
                    "快照的日历纪在倒退：这张表写的是 ${snapshot.epoch.label()}，" +
                        "${from.id} 装表时装的是 ${last.epoch.label()}。" +
                        "倒退是**回滚**（310 的 EpochRuntime.rollbackToPrevious：即时、不走审批），不是新纪元"
                )
            }
            install(to, snapshot)
            transitions.add(
                EpochTransition(
                    fromId = from.id,
                    toId = to.id,
                    reason = to.reason,
                    rulingRef = rulingRef,
                    atMs = atMs,
                )
            )
            EpochResult(true, "切纪 ${from.id} → ${to.id}（依据 $rulingRef：${to.reason}）")
        }

    /**
     * 记一条判据变更（判据②与④都咬在这里）。
     *
     * 顺序是固定的五步，先严后宽：
     * ① 空锚点先拒——不写锚点就想改判据，是在绕开②那道清单；
     * ② 撞不可进化清单即拒——**纪内改这三条＝拒**，且拒的是**记进变更账**这一步；
     * ③ 归属：`epochId` 必须写，且必须是当前纪；追不到纪的行只许从读口回来；
     * ④ 时刻自洽：行上的 `atMs` 不许早于本纪起点——把老行挂进当前纪就是假账；
     * ⑤ id 重复即拒——同一笔记两遍就是有人在改账。
     */
    fun record(change: CriteriaChange): EpochResult = synchronized(this) {
        val cur = current() ?: return@synchronized refused("判据变更必须有归属，但还没开纪：先 open")
        if (change.target.isBlank()) {
            return@synchronized refused("变更 ${change.changeId} 没有锚点：空锚点就是不可进化清单的逃逸口")
        }
        val hit = ImmutableCriteria.match(change.target)
        if (hit != null) {
            return@synchronized refused(
                "纪内不许改不可进化条「${hit.cn}」（法典 ${hit.lawRef}，锚点 ${change.target}）：" +
                    "这三条只有用户亲手改，写变更账也不行"
            )
        }
        if (!change.attributed()) {
            return@synchronized refused(
                "变更 ${change.changeId} 没写 epochId：新行必须归属当前纪 ${cur.id}" +
                    "（「${CriteriaChange.UNATTRIBUTED}」是老数据在读口的标注，不是写口开的洞）"
            )
        }
        if (change.epochId != cur.id) {
            return@synchronized refused(
                "变更要归当前纪元：此刻是 ${cur.id}，这条写的是 ${change.epochId}；" +
                    "要给下一纪记变更，先把纪切过去（transition）"
            )
        }
        if (change.atMs < cur.startedAt) {
            return@synchronized refused(
                "变更 ${change.changeId} 的时刻 ${change.atMs} 早于当前纪 ${cur.id} 的起点 ${cur.startedAt}：" +
                    "归属与时刻不符＝把老账挂进新纪（老行只许从读口回来）"
            )
        }
        if (changes.any { it.changeId == change.changeId }) {
            return@synchronized refused("变更 id 重复：${change.changeId}（同一笔记两遍＝改账）")
        }
        changes.add(change)
        EpochResult(true, "变更 ${change.changeId} 记入 ${cur.id}：判据 ${change.criterionId} 动 ${change.target}")
    }

    /** 形状层：指纹、判据条数、时刻与日历纪是否自洽。 */
    private fun shapeReject(epoch: Epoch, snapshot: CriteriaSnapshot, atMs: Long): String? {
        if (snapshot.criteria.isEmpty()) {
            return "空判据表不算一个纪元：${epoch.id}（把判据拆光不叫进化）"
        }
        if (epoch.criteriaHash != Epoch.criteriaHashOf(snapshot.criteria)) {
            return "纪元 ${epoch.id} 声明的指纹 ${epoch.criteriaHash} 与实际装入的快照不符：" +
                "账上算出来是 ${Epoch.criteriaHashOf(snapshot.criteria)}"
        }
        if (atMs < epoch.startedAt) {
            return "落账时刻 $atMs 早于纪元 ${epoch.id} 的起点 ${epoch.startedAt}：先有纪，才有所属的行"
        }
        val now = EpochId.of(atMs)
        if (now != snapshot.epoch) {
            return "快照的日历纪与落账时刻不符：$atMs 落在 ${now.label()}，这张表写的是 ${snapshot.epoch.label()}"
        }
        return null
    }

    private fun install(epoch: Epoch, snapshot: CriteriaSnapshot) {
        epochs[epoch.id] = epoch
        snapshots.getOrPut(epoch.id) { ArrayList() }.add(snapshot)
        currentId = epoch.id
    }

    private fun refused(reason: String): EpochResult = EpochResult(false, reason)

    /** 测试／进程内重置用；生产不调用（照 `EpochRuntime.reset` 的口径）。 */
    fun reset() = synchronized(this) {
        epochs.clear()
        snapshots.clear()
        transitions.clear()
        changes.clear()
        currentId = null
        Unit
    }
}
