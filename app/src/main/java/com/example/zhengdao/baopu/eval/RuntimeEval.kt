// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第五十批 · 运行时提案自检 v1（2026-10-19 蓝总管派工单）。
//
// 这一层回答一个问题：**把模型提案落盘之后，抱朴干活的本事有没有当场变差。**
// 它把构建期门禁那套（`EvalHarness` + `eval/cases.json` 10 例）搬进运行时，
// 但有一个不许含糊的区别：**系统提示必须用「落盘后的有效人设」**（A 内核 ＋ 当前 B/C）。
// 拿 `BaopuPreset.personaText` 单独跑＝测的是改之前的人设，自检就是演戏（派工单 §0 硬约束）。
package com.example.zhengdao.baopu.eval

import android.content.Context
import com.example.zhengdao.baopu.LlmProtocol
import com.example.zhengdao.baopu.PersonaFiles
import com.example.zhengdao.baopu.PersonaLayer
import com.example.zhengdao.baopu.PersonaRules
import com.example.zhengdao.baopu.Provider
import com.example.zhengdao.baopu.ProviderPrefs
import com.example.zhengdao.baopu.Providers
import com.example.zhengdao.rootfs.RunLog
import java.io.File

/**
 * 运行时自检的系统提示组装（**唯一入口**）。
 *
 * 与线上同源：`RunEngine` 每轮也是 `PersonaRules.assemble(A, B, C)`，这里只是把同一组段
 * 拼成一段字符串（评测是单轮非流式，不需要分段元数据）。
 *
 * 单独成函数而不是就地拼：负例要能把「用落盘后人设」改成「只用 A 内核」并看到用例变红，
 * 有一个可 grep 的名字比内联一段 `assemble(...)` 好钉得多。
 */
/** 一次自检的整体读数（10 例跑完的结果）。 */
internal data class EvalRun(
    val results: List<CaseResult>,
    val elapsedMs: Long,
    /**
     * 被跳过的用例 id + 原因（第八十四批：带 persona fixture 的用例不在自检口径里，
     * 见 [EvalCases.PERSONAS]）。跳过必须留名——安静少跑几条比红更糟。
     */
    val skipped: List<Pair<String, String>> = emptyList(),
) {
    val total: Int get() = results.size
    val passed: Int get() = results.count { it.ok }
    val ok: Boolean get() = total > 0 && passed == total
    val failures: List<CaseResult> get() = results.filter { !it.ok }
    val tokensIn: Int get() = results.sumOf { it.tokensIn }
    val tokensOut: Int get() = results.sumOf { it.tokensOut }

    /** 谁挂了（给用户看的短名单，最多 3 条）。 */
    fun failedIds(): String = failures.take(3).joinToString("、") { it.id }
}

/**
 * 成本的工程估算（**不是账单**）。
 *
 * 口径：输入 ¥2 / 百万 token、输出 ¥8 / 百万 token。
 * 这个数不是拍的——派工单给的经验值是「10 例约 ¥0.09」，代入实测量级（入 ~30k / 出 ~3k）
 * 正好落在同一个数量级；真要记账必须以服务商账单为准。
 */
internal object EvalCost {
    private const val YUAN_PER_MILLION_IN = 2.0
    private const val YUAN_PER_MILLION_OUT = 8.0

    fun yuan(tokensIn: Int, tokensOut: Int): String {
        val y = tokensIn / 1_000_000.0 * YUAN_PER_MILLION_IN +
            tokensOut / 1_000_000.0 * YUAN_PER_MILLION_OUT
        return "%.2f".format(y)
    }
}

/** 用例来源：构建期从仓库 `eval/` 复制进 `assets/eval/`（仓库那份是唯一真源）。 */
internal object EvalAssets {
    const val CASES_PATH = "eval/cases.json"
    const val BASELINE_PATH = "eval/baseline.json"

