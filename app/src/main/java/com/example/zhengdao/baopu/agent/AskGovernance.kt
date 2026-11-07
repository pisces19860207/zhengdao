// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百九十九批 · 打扰治理（＝487 授权档 ＋ 489 在环快判的档位半边）。
//
// 这一文件管三件事，一件不多：
//   A 档位：严格（现状）／标准（缺省：根内 R1 不问）／放权（根内 R1/R2 不问，
//          前提「进回收站＋可回滚」）。硬三条任何档都不许跳：不可逆且无回收站 ⇒ 问；
//          外发/花钱/账号（R3/必问/未分类）⇒ 问；原件区/密码框/系统与凭据目录 ⇒ 矩阵硬拒
//          （在 [GuardPipeline] 里发生，本文件不碰）。
//   B 留证：被档位自动放过的动作**仍记账**（"先做后审"的审就是这本账）；切档落账；
//          弹/不弹每次一行（487 弹卡权归属④）。
//   C 读数：体检一行（授权档·今日放过 N·硬拒 M·误拦率分场景）＋误拦申诉入口。
//
// 三条纪律：
//   1. **判定纯函数**：[AskTierRules.decide] 零 IO、零时钟、不跨轮（C 段判据⑩的靶子）；
//      账是判完之后另一步落（留痕不是闸门，208 同一口径）。
//   2. **不改矩阵**：风险档位、硬拒、必问的判定全在 `RiskMatrix`/`RiskGate`，本文件只读
//      [RiskVerdict] 的档位与标志，一个字段不回写。
//   3. **左相优先**：调解器的压卡闸（406）在引擎侧，档位闸在管线侧，两把尺各管各的；
//      压卡落账（who=LEFT）也从这里落（D 段③）。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.FilePolicy
import com.example.zhengdao.baopu.RiskClass
import com.example.zhengdao.baopu.RiskVerdict
import com.example.zhengdao.baopu.backupSkippedFor
import com.example.zhengdao.core.PathGuard
import com.example.zhengdao.core.RunLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 授权档（A 段）。`DEFAULT`＝标准（⑥：新装读到标准）。 */
enum class AskTier(val wire: String, val cn: String) {
    /** 现状：R1 CONFIRM/R2/R3 一律照旧问。 */
    STRICT("strict", "严格"),

    /** 缺省：根内可回滚 R1 不问（先做后审）；R2 问一次（租约 467 另说）。 */
    STANDARD("standard", "标准"),

    /** 根内可回滚 R1/R2 都不问；R3/必问/未分类/根外/不可逆照旧问。 */
    DELEGATED("delegated", "放权"),
    ;

    companion object {
        val DEFAULT: AskTier = STANDARD

        /** 键 → 档位（认不出返回 null：SP 里的旧值不许猜）。 */
        fun ofWire(wire: String): AskTier? = entries.firstOrNull { it.wire == wire }
    }
}

/** 档位判定的一次事实（全部由调用方给：判定零 IO）。 */
data class AskTierFacts(
    val tier: AskTier,
    /** 矩阵判出的风险档（null＝表外工具：任何档位都不豁免）。 */
    val risk: RiskClass?,
    /** 矩阵点名的必问（R3 语义、批量、用户点名…）：任何档位都问。 */
    val mustAsk: Boolean,
    /** 未分类（fail-closed）：任何档位都问。 */
    val unclassified: Boolean,
    /** 目标全部在工作区根内（无路径目标的 CONFIRM 工具也算根内）。 */
    val inRoot: Boolean,
    /** 可回滚：进回收站（delete 非彻底删）或有备份（写/改的旧版在 备份/），或纯新建文件。 */
    val reversible: Boolean,
) {
    /** 硬线②：R3（外发/花钱/账号）、矩阵必问、未分类——任何档位都问。 */
    val hardAsk: Boolean get() = mustAsk || unclassified || risk == RiskClass.R3
}

/** 档位判定的结论（[basis] 是落账依据那一列）。 */
data class AskTierDecision(
    /** true＝档位放行（不弹卡，先做后审）；false＝照旧弹。 */
    val pass: Boolean,
    val basis: String,
)

