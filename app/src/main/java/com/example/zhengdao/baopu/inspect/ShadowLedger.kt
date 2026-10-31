// 独立开发声明：本文件为本项目原创，不含第三方源码拷贝。
//
// 依据：派工单-2026-10-26-第二百三十九批-纠察 §9「影子跑完只留一份 shadow.json
// （动作清单＋影响面＋判据结果），明细进审计栏」。
//
// **§10② 裁定（2026-10-27）照此**：影子账 = **JSONL append-only，文件名
// `影子执行.jsonl`**；单里的 `shadow.json` **作废**。
// 裁定之前本批就已按 JSONL 落（当时的理由原样留着，证明这不是事后补的口径）：
// 单个 `shadow.json` 会被下一次预演覆盖 ⇒ 审计栏查不到历史，I6 也就无从知道
// 「上次不一致的那条后来有没有真被拦」；而本仓所有留痕（208 `决策记录.jsonl`、
// 219 `抽检结论.jsonl`、237 审计栏）都是追加式一行一条——一次动作多行、可追、
// 可 grep、不怕写一半。「一份」按「一次预演一份影子账」读——每行的形状就是
// §9 那三项：动作清单＋影响面＋判据结果。
//
// 房型照 [com.example.zhengdao.baopu.DecisionReview]：只追加、`@Synchronized`、
// 父目录不存在就建、**写失败返回 false 由调用方 log**（影子的账丢了不是事故，
// 但影子的**判定**丢了是——那由 [ShadowRehearsal] 的返回值负责，与这条写盘无关）。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.baopu.RiskClass
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** 一次影子预演的留痕（[ShadowLedger] 的一行）。 */
internal data class ShadowEntry(
    val id: String,
    val atMs: Long,
    /** 来源会话；-1 = 无会话（系统级动作）。 */
    val sessionId: Long,
    /**
     * 动作步号（[ActionStepClock] 发的那一半；**口径变更点 2026-10-30 / 306 批**：此前存量行恒 0，
     * 照原样留着不改写——追加式账本不回头改行；读侧对缺 `round` 格的老行按 0 收）。
     */
    val step: Int,
    /** 轮号（306 批起新行才带；0 ＝ 不在任何轮里/旧口径行，不冒充轮号）。 */
    val roundId: Int = 0,
    /** 219 判的档（线值；"null"＝没判过——**照实写**，不许填空串冒充 R0）。 */
    val riskWire: String,
    val reversible: Boolean,
    /** 动作清单（`tool:目标` 的短形；§9 点名的第一项）。 */
    val actions: List<String>,
    /** 推演所称 / 影子实测（§9 点名的第二项：影响面）。 */
    val claimed: ImpactShape,
    val observed: ImpactShape,
    /** 三件事的逐项结果（§9 点名的第三项：判据结果）。 */
    val items: List<ShadowItem>,
    val verdict: ShadowVerdict,
    /** 当时给模型的那一句（原样存下来：审计栏要能证明「只给了一句」）。 */
    val modelLine: String,
) {
    /** 会话×步骤——与 I1/I4 同一把联键（`persistToolExchange` 与 `enterAwaitConfirm` 共用它）。 */
    val key: String get() = ShadowLedger.keyOf(sessionId, step)

    /** 这一条是不是「不该放行」的那一类（不一致/接错）。 */
    val violated: Boolean get() = verdict == ShadowVerdict.MISMATCH || verdict == ShadowVerdict.MISWIRED

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("at", atMs)
        .put("session", sessionId)
        .put("round", roundId)
        .put("step", step)
        .put("risk", riskWire)
        .put("reversible", reversible)
        .put("actions", JSONArray(actions))
        .put("claimed", shapeToJson(claimed))
        .put("observed", shapeToJson(observed))
        .put("items", JSONArray().apply { items.forEach { put(itemToJson(it)) } })
        .put("verdict", verdict.wire)
        .put("model_line", modelLine)

    private companion object {
        fun shapeToJson(s: ImpactShape): JSONObject = JSONObject()
            .put("new", s.newFiles)
            .put("over", s.overwriteTotal)
            .put("del", s.deleteCount)
            .put("bytes", s.deleteBytes)
            .put("ledger", s.ledgerRows)
            .put("moved", s.moved)
            .put("clash", s.collisions)
            .put("paths", JSONArray(s.touchedPaths))
            .put("unknown", s.unknown)

        /** `passed` 三态用串存（JSON 的 null 与 false 在读回来时分不开，三态必须分得开）。 */
        fun itemToJson(i: ShadowItem): JSONObject = JSONObject()
            .put("k", i.key)
            .put("cn", i.cn)
            .put("p", if (i.passed == null) "unk" else if (i.passed) "y" else "n")
            .put("d", i.detail)
    }
}

/** 影子台账（读与写）。 */
internal object ShadowLedger {

    /** 文件名（与 208/219 同一棵目录：`抱朴记忆/`，用户可看可备份）。 */
    const val FILE_NAME = "影子执行.jsonl"

    /** 没判过风险档时写这个字面量（读的人一眼知道「这条没档」，而不是档丢了）。 */
    const val RISK_NONE = "none"

    /** 联键（会话×步骤）。 */
    fun keyOf(sessionId: Long, step: Int): String = "$sessionId:$step"

