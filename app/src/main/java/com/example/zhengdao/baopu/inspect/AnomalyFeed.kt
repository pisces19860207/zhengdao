// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百三十九批 · **灾异证据源**（339 工单：给 323 承接方）——把「事实」与「判决」拆成两半的第一半。
//
// 出处：339 工单口径（御批 2026-10-31）：只发事实事件、**不判决**；第一版只认三类——
// 账实不符／越权越界／连 3 次 FAIL 含不可逆动作；**每件事必须带出处（账行 id 或 文件:行），无出处不发**。
// 判决（是不是灾异、进不进紧急通道）唯一归 `law/AnomalyGate`——本文件一个字都不判。
//
// ## 判据（输入→输出，逐条可执行）
//   ①**三类闭集**（[AnomalyFactKind]）：账实不符／越权越界／连3FAIL含不可逆——闭集之外没有第四类；
//   ②**无出处不发**（[AnomalyFeed.provenanceAnchor]）：出处必须是「账行 id」或「文件:行」，
//     可执行判据＝非空＋带冒号＋冒号后带数字锚（`op:1800000000000:1`／`agent/RunEngine.kt:1453` 都过；
//     「感觉很糟」「RunEngine.kt:」「:123」都拒）。拒＝返回 null＋不落账＋留一行日志——
//     没有出处的事件连被误信的资格都没有；
//   ③**连 3 次 FAIL 含不可逆**（[AnomalyFeed.observeExecutionFail]）：连续计数、成功打断；
//     第 3 次到线且本连击段**含过不可逆动作** ⇒ 发 [AnomalyFactKind.FAIL_STREAK] 事件并发后归零；
//     三连全是可逆 ⇒ 不发（口径写死「含不可逆」）；**无出处的失败连计数都不进**（不可审计的失败
//     不许替连击攒次数）；
//   ④**不判决**（源级）：本文件不许出现 AnomalyGate 的任何引用——事件只有事实列
//     （是什么/何时/出处/一句事实），没有「是不是灾异」这一列。
//
// ## 为什么证据源不判决
// 判决要 thresholds（改阈值须御批）、要「不可局部回滚」与证据行的复合判定——那是 AnomalyGate 的辖地。
// 证据源要是顺手判了，同一件事就有了两个口径（271-补 治的就是这个病）；而且无出处的「感觉很糟」
// 会在证据源这一层就被拦下，根本到不了判决层。
//
// ## 为什么只有采集
// 真实调用方（勘验层报账实不符／授权闸报越权越界／执行结果流报 FAIL）归在飞批次各自接线
// （RunEngine 归 325、GuardPipeline 归 298，都是别人租约里的文件）；本批交的是采集器本体＋账，
// 与 315 的 EmergencyWiring 同款：先有件、后有接线，门禁反钉。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.scout.LedgerFanout
import com.example.zhengdao.core.RunLog
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** 灾异证据源只认的三类事实（闭集；[wire] 是账上稳定键，文案会改键不许改）。 */
internal enum class AnomalyFactKind(val wire: String, val cn: String) {
    /** 账实不符：账上说做了/该有的，实地没有；或实地有的，账上没有。 */
    LEDGER_MISMATCH("ledger_mismatch", "账实不符"),

    /** 越权越界：做了授权范围之外的事（越授权/越白名单/越工作区）。 */
    OVER_SCOPE("over_scope", "越权越界"),

    /** 连 3 次 FAIL 含不可逆动作：连续失败计数到线，且本连击段出现过不可逆动作。 */
    FAIL_STREAK("fail_streak", "连3FAIL含不可逆"),
    ;
}

/**
 * 一条事实事件（只有事实列：是什么/何时/出处/一句事实＋机器键；**没有判决列**）。
 *
 * @param provenance 出处＝账行 id 或 文件:行（[AnomalyFeed.provenanceAnchor] 核验过才落得了账）
 * @param gist 一句事实陈述（写发生了什么，不写「是不是灾异」；空 ⇒ 拒收）
 * @param evidence 机器键（FAIL_STREAK 由采集器盖 `streak:N`/`irreversible:true`；调用方可附自己的键）
 */