/**
 * 档位判定（**纯函数**，C 段判据⑩的判定段：p95 <50ms、无 IO、不跨轮）。
 *
 * 顺序即法条：先硬线（任何档都不许跳），再"可回滚"资格，最后才轮到档位放行——
 * 放权档的「根内 R1/R2 不问」带着括号原文「（进回收站＋可回滚）」，不是无条件放。
 */
internal object AskTierRules {

    fun decide(f: AskTierFacts): AskTierDecision = when {
        f.hardAsk -> AskTierDecision(false, "硬线：必问/外发花钱账号/未分类，任何档位都问")
        !f.inRoot -> AskTierDecision(false, "目标在工作区外：档位不豁免根外动作")
        !f.reversible -> AskTierDecision(false, "不可逆且无回收站/备份：任何档位都问")
        f.risk == null -> AskTierDecision(false, "表外工具：档位不豁免")
        f.tier == AskTier.STRICT -> AskTierDecision(false, "严格档：按现状问")
        f.tier == AskTier.STANDARD && f.risk == RiskClass.R2 ->
            AskTierDecision(false, "标准档：R2 问一次（可给任务级授权）")
        f.tier == AskTier.STANDARD -> AskTierDecision(true, "标准档：根内可回滚 R1 不问，先做后审")
        else -> AskTierDecision(true, "放权档：根内可回滚 R1/R2 不问（回收站/备份可回滚）")
    }
}

/**
 * 「这一步可不可回滚」的事实（A 段放行资格的唯一口径）。
 *
 * 三类可回滚（与工具自己的回执纪律一一对应）：
 * - **进回收站**：delete_path 缺省（`回收站/<时刻>-<原名>`，同分区 rename 原子）；
 *   `permanent=true`/`empty_trash=true` 是真删 ⇒ 不可逆；
 * - **有备份**：write_file（overwrite 先备份旧版）/edit_file/convert_encoding
 *   （先备份原始字节）——旧文件超过 [FilePolicy.BACKUP_MAX_BYTES] 不备份 ⇒ 不可逆；
 * - **纯新建**：copy_path/image_ops/卡片图表类只写新文件，目标已存在 ⇒ 不算。
 *
 * 其余（move/bulk_move/organize/import_data/浏览器动作…源会消失或语义在盘外）一律
 * 不可逆——宁可多问一张，不冒充"可回滚"。
 */
internal object AskReversibility {

    data class Fact(val reversible: Boolean, val why: String)

    private val CREATE_ONLY = setOf(
        "copy_path", "image_ops", "card_build", "draw_chart", "draw_table",
        "render_artifact", "write_skill", "append_persona", "bulk_copy",
    )

    /**
     * @param targets 这次要写的目标（绝对路径；调用方从 [GuardPipeline.writeTargets] 取）
     * @param root 工作区根（根内性由调用方判好传 [AskTierFacts.inRoot]，这里只看存在与大小）
     */
    fun of(tool: String, args: JSONObject, targets: List<File>): Fact = when {
        tool == "delete_path" && args.optBoolean("empty_trash") ->
            Fact(false, "清空回收站：数量未知且必然不可逆")
        tool == "delete_path" && args.optBoolean("permanent") ->
            Fact(false, "彻底删（permanent=true）：不进回收站")
        tool == "delete_path" && targets.isNotEmpty() ->
            Fact(true, "默认进回收站，可捞回")
        tool == "write_file" || tool == "edit_file" || tool == "convert_encoding" ->
            backupFact(tool, targets)
        tool in CREATE_ONLY && targets.isNotEmpty() ->
            newFileFact(tool, targets)
        else -> Fact(false, "该动作没有回收站/备份语义")
    }

    /** 备份类：旧文件超限不备份 ⇒ 不可逆（287 的口径，这里不抄第二遍）。 */
    private fun backupFact(tool: String, targets: List<File>): Fact {
        val t = targets.firstOrNull() ?: return Fact(false, "取不到写目标")
        if (!t.exists()) {
            // write_file 对不存在目标是新建（可回滚：删掉即还原）；edit/convert 没东西可改
            return if (tool == "write_file") Fact(true, "新建文件") else Fact(false, "目标不存在")
        }
        return if (backupSkippedFor(t.length())) {
            Fact(false, "旧文件超限不备份（${FilePolicy.BACKUP_MAX_BYTES} 字节上限）")
        } else {
            Fact(true, "旧版先备份到 备份/，可回滚")
        }
    }

