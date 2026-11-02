// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百六十四批 · 纪元（Epoch）**实体化**：把「第几纪、什么时候起的、装的哪版判据、凭什么切的」
// 从人的回忆变成一个查得到的对象。
//
// **本文件持有什么**：历次纪元的身份证（[Epoch]）、跨纪元那一跳的账（[EpochTransition]）、
// 每纪装过的快照（[EpochRegistry.snapshotsOf]）、归属到纪的判据变更账（[CriteriaChange] 与
// [EpochRegistry.record]）。
// **本文件不持有什么**：不持周历计算、不持换表判定、不持回滚判定、不持三态、**不持「此刻生效的是
// 哪张表」的任何副本**。那几件全在 310 的 `CriteriaEpoch.kt`：[EpochId] 算纪、[CriteriaSnapshot]
// 装表、[EpochRuntime] 换表与回滚、[EpochState] 三态。本文件对「当前」只有一处动作——
// **读一次** [EpochRuntime.currentSnapshot]，拿来比对，不存下来。
//
// **周历、边界、回滚一律不还手算**（2026-11-02 用户裁定①）。这句话是给下一个人的路桩：
// 这里曾经有过 `open`／`transition`／`currentId` 三个东西，自己判「起点往不往后挪、日历纪倒没倒、
// 此刻是哪一纪」——那是 310 的第二套状态机，两条实现必然漂移（同仓已吃过「两个盒子各算一遍」的亏）。
// 现在只有一个写口 [EpochRegistry.append]：它只核**账目**，「装的不是此刻生效那张表 ⇒ 拒落账」。
// 想让一张表生效，走 [EpochRuntime.swap]；想退回旧表，走 [EpochRuntime.rollbackToPrevious]。
//
// 分工跟 310 划清，**不建第二套纪元**：
//   · 310 = 「此刻生效的是哪张表」（周历＋换表写口＋三态账）；本文件 = 「历次纪元的身份证与账」；
//   · 快照沿用 [CriteriaSnapshot]、日历纪沿用 [EpochId]、不可进化清单沿用 [ImmutableCriteria]，
//     这里一条都不重定义；
//   · **寻址同源**（裁定②）：机器那一列就是 [EpochId.ordinal] 的十进制（[Epoch.id]），
//     人读那一列就是 [EpochId.label] 那句 `E1（周一 … 起）`（[Epoch.label]），两列并存；
//     [CriteriaChange] 的纪列同源。账是给人看的，不许上面只有 2964 这种裸数字。
//   · 接线（谁来调用本文件）归第三百八十批。本批照 310 的口径只出**对象＋闸＋测试＋账**，
//     在线路径一个字不读这里——那条禁令由 310 的 `tools/check-criteria-gates.py` 尺一钉着
//     （本批把 [EpochRegistry]／[CriteriaChange]／[EpochTransition] 三个符号也加进那张尺）。
//
// 零 Android／零 IO／零时钟：`startedAt` 与 `atMs` 都由调用方给（同 310——自己读一次时钟，
// 同一张表就会在午夜前后给出两个纪元号）。[CriteriaChange.wire]／[CriteriaChange.rows] 只出与读
// **文本形态**，落不落盘、落在哪个文件都不归本文件管。
package com.example.zhengdao.baopu.policy

import java.security.MessageDigest

/**
 * 一个纪元：派工单那五个字段（id／label／startedAt／criteriaHash／reason），不加不减。
 *
 * `id` 与 `label` 不是另起炉灶：它们是 310 的 [EpochId] 的两副面孔——
 * `id` ＝ [EpochId.ordinal] 的十进制（机读、寻址用），`label` ＝ [EpochId.label] 那句人读的话。
 * 所以本文件不算周历，也没有「第二个纪元号」可以漂。建对象走 [of]，两列由 [EpochId] 一次给全。
 *
 * 构造期自检（任何一条不满足**根本建不出来**）：
 * - `id` 必须是非负十进制（挂 ordinal），`label`、`reason` 不许空白——`reason` 就是
 *   判据①「原因必填，空拒」落的地方；
 * - `startedAt` 不许为负，且由调用方给（本包零时钟）；
 * - `criteriaHash` 必须是 64 位小写十六进制——只认 [criteriaHashOf] 算出来的形态，
 *   手填一串「abc」想在账上冒充某版判据，是建不出对象的。
 */
