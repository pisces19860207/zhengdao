// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百三十八批 · 校准注入**装配缝**（真接线落点；provider 注入，默认未装配 ⇒ 恒空）。
//
// 两条核心判据都压在这里：
//   ① 未命中 ⇒ calibrate=false ⇒ 一个索引 provider 都不调（零读取、token 增量 0）；
//   ⑥ 开关关 ⇒ 直接空表 ⇒ 与"没有本单"逐字节一致。
// 生产端数据源（660 BodyState→722 三行、711/719/723 真源）由装配层提供；本对象不读盘、
// 不检索、不调模型，只按决策调 provider 并把结果交给纯函数 CalibrationInject。
package com.example.zhengdao.baopu.calibration

import com.example.zhengdao.baopu.state.cavity.CompoundRegistration
import com.example.zhengdao.baopu.state.cavity.CompoundWiring
import com.example.zhengdao.baopu.state.readback.ReadbackLines

/** 校准注入的装配缝。所有 provider 都是 @Volatile，装配层可热替换；测试可重置。 */
internal object CalibrationWiring {

    /** 注入总开关：关 ⇒ notes 恒空 ⇒ 逐字节一致（判据⑥）。默认开；未装配 provider 仍恒空。 */
    @Volatile
    var enabled: () -> Boolean = { true }

    /** 触发词表（669 部位名等）；未装配用 MINIMAL 兜底。词表不是索引，未命中也允许读。 */
    @Volatile
    var lexiconProvider: (() -> CalibrationLexicon)? = null

    /** 722 三行（由装配层用 BodyReadback.render 产出）。未装配 ⇒ 恒空。 */
    @Volatile
    var readbackProvider: (() -> ReadbackLines?)? = null

    /** 711 observable-index.json 原文（只读解析，非检索）。 */
    @Volatile
    var observableProvider: (() -> String)? = null

    /** 719 fact-slices.json 原文。 */
    @Volatile
    var factsProvider: (() -> String)? = null

    /** 723 variants.json 原文。 */
    @Volatile
    var variantProvider: (() -> String)? = null

    /**
     * 774 装配缝：额外候选条目（人物自身的 660 读数，含触发式敏感层）。
     * 拿到的是本轮命中的区名——空表＝未命中，装配层一条都不给；未装配（null）⇒ 与没有 774 一致。
     */
    @Volatile
    var entryProvider: ((List<String>) -> List<SourceEntry>)? = null

    /** 774 装配缝：注入回执观察者。收到的是最终 notes，脱敏由观察者自己做；空表不调用。 */
    @Volatile
    var notesObserver: ((List<String>) -> Unit)? = null

    /**
     * 798 段 B 装配缝：教材注入块（复用本管道，纯追加在本轮 notes 尾部）。
     * 入参＝本轮命中区名（matchedZones）——教材"当前命中"据此判定，随轮变化、下一轮不命中即回基线。
     * null／未装配 ⇒ 空表 ⇒ 与没有段 B 逐字节一致；教材块独立 ≤300 token，不动 722 三行与 L1/L2 预算。
     */
    @Volatile
    var textbookNotesProvider: ((List<String>) -> List<String>)? = null

    /**
     * 852 段·外观卡装配缝（尾部纯追加在本轮 notes 最末，不动 722 三行与 L1/L2 预算）。
     * 拿到的是 CastingRuntime 渲染好的 `[外观] <≤120 字摘要>` 行；null／未绑卡源 ⇒ 空表
     * ⇒ 与没有本单逐字节一致。只进本轮上下文，不落库、不进闸、不改判定。
     */
    @Volatile
    var castingProvider: (() -> List<String>)? = null

    /** 870 装配缝：本期只做写后校验，notes 不新增注入；未装配 ⇒ 与没有本单逐字节一致。 */
    @Volatile
    var torsoProvider: (() -> List<String>)? = null

    @Volatile
    var compoundProvider: (() -> List<CompoundRegistration>)? = null

    /**
     * 本轮注入文本。纯决策后短路：未命中／开关关／未装配三行 ⇒ 空表，零索引读取。
     * 返回值直接进 ContextAssembler.extraNotes（不进任何闸、不改判定）。
     */
    fun notes(userText: String?): List<String> {
        if (userText.isNullOrBlank()) return emptyList()
        if (!runCatching { enabled() }.getOrDefault(false)) return emptyList()
        val decision = CalibrationTrigger.decide(
            userText,
            lexiconProvider?.invoke() ?: CalibrationLexicon.MINIMAL,
        )
        if (!decision.calibrate) return emptyList()
        val readback = readbackProvider?.invoke() ?: return emptyList()
        val entries = when (decision.level) {
            CalibrationLevel.L1, CalibrationLevel.L2 -> loadEntries(decision.matchedZones)
            else -> emptyList()
        }
        val variants = if (decision.collapse) loadVariants() else emptyList()
        val notes = ArrayList(CalibrationInject.build(decision, readback, entries, variants).notes)
        // 798 段 B：教材块纯追加在 738 既有 notes 之后，不动三行顺序与 L1/L2 预算；
        // provider 未装配 ⇒ 空表 ⇒ 逐字节一致。只进本轮上下文，不落库、不进闸。
        val teach = runCatching { textbookNotesProvider?.invoke(decision.matchedZones) }
            .getOrDefault(emptyList()).orEmpty()
        notes += teach
        // 852 外观卡：再纯追加在教材块之后，仍是本轮 notes 最末一段。
        // 回执按前缀计数（只认 738 自己的 `[校准·` 条目），`[外观]` 行不进 entryCount／categories，
        // 所以 722 三行与敏感层预算的读数与本单之前逐字相同（判据④）。
        val casting = runCatching { castingProvider?.invoke() }.getOrDefault(emptyList()).orEmpty()
        notes += casting
        val torso = runCatching { torsoProvider?.invoke() }.getOrDefault(emptyList()).orEmpty()
        notes += torso
        if (notes.isNotEmpty()) notesObserver?.invoke(notes)
        return notes
    }

    /** 711 + 719 取数；两路 provider 各自独立，缺一路不影响另一路（缺源条目由 build 丢）。 */
    private fun loadEntries(matchedZones: List<String>): List<SourceEntry> {
        val out = ArrayList<SourceEntry>()
        // 774：人物自身的读数排在前面——命中区名时先给"这个人"的规范值，再补索引事实。
        entryProvider?.invoke(matchedZones)?.let { out += it }
        observableProvider?.invoke()?.let { out += CalibrationSource.fromObservableIndex(it) }
        factsProvider?.invoke()?.let { out += CalibrationSource.fromFactSlices(it) }
        return out
    }

    /** 723 取数（塌缩请求的变体档）。 */
    private fun loadVariants(): List<String> =
        variantProvider?.invoke()?.let { CalibrationSource.variantLevels(it) } ?: emptyList()

    /** 测试重置：清空全部 provider 与开关（避免测试间串味）。 */
    fun resetForTest() {
        enabled = { true }
        lexiconProvider = null
        readbackProvider = null
        observableProvider = null
        factsProvider = null
        variantProvider = null
        entryProvider = null
        notesObserver = null
        textbookNotesProvider = null
        castingProvider = null
        torsoProvider = null
        compoundProvider = null
        CompoundWiring.detach()
    }
}