    /** 由判定造留痕（纯函数；id 缺省按「会话:步骤:时刻」推，够用且不引入全局序号）。 */
    fun entryOf(
        outcome: ShadowOutcome,
        actions: List<String>,
        risk: RiskClass?,
        reversible: Boolean,
        sessionId: Long,
        step: Int,
        atMs: Long,
        id: String = "",
        /** 306 批：轮号（缺省 0＝旧口径；新行由授权链从 [ActionStepClock] 盖章带真轮号）。 */
        roundId: Int = 0,
    ): ShadowEntry = ShadowEntry(
        // id 缺省按「会话:步骤:时刻」推：够用，且不引入全局序号（208 的 seq 是它的进程级
        // 状态，影子账不需要再背一个）。
        id = id.ifBlank { "sh:$sessionId:$step:$atMs" },
        atMs = atMs,
        sessionId = sessionId,
        step = step,
        roundId = roundId,
        riskWire = risk?.name ?: RISK_NONE,
        reversible = reversible,
        actions = actions.take(InspectDetailLimits.MAX_EVIDENCE_PER_FINDING),
        // 留痕里逐项结果与两侧影响面**原样存**；给模型的那一句单独算，
        // 两边各一份，门禁才钉得住「模型那句不含明细」。
        claimed = outcome.claimed,
        observed = outcome.observed,
        items = outcome.items,
        verdict = outcome.verdict,
        modelLine = outcome.modelLine,
    )

    /** 落点文件。 */
    fun fileIn(globalRoot: File): File = File(globalRoot, FILE_NAME)

    /** 追加一条（失败返回 false——影子的账丢一条不算事故，但调用方要 log）。 */
    @Synchronized
    fun append(globalRoot: File, entry: ShadowEntry): Boolean = runCatching {
        val f = fileIn(globalRoot)
        f.parentFile?.mkdirs()
        f.appendText(entry.toJson().toString() + "\n", Charsets.UTF_8)
        true
    }.getOrDefault(false)

    /** 现读（坏行跳过；新→旧；上限 [InspectLimits.READ_LIMIT] 行——不全检）。 */
    fun read(globalRoot: File, limit: Int = InspectLimits.READ_LIMIT): List<ShadowEntry> = runCatching {
        val f = fileIn(globalRoot)
        if (!f.isFile) return@runCatching emptyList()
        f.readLines(Charsets.UTF_8).asSequence()
            .mapNotNull { parseLine(it) }
            .sortedByDescending { it.atMs }
            .take(limit)
            .toList()
    }.getOrDefault(emptyList())

    /** 一行 ⇒ 一条（缺 id／认不出 verdict ⇒ null：自造定性一律不渲染，也不静默当别的）。 */
    fun parseLine(raw: String): ShadowEntry? {
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val id = o.optString("id")
        val verdict = ShadowVerdict.ofWire(o.optString("verdict")) ?: return null
        if (id.isEmpty()) return null
        return ShadowEntry(
            id = id,
            atMs = o.optLong("at", 0L),
            sessionId = o.optLong("session", -1L),
            step = o.optInt("step", 0),
            // 306 批：老行没有 round 格 ⇒ 0（旧口径原样收，不改写也不冒充轮号）。
            roundId = o.optInt("round", 0),
            riskWire = o.optString("risk", RISK_NONE),
            reversible = o.optBoolean("reversible", true),
            actions = strList(o.optJSONArray("actions")),
            claimed = shapeOf(o.optJSONObject("claimed")),
            observed = shapeOf(o.optJSONObject("observed")),
            items = itemsOf(o.optJSONArray("items")),
            verdict = verdict,
            modelLine = o.optString("model_line"),
        )
    }

    /** 不一致/接错的那些（巡按栏 I6 的命中集）。 */
    fun violations(entries: List<ShadowEntry>): List<ShadowEntry> = entries.filter { it.violated }

    /** 跑不出结论的那些（**不算通过**，也不进 HIT——它把整项降成 [InspectVerdict.NO_DATA]）。 */
    fun unverifiable(entries: List<ShadowEntry>): List<ShadowEntry> =
        entries.filter { it.verdict == ShadowVerdict.UNVERIFIABLE }

    /** 记录落在哪个 (会话,步骤) 上（I6 拿它和 run_log 的写痕迹对表）。 */
    fun keysOf(entries: List<ShadowEntry>): Set<String> = entries.map { it.key }.toSet()

    private fun strList(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull { i -> a.optString(i).ifEmpty { null } }
    }

    private fun shapeOf(o: JSONObject?): ImpactShape {
        if (o == null) return ImpactShape()
        return ImpactShape(
            newFiles = o.optInt("new", 0),
            overwriteTotal = o.optInt("over", 0),
            deleteCount = o.optInt("del", 0),
            deleteBytes = o.optLong("bytes", 0L),
            ledgerRows = o.optInt("ledger", 0),
            moved = o.optInt("moved", 0),
            collisions = o.optInt("clash", 0),
            touchedPaths = strList(o.optJSONArray("paths")),
            unknown = o.optBoolean("unknown", false),
        )
    }

    private fun itemsOf(a: JSONArray?): List<ShadowItem> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            ShadowItem(
                key = o.optString("k"),
                cn = o.optString("cn"),
                passed = when (o.optString("p")) {
                    "y" -> true
                    "n" -> false
                    else -> null
                },
                detail = o.optString("d"),
            )
        }
    }
}
