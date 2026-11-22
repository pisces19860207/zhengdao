// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百一十九批 · 性格与语声：数据模型。
// 三层不许压成一层：①底层 core（需求/恐惧/价值排序，每条带触发）→②稳定 trait（七轴）
// →③表层 state（由 670 角色轨状态推，不改①②）。再加语声十维与台词样本。
// 纯数据、无 IO、无随机、无时钟；禁心理诊断（依恋类型/量表一律不进）。
package com.example.zhengdao.baopu.state.persona

// ============================================================ ① 底层 core

/** 核心需求：五种，锁死。 */
enum class CoreNeed(val label: String) {
    RECOGNITION("被认可"),
    SECURITY("安全感"),
    DOMINANCE("支配"),
    BELONGING("归属"),
    FREEDOM("自由"),
}

/**
 * 一条底层条文：需求 + 该需求对应的核心恐惧 + 价值排序位次 + 触发条件。
 * [trigger] 必填：只列需求不给触发条件 ⇒ 检查器提示（不许当成"他就是这种人"的证据）。
 * [rank] 越小越靠前；同层内不许并列。
 */
data class CoreDrive(
    val need: CoreNeed,
    val coreFear: String,
    val rank: Int,
    val trigger: String,
) {
    val hasTrigger: Boolean get() = trigger.isNotBlank()
}

/** 底层 core：3–5 条条文。 */
data class CoreLayer(val drives: List<CoreDrive>)

// ============================================================ ② 稳定特质 trait（七轴）

enum class Sociability(val label: String) { EXTROVERT("外向"), INTROVERT("内向") }
enum class Directness(val label: String) { DIRECT("直接"), INDIRECT("迂回") }
enum class StanceStyle(val label: String) { LEADING("主导"), FOLLOWING("配合") }
enum class Orientation(val label: String) { PRACTICAL("务实"), IDEALISTIC("务虚") }
enum class SelfControl(val label: String) { HIGH("自控高"), LOW("自控低") }
enum class Aggressiveness(val label: String) { HIGH("攻击性高"), LOW("攻击性低") }
enum class Humor(val label: String) { COLD("冷幽默"), WARM("暖幽默"), NONE("无幽默") }

/** 稳定特质七轴，全部必填——少一轴不算齐。 */
data class TraitLayer(
    val sociability: Sociability,
    val directness: Directness,
    val stance: StanceStyle,
    val orientation: Orientation,
    val selfControl: SelfControl,
    val aggressiveness: Aggressiveness,
    val humor: Humor,
)

// ============================================================ ③ 表层 state（由 670 推）

/** 表层五维。 */
enum class SurfaceAxis(val label: String) {
    PATIENCE("耐心"),
    INTOXICATION("酒意"),
    FATIGUE("疲惫"),
    SHAME("羞耻"),
    TENSION("紧张"),
}

/**
 * 670 角色轨状态的只读快照。字段语义对齐 CharacterTrack.stateView（显性度/立场/阶段/轮数/硬否决），
 * 酒意与疲惫是外部喂入信号（同 670 的"信号只来自六体读数或用户输入"纪律）。
 * 本类不 import、不修改 670：只复制可读字段的语义，推导器全在函数内。
 */
data class TrackStateSnapshot(
    val salience: Int,       // 显性度 Lv1–4
    val stanceLabel: String, // 立场：欲望/中立/排斥
    val dStage: Int,         // 防守阶段 0–6
    val turns: Int,          // 已进行轮数（≥0）
    val vetoActive: Boolean, // 硬否决中
    val intoxication: Int,   // 酒意 0–3（外部喂入）
    val fatigue: Int,        // 疲惫 0–3（外部喂入）
)

/** 表层一维：档位 0–3 + 由哪条状态推出（source 必填，空串⇒提示/红）。 */
data class SurfaceLevel(val axis: SurfaceAxis, val tier: Int, val source: String) {
    init {
        require(tier in 0..3) { "表层档位只取 0–3：${axis.label}=$tier" }
    }
}

/** 表层状态：五轴齐，每轴带推导依据。 */
data class SurfaceState(val levels: List<SurfaceLevel>)

// ============================================================ 语声十维

enum class SentenceLength(val label: String) { SHORT("短句"), MEDIUM("中句"), LONG("长句") }
enum class DictionLevel(val label: String) { PLAIN("白话"), NEUTRAL("中性"), LITERARY("书面") }
enum class Profanity(val label: String) { NONE("无脏话"), MILD("轻口"), HEAVY("重口") }
enum class RhetoricStyle(val label: String) { QUESTION("多反问"), DIRECT("多直陈"), BALANCED("兼有") }
enum class Interruption(val label: String) { NEVER("不打断"), SOMETIMES("偶尔打断"), OFTEN("常打断") }

/**
 * 语声十维。口头禅 ≤6 字、可空；崩相必指定（null ⇒ 提示"永不崩"）。
 * [address] 称呼习惯为短标签（如"叫全名/叫哥/敬称/无称呼"）。
 */
data class VoiceDimensions(
    val sentenceLength: SentenceLength,
    val diction: DictionLevel,
    val profanity: Profanity,
    val address: String,
    val catchphrase: String?,
    val modalParticle: String,
    val pauseEllipsis: String,
    val rhetoric: RhetoricStyle,
    val interruption: Interruption,
    val breakdown: String?,
) {
    val catchphraseTooLong: Boolean get() = catchphrase != null && catchphrase.length > 6
    val hasBreakdown: Boolean get() = !breakdown.isNullOrBlank()
}

/** 一句台词的可观察特征——台词互换测试只看这四维（句长/用词/称呼/口头禅）。 */
data class LineFeatures(
    val length: SentenceLength,
    val diction: DictionLevel,
    val address: String,
    val catchphrase: String?,
)

/** 一条台词样本：[text] 给人看，[features] 给判据读。 */
data class VoiceLine(val text: String, val features: LineFeatures)

/** 一个角色的语声档案：十维 + 台词样本（口头禅是否真出现靠样本验）。 */
data class PersonaVoice(val name: String, val dimensions: VoiceDimensions, val lines: List<VoiceLine>)

// ============================================================ 归属与检查结果

/** 人物描述归属到哪一层：写"他就是这种人"须指到 TRAIT/CORE，写"他今天这样"只能指 SURFACE。 */
enum class PersonaLayer(val label: String) { CORE("①底层"), TRAIT("②稳定特质"), SURFACE("③表层") }

enum class Severity(val label: String) { ERROR("红"), WARNING("提示") }

/** 一条检查结果。[code] 稳定机读码，便于测试逐条钉住。 */
data class CheckItem(val severity: Severity, val code: String, val message: String)

/** 汇总结果：errors 非空即红；warnings 不红但要提示。 */
data class CheckReport(val items: List<CheckItem>) {
    val errors: List<CheckItem> get() = items.filter { it.severity == Severity.ERROR }
    val warnings: List<CheckItem> get() = items.filter { it.severity == Severity.WARNING }
    val passed: Boolean get() = errors.isEmpty()
    fun line(): String = "红=${errors.size} 提示=${warnings.size}|" +
        items.joinToString("；") { "${it.severity.label}:${it.code}" }
}

/** 一个完整人物：三层 + 语声。传给 PersonaChecks 做总检。 */
data class Persona(
    val core: CoreLayer,
    val trait: TraitLayer,
    val surface: SurfaceState,
    val voice: PersonaVoice,
)
