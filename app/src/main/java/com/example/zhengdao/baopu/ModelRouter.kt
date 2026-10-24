// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单《第九十三批 · 模型路由 v1》§2 的**能力过滤**部分——
// 「输入：任务所需能力（vision/tools/context/thinking）」；第九十三批同时带进来一个
// AUTO_ROUTE 自动排序路径，但那个开关从落地起就没有写入口（`setAutoRoute` 生产零调用、
// key 私有），读到的永远是 false ⇒ 第一百六十九批按派工单 A 案移除排序/开关，
// 只留「这条通道做不做得到这件事」这一条判据（看图的 VisionRouting 正在用）。
//
// 零 Android 依赖（不碰 Context / SharedPreferences / 数据库）：判据全在这一个文件里 JVM 直测，
// 存储与读 Key 的活留在 ProviderPrefs（「Context-free 内核 ＋ SP 薄壳」）。
package com.example.zhengdao.baopu

/**
 * 能力过滤的**判据本体**。
 *
 * 三句话：
 * 1. 「做不到」要**有依据**才算：表里 UNKNOWN 与 NO 一样不许放行（宁可不选，不乱猜）；
 * 2. 缺什么要说清：`missingOf` 返回人类可读的缺项清单，空表 = 满足；
 * 3. 本文件不选人、不排序、不写状态：谁有资格当候选、切不切，仍是 `ProviderPrefs`
 *    与第六十四批状态机的活（「当前模型」只有一份真相）。
 *
 * ## Key 的纪律
 * ⚠️ [Provider] 是 data class，`toString()` **会把 apiKey 原文打出来** ⇒ 本文件任何字符串
 * 都不许内插一个 Provider 对象（只取 `.id` / `.model`）。用例把哨兵 key 塞进候选再核对输出。
 */
internal object ModelRouter {

    // ── 输入：任务所需能力 ────────────────────────────────────────────────

    /**
     * 这一件事需要什么能力（**没有一项是"猜出来的"**：调用方不知道就别传，缺省 false/null =
     * 「这项没要求」，与「要求不满足」是两码事）。
     *
     * @param vision 本轮要发图（第二十四批：能不能看图只看 `Provider.vision`）
     * @param tools 本轮要带原生 `tools` 数组（协议 A）
     * @param contextTokens 这一步至少要塞进窗口的 token 数；null = 没算过
     * @param thinking 想要的思考档位（`ModelParams` 的界面四档之一）；null = 不挑
     */
    data class Need(
        val vision: Boolean = false,
        val tools: Boolean = false,
        val contextTokens: Int? = null,
        val thinking: String? = null,
    )

    // ── 候选：Provider 真相 ＋ 表里那几列 ─────────────────────────────────

    /**
     * 一条候选的完整视图。
     *
     * `vision` / `windowTokens` **读的是 [Provider]**（唯一真相，[ModelCapabilityTable] 里没有
     * 这两列）；`row` 只补 Provider 表达不了的列。窗口的「没声明怎么算」也复用既有那一处
     * [ContextAssembler.ContextBudgets.windowOf]，路由不自带第二套回退。
     */
    data class Candidate(val provider: Provider, val row: ModelCapabilityTable.Row) {
        val vision: Boolean get() = provider.vision
        val windowTokens: Int get() = ContextAssembler.ContextBudgets.windowOf(provider)
    }

    /**
     * 把一个 [Provider] 配上它那行能力数据（**纯函数**；查不到行 ⇒ 全 UNKNOWN 的空行）。
     * 能力行只从 [ModelCapabilityTable] 来，vision/窗口只从 [Provider] 来 —— 两处的分工见那张表的 KDoc。
     */
    fun candidateOf(p: Provider): Candidate =
        Candidate(p, ModelCapabilityTable.rowOrUnknown(p.id, p.model))

    // ── 能力过滤 ─────────────────────────────────────────────────────────

    /**
     * 缺什么（返回人类可读的缺项清单；**空表 = 满足**）。
     *
     * 三种"做不到"要分开说，混成一句就是编：
     * - 「不支持」＝有依据地说 NO；
     * - 「无依据」＝表里 UNKNOWN，没查过 ⇒ 同样不许选（宁可不选，不乱猜）；
     * - 「档位清单无依据」＝知道它会思考，但不知道它认哪几档 ⇒ 挑特定档位时不算满足。
     */
    fun missingOf(c: Candidate, need: Need): List<String> {
        val out = mutableListOf<String>()
        if (need.vision && !c.vision) {
            out += "看图（${c.provider.id}/${c.provider.model} 的 Provider.vision=false）"
        }
        if (need.tools && c.row.toolSchema != ModelCapabilityTable.Support.YES) {
            out += "工具 schema（${explain(c.row.toolSchema)}）"
        }
        need.contextTokens?.let { want ->
            if (c.windowTokens < want) {
                out += "上下文窗口（要 $want，这条只有 ${c.windowTokens}）"
            }
        }
        need.thinking?.let { tier ->
            when {
                c.row.thinking == ModelCapabilityTable.Support.NO ->
                    out += "思考档位 $tier（这条通道不认思考字段：${c.row.channelId}）"

                c.row.thinking == ModelCapabilityTable.Support.UNKNOWN ->
                    out += "思考档位 $tier（${explain(c.row.thinking)}）"

                c.row.thinkingTiers.isEmpty() ->
                    out += "思考档位 $tier（知道它会思考，但档位清单没有依据 ⇒ 不猜）"

                tier !in c.row.thinkingTiers ->
                    out += "思考档位 $tier（清单只有 ${c.row.thinkingTiers.joinToString("/")}）"
            }
        }
        return out
    }

    fun satisfies(c: Candidate, need: Need): Boolean = missingOf(c, need).isEmpty()

    private fun explain(s: ModelCapabilityTable.Support): String = when (s) {
        ModelCapabilityTable.Support.UNKNOWN -> "没有依据，不猜"
        ModelCapabilityTable.Support.NO -> "有依据地不支持"
        ModelCapabilityTable.Support.YES -> "有依据地支持"
    }
}
