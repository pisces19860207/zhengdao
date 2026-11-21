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
            CalibrationLevel.L1, CalibrationLevel.L2 -> loadEntries()
            else -> emptyList()
        }
        val variants = if (decision.collapse) loadVariants() else emptyList()
        return CalibrationInject.build(decision, readback, entries, variants).notes
    }

    /** 711 + 719 取数；两路 provider 各自独立，缺一路不影响另一路（缺源条目由 build 丢）。 */
    private fun loadEntries(): List<SourceEntry> {
        val out = ArrayList<SourceEntry>()
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
    }
}