    /** 纯新建类：目标已在盘上 ⇒ 这次会覆盖/混写，不算可回滚。 */
    private fun newFileFact(tool: String, targets: List<File>): Fact =
        if (targets.any { it.exists() }) Fact(false, "目标已存在，会覆盖") else Fact(true, "只写新文件")
}

/** 治理账的一行（kind/who/basis 的词表见 [AskGovernance] 各 note 入口）。 */
data class AskGovRow(
    val id: String,
    val atMs: Long,
    val kind: String,
    val who: String,
    val basis: String,
    val tool: String,
    val scene: String,
    val detail: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("v", AskGovLedger.VERSION)
        .put("id", id)
        .put("at", atMs)
        .put("kind", kind)
        .put("who", who)
        .put("basis", basis)
        .put("tool", tool)
        .put("scene", scene)
        .put("detail", detail)

    companion object {
        fun parse(raw: String): AskGovRow? {
            val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            val id = o.optString("id")
            val kind = o.optString("kind")
            if (id.isEmpty() || kind.isEmpty()) return null
            return AskGovRow(
                id = id,
                atMs = o.optLong("at", 0L),
                kind = kind,
                who = o.optString("who"),
                basis = o.optString("basis"),
                tool = o.optString("tool"),
                scene = o.optString("scene"),
                detail = o.optString("detail"),
            )
        }
    }
}

/**
 * 打扰治理账（`打扰治理.jsonl`，与 208 同一棵目录）。
 *
 * 为什么独立一本而不是复用 208：208 的 kind 是闭集（红线：不加项），而这里要记的是
 * 「问不问的决定」——与「做没做成」分属两本（292 P1 的"问过≠做过"同款理由）。
 * 内存兜底 ＋ 盘上回放（与 OpinionWiring 同口径）；行是追加式，只增不改。
 */
internal object AskGovLedger {

    const val FILE_NAME = "打扰治理.jsonl"
    const val VERSION = 1

    /** kind 词表（稳定机器键：改了等于让老账读不出）。 */
    const val KIND_PASS = "pass"
    const val KIND_ASK = "ask"
    const val KIND_CARD = "card"
    const val KIND_REFUSE = "refuse"
    const val KIND_SUPPRESS = "suppress"
    const val KIND_TIER_SWITCH = "tier_switch"
    const val KIND_COMPRESS = "compress"
    const val KIND_SELF_CHECK = "self_check"
    const val KIND_REPEATED = "repeated"
    const val KIND_VIOLATION = "violation"
    const val KIND_APPEAL = "appeal"
    const val KIND_PROFILE = "profile"

    /** who 词表：tier=授权档闸；LEFT=左相（调解器）；budget/在环闸；intake=先自查闸；user=人。 */
    const val WHO_TIER = "tier"
    const val WHO_LEFT = "LEFT"

    /** 场景（JEV 对齐：误拦率按场景分开统计，不许混成一个总指标）。 */
    const val SCENE_OBJECTIVE = "objective"
    const val SCENE_SUBJECTIVE = "subjective"

    private const val MEMORY_CAP = 300

    private val memory = ArrayDeque<AskGovRow>()
    private var seq = 0

    /** 落一行；没装配落点（纯 JVM 单测）⇒ 只进内存。返回有没有真落上。 */
    @Synchronized
    fun append(
        kind: String,
        who: String,
        basis: String,
        tool: String = "",
        scene: String = SCENE_OBJECTIVE,
        detail: String = "",
        atMs: Long = System.currentTimeMillis(),
    ): AskGovRow {
        seq += 1
        val row = AskGovRow(
            id = "ag:$atMs:$seq",
            atMs = atMs,
            kind = kind,
            who = who,
            basis = basis,
            tool = tool,
            scene = scene,
            detail = detail,
        )
        memory.addLast(row)
        while (memory.size > MEMORY_CAP) memory.removeFirst()
        val root = DecisionLedger.installedRoot() ?: return row
        runCatching {
            val f = fileIn(root)
            f.parentFile?.mkdirs()
            f.appendText(row.toJson().toString() + "\n", Charsets.UTF_8)
        }.onFailure {
            RunLog.log("抱朴治理：账没落上（${row.id}）——${it.javaClass.simpleName}")
        }
        return row
    }

