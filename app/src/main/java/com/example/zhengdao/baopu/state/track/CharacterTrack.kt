// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百七十批 · 神轨第一机：沦陷链 S0–S6（防守）＋升温链 A–F（进攻）＋显性度 Lv1–4（观测）。
// 规格来源：素材库/01-沦陷链 v2.0（10 条）＋02-欲望图谱（3 条）；
// 行为基准：证道设计报告/附件-角色轨状态机/character_track.py（照翻，未重新设计；
// 跨语言对拍见 tools/character-track-parity.py 与 TrackParityTest）。
//
// 两条补（v0.8 仿真抓出的欠约束，已拍板）：
// ① 进攻链/接触层上限 = min(显性度映射, 阶段映射) 双重约束；
// ② 单向门：显性度只做观测不做锁（「显性度不能抢跑」会互锁死锁，首轮仿真已验证）。
// 追加（v0.9）：hard_veto 硬否决——触发即三态归零并写账，不参与渐进，
// 解除只认安全信号；状态机不得自己产生信号（信号只来自六体读数或用户输入）。
package com.example.zhengdao.baopu.state.track

/** 欲望立场（欲望图谱总览）：每对角色独立记录，可随剧情变化。 */
enum class Stance(val label: String) {
    DESIRE("欲望"),
    NEUTRAL("中立"),
    AVERSION("排斥"),
}

/** 信号来源：状态机是六体的消费者，不是第七件——信号只许来自六体读数或用户输入。 */
enum class SignalSource { AMYGDALA, STRIATUM, INSULA, PREFRONTAL, HIPPOCAMPUS, THALAMUS, USER }

/** 观测信号：+1 正向 / -1 拒绝 / 0 不明。来源必填：没有匿名信号入口（追加判据）。 */
data class TrackSignal(val value: Int, val source: SignalSource) {
    init {
        require(value in -1..1) { "信号只取 -1/0/+1：$value" }
    }
}

/**
 * 无排斥确认三条件（欲望图谱·信号读取规则 §5，硬安全门）：
 * 没有明显后退／没有出声拒绝／肌肉没有明显僵硬。任意一条不满足 ⇒ 显性度不升反降、拒绝推进。
 * 三条件本身锁死（自进化边界：改它等同改规格，须人批）。
 */
data class NoRepulsion(
    val noRetreat: Boolean = true,
    val noVerbalReject: Boolean = true,
    val noMuscleRigid: Boolean = true,
) {
    val confirmed: Boolean get() = noRetreat && noVerbalReject && noMuscleRigid

    companion object {
        val CONFIRMED = NoRepulsion()
    }
}

/** 硬否决解除只认安全信号：用户输入／角色主动撤离；「多试几轮」不算（追加③）。 */
enum class SafetySignal(val label: String) {
    USER_INPUT("用户输入"),
    CHARACTER_WITHDRAW("角色主动撤离"),
}

/** 硬否决账行：哪条触发物、哪轮触发、持续几轮、如何解除（触发即写账）。 */
data class VetoRecord(
    val trigger: String,
    val atTurn: Int,
    var heldTurns: Int = 0,
    var releasedBy: String? = null,
)

/** 沦陷链 S0–S6 阶段名（锁死：名称与含义改动须人批＋升主版本）。 */
val STAGE_NAMES: List<String> = listOf("S0情境", "S1破冰", "S2唤醒", "S3掩饰", "S4憋声", "S5承受", "S6沦陷")

/** 升温链 A–F 阶段名（锁死，同上）。 */
val ATTACKER_NAMES: List<String> = listOf("A观察", "B正当接触", "C越界", "D突破", "E掌控", "F催逼")

/** 对拍快照：与 Python 参考实现的状态字段一一对应（判据⑨逐条比对的载体）。 */
data class TrackStateView(
    val stance: String,
    val salience: Int,
    val dStage: Int,
    val aStage: Int,
    val layer: Int,
    val turns: Int,
    val anchorsHit: List<Int>,
    val window: List<Int>,
    val inertia: String?,
)

/**
 * 每对角色一份。纯状态机：零随机、零时钟、零联网——不自己产生信号，
 * 推进全部由外部喂入的观测信号驱动（本单不做「自动推进」）。
 */
