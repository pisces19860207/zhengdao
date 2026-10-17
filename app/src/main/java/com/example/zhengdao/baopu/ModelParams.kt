// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第十四批（刀 3 · B 段）§2 C 段——思考深度四档（关/低/高/最高 =
// none/low/high/max，默认 high）＋ §7 白名单「新建 baopu/ModelParams.kt（参数解析
// 与格式化）」；第十二批追加一 §11.3——设置页只读展示「上下文/输出上限」两个参数。
//
// 实测取证（①，2026-10-17 蓝总管探针 D:\zd-tools\ds-effort-probe2.txt + 本会话复探）：
// - `reasoning_effort` 是**真字段**：8 档 none/minimal/low/medium/high/xhigh/ultra/max，
//   非法值 422 并把枚举报回来；未传时服务端默认 high；
// - `effort` / `max_reasoning_effort` 是**假字段**：塞 banana 仍 200（静默忽略），禁发。
package com.example.zhengdao.baopu

import android.content.Context
import java.util.Locale

/**
 * 模型参数的**纯判据内核 + 薄壳**（刀 3 B 段 · 2026-10-17）。
 *
 * 为什么抽 Context-free 内核：本仓无 Robolectric ⇒ SharedPreferences 在 JVM 单测里
 * 实例化不了（ProviderPrefs 同款教训）。四档映射、非法值归一、参数摘要格式化全是
 * 纯函数，JVM 直测；薄壳只做 SP 读写。
 *
 * ## 三个不要混淆的「默认 high」
 * 1. [DEFAULT_EFFORT]：**界面**当前档位（用户没选过时显示「高」）；
 * 2. DeepSeek 预设自带 `reasoningEffort = "high"`（服务端默认也是 high）；
 * 3. 请求体只在 provider **显式声明**了 effort 时才发 `reasoning_effort`
 *    （派工单原话「按预设发」）——不替不认这个字段的通道瞎发（OpenAI 的
 *    gpt-4o-mini 会 400），这是与「无脑永远发 high」的分界线。
 */
internal object ModelParams {

    // ── 思考深度四档（界面值 = 服务端值，一字不差） ──────────────────────

    const val EFFORT_NONE = "none"
    const val EFFORT_LOW = "low"
    const val EFFORT_HIGH = "high"
    const val EFFORT_MAX = "max"

    /** 界面四档（顺序即设置页展示序）。 */
    val EFFORT_LEVELS: List<String> = listOf(EFFORT_NONE, EFFORT_LOW, EFFORT_HIGH, EFFORT_MAX)

    /** 服务端实际接受的 8 档（422 枚举实测；UI 只给四档，其余原样放行不拦）。 */
    val SERVER_EFFORT_LEVELS: List<String> = listOf(
        "none", "minimal", "low", "medium", "high", "xhigh", "ultra", "max",
    )

    /** 界面默认档（用户没选过、provider 也没声明时）。 */
    const val DEFAULT_EFFORT = EFFORT_HIGH

    /**
     * 归一化一个 effort 值：在服务端 8 档里 ⇒ 原样返回；不在（含 null/空/脏数据）
     * ⇒ null（**不猜**——调用方决定回落，缺省语义各不相同）。
     */
    fun normalizeEffort(raw: String?): String? {
        val v = raw?.trim()?.lowercase(Locale.ROOT) ?: return null
        return v.takeIf { it in SERVER_EFFORT_LEVELS }
    }

    /**
     * 设置页的当前档位：用户存过的选择优先；没存过看 provider 声明；都没有 ⇒ 默认 high。
     * （纯函数：两个入参都由壳层喂进来，JVM 直测——第十四批 J7「默认 high」判在这。）
     */
    fun uiEffort(stored: String?, providerEffort: String?): String =
        normalizeEffort(stored) ?: normalizeEffort(providerEffort) ?: DEFAULT_EFFORT

    // ── SP 薄壳（用户选过的思考深度；没选过 = 没有这个键） ─────────────────

    private const val FILE = "zhengdao-baopu-model-params"
    private const val KEY_EFFORT = "reasoning_effort"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 用户**显式选过**的档位（null = 没选过；请求体据此决定要不要覆盖预设）。 */
    fun storedEffort(context: Context): String? =
        normalizeEffort(prefs(context).getString(KEY_EFFORT, null))

    /** 保存档位（设置页「思考深度」落点；脏值不进 SP——[normalizeEffort] 先过）。 */
    fun saveEffort(context: Context, effort: String) {
        val v = normalizeEffort(effort) ?: DEFAULT_EFFORT
        prefs(context).edit().putString(KEY_EFFORT, v).apply()
    }

    // ── 参数摘要（第十二批追加一 §11.3 / 第十四批 J12：只读展示，不许编数） ──

    /** 两个参数的只读摘要（null = 该通道没声明 ⇒ 界面显示「未声明」，**不许显示 0 或 128000**）。 */
    data class ParamSummary(val window: String?, val maxOutput: String?)

    /**
     * 从 provider 元数据算摘要（纯函数）。千分位固定用 [Locale.ROOT] 口径，
     * 不随系统语言漂移（单测要能钉死 "1,048,576" 这个字符串）。
     */
    fun summaryOf(p: Provider): ParamSummary = ParamSummary(
        window = p.contextWindow?.takeIf { it > 0 }?.let { group(it) },
        maxOutput = p.maxOutput?.takeIf { it > 0 }?.let { group(it) },
    )

    private fun group(tokens: Int): String = String.format(Locale.ROOT, "%,d", tokens)
}
