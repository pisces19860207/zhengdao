// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百九十八批 · 段 B · 装配层：把段 A 教材库 → 命中 → 注入块，接到 738 管道尾部。
// 复用而非新造：只提供一条 provider 缝给 CalibrationWiring，产出 List<String> 纯文本，
// 由 738 已接好的 RunEngine.turnCalibrationNotes → extraNotes → ContextAssembler 落本轮上下文。
// 默认未装配 ⇒ 空块 ⇒ 与"没有段 B"逐字节一致（只影响本轮的对照夹具基础）。
package com.example.zhengdao.baopu.textbook.inject

import com.example.zhengdao.baopu.textbook.TextbookEntry

/** 段 B 装配缝。生产由 BaopuRuntime 接一次；测试直接注入 provider，不读盘。 */
internal object TextbookTeaching {

    /**
     * 教材快照 provider：返回教材库当前全部条目（段 A 的 AppendOnlyStore.all()）。
     * null ⇒ 未装配 ⇒ 注入空块（miss），与没有段 B 一致。
     */
    @Volatile
    var entriesProvider: (() -> List<TextbookEntry>)? = null

    /** 域白名单 provider（可选）：限定本轮只注入这些域的教材；null ⇒ 不限域。 */
    @Volatile
    var domainsProvider: (() -> List<com.example.zhengdao.baopu.textbook.Domain>)? = null

    /** 可解释日志出口（落工作区日志，不进 prompt）。null ⇒ 只保留在 TeachInjection.explain。 */
    @Volatile
    var traceSink: ((String) -> Unit)? = null

    /**
     * 一次装配内现算的教材注入结果。
     * @param matchedZones 本轮命中区名（来自 738 decision）——教材"当前命中"的关键词。
     * provider 未装配或关键词为空 ⇒ miss 空块。
     */
    fun inject(matchedZones: List<String>): TeachInjection {
        val entries = runCatching { entriesProvider?.invoke() }.getOrNull()
            ?: return TeachInjection.empty("miss（教材库未装配）")
        if (matchedZones.isEmpty()) return TeachInjection.empty("miss（本轮无命中区名）")
        val context = TeachContext(
            keywords = matchedZones,
            domains = runCatching { domainsProvider?.invoke() }.getOrDefault(emptyList()).orEmpty(),
        )
        val result = TextbookInject.build(TextbookMatcher.match(entries, context))
        runCatching { traceSink?.invoke(result.explain) }
        return result
    }

    /** 进 738 管道的正文行（miss ⇒ 空表）。 */
    fun notes(matchedZones: List<String>): List<String> = inject(matchedZones).lines

    /** 测试重置：回到未装配（与没有段 B 一致）。 */
    fun resetForTest() {
        entriesProvider = null
        domainsProvider = null
        traceSink = null
    }
}