internal data class AnomalyFactEvent(
    val id: String,
    val atMs: Long,
    val kind: AnomalyFactKind,
    val provenance: String,
    val gist: String,
    val evidence: List<String> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("v", VERSION)
        .put("id", id)
        .put("at", atMs)
        .put("kind", kind.wire)
        .put("src", provenance)
        .put("gist", gist)
        .put("ev", JSONArray(evidence))

    companion object {
        /** 行格式版本（改形状才 +1；加字段不算）。 */
        const val VERSION = 1

        fun parse(raw: String): AnomalyFactEvent? {
            val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            val kind = AnomalyFactKind.entries.firstOrNull { it.wire == o.optString("kind") } ?: return null
            val id = o.optString("id")
            val provenance = o.optString("src")
            val gist = o.optString("gist")
            if (id.isEmpty() || provenance.isBlank() || gist.isBlank()) return null
            return runCatching {
                AnomalyFactEvent(
                    id = id,
                    atMs = o.optLong("at", 0L),
                    kind = kind,
                    provenance = provenance,
                    gist = gist,
                    evidence = (o.optJSONArray("ev") ?: JSONArray()).let { a ->
                        (0 until a.length()).mapNotNull { i -> a.optString(i).ifEmpty { null } }
                    },
                )
            }.getOrNull()
        }
    }
}

/**
 * 灾异证据源（进程内单例）：只采集、只发事件、只记账——判决归 `law/AnomalyGate`（本层零引用）。
 *
 * 落点＝208 已装配的那棵目录（`灾异事件.jsonl`，与 紧急处置.jsonl 同一棵）；
 * 没装配（纯 JVM 单测）⇒ 只进内存，回放照样查得到。append-only、不设时间窗（账本同一纪律）。
 */
internal object AnomalyFeed {

    /** 账本文件名（与 紧急处置.jsonl 同一棵目录）。 */
    const val FILE_NAME = "灾异事件.jsonl"

    /** 连 FAIL 的口径：连 3 次（御批口径写死，改它＝改工单）。 */
    const val FAIL_STREAK_LEN = 3

    /** 内存里最多留多少条（回放以盘为准；这条只是"没装配落点"时的兜底）。 */
    private const val MEMORY_CAP = 200

    private val memory = ArrayList<AnomalyFactEvent>()
    private var seq = 0

    /** 连击计数：连续 FAIL 次数（成功打断；发事件后归零）。 */
    private var failStreak = 0

    /** 本连击段是否出现过不可逆动作。 */
    private var streakSawIrreversible = false

    /**
     * 报一类事实（账实不符／越权越界这两类走这里）。
     *
     * **无出处不发**：[provenance] 过不了 [provenanceAnchor] ⇒ 返回 null、不落账、留日志；
     * [gist] 空白同样拒——事实事件连"发生了什么"都说不清，就不是事实。
     */
    @Synchronized
    fun report(
        kind: AnomalyFactKind,
        provenance: String,
        gist: String,
        nowMs: Long = System.currentTimeMillis(),
        evidence: List<String> = emptyList(),
    ): AnomalyFactEvent? {
        val src = provenanceAnchor(provenance)
        if (src == null) {
            RunLog.log("抱朴灾异：无出处，不发（${kind.wire}｜出处=「${provenance.take(40)}」）")
            return null
        }
        if (gist.isBlank()) {
            RunLog.log("抱朴灾异：事实为空，不发（${kind.wire}｜$src）")
            return null
        }
        return emit(
            AnomalyFactEvent(
                id = "af:$nowMs:${nextSeq()}",
                atMs = nowMs,
                kind = kind,
                provenance = src,
                gist = gist.trim(),
                evidence = evidence,
            ),
        )
    }