    /** 回放（盘上 ＋ 内存，去重按 id，新→旧）。 */
    @Synchronized
    fun replay(): List<AskGovRow> {
        val fromFile = DecisionLedger.installedRoot()?.let { root ->
            runCatching {
                val f = fileIn(root)
                if (!f.isFile) emptyList() else f.readLines(Charsets.UTF_8).mapNotNull(AskGovRow::parse)
            }.getOrDefault(emptyList())
        }.orEmpty()
        return (fromFile + memory.toList()).distinctBy { it.id }.sortedByDescending { it.atMs }
    }

    fun fileIn(globalRoot: File): File = File(globalRoot, FILE_NAME)

    /** 清空（测试专用：谁用谁还）。 */
    @Synchronized
    fun resetForTest() {
        memory.clear()
        seq = 0
    }
}

/**
 * 授权档的进程内状态 ＋ 治理读数（单例）。
 *
 * 档位为什么是进程内单例＋持久化钩子：管线（零 Android）经 `() -> AskTier` 读它；
 * 持久化由装配层挂 [tierSink]（SP），JVM 单测不挂钩子直接 [setTier]。
 * 切档必须落账（判据④）；恢复（[restore]）是装配层读 SP 的回填，**不落账**——
 * 那不是一次"切换"，是把上次切换的结果装回来。
 */
internal object AskGovernance {

    /** 硬拒一句话原因的上限（C 段：≤80 字）。 */
    const val HARD_REJECT_MAX = 80

    @Volatile
    var currentTier: AskTier = AskTier.DEFAULT
        private set

    /** 持久化钩子（装配层挂；null＝不持久化，例如 JVM 单测）。 */
    @Volatile
    var tierSink: ((AskTier) -> Unit)? = null

    /**
     * 切档（人改；预算与档位只能人改）。切档落一行（谁/何时/从哪档到哪档）。
     * @return 落成的那行账（同档切换也照记——"确认了现在的档位"也是一次人操作）。
     */
    fun setTier(
        to: AskTier,
        who: String = "user",
        atMs: Long = System.currentTimeMillis(),
    ): AskGovRow {
        val from = currentTier
        currentTier = to
        tierSink?.invoke(to)
        return AskGovLedger.append(
            kind = AskGovLedger.KIND_TIER_SWITCH,
            who = who,
            basis = "授权档 ${from.cn}→${to.cn}",
            detail = "who=$who",
            atMs = atMs,
        )
    }

    /** 装配层回填（不落账：见类 KDoc）。 */
    fun restore(tier: AskTier) {
        currentTier = tier
    }

    /** 档位放行（⑤：被放过的动作仍记账——"先做后审"的审就靠这行）。 */
    fun notePass(
        tool: String,
        decision: AskTierDecision,
        opId: String,
        atMs: Long = System.currentTimeMillis(),
    ): AskGovRow = AskGovLedger.append(
        kind = AskGovLedger.KIND_PASS,
        who = AskGovLedger.WHO_TIER,
        basis = "${decision.basis}｜tier=${currentTier.wire}",
        tool = tool,
        detail = "op=$opId",
        atMs = atMs,
    )

    /** 档位闸评估后照旧弹的那一行（487 弹卡权④：每次"弹/不弹"记账）。 */
    fun noteAsk(
        tool: String,
        decision: AskTierDecision,
        opId: String,
        atMs: Long = System.currentTimeMillis(),
    ): AskGovRow = AskGovLedger.append(
        kind = AskGovLedger.KIND_ASK,
        who = AskGovLedger.WHO_TIER,
        basis = "${decision.basis}｜tier=${currentTier.wire}",
        tool = tool,
        detail = "op=$opId",
        atMs = atMs,
    )

