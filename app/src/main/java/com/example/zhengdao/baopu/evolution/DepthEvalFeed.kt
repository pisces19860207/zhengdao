// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百六十三批 · 深度裁定的**右相证据位改读 495 评估账**（517 的残留）。
//
// ## 517 当时读的是什么，缺什么
//
// 517 把深度裁定接进生产时，右相的证据只从一个地方来：`gap is ProofGap.Available`
// ——即「本场有没有合格的四条验证读数」。那是**验收读数**（CI 跑出来的 unit/regression/
// anchor/detekt），说的是"这一版改得对不对"，不是"这台机器上评测跑没跑过"。
//
// 495 的账（`evolution/trigger-ledger.jsonl`，304 的触发账，495 把它的缺读数语义分流出去）
// 里躺着的才是**本机评测读数**：每次触发（收工／任务失败／手动）跑完一轮评测，一行，
// 带 run/passed/passRate。深度定档要考虑"这件事有没有证据"，这两本都得看：
//   · 四条验证读数在 ⇒ 这一版验过；
//   · 评测账里有读数 ⇒ 这台机器真的评过（不是没跑过）。
//
// 所以本批只做一件事：把 495 那本账接进 [EvidenceFacts] 的"证据足"这一格，
// 且**两本取合**（缺任一本都不算证据足）。这是收紧，不是放松——505 判据②的老话：
// "不知道"永远不升档。
//
// ## 三条不许碰
//
// 1. **两本旧账原样不动**：`进化尝试.jsonl` 仍是 7 列闭集（[EvolutionAttemptRow.wire]）、
//    三个触发名仍是 MISJUDGMENT／HUMAN_ORDER／GAP；`进化深度.jsonl` 仍是"一次定档一行"
//    （[EvolutionDepth.adjudicate] 每次只 append 一次）。本批只在**读侧**多读一本账。
// 2. **历史深度账仍能读**：本批只往 `rd`（当时读数那张自由表）里**加键**，不删不改旧键、
//    不升 [DepthLedger.VERSION]——老行缺 eval* 键照样 parse 成 [DepthDecision]，
//    不回填、不改写。
// 3. **读账不许写账**：[DepthEvalFeed.read] 是纯读（无 append、无 mark），
//    因此读一万遍也不会把 [DepthLedger.latest] 顶掉——这条有负例钉着。
//
// ## 字段名的唯一真源
//
// 495 账的 schema 归 304 的 `TriggerRowCodec`（同包另一处）。本文件**不 import** 那个包：
// `evolution.trigger` 已经 import `evolution.eval`，反向再拉一条会让包图打结；这里只按
// 已公开的字段名取五格，并由测试 `TriggerRowCodec.encode → DepthEvalFeed.read` 对拍
// （字段名一改，那条测试立刻红——schema 漂移由测试守，不靠注释守）。
package com.example.zhengdao.baopu.evolution

import java.io.File
import org.json.JSONObject

/**
 * 495 评估账里的一行读数（只取判档要用的几格，不复制 304 的 13 字段面）。
 *
 * @param atMs 这行评测的时刻（账上 `at`）——**这就是"可追"的那把钥匙**：拿它＋type 回账上
 *   就能找到原行；定档行的 `rd` 里记的就是它。
 * @param type 触发类型（账上 `type`：RUN_FAILED／SKILL_EVENT／MANUAL 之一）
 * @param ran 评测跑出的用例数（`evalRan`；0＝没跑出读数）
 * @param passed 通过数（`evalPassed`）
 * @param total 总用例数（`evalTotal`）
 * @param passRate 通过率（`evalPassRate`；null＝没跑出读数，**不许写成 0.0 冒充全挂**）
 * @param ref 证据引用（`ref`：`task:<run_id>` / `skill_event:<id>` / `manual`）
 */
internal data class DepthEvalReading(
    val atMs: Long,
    val type: String,
    val ran: Int,
    val passed: Int,
    val total: Int,
    val passRate: Double?,
    val ref: String,
) {
    /**
     * 评测这一侧的证据算不算足。
     *
     * 口径刻意只问"有没有"，不问"好不好"：过没过线是决策口 `EvolutionPolicy` 的
     * `EVAL_BELOW_FLOOR` 那件事，在这里再造一条阈值线就是第二套判据（495 报告 §五·2 点名过
     * 同款风险）。运行数为 0 或通过率解析不出，都算"没评过"。
     */
    fun evidenceEnough(): Boolean = ran > 0 && total > 0 && passRate != null

    /** 账上那一行的地址（诊断/报告用；`at` 是毫秒时刻，与账上 `at` 同值）。 */
    fun trace(): String = "eval@${atMs}/${type}${if (ref.isBlank()) "" else "/$ref"}"
}

/**
 * 495 评估账的读侧（**纯读**：不 append、不 mark、不改文件）。
 *
 * 只认两种行：`triggered=true`（真跑过评测）且 `evalRan>0`（真出了读数）；
 * 取 `at` 最大的那一行。一行都没有 ⇒ null（＝这台机器还没评过，判档按"证据不足"走）。
 */
internal object DepthEvalFeed {

    /** 读一本账（文件不在／全坏行 ⇒ null；坏行逐行丢弃，不许一行坏账让整本读不出来）。 */
    fun read(file: File): DepthEvalReading? {
        if (!file.isFile) return null
        val lines = runCatching { file.readLines(Charsets.UTF_8) }.getOrDefault(emptyList())
        var best: DepthEvalReading? = null
        for (raw in lines) {
            if (raw.isBlank()) continue
            val row = parse(raw) ?: continue
            // 被限频挡下的行（triggered=false）与"跑了但 0 例"的行都不是读数：
            // 后者连通过率都没有，收进来只会让 rd 里出现一个骗人的 evalAt。
            if (!row.first || row.second.ran <= 0) continue
            val cur = best
            if (cur == null || row.second.atMs >= cur.atMs) best = row.second
        }
        return best
    }

    /** 一行 JSON ⇒ (是否真跑过, 读数)；解析不出／必需格缺失 ⇒ null（坏行）。 */
    private fun parse(raw: String): Pair<Boolean, DepthEvalReading>? = runCatching {
        val o = JSONObject(raw)
        val triggered = o.getBoolean("triggered")
        DepthEvalReading(
            atMs = o.getLong("at"),
            type = o.getString("type"),
            ran = o.optInt("evalRan", 0),
            passed = o.optInt("evalPassed", 0),
            total = o.optInt("evalTotal", 0),
            passRate = if (o.has("evalPassRate")) o.getDouble("evalPassRate") else null,
            ref = o.optString("ref"),
        ) to triggered
    }.getOrNull()?.let { (reading, triggered) -> triggered to reading }

    /**
     * 装配缝：495 账的文件由谁给（`BaopuRuntime` 装，`EvalTriggerWiring.ledgerFile(appCtx)`）。
     *
     * 为什么不在本文件里拼路径：本包零 Android、零 Context（与 [DepthFactsWiring] 同一房型）；
     * 没装 ⇒ 读不到 ⇒ "证据不足" ⇒ 停在浅档（保守方向，不是遗漏）。
     */
    @Volatile
    var ledgerFile: (() -> File?)? = null

    /** 读装配进来的那本账；没装／读抛了 ⇒ null（不许一个 IO 异常把定档带塌）。 */
    fun readInstalled(): DepthEvalReading? =
        runCatching { ledgerFile?.invoke()?.let { read(it) } }.getOrNull()

    /** 直测收尾（谁装谁还）。 */
    fun resetForTest() {
        ledgerFile = null
    }
}