    fun casesJson(context: Context): String? = runCatching {
        context.assets.open(CASES_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrNull()

    fun baselineJson(context: Context): String? = runCatching {
        context.assets.open(BASELINE_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrNull()
}

/**
 * 运行时自检的执行壳（**Context 相关的部分全在这里**，纯判据在 [ProposalSelfCheck]）。
 *
 * 三条边界：
 * 1. **不假装跑**：没 key / 通道不是 OpenAI 兼容形状 / 用例读不到 ⇒ 明确返回 `NotRun`，
 *    由上层显示「自检未跑（无可用通道）」，**既不算通过也不回滚**；
 * 2. **总预算 120s**：单例有自己的读超时，这里再掐一个总闸——超了就停，没跑的算失败；
 * 3. **临时工作区**：每例自己建临时目录（`EvalHarness` 原有房型），跑完不留业务目录。
 */
internal object RuntimeEval {

    /** 总时长上限（派工单 §3 建议 120s）。 */
    const val TOTAL_BUDGET_MS = 120_000L

    /**
     * 这条通道能不能跑自检。
     *
     * - 没有 key（`""`）⇒ 不能；
     * - 内置免费通道的占位 key（`"null"`）⇒ 不能——2026-10-17 真机实测整条 403，
     *   拿它跑只会得到 10 条网络失败，再被当成「人设变差」自动回滚，那是纯事故；
     * - 非 OpenAI 兼容协议（Anthropic）⇒ 不能——`EvalHarness` 只实现了一种请求形状。
     */
    fun unusableReason(provider: Provider): String? = when {
        provider.builtinFree -> "无可用通道（内置免费通道整条 403，2026-10-17 真机实测）"
        provider.apiKey.isBlank() || provider.apiKey == Providers.FREE_PLACEHOLDER_KEY -> "无可用通道（当前通道没填 Key）"
        provider.protocol != LlmProtocol.OPENAI_COMPAT -> "无可用通道（自检只支持 OpenAI 兼容通道）"
        else -> null
    }

    fun configOf(provider: Provider, systemPrompt: String): EvalConfig = EvalConfig(
        baseUrl = provider.baseUrl,
        model = provider.model,
        apiKey = provider.apiKey,
        systemPrompt = systemPrompt,
    )

    /**
     * 跑 10 例。`budgetMs` 内没跑完的用例记为失败（**不许挂死**）。
     *
     * `now` 可注入，便于 JVM 里把「预算超时」这条路径测出来（不联网、不真等两分钟）。
     */
    fun run(
        cases: List<EvalCase>,
        cfg: EvalConfig,
        budgetMs: Long = TOTAL_BUDGET_MS,
        now: () -> Long = { System.currentTimeMillis() },
    ): EvalRun {
        val started = now()
        val results = ArrayList<CaseResult>(cases.size)
        var cleaned = 0
        val skipped = mutableListOf<Pair<String, String>>()
        for (case in cases) {
            // 第八十四批：带 persona fixture 的用例**不在自检口径里**——自检验的是
            // 「用户自己那两层没把事办坏」，fixture 是写死的样板文案，拿它跑等于演戏。
            // 跳过必须留名（安静少跑几条比红更糟）；这类用例只在显式回放
            // （`ZHENGDAO_EVAL_PERSONA=1`，见 `EvalGateTest`）里跑。
            if (case.usesLayerFixture) {
                skipped += case.id to "persona fixture 用例（显式回放 ZHENGDAO_EVAL_PERSONA=1 才跑）"
                continue
            }
            if (now() - started > budgetMs) {
                results += CaseResult(
                    id = case.id,
                    note = case.note,
                    ok = false,
                    steps = 0,
                    failures = listOf("总时长超过 ${budgetMs / 1000}s，这一例没跑"),
                    finalText = "",
                    toolCalls = emptyList(),
                )
                continue
            }
            val r = EvalHarness.runCase(case, cfg)
            results += r
            // 临时工作区跑完就删（派工单 §3）：每例一个临时目录，留着就是 10 份垃圾，
            // 而且里面是模型当时写的产物。删不动只记账，不许静默吞。
            if (r.dir != null && runCatching { r.dir.deleteRecursively() }.getOrDefault(false)) cleaned++
        }
        if (cleaned > 0) RunLog.log("[proposal-selfcheck] 清理临时工作区 $cleaned 份")
        return EvalRun(results = results, elapsedMs = now() - started, skipped = skipped)
    }

    /**
     * 端到端：从 assets 读用例 → 用**落盘后的**人设拼系统提示 → 跑 → 返回读数。
     *
     * @return null 表示「跑不了」，原因由 [unusableReason] 或「用例读不到」给出（上层显示未跑）
     */
    fun runFor(
        context: Context,
        personaFiles: PersonaFiles,
        workspace: File,
        provider: Provider,
    ): EvalRun? {
        if (unusableReason(provider) != null) return null
        val json = EvalAssets.casesJson(context) ?: return null
        val cases = runCatching { EvalCases.parse(json) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        val prompt = PersonaPrompt.ofDisk(personaFiles, workspace)
        RunLog.log("[proposal-selfcheck] 开始：${cases.size} 例，通道 ${provider.id}/${provider.model}")
        val run = run(cases, configOf(provider, prompt))
        if (run.skipped.isNotEmpty()) {
            RunLog.log(
                "[proposal-selfcheck] 跳过 ${run.skipped.size} 条 persona fixture 用例：" +
                    run.skipped.joinToString("、") { it.first },
            )
        }
        // 全是 persona fixture 用例（本仓评测集另有 10 条非 persona 用例，正常到不了这）
        // ⇒ 没有可跑的自检：按「跑不了」处理，不拿空结果去回滚用户刚写的东西。
        if (run.results.isEmpty() && run.skipped.isNotEmpty()) return null
        return run
    }

    /** 自检能不能跑的原因（给 UI 用的那一句）。 */
    fun notRunReason(context: Context, provider: Provider): String? {
        unusableReason(provider)?.let { return it }
        return if (EvalAssets.casesJson(context) == null) "自检未跑（APK 里没有 eval/cases.json）" else null
    }

    /** 运行时用的 provider（与聊天同一份真源）。 */
    fun providerOf(context: Context): Provider = ProviderPrefs.current(context)
}