    /**
     * 左相压卡落账（D 段③：who=LEFT、依据＝档位＋预算、卡类型与时间齐备）。
     *
     * 第五百零七批收口：生产调用只许 [askViaMediator] 一处（闸的内部），
     * 别处不许再手写这份判断。卡类型进 `detail`（`card=…`），行结构不扩列。
     */
    fun noteSuppressLeft(
        reason: String,
        budgetBasis: String,
        card: String = "",
        atMs: Long = System.currentTimeMillis(),
    ): AskGovRow =
        AskGovLedger.append(
            kind = AskGovLedger.KIND_SUPPRESS,
            who = AskGovLedger.WHO_LEFT,
            basis = "$reason｜$budgetBasis",
            tool = "ask_user",
            detail = if (card.isBlank()) "" else "card=$card",
            atMs = atMs,
        )

    // ── 第五百零七批 · 弹卡收口：唯一的左相闸薄封装 ─────────────────────────
    //
    // 为什么在这里而不在 MediatorWiring（policy）：压卡要落的是这本治理账
    // （AskGovLedger/DecisionLedger，app 侧），policy 不能反向依赖 app；
    // 为什么不在 GuardPipeline：管线判的是**动作**的风险，问卡不是动作——
    // 可省问卡的唯一出口在引擎的 askUser/askGroup，闸贴着出口放。
    //
    // 铁律（406，判据原话）：**只少问，不压必问**——mustAsk=true 走让步格
    // （MediatorDecide.shouldAsk：恒放行、不占预算），所以这里压掉的只有可省的
    // 问卡；必问卡（确认卡／御批人批闸／许可门禁）根本不经这口，或带 mustAsk 进来。

    /** 本轮三数（§二 可观测）：问卡 N ／ 被压 M ／ 必问 K。守恒：N = M + K + 可省弹出。 */
    data class TurnTally(val attempts: Int, val suppressed: Int, val mustAsk: Int) {
        /** 过闸放行的卡（必问 K 张恒在其中；余下就是"实际弹出"的可省卡）。 */
        val popped: Int get() = attempts - suppressed
    }

    @Volatile
    private var turn = TurnTally(0, 0, 0)

    /** 新的一轮（与 `MediatorWiring.onUserTurn` 同一道：BaopuChatController.send 里紧挨着调）。 */
    fun beginTurn() {
        turn = TurnTally(0, 0, 0)
    }

    /** 本轮三数读数（体检单与测试用）。 */
    fun turnTally(): TurnTally = turn

    /**
     * 体检单里本轮三数那一行（507 §二判据的形状）：
     * `本轮问卡 N｜被压 M｜必问 K｜弹出 P（N = M + K + 可省 P）`
     *
     * 口径：计入的是**到过闸的卡**。被免打扰延后的卡已在本轮放行过闸（它"过会儿再问"
     * 走原始出口补问，不过闸、不重复计数），所以守恒恒在闸上成立；
     * "她面前真出卡"另有 PlanContractBoard.noteAsk 那本问人账，两本各对各的。
     */
    fun turnTallyLine(): String {
        val t = turn
        return "本轮问卡 ${t.attempts}｜被压 ${t.suppressed}｜必问 ${t.mustAsk}" +
            "｜弹出 ${t.popped}（N = M + K + 可省 ${t.popped - t.mustAsk}）"
    }

    /**
     * 弹卡前问左相——**全部可省问卡的唯一闸口**（507 收口：不许每处各写一遍判断）。
     *
     * · 放行 ⇒ 原样返回建议，调用方照常出卡（记一行"过闸"的 N 已含它）；
     * · 压下 ⇒ 落账（who=LEFT、依据＝档位＋预算、卡类型、时间）＋ RunLog 一行，
     *   返回 null：调用方立即收手，把"没人答"照实往上报——不弹、不进补问队列
     *   （LOOP 的契约是"只给两条路"，不是过会儿再问）；
     * · 必问（mustAsk=true）⇒ 让步格恒放行：调解器压不到任何必问（铁律）。
     *
     * @param card 卡类型（落账用：ask_user／求助卡／组授权御批卡／人设提案卡／多问卡…）
     */
    fun askViaMediator(
        mustAsk: Boolean,
        card: String,
        atMs: Long = System.currentTimeMillis(),
    ): com.example.zhengdao.baopu.MediatorDecide.AskAdvice? {
        turn = TurnTally(turn.attempts + 1, turn.suppressed, turn.mustAsk + if (mustAsk) 1 else 0)
        val advice = com.example.zhengdao.baopu.MediatorWiring.consultAsk(mustAsk)
        if (advice.allowed) return advice
        turn = TurnTally(turn.attempts, turn.suppressed + 1, turn.mustAsk)
        noteSuppressLeft(
            reason = advice.reason,
            budgetBasis = advice.budget.basis,
            card = card,
            atMs = atMs,
        )
        RunLog.log(advice.line() + "｜card=$card")
        return null
    }

