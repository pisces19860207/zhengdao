// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第九十九批（2026-10-20 · 进化闭环 v1 §1.5.2）：**eval 证据的唯一口径**——
// 只认**最近一次发版 eval 的结果文件**（90/94 的产物 `eval/report-<毫秒>.json`，
// 由构建期挑最新一份 stage 进 `assets/eval/eval-result.json`，见 app/build.gradle.kts
// 的 StageEvalAssetsTask）。
//
// 三条硬口径（派工单原文）：
//   1. 没有结果文件 ⇒ 明确返回 null（上层明写 eval=null ⇒ policy 判 DEFER，
//      UI 理由看得出「eval 未跑」）——**不许拿旧结果冒充、不许默认过线**；
//   2. 读不动／形状不对 ⇒ 同样 null（判不了就不猜，不编一个分数）；
//   3. 「最近一次」在**构建期**定死（文件名时间戳最大那份），运行时只认固定名字——
//      运行时自己挑文件等于把「哪次算最近」变成设备状态，换台机器读数就不同。
package com.example.zhengdao.baopu.eval

import android.content.Context
import com.example.zhengdao.baopu.EvolutionEval
import org.json.JSONObject

/** 发版 eval 结果文件的读取壳（Context 相关只在这一层；[parse] 是纯函数，可 JVM 直测）。 */
internal object EvalResultFile {

    /** stage 进 assets 后的固定名字（运行时只认它）。 */
    const val FILE_NAME = "eval-result.json"

    /** assets 里的路径（与 [EvalAssets] 同一目录口径）。 */
    const val ASSET_PATH = "eval/$FILE_NAME"

    /**
     * 最近一次发版 eval 的结论；没有／读不动／形状不对 ⇒ null。
     *
     * 读失败只当没有（不抛）：证据供给断一条不该把收尾判定带崩（65/89「只记录不阻塞」同款口径）。
     */
    fun latest(context: Context): EvolutionEval? {
        val json = runCatching {
            context.assets.open(ASSET_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull() ?: return null
        return parse(json)
    }

    /**
     * 纯解析：`passRate` 在 0–1 且带用例 id  ⇒ [EvolutionEval]；否则 null。
     *
     * 只认 `passRate`（通过率）一个分数：它就是 94 批报告里与基线对比的那个数，
     * policy 的 evalFloor（默认 0.8）直接与它比——不另造加权分。
     */
    fun parse(json: String): EvolutionEval? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (!o.has("passRate")) return null
        val rate = o.optDouble("passRate", Double.NaN)
        if (rate.isNaN() || rate !in 0.0..1.0) return null
        val arr = o.optJSONArray("cases")
        val ids = buildList {
            for (i in 0 until (arr?.length() ?: 0)) {
                val id = arr?.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }
                if (id != null) add(id)
            }
        }
        return EvolutionEval(score = rate, caseIds = ids)
    }
}
