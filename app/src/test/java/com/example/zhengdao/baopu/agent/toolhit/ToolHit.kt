// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 412 单 · 工具命中率测量仪（**测试侧仪器，不进产品**）。
//
// 回答一个问题：给定一批「模型会发出的调用」，按现行注册表机械地判成四类——
//   ok  = 工具对、参数过 SchemaCheck（真跑前的静态口径）；
//   a   = 选错工具（意图指向的工具 ≠ 实际调用的工具）；
//   b   = 参数过不了 SchemaCheck（缺必填/类型错/枚举外/越界/幻觉参数）；
//   c   = 工具根本不存在（没入口/没接线）；
//   sem = SchemaCheck 与执行体都放行、但按工具语义仍是错调用（如行号口径差一）。
//
// 证据分级（交付报告同口径，这里再钉一遍）：
//   「sem」由语料标注（`trap`），仪器只负责证明「schema 放行」这一半；
//   a 类的判定完全机械（名字比对），但「模型真的会这么选」是构造假设，不是读数。
package com.example.zhengdao.baopu.agent.toolhit

import com.example.zhengdao.baopu.agent.ToolRegistry
import org.json.JSONObject

/** 四类＋一档的判定（与交付报告的分类一一对应，不许出现第五种拼写）。 */
enum class HitVerdict { ok, a, b, c, sem }

/** 语料里的一条：一个真实口吻的意图 ＋ 模型会发出的那个调用。 */
internal data class HitCase(
    val id: String,
    val intent: String,
    val expectTool: String,
    val callTool: String,
    val args: JSONObject,
    /** 修前实测判定（门禁钉住：注册表行为变了必须显式改语料，不许静默漂移）。 */
    val expectVerdict: HitVerdict,
    /** 证据来源：构造 / 真机 / 现场记载。 */
    val source: String,
    /** 判定依据的人话（进报告表格）。 */
    val why: String,
    /** 重试链：本次调用是重试哪一条（空 = 链头）。 */
    val retryOf: String,
    /** sem 档候选：schema 放行但语义仍错的调用（只有标了才判 sem）。 */
    val trap: Boolean,
) {
    companion object {
        val SOURCES: Set<String> = setOf("构造", "真机", "现场记载")
        fun fromJson(o: JSONObject): HitCase = HitCase(
            id = o.getString("id"),
            intent = o.optString("intent"),
            expectTool = o.optString("expectTool"),
            callTool = o.getJSONObject("call").optString("tool"),
            args = o.getJSONObject("call").optJSONObject("args") ?: JSONObject(),
            expectVerdict = HitVerdict.valueOf(o.getString("expect")),
            source = o.optString("source", "构造"),
            why = o.optString("why"),
            retryOf = o.optString("retryOf", ""),
            trap = o.optBoolean("trap", false),
        )
    }
}

/** 判定器（纯函数：只吃注册表与一条语料，不执行任何工具）。 */
internal object ToolHitJudge {

    /**
     * 机械判定。顺序即语义：
     * 1. 注册表里没这个名字 ⇒ c（模型看不见入口，或根本没接）；
     * 2. 调的名字 ≠ 意图指向的工具 ⇒ a（选错）；
     * 3. 过不了 SchemaCheck ⇒ b（参数不合 schema，真机里会被回灌原文重试）；
     * 4. schema 放行但语料标注为语义陷阱 ⇒ sem（校验层拦不住的一档）；
     * 5. 其余 ⇒ ok。
     */
    fun classify(registry: ToolRegistry, case: HitCase): HitVerdict = when {
        registry.find(case.callTool) == null -> HitVerdict.c
        case.callTool != case.expectTool -> HitVerdict.a
        registry.validateCall(case.callTool, case.args) != null -> HitVerdict.b
        case.trap -> HitVerdict.sem
        else -> HitVerdict.ok
    }
}

/** 一批语料的读数（失败率 / 重试率 / 按工具分布）。 */
internal data class HitStats(
    val total: Int,
    val counts: Map<HitVerdict, Int>,
    /** 按工具聚合的失败条数（a+b+c+sem），Top-10 表的数据源。 */
    val byTool: Map<String, Int>,
    val retryChains: Int,
) {
    /** 失败率：语料口径（构造语料本来就以病灶为主，只许与报告同口径引用）。 */
    fun failureRate(): Double = (counts[HitVerdict.a]!! + counts[HitVerdict.b]!! +
        counts[HitVerdict.c]!! + counts[HitVerdict.sem]!!).toDouble() / total

    fun retryRate(): Double = retryChains.toDouble() / total

    companion object {
        fun of(registry: ToolRegistry, cases: List<HitCase>): HitStats {
            val verdicts = HashMap<HitVerdict, Int>()
            val tools = HashMap<String, Int>()
            cases.forEach { c ->
                val v = ToolHitJudge.classify(registry, c)
                verdicts.merge(v, 1, Int::plus)
                if (v != HitVerdict.ok) tools.merge(c.callTool, 1, Int::plus)
            }
            return HitStats(
                total = cases.size,
                counts = verdicts,
                byTool = tools,
                retryChains = cases.count { it.retryOf.isNotBlank() },
            )
        }
    }
}