internal data class Epoch(
    /** 机器寻址：310 的 [EpochId.ordinal] 的十进制（同源，不许自己起名）。 */
    val id: String,
    /** 给人看的那一句（`E1（周一 … 起）`）：账与卡面上出现的就是它。 */
    val label: String,
    /** 本纪起点（毫秒，调用方给）。 */
    val startedAt: Long,
    /** 本纪判据集合的指纹：见 [criteriaHashOf]。 */
    val criteriaHash: String,
    /** 凭什么切这一纪（必填）。 */
    val reason: String,
) {
    init {
        require(ORDINAL.matches(id)) {
            "纪元 id 必须是 310 日历纪的序号（非负十进制），实测「$id」——寻址同源，不许自己起一个"
        }
        require(label.isNotBlank()) { "纪元 label 不能空——机读用 ordinal、人读用 label，两列都得有" }
        require(startedAt >= 0L) { "纪元起始时刻由调用方给（本包零时钟），且不许为负" }
        require(HASH_HEX.matches(criteriaHash)) {
            "criteriaHash 必须是 64 位小写十六进制 sha256，实测「$criteriaHash」——" +
                "要建纪元请用 Epoch.of(...)，指纹由判据集合算，不许手填"
        }
        require(reason.isNotBlank()) { "切纪元必须写原因（第三百六十四批判据①：空原因拒）" }
    }

    /** 寻址的那个数（id 已由构造期保证可解析）。 */
    fun ordinal(): Long = id.toLong()

    companion object {
        /** sha256 的十六进制形态：整串都要，缺一位就不是那版判据。 */
        private val HASH_HEX = Regex("^[0-9a-f]{64}$")

        /** 日历纪序号的形态：非负十进制。负数与「E1」这种人名都不许当 id。 */
        private val ORDINAL = Regex("^\\d+$")

        /** 同一个纪元号的机器面孔（变更账、身份证、快照三处都用它，出处只有一个）。 */
        fun idOf(epoch: EpochId): String = epoch.ordinal.toString()

        /**
         * 建纪元唯一正路：id 与 label 都从 310 的 [EpochId] 来，指纹从传进来的判据集合算出来，
         * 三个都不给手填的口。
         *
         * 这里刻意不收 [CriteriaSnapshot] 而是收它的 `criteria`——310 的快照构造期会替每条判据
         * 再核一遍不可进化清单，所以「先建表、再拿表的条目建纪元」这条顺序本身就带那道闸。
         */
        fun of(
            epoch: EpochId,
            startedAt: Long,
            criteria: List<CriterionEntry>,
            reason: String,
        ): Epoch {
            require(criteria.isNotEmpty()) { "空判据表不算一个纪元：${epoch.label()}（把判据拆光不叫进化）" }
            return Epoch(
                id = idOf(epoch),
                label = epoch.label(),
                startedAt = startedAt,
                criteriaHash = criteriaHashOf(criteria),
                reason = reason,
            )
        }

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
 * 跨纪元的一跳（派工单②）：旧纪 → 新纪 ＋ 原因 ＋ 依据裁定编号。
 *
 * 只增不改——它是一次**事件**，不是可以随时重算的读数。`reason` 取自新纪元的 `reason`，
 * 不在这里再收一遍，免得出现「同一跳两个原因」。
 *
 * 机读两列（`fromId`／`toId` ＝ ordinal 十进制）与人读两列（`fromLabel`／`toLabel`）并存：
 * 这一跳归谁、跳到谁，账上要能被人读出来，不许只剩两个数。
 */
internal data class EpochTransition(
    val fromId: String,
    val toId: String,
    /** 旧纪的人读名（310 的 `EpochId.label()`）。 */
    val fromLabel: String,
    /** 新纪的人读名。 */
    val toLabel: String,
    val reason: String,
    /** 依据的裁定编号（御案／派工单上那句「谁批准的」）。 */
    val rulingRef: String,
    val atMs: Long,
) {
    init {
        require(fromId.isNotBlank()) { "transition 缺旧纪元 id：不知道从哪一跳来的，就不叫跨纪元" }
        require(toId.isNotBlank()) { "transition 缺新纪元 id" }
        require(fromId != toId) { "transition 的旧新 id 相同（$fromId）：原地踏步不叫切纪元" }
        require(fromLabel.isNotBlank() && toLabel.isNotBlank()) { "transition 缺人读的纪名——账是给人看的" }
        require(reason.isNotBlank()) { "切纪元必须写原因（判据①：空原因拒）" }
        require(rulingRef.isNotBlank()) { "跨纪元必须带依据裁定编号：没有编号＝没有御批" }
        require(atMs >= 0L) { "跳转时刻由调用方给（本包零时钟），且不许为负" }
    }

    /** 落盘形态：`旧→新|旧名→新名|原因|裁定编号|时刻`（机读列在前，人读列紧跟）。 */
    fun wire(): String = "$fromId→$toId|$fromLabel→$toLabel|$reason|$rulingRef|$atMs"
}

/**
 * 变更账的一行（判据④）：每行带纪元归属，老数据允许 `epochId = null` ＝未归属。
 *
 * 写口只有一条：[EpochRegistry.record]（那里没写归属 ⇒ 拒）。
 * 本类型的 [decode]／[rows] 是**读口**：把已经落在账上的文本读回来。两条不许串——
 * 「读时允许 null」是给老数据的出路，不是给新数据开的逃逸口。
 *
 * 纪元的两副面孔都在这一行上：`epochId`（机读＝ordinal 十进制）与 `epochLabel`（人读），
 * 与 [Epoch] 同源；追不到纪的老行，第二列空、第三列写 [UNATTRIBUTED]。
 */
internal data class CriteriaChange(
    val changeId: String,
    /** 归属的纪元（310 的日历纪序号）；`null` ＝这笔变更追不到纪元（老账行）。 */
    val epochId: String?,
    /** 归属的人读名；追不到就明写「未归属」，不假装属于当前纪。 */
    val epochLabel: String,
    /** 被改的判据 id。 */
    val criterionId: String,
    /** 它动的生产锚点：`路径#符号`（与 310 的清单同一形态）。 */
    val target: String,
    val atMs: Long,
) {
    init {
        require(changeId.isNotBlank()) { "变更行必须有 id：没有 id 就没法判重（同一笔记两遍＝改账）" }
        require(criterionId.isNotBlank()) { "变更行必须有判据 id：不知道改的是哪条判据，就不叫变更" }
        require(atMs >= 0L) { "变更时刻由调用方给（本包零时钟），且不许为负" }
    }

    /** 归属有没有追得到。 */
    fun attributed(): Boolean = epochId?.isNotBlank() == true

    /** 落盘形态：`变更id|纪元序号|纪元名|判据id|锚点|时刻`；没归属时第二列留空、第三列「未归属」。 */
    fun wire(): String = "$changeId|${epochId ?: ""}|$epochLabel|$criterionId|$target|$atMs"

    companion object {
        /** 判据④那句话：老数据不猜、不补，标出来。 */
        const val UNATTRIBUTED = "未归属"

        private const val COLUMNS = 6
        private const val LEGACY_COLUMNS = 4

        /**
         * 读一行账；读不回一个合法行就返回 null（坏行跳过不崩，与 343 变更账同口径）。
         *
         * 两种形态都要认：
         * - **本批**：六列 `变更id|纪元序号|纪元名|判据id|锚点|时刻`，第二列可以空着＝没归属；
         * - **本批之前**（老数据）：四列 `变更id|判据id|锚点|时刻`，压根没有纪元列 ⇒ `epochId = null`。
         *
         * 老行一律落成 [UNATTRIBUTED]：读侧不替它补纪（那是假账），也不许反过来给写口开洞。
         */
        fun decode(line: String): CriteriaChange? {
            val cols = line.trim().split("|")
            return when (cols.size) {
                COLUMNS -> fromColumns(cols)
                LEGACY_COLUMNS -> fromLegacyColumns(cols)
                else -> null
            }
        }

        /** 六列：机读列可以空着（＝没归属），但空了人读列必须写 [UNATTRIBUTED]，两列不许各说一套。 */
        private fun fromColumns(cols: List<String>): CriteriaChange? {
            val atMs = cols[5].toLongOrNull() ?: return null
            val epochId = cols[1]
            val givenLabel = cols[2]
            if (epochId.isNotBlank() && givenLabel.isBlank()) {
                return null
            }
            val unattributed = epochId.isBlank()
            return build(
                changeId = cols[0],
                epochId = if (unattributed) null else epochId,
                epochLabel = if (unattributed) UNATTRIBUTED else givenLabel,
                criterionId = cols[3],
                target = cols[4],
                atMs = atMs,
            )
        }

        /** 四列（本批之前的老账）：压根没有纪元列 ⇒ 归属追不到，明写 [UNATTRIBUTED]，不替它补纪。 */
        private fun fromLegacyColumns(cols: List<String>): CriteriaChange? {
            val atMs = cols[3].toLongOrNull() ?: return null
            return build(
                changeId = cols[0],
                epochId = null,
                epochLabel = UNATTRIBUTED,
                criterionId = cols[1],
                target = cols[2],
                atMs = atMs,
            )
        }

        /** 三个非空字段一起验（`changeId` 还要能判重）；空了就当坏行，不返回半截行。 */
        private fun build(
            changeId: String,
            epochId: String?,
            epochLabel: String,
            criterionId: String,
            target: String,
            atMs: Long,
        ): CriteriaChange? {
            if (changeId.isBlank() || criterionId.isBlank() || target.isBlank()) {
                return null
            }
            return CriteriaChange(
                changeId = changeId,
                epochId = epochId,
                epochLabel = epochLabel,
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
 * **写口只有两条，各管一件事**：
 * - [append]：把一个纪元连同它装的快照落进账里（派工单②「切纪」＝再 append 下一纪）；
 * - [record]：记一条判据变更，归属到纪。
 *
 * [append] **只核账目**：原因必填（[Epoch] 构造期）／裁定编号必填／指纹绑对象／硬闸锚点拒／
 * 纪元 id 重复拒，再加一条「读一次 [EpochRuntime.currentSnapshot]：装的不是此刻生效那张表 ⇒ 拒落账」。
 * **周历、边界、回滚一律不还手算**——那三件是 310 的 [EpochRuntime] 在 `swap`／
 * `rollbackToPrevious` 里判的，本文件既不重算也不缓存结果；
 * 「不许在这里再存一份 current」就是这条 KDoc 存在的原因。
 */
internal object EpochRegistry {

    /** 按落账顺序存的身份证（key＝纪元序号的十进制，见 [Epoch.idOf]）。 */
    private val epochs = LinkedHashMap<String, Epoch>()

    /** 每纪装过的快照（按落账顺序）——判据③「给定 epochId 可列快照」就是这张表。 */
    private val snapshots = LinkedHashMap<String, MutableList<CriteriaSnapshot>>()

    private val transitions = ArrayList<EpochTransition>()

    private val changes = ArrayList<CriteriaChange>()

    /** 历次纪元（落账顺序）。 */
    fun epochs(): List<Epoch> = epochs.values.toList()

    fun epochOf(id: String): Epoch? = epochs[id]

    /** 跨纪元的事件序列（只增不改）。 */
    fun epochTransitions(): List<EpochTransition> = ArrayList(transitions)

    /** 账上已归属的变更行（全部经 [record] 落进来；未归属的老行只在读口 [CriteriaChange.rows] 出现）。 */
    fun changeRows(): List<CriteriaChange> = ArrayList(changes)

    /**
     * 判据③：给定 epochId 列出该纪装过的快照。
     *
     * 查不到就返回空表——「没有这一纪」与「这一纪没装判据」是两件事，
     * 前者由 [epochOf] 答，这里不替它编结论。
     */
    fun snapshotsOf(epochId: String): List<CriteriaSnapshot> = snapshots[epochId]?.toList() ?: emptyList()

    /** 这一纪改了哪几条（派工单开头那句「换了哪几条」的落点）。 */
    fun changesOf(epochId: String): List<CriteriaChange> = changes.filter { it.epochId == epochId }

    /**
     * 落账：把 [epoch] 连同它装的 [snapshot] 记进纪元册（唯一的历史写口）。
     *
     * 五步，全是**账目**：① 依据裁定编号必填；② 读一次 310 的当前生效表——不是它 ⇒ 拒；
     * ③ 身份证与表对不对得上（id／label 同源、指纹绑对象、硬闸锚点）；④ 同一纪不许记两遍；
     * ⑤ 都过了才落，并从上一纪补一行 [EpochTransition]（第一纪没有「那一跳」，所以没有跳账）。
     *
     * 拒了的现场**一行都不落**：`epochs()`／`snapshotsOf()`／`epochTransitions()` 都不许动。
     */
    fun append(epoch: Epoch, snapshot: CriteriaSnapshot, rulingRef: String, atMs: Long): EpochResult =
        synchronized(this) {
            if (rulingRef.isBlank()) {
                return@synchronized refused("落账必须有依据裁定编号（派工单②）：没有编号＝没有御批，凭什么记这一纪")
            }
            val offBoundary = effectiveReject(snapshot)
            if (offBoundary != null) {
                return@synchronized refused(offBoundary)
            }
            val unfiled = ledgerReject(epoch, snapshot)
            if (unfiled != null) {
                return@synchronized refused(unfiled)
            }
            val from = epochs.values.lastOrNull()
            epochs[epoch.id] = epoch
            snapshots.getOrPut(epoch.id) { ArrayList() }.add(snapshot)
            if (from != null) {
                transitions.add(
                    EpochTransition(
                        fromId = from.id,
                        toId = epoch.id,
                        fromLabel = from.label,
                        toLabel = epoch.label,
                        reason = epoch.reason,
                        rulingRef = rulingRef,
                        atMs = atMs,
                    )
                )
            }
            val hop = if (from == null) "落纪 ${epoch.label}" else "切纪 ${from.label} → ${epoch.label}"
            EpochResult(true, "$hop：${snapshot.criteria.size} 条判据，依据 $rulingRef（${epoch.reason}）")
        }

    /**
     * 记一条判据变更（判据②与④都咬在这里）。
     *
     * 顺序是固定的，先严后宽：① 空锚点先拒——不写锚点就想改判据，是在绕开②那道清单；
     * ② 撞不可进化清单即拒——**纪内改这三条＝拒**，且拒的是**记进变更账**这一步；
     * ③ 归属（见 [attributionReject]）：必须有纪、必须是 310 此刻生效的那一纪、且那一纪已在册；
     * ④ id 重复即拒——同一笔记两遍就是有人在改账。
     */
    fun record(change: CriteriaChange): EpochResult = synchronized(this) {
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
        val unowned = attributionReject(change)
        if (unowned != null) {
            return@synchronized refused(unowned)
        }
        if (changes.any { it.changeId == change.changeId }) {
            return@synchronized refused("变更 id 重复：${change.changeId}（同一笔记两遍＝改账）")
        }
        changes.add(change)
        EpochResult(true, "变更 ${change.changeId} 记入 ${change.epochLabel}：判据 ${change.criterionId} 动 ${change.target}")
    }

    /**
     * 那道「读一次」的闸：本文件对「此刻生效的是哪张表」的唯一动作。
     *
     * 拿不到（生产还没换过表）也是拒——**不许在这里存第二份 current**，也不许自己造一张「当作生效」。
     * 周历、边界、回滚的判定全在 [EpochRuntime.swap] 与 [EpochRuntime.rollbackToPrevious] 里，
     * 这里只问一句「你要落账的这张表，是不是此刻生效的那一张」。
     */
    private fun effectiveReject(snapshot: CriteriaSnapshot): String? {
        val effective = EpochRuntime.currentSnapshot()
            ?: return "310 的 EpochRuntime.currentSnapshot() 是空的（这张表还没生效过）：没有「此刻」可比，不落账。" +
                "先走 EpochRuntime.swap；不许在纪元册里再存一份 current"
        if (snapshot != effective) {
            return "append 装的不是此刻生效那张表：账上要装 ${snapshot.epoch.label()} 的第 ${snapshot.seq} 号，" +
                "310 现在跑的是 ${effective.epoch.label()} 的第 ${effective.seq} 号。" +
                "换表走 EpochRuntime.swap（周历与边界归它），倒退走 rollbackToPrevious（那叫回滚，不叫新纪元）"
        }
        return null
    }

    /** 账目层：身份证与表对不对得上，以及这一纪是不是已经记过。 */
    private fun ledgerReject(epoch: Epoch, snapshot: CriteriaSnapshot): String? {
        if (epochs.containsKey(epoch.id)) {
            return "纪元 ${epoch.label} 已经记过（id ${epoch.id} 一张身份证）：换判据是切下一纪，不是修历史"
        }
        if (epoch.id != Epoch.idOf(snapshot.epoch)) {
            return "身份证与表不同源：这证写的是纪 ${epoch.id}，这张表的日历纪是 ${snapshot.epoch.ordinal}（${snapshot.epoch.label()}）"
        }
        if (epoch.label != snapshot.epoch.label()) {
            return "身份证的人读名与表不符：这证写「${epoch.label}」，那张表是「${snapshot.epoch.label()}」"
        }
        if (epoch.criteriaHash != Epoch.criteriaHashOf(snapshot.criteria)) {
            return "纪元 ${epoch.label} 声明的指纹 ${epoch.criteriaHash} 与实际装入的快照不符：" +
                "账上算出来是 ${Epoch.criteriaHashOf(snapshot.criteria)}"
        }
        val hit = snapshot.criteria.firstOrNull { ImmutableCriteria.match(it.target) != null }
        if (hit != null) {
            return "这张表要装不可进化条「${hit.id}」的锚点（${hit.target}）：硬闸不许随纪元换装，落账这一步就拒"
        }
        return null
    }

    /**
     * 归属层（判据④）：变更必须归到「310 此刻生效的那一纪」，且那一纪已在册。
     *
     * 「此刻是哪一纪」这里不自己算——读 [EpochRuntime.currentSnapshot] 的日历纪；
     * 起点只从在册的 [Epoch.startedAt] 拿（那是当初落账时调用方给的那个数，不是重算出来的边界）。
     */
    private fun attributionReject(change: CriteriaChange): String? {
        if (!change.attributed()) {
            return "变更 ${change.changeId} 没写纪元归属：新行必须归属此刻生效的那一纪" +
                "（「${CriteriaChange.UNATTRIBUTED}」是老数据在读口的标注，不是写口开的洞）"
        }
        val effective = EpochRuntime.currentSnapshot()
            ?: return "310 还没有生效表：没有「此刻」，变更无处归属。先 EpochRuntime.swap，别在纪元册里存副本"
        if (change.epochId != Epoch.idOf(effective.epoch)) {
            return "变更要归此刻生效的那一纪：此刻是 ${effective.epoch.label()}，这条写的是 ${change.epochId}；" +
                "要给下一纪记变更，先 swap 换表、再 append 落那一纪"
        }
        val owner = epochs[change.epochId]
            ?: return "变更要归属的纪 ${change.epochId} 还没在册上落账：先 EpochRegistry.append，再记它的变更"
        if (change.epochLabel != owner.label) {
            return "变更行的人读纪名「${change.epochLabel}」与在册的「${owner.label}」不符：机读与人读各说一套＝假账"
        }
        if (change.atMs < owner.startedAt) {
            return "变更 ${change.changeId} 的时刻 ${change.atMs} 早于 ${owner.label} 的起点 ${owner.startedAt}：" +
                "归属与时刻不符＝把老账挂进新纪（老行只许从读口回来）"
        }
        return null
    }

    private fun refused(reason: String): EpochResult = EpochResult(false, reason)

    /** 测试／进程内重置用；生产不调用（照 `EpochRuntime.reset` 的口径）。 */
    fun reset() = synchronized(this) {
        epochs.clear()
        snapshots.clear()
        transitions.clear()
        changes.clear()
        Unit
    }
}
