// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百五十八批 · 御裁 2026-11-04 04:36 的那半条：「勘验」接入点本批落地——
// **军师把报告里"会改 N 个文件"的那份清单交给勘验当事后核验基线**（派单 §二 勘验行）。
//
// 为什么这只文件长在 `inspect/` 而不是 `agent/`：交付物要落在**收方**那一层才叫"交给勘验"，
// 而不是军师自己存一份然后说"勘验随时来取"。放在勘验层还有一处好处——勘验的只读闸
// （`InspectReadOnlyGateTest` 负例③）不许 `inspect/` 里 import `agent.*`，所以这里
// 天生只能收**朴素字符串**：清单传进来就脱掉了军师的对象，两边不再有类型耦合，
// 军师哪天改报告形状，这一层不会跟着塌。
//
// 三条纪律（每条都有测试，摘掉守卫必红）：
//   1. **没清单就没有基线**：空 runId／空清单 ⇒ 登记就拒。登记不进去就不会出现
//      "基线是空的、实际也是空的 ⇒ 对得上"那种假绿——这正是 272 那条「缺侧不当一致」的口径。
//   2. **只对已跑完的文件预演发基线**：取消的那趟清单是半截的，降级（纯计划层）的那趟
//      根本没动过文件；调用方（`RehearsalRunner`）把这两种都挡住，本层不看模式、只看清单。
//   3. **不写账**：进程内一张表，落不了盘、进不了任何一本账（派单【隔离】表：军师「不写任何账」
//      ＋勘验层写口由 `InspectReadOnlyGateTest` 钉着）。基线的寿命＝这一次进程的寿命，
//      真机上「事后核验」要跨进程，归接线批（报告里写明）。
package com.example.zhengdao.baopu.inspect

import java.util.concurrent.ConcurrentHashMap

/**
 * 推演基线（军师交给勘验的那份"应该会改成这些文件"的清单）。
 *
 * 键是 runId（`rh-…`）：派单【隔离】② 要的就是"日志与报告里一眼看得出是谁"，
 * 拿 runId 当键，勘验这边报出来的每一条都追得回是哪一趟预演给的基线。
 */
internal object RehearsalBaselineStore {

    /** 勘验回话里最多点几条名（与军师报告那格同量级，多了只报数）。 */
    const val MAX_NAMES_IN_VERDICT = 6

    private val baselines = ConcurrentHashMap<String, List<String>>()

    /** 路径写法的唯一口径：反斜杠换正斜杠、去空白、去空、去重（保先后）。 */
    private fun normalize(paths: List<String>): List<String> =
        paths.map { it.trim().replace('\\', '/') }.filter { it.isNotEmpty() }.distinct()

    /**
     * 登记基线。空 runId 或空清单 ⇒ 拒（纪律 1：宁可不登记，也不留一条会冒充"对得上"的空基线）。
     *
     * @return 真登记进去了才 true；同一 runId 重复登记也 false（一份预演只交一份基线）。
     */
    fun register(runId: String, paths: List<String>): Boolean {
        val key = runId.trim()
        val list = normalize(paths)
        if (key.isEmpty() || list.isEmpty()) return false
        return baselines.putIfAbsent(key, list) == null
    }

    /** 这份基线登记过没有（没登记 ⇒ 后面一律「无法核验」，不判一致）。 */
    fun has(runId: String): Boolean = baselines.containsKey(runId.trim())

    /** 登记着的清单（没登记交空表；空表在这里不等于"核验通过"，只在 [verdict] 里当缺侧用）。 */
    fun baseline(runId: String): List<String> = baselines[runId.trim()] ?: emptyList()

    /** 核验完就摘掉：基线是一次性的，留在表里会被下一趟同 runId 的核验错认。 */
    fun forget(runId: String): Boolean = baselines.remove(runId.trim()) != null

    /** 现在表上有几份基线（只用于测试与自检，不参与判决）。 */
    fun count(): Int = baselines.size

    /**
     * 事后核验：把实际改动的文件清单折好递进来，交回一句勘验话。
     *
     * 四档（互斥、全覆盖）：缺基线 ⇒ 不判；少了（基线有、实际没动）；多了（实际动了、基线没说）；
     * 都对得上。少了／多了**同时**存在时两笔都点出来，不合并成一句"有出入"。
     */
    fun verdict(runId: String, actual: List<String>): String {
        val key = runId.trim()
        val expected = baselines[key]
        if (expected == null) return "没有 $key 的推演基线 ⇒ 无法核验（不判一致）"
        val reality = normalize(actual)
        val missing = expected - reality.toSet()
        val extra = reality - expected.toSet()
        if (missing.isEmpty() && extra.isEmpty()) return "对得上：${expected.size} 条一条不差"
        return listOfNotNull(
            if (missing.isEmpty()) null else "少了 ${missing.size} 条（${named(missing)}）",
            if (extra.isEmpty()) null else "多了 ${extra.size} 条（${named(extra)}）",
        ).joinToString("；")
    }

    private fun named(paths: List<String>): String =
        if (paths.size <= MAX_NAMES_IN_VERDICT) paths.joinToString("、")
        else paths.take(MAX_NAMES_IN_VERDICT).joinToString("、") + "，另 ${paths.size - MAX_NAMES_IN_VERDICT} 条没点名"

    /** 测试清场用（生产不调：进程内状态，重启自然归零）。 */
    internal fun resetForTest() {
        baselines.clear()
    }
}