class CharacterTrack(
    val params: TrackParams = TrackParams.DEFAULT,
    val anchors: AnchorConfig = AnchorConfig(),
    stance: Stance = Stance.NEUTRAL,
) {
    var stance: Stance = stance
        private set
    var salience: Int = 1
        private set
    var dStage: Int = 0
        private set
    var aStage: Int = 0
        private set
    var layer: Int = 1
        private set
    var turns: Int = 0
        private set

    /** 惯性（层·部位·距离）：岛叶/形轨写入的持久状态，跨打断不重置。 */
    var inertia: String? = null
        private set

    private val anchorsHit = mutableSetOf<Int>()
    private val window = ArrayDeque<Int>()
    private var lastConfirmation = NoRepulsion.CONFIRMED
    private var activeVeto: VetoRecord? = null
    private val vetoLog = mutableListOf<VetoRecord>()
    private val autonomousEvents = mutableListOf<String>()
    private var turnCount = 0

    /** 欲望立场随剧情变化（欲望图谱总览）。 */
    fun changeStance(next: Stance) {
        stance = next
    }

    /**
     * 每轮喂一个观测信号（不对称滤波器：升需 advanceNeed 个正向，降只需 retreatNeed 个拒绝）。
     * 无排斥确认不满足 ⇒ 显性度不升反降（硬安全门）。
     * 硬否决期间一切推进输入整体丢弃（御裁②：优先级 hard_veto ＞ 双约束 ＞ 单向门——
     * veto 是「身体说停」，不属推进链，不能被双约束算回中间值）。
     */
    fun feed(signal: TrackSignal, confirmation: NoRepulsion = NoRepulsion.CONFIRMED): String {
        if (activeVeto != null) return "硬否决中→Lv$salience"
        lastConfirmation = confirmation
        window.addLast(signal.value)
        while (window.size > params.window) window.removeFirst()
        val rejected = window.count { it == -1 } >= params.retreatNeed
        if (rejected || !confirmation.confirmed) {
            if (salience > 1) salience -= 1
            enforceCaps()
            return if (rejected) "显性度回退→Lv$salience" else "无排斥未确认→Lv$salience"
        }
        if (window.count { it == 1 } >= params.advanceNeed && salience < SALIENCE_MAX) {
            salience += 1
            return "显性度上升→Lv$salience"
        }
        return "保持Lv$salience"
    }

    /**
     * 一轮结束：防守方推进判定。最短停留未满 ⇒ 不动；锚点命中缩短停留但不改拓扑；
     * 上限由显性度把守（单向门：显性度是天花板不是动力，阶段只靠轮数推进）。
     * 无排斥未确认或硬否决期间 ⇒ 拒绝推进。返回换阶段消息，未换返回 null。
     */
    fun tick(anchorHit: Boolean = false): String? {
        turnCount += 1
        val veto = activeVeto
        if (veto != null) {
            veto.heldTurns += 1
            return null
        }
        if (!lastConfirmation.confirmed) return null
        if (anchorHit) anchorsHit.add(dStage)
        var dwell = params.minDwell
        if (dStage in anchorsHit) dwell = maxOf(1, (dwell * params.anchorFactor).toInt())
        turns += 1
        if (turns < dwell) return null
        if (dStage >= stageCap()) return null
        if (dStage < DEFENDER_STAGES - 1) {
            dStage += 1
            turns = 0
            return "防守→${STAGE_NAMES[dStage]}"
        }
        return null
    }

    /** 进攻方推进：受显性度与防守阶段双重硬约束（补①），禁止跳级，每步至多 +1。 */
    fun attackerStep(): String {
        if (activeVeto == null && lastConfirmation.confirmed && aStage < attackerCap()) aStage += 1
        return ATTACKER_NAMES[aStage]
    }

    /** 接触请求：双重约束（补②），超层即拒；无排斥未确认或硬否决期间一律拒。 */
    fun touch(requested: Int): String {
        require(requested in 1..CONTACT_LAYERS) { "接触层只取 1–3：$requested" }
        val cap = layerCap()
        if (activeVeto != null || !lastConfirmation.confirmed || requested > cap) {
            return "拒绝接触层$requested（当前上限$cap）"
        }
        layer = requested
        return "接触层$layer"
    }

    /**
     * 硬否决（追加③，御裁②）：PTSD 类触发 ⇒ 阶段锁 S0、接触层锁 1、显性度锁 Lv1，并写账；
     * 优先级 hard_veto ＞ 双约束 ＞ 单向门。解除不是回原值，而是从 S0/Lv1 重新按双约束推进
     * （惯性保留、显性度不追溯——窗口已清）。进攻链按双约束钳到当前上限。
     */
    fun hardVeto(trigger: String) {
        require(trigger.isNotEmpty()) { "触发物必须写明（写账用）" }
        dStage = 0
        layer = 1
        salience = 1
        turns = 0
        window.clear()
        enforceCaps()
        activeVeto = VetoRecord(trigger, atTurn = turnCount)
    }

    /** 解除只认安全信号；解除后照常走信号驱动，不自动补偿进度。 */
    fun releaseVeto(safety: SafetySignal): Boolean {
        val veto = activeVeto ?: return false
        veto.releasedBy = safety.label
        vetoLog.add(veto)
        activeVeto = null
        return true
    }

    /** 否决账（含在飞的那条）：触发物/轮次/持续轮数/解除方式。 */
    fun vetoHistory(): List<VetoRecord> = vetoLog + listOfNotNull(activeVeto)

    fun vetoActive(): Boolean = activeVeto != null

    /** 惯性写入（岛叶/形轨侧）：引擎只持久化，不解释内容。 */
    fun holdInertia(value: String?) {
        inertia = value
    }

    /** 自主事件（如私密场景走神）：由形轨按频率参数生成后写入，不经 AI 决定；引擎只记录进状态串。 */
    fun recordAutonomous(event: String) {
        require(event.isNotEmpty()) { "自主事件名须非空" }
        autonomousEvents.add(event)
    }

    /** 不变量（参考实现 check 的移植）：防跳级后的越界状态，每步断言用。 */
    fun invariantsHold(): Boolean =
        dStage in 0 until DEFENDER_STAGES &&
            aStage in 0 until ATTACKER_STAGES &&
            layer in 1..CONTACT_LAYERS &&
            dStage <= stageCap() &&
            aStage <= attackerCap() &&
            layer <= layerCap()

    /** 对拍视图：字段与 Python PairState 一一对应。 */
    fun stateView(): TrackStateView = TrackStateView(
        stance = stance.label,
        salience = salience,
        dStage = dStage,
        aStage = aStage,
        layer = layer,
        turns = turns,
        anchorsHit = anchorsHit.sorted(),
        window = window.toList(),
        inertia = inertia,
    )

    /** Python 参考实现 snapshot 的逐字节移植（判据⑨对拍锚点；锚点显示阶段编号）。 */
    fun snapshot(): String {
        val anchorText = if (anchorsHit.isEmpty()) "—" else anchorsHit.sorted().joinToString(", ", "[", "]")
        return "[角色轨] ${STAGE_NAMES[dStage]}｜${ATTACKER_NAMES[aStage]}｜层$layer" +
            "｜Lv$salience·${stance.label}｜锚$anchorText｜惯性${inertia ?: "—"}"
    }

    /**
     * 一行状态串（给模型看，替换「每轮重读沦陷链全文」；653 的 RunState 带这一行，不另造第二份）。
     * 锚点名单来自配置项；token 预算护栏：超预算先压锚点名单、再压自主事件、最后压惯性。
     */
    fun summaryLine(): String {
        var names = anchorNames()
        var events = autonomousEvents.toList()
        var inertiaText = inertia ?: "—"
        var line = renderSummary(names, events, inertiaText)
        while (TrackSummary.tokenEst(line) > TrackSummary.TOKEN_BUDGET) {
            when {
                names.size > 1 -> names = names.dropLast(1)
                names.isNotEmpty() && names != listOf("…") -> names = listOf("…")
                events.isNotEmpty() -> events = events.dropLast(1)
                inertiaText.length > 1 -> inertiaText = inertiaText.dropLast(1)
                else -> break
            }
            line = renderSummary(names, events, inertiaText)
        }
        return line
    }

    private fun anchorNames(): List<String> = anchorsHit.sorted().flatMap { stage ->
        val configured = anchors.namesByStage[stage]
        if (configured.isNullOrEmpty()) listOf(stage.toString()) else configured
    }

    private fun renderSummary(names: List<String>, events: List<String>, inertiaText: String): String {
        val anchorText = if (names.isEmpty()) "—" else names.joinToString("·", "[", "]")
        val base = "[角色轨] ${STAGE_NAMES[dStage]}｜${ATTACKER_NAMES[aStage]}｜层$layer" +
            "｜Lv$salience·${stance.label}｜锚$anchorText｜惯性:$inertiaText"
        return if (events.isEmpty()) base else base + "｜自主:" + events.joinToString("·")
    }

    /** 上限 = min(显性度映射, 阶段映射)——min 组合规则锁死（改它等同改规格）。 */
    private fun stageCap(): Int = params.salienceStageMax.getValue(salience)

    private fun attackerCap(): Int = minOf(
        params.salienceAttackerMax.getValue(salience),
        params.attackerCapByStage.getValue(dStage),
    )

    private fun layerCap(): Int = minOf(
        params.salienceLayerMax.getValue(salience),
        params.layerCapByStage.getValue(dStage),
    )

    /** 回退钳位（参考实现 _enforce_caps 的移植）：阶段回退重置停留计数。 */
    private fun enforceCaps() {
        if (dStage > stageCap()) {
            dStage = stageCap()
            turns = 0
        }
        if (aStage > attackerCap()) aStage = attackerCap()
        if (layer > layerCap()) layer = layerCap()
    }
}

/**
 * tokenEst/1 估算口径（御裁③）：CJK 单字＝1、ASCII 字母数字连续段＝1、空白与标点不计。
 * **不是模型 tokenizer**——只用于预算、两态比较、前后差值；对外不许声称「精确 N token」，
 * 计数一律写「约 N token（tokenEst/1）」。换口径 ⇒ TOKEN_EST_VERSION 升号，旧新读数不得相加。
 */
object TrackSummary {
    const val TOKEN_EST_VERSION = "1"
    const val TOKEN_BUDGET = 40
    private const val CJK_START = 0x4E00
    private const val CJK_END = 0x9FFF
    private const val ASCII_END = 128

    fun tokenEst(line: String): Int {
        var count = 0
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c.code in CJK_START..CJK_END -> {
                    count += 1
                    i += 1
                }
                c.code < ASCII_END && c.isLetterOrDigit() -> {
                    var j = i
                    while (j < line.length && line[j].code < ASCII_END && line[j].isLetterOrDigit()) j += 1
                    count += 1
                    i = j
                }
                else -> i += 1
            }
        }
        return count
    }
}