    /** 误拦申诉（C 段：一键申诉 ⇒ 落误拦账；申诉不自动改规则——只记账，不动档位/预算/矩阵）。 */
    fun appeal(subject: String, scene: String, atMs: Long = System.currentTimeMillis()): AskGovRow =
        AskGovLedger.append(
            kind = AskGovLedger.KIND_APPEAL,
            who = "user",
            basis = "误拦申诉",
            scene = scene,
            detail = subject,
            atMs = atMs,
        )

    /**
     * 体检那一行（487 §三②的形状，加上 499 的分场景误拦率）：
     * `授权档：标准 · 今日自动放过 n · 今日问卡 N · 汇总压卡 c · 误拦申诉 a（客观 x/y · 主观 x/y）`
     */
    fun healthLine(nowMs: Long = System.currentTimeMillis()): String {
        val today = dayOf(nowMs)
        val rows = AskGovLedger.replay().filter { dayOf(it.atMs) == today }
        val passes = rows.filter { it.kind == AskGovLedger.KIND_PASS }
        val cards = rows.filter { it.kind == AskGovLedger.KIND_CARD }
        val appeals = rows.filter { it.kind == AskGovLedger.KIND_APPEAL }
        val passCount = if (currentTier == AskTier.STRICT) 0 else passes.size
        return "授权档：${currentTier.cn} · 今日自动放过 $passCount 次 · 今日问卡 ${cards.size} 张" +
            " · 汇总压卡 ${rows.count { it.kind == AskGovLedger.KIND_COMPRESS }} 次" +
            " · 误拦申诉 ${appeals.size} 次（${rateOf(cards, appeals, AskGovLedger.SCENE_OBJECTIVE)}" +
            " · ${rateOf(cards, appeals, AskGovLedger.SCENE_SUBJECTIVE)}）"
    }

    /** 分场景误拦率（目标 <5%）：申诉数 / 问卡数（同场景、同日）；分母 0 ⇒ "无样本"。 */
    private fun rateOf(cards: List<AskGovRow>, appeals: List<AskGovRow>, scene: String): String {
        val denominator = cards.count { it.scene == scene }
        val numerator = appeals.count { it.scene == scene }
        if (denominator == 0) return "场景=${sceneName(scene)} 无样本"
        val pct = numerator * 100.0 / denominator
        return "场景=${sceneName(scene)} ${"%.1f".format(pct)}% ($numerator/$denominator)"
    }

    private fun sceneName(scene: String): String =
        if (scene == AskGovLedger.SCENE_SUBJECTIVE) "主观" else "客观"

    /**
     * 硬拒一句话原因（C 段：≤80 字）。
     *
     * 为什么从这里给而不是直接用 `RiskTell.rejectText`：那是给模型的原文（带机器键）；
     * 给人的那一行要更短——超 [HARD_REJECT_MAX] 截断，且保证有内容（不交白卷）。
     */
    fun hardRejectLine(verdict: RiskVerdict): String {
        val why = verdict.reasons.joinToString("；") { it.detail }.ifBlank { "命中硬红线" }
        val line = "动作被拒：$why"
        return if (line.length <= HARD_REJECT_MAX) line else line.take(HARD_REJECT_MAX - 1) + "…"
    }

    /** 本地时区的"哪一天"（今日计数按它切）。 */
    internal fun dayOf(nowMs: Long): LocalDate =
        Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).toLocalDate()

    /** 日预算的换日键（本地日历天；换日即归零）。 */
    internal fun dayKey(nowMs: Long): Int = dayOf(nowMs).toEpochDay().toInt()

    /** 清空（测试专用：单例状态＋账＋档位＋本轮三数一起还）。 */
    @Synchronized
    fun resetForTest() {
        currentTier = AskTier.DEFAULT
        turn = TurnTally(0, 0, 0)
        AskGovLedger.resetForTest()
    }
}