    /**
     * 执行结果流报一笔 FAIL（连击计数）。
     *
     * 第 [FAIL_STREAK_LEN] 次连续 FAIL 且本连击段**含过不可逆动作** ⇒ 自动发
     * [AnomalyFactKind.FAIL_STREAK] 事件并发后归零；三连全是可逆 ⇒ 不发（口径：含不可逆才发）。
     * **无出处的失败连计数都不进**（出处核验失败 ⇒ 返回 null，连击原地不动）。
     */
    @Synchronized
    fun observeExecutionFail(
        irreversible: Boolean,
        provenance: String,
        nowMs: Long = System.currentTimeMillis(),
        evidence: List<String> = emptyList(),
    ): AnomalyFactEvent? {
        val src = provenanceAnchor(provenance)
        if (src == null) {
            RunLog.log("抱朴灾异：无出处的 FAIL 不计数（出处=「${provenance.take(40)}」）")
            return null
        }
        failStreak += 1
        if (irreversible) streakSawIrreversible = true
        if (failStreak < FAIL_STREAK_LEN || !streakSawIrreversible) return null
        val event = emit(
            AnomalyFactEvent(
                id = "af:$nowMs:${nextSeq()}",
                atMs = nowMs,
                kind = AnomalyFactKind.FAIL_STREAK,
                provenance = src,
                gist = "连续 ${failStreak} 次 FAIL，本连击段含不可逆动作（出处为最近一次失败的锚）",
                evidence = evidence + listOf("streak:$failStreak", "irreversible:true"),
            ),
        )
        failStreak = 0
        streakSawIrreversible = false
        return event
    }

    /** 一笔成功/非 FAIL 结果：连击打断（归零；「含不可逆」随之清掉）。 */
    @Synchronized
    fun observeExecutionOk() {
        failStreak = 0
        streakSawIrreversible = false
    }

    /**
     * 出处锚的**可执行判据**：非空＋带冒号＋冒号后带数字。
     *
     * 账行 id（`op:…`／`em:…`／`sr:…`）与 文件:行（`agent/RunEngine.kt:1453`）都长这样；
     * 「感觉很糟」「RunEngine.kt:」「:123」都过不了——出处必须锚得到一行账或一行代码。
     */
    internal fun provenanceAnchor(provenance: String): String? {
        val p = provenance.trim()
        if (p.isEmpty()) return null
        val idx = p.lastIndexOf(':')
        if (idx <= 0 || idx == p.length - 1) return null
        return if (p.substring(idx + 1).any { it.isDigit() }) p else null
    }

    /** 回放（不设时间窗：30 天后仍可读——账本同一纪律）。 */
    @Synchronized
    fun replay(limit: Int = 0): List<AnomalyFactEvent> {
        val fromFile = DecisionLedger.installedRoot()?.let { root ->
            runCatching {
                val f = fileIn(root)
                if (!f.isFile) emptyList() else f.readLines(Charsets.UTF_8).mapNotNull(AnomalyFactEvent::parse)
            }.getOrDefault(emptyList())
        }.orEmpty()
        val rows = (fromFile + memory).distinctBy { it.id }.sortedByDescending { it.atMs }
        return if (limit > 0) rows.take(limit) else rows
    }

    /** 落点文件（与 208 同一棵目录；没装配 ⇒ 只进内存）。 */
    fun fileIn(globalRoot: File): File = File(globalRoot, FILE_NAME)

    /** 清空（测试专用：单例状态会跨用例污染，谁用谁还）。 */
    @Synchronized
    fun resetForTest() {
        memory.clear()
        seq = 0
        failStreak = 0
        streakSawIrreversible = false
    }

    /** 落账（内存照记，落盘走 scout 的 [LedgerFanout]——与 EmergencyWiring 同一口径）。 */
    private fun emit(event: AnomalyFactEvent): AnomalyFactEvent {
        memory.add(event)
        if (memory.size > MEMORY_CAP) memory.removeAt(0)
        val root = DecisionLedger.installedRoot()
        if (root != null) {
            val ok = runCatching {
                LedgerFanout(listOf(root), FILE_NAME).appendLine(event.toJson().toString())
            }.getOrDefault(false)
            if (!ok) RunLog.log("抱朴灾异：账没落上（${event.id}）")
        }
        RunLog.log("抱朴灾异：${event.kind.cn}｜${event.gist}｜出处=${event.provenance}")
        return event
    }

    private fun nextSeq(): Int {
        seq += 1
        return seq
    }
}
