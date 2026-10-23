// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第九十九批（2026-10-20 · 进化闭环 v1 §1.5.2）：**eval 证据的唯一口径**——
// 只认**最近一次发版 eval 的结果文件**（90/94 的产物 `eval/report-<毫秒>.json`，
// 由构建期挑最新一份 stage 进 `assets/eval/eval-result.json`，见 app/build.gradle.kts
// 的 StageEvalAssetsTask）。
//
// 第一百六十八批：同文件新增 [ProdEvalResultFile]（本机生产证据的读壳）。两份读壳
// **分开两个对象、同一把尺判形状**；先读谁后读谁在 `EvalEvidence.selectEvalEvidence`
// （纯函数，JVM 直测）——本文件只负责「读得到/读不到」与「形状对不对」。
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
        return EvolutionEval(
            score = rate,
            caseIds = ids,
            // 第一百六十八批：来源恒为发版证据（这个对象读的就是 assets 里那份）；
            // ranAt 旧报告没有（如 2026-10-21 rerun）⇒ null ⇒ 卡上显示「日期不详」，
            // 不许从文件名或 mtime 编一个——判不了就不猜（99 批同款口径）。
            source = EvalEvidenceSource.RELEASE,
            ranAt = if (o.has("ranAt")) o.optLong("ranAt").takeIf { it > 0L } else null,
        )
    }
}

/**
 * 第一百六十八批：本机 ProdEval 产物的读取壳（`filesDir/eval/prod-result.json`，
 * 见 [ProdEval.artifactFile]）。
 *
 * 与 [EvalResultFile] 的差别只有两条，都必须钉死：
 * 1. **必须带 ranAt**：决策口按 7 天保鲜期选它，没有时间戳就判不了新鲜程度
 *    ⇒ 按没有处理（不猜新鲜度，也不拿旧产物当过线依据）；
 * 2. **必须对得上 schema**：`zhengdao-prod-eval/1`（[ProdEval.PROD_EVAL_SCHEMA]）——
 *    形状不对一律 null（99 批「读不动／形状不对 ⇒ null」同款）。
 */
internal object ProdEvalResultFile {

    /** 现读本机产物；没有／读不动／形状不对 ⇒ null（上层按「没有个人生产证据」办）。 */
    fun latest(context: Context): EvolutionEval? {
        val file = ProdEval.artifactFile(context)
        if (!file.isFile) return null
        val json = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        return parse(json)
    }

    /** 纯解析：schema 对上 ＋ passRate 0–1 ＋ ranAt 在 ⇒ [EvolutionEval]；否则 null。 */
    fun parse(json: String): EvolutionEval? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (o.optString("schema") != ProdEval.PROD_EVAL_SCHEMA) return null
        if (!o.has("passRate")) return null
        val rate = o.optDouble("passRate", Double.NaN)
        if (rate.isNaN() || rate !in 0.0..1.0) return null
        // 没有 ranAt ⇒ 判不了新鲜度 ⇒ 没有证据（DEFER 的一种，不是通过）
        val ranAt = o.optLong("ranAt", 0L).takeIf { it > 0L } ?: return null
        val arr = o.optJSONArray("cases")
        val ids = buildList {
            for (i in 0 until (arr?.length() ?: 0)) {
                val id = arr?.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }
                if (id != null) add(id)
            }
        }
        return EvolutionEval(
            score = rate,
            caseIds = ids,
            source = EvalEvidenceSource.PERSONAL_PROD,
            ranAt = ranAt,
        )
    }
}
