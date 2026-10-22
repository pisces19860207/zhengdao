// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单《第一百二十四批 · 多模态输入 v1》＋ 2026-10-21 用户口径：
// 「vision 唯一真相是 `Provider.vision`；路由用 `ModelRouter.need.vision`，不另存一份」
// 「不碰 [ModelCapabilityTable]，也不改 `ModelRoutingGateTest` G2」。
package com.example.zhengdao.baopu

/**
 * 「哪几条通道算**已验证能看图**」（第一百二十四批）——纯判据，零 Android，JVM 直测。
 *
 * ## 为什么只有这么点东西
 * 它**不是一套新的候选机器**。第一百二十四批的口径是把「找已验证通道」这件事
 * 拆成两件已有的事：
 *
 * 1. **谁有资格当候选**——第九十三批从第六十四批 `fallbackFrom` 里原样抽出来的
 *    [ProviderPrefs.routeCandidates]（排除当前通道、排除内置免费通道、预设必须已填 Key、
 *    自定义通道三件齐才进、序按 `Providers.all()`）。这条规矩**一处都不改**，
 *    改了就等于同仓库长出第二套「下一个该切谁」；
 * 2. **能力过不过得了**——[ModelRouter.satisfies] 配 `Need(vision = true)`。
 *    它的判据是 `Candidate.vision = provider.vision`（[ModelRouter.Candidate] 的 getter），
 *    所以「能不能看图」的真相仍然只在 [Provider.vision] 那一处，本文件不落第二份。
 *
 * 排序**故意没有**：今天 [ModelCapabilityTable] 的成本/延迟/质量三列全表 UNKNOWN
 * （第九十三批门禁 G9 钉着），拿不出依据的排序就是编。候选序＝名单序，
 * 卡上第一条就是它，理由写在 [VisionConsent.request] 的文案里。
 */
internal object VisionRouting {

    /** 这条能力的「需要」＝本轮要发图（与 [ModelRouter.Need.vision] 同一含义，不另立口径）。 */
    private val NEED_VISION: ModelRouter.Need = ModelRouter.Need(vision = true)

    /**
     * 已验证能看图、且**真的能切过去**的候选通道。
     *
     * @param currentId 当前通道 id（候选规则会排掉它——切回自己不算切换）
     * @param keyFor 取 Key 的接缝（Key 只从 [ProviderPrefs.storedKey] 来，**由调用方给**；
     *   本文件不碰 Context / SharedPreferences，与 `ModelRouter` 同一纪律）
     * @param customBase customModel 自定义通道三件里的两件（不齐就不进候选）
     */
    fun verifiedCandidates(
        currentId: String,
        keyFor: (String) -> String?,
        customBase: String? = null,
        customModel: String? = null,
        customProtocol: String? = null,
    ): List<Provider> = verifiedAmong(
        ProviderPrefs.routeCandidates(currentId, keyFor, customBase, customModel, customProtocol),
    )

    /**
     * 过滤内核（把「名单怎么来」与「怎么算已验证」分开，方便单测直接喂名单）。
     *
     * 判据只有一条：`Provider.vision == true`。`false` 一律当「未验证/不许自动路由」
     * ——**不区分**「有依据地不支持」与「没查过」：第一百二十四批明令不给
     * [Provider] 加 `visionState`，也不给能力表加列，一份真相两种读法就是漂移的起点。
     */
    fun verifiedAmong(candidates: List<Provider>): List<Provider> =
        candidates.filter { ModelRouter.satisfies(ModelRouter.candidateOf(it), NEED_VISION) }

    /**
     * 一句话读数（进 run_log；只有 id/model，**永不含 Key**）。
     *
     * [Provider] 是 data class，`toString()` 会把 `apiKey` 原文带出来 ⇒ 这里逐字段取，
     * 负例用例（`VisionConsentWiringTest`）塞哨兵 Key 对拍。
     */
    fun describe(candidates: List<Provider>): String =
        if (candidates.isEmpty()) {
            "已验证看图的候选通道：0 条"
        } else {
            "已验证看图的候选通道：${candidates.size} 条（" +
                candidates.joinToString("、") { "${it.id}/${it.model}" } + "）"
        }
}
