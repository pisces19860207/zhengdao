// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百八十四批 · 上半 ②：**准入流程**——把 345 的四层考卷与 365 的画像串成一条链，
// 末端交给 [AdmissionGate] 出结论，并把结论落盘（判据③「结果落盘可回看」）。
//
// 四步成链（每步留痕，照 365 交来的调用约定，**画像侧一个字没改**）：
//   ① `enroll(...)`      登记候选（谁、什么版本、什么时刻取证）
//   ② `.declare(自报)`   简历只存档（放行判定不读它——365 源级钉死）
//   ③ `.examine(5 探针留痕)`  笔试（判卷仍是 345 的考官，本文件不判）
//   ④ `.conductExam(四例留痕)` 品行考（335 四道坏题，本文件不判）
//   ＋ `refreshLedger(账行)` 绩效＝账本的只读投影
//   ⑤ 问 [AdmissionGate] 要结论（可用／只读／不用）——**判定不在本文件**
//
// 两个查询口照 365 的约定用：`canAccept` 由 AdmissionGate 经 clearance 消费；
// `needsReprobe` 随结论一并出（**它不改准入结论**——版本变了要重探，不是"门关了"）。
//
// 落盘两处（都在调用方给的根目录下，不自己造全局单例——免得又多一件"装了没人用"）：
//   · `准入画像-<agent>.json`  当前画像快照＋结论（可回看，`load` 读回等价对象）
//   · `准入账.jsonl`           一行一次准入（append-only：当时怎么判的本身是证据）
//
// 依赖方向（派工单「不许反」）：本文件 → 365 画像／345 考官；画像**不**反向依赖本文件与
// `law/AdmissionGate`（那个名字在本仓并不存在——365 报告里是假设名，本批按实际落地在 intake/）。
package com.example.zhengdao.baopu.intake

import com.example.zhengdao.baopu.policy.CapabilityProfile
import com.example.zhengdao.baopu.policy.TaskLedgerRow
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal object AdmissionFlow {

    /** 一次准入要喂的全部事实（时刻由调用方给：本文件不读时钟）。 */
    data class Request(
        val agentId: String,
        /** 自报版本（与画像作证版本同口径）。 */
        val version: String,
        /** 本轮考核取证时刻（毫秒）。 */
        val probedAtMs: Long,
        val selfReport: SelfReport,
        /** 五枚探针的行为留痕（缺哪枚哪枚按没过算，345 口径）。 */
        val probeTraces: Map<ProbeId, Trace>,
        /** 四道品行考的行为留痕（缺哪道哪道按没过算）。 */
        val conductTraces: Map<ConductQuestionId, Trace>,
        /** 账本投影（有就喂，没有就是空账——画像不自己造账）。 */
        val ledgerRows: List<TaskLedgerRow> = emptyList(),
        /** 现行版本（缺省＝自报版本）：对不上 ⇒ needsReprobe，但**不改准入结论**。 */
        val currentVersion: String = version,
    )

    /** 一次准入的完整留档：画像 ＋ 结论 ＋ 四步留痕 ＋ 重探标记。 */
    data class Record(
        val atMs: Long,
        val profile: CapabilityProfile,
        val admission: AdmissionGate.Admission,
        val needsReprobe: Boolean,
        /** 四步各自的留痕（给人看：每步到底考了几分、账有几行）。 */
        val trail: List<String>,
    ) {
        val agentId: String get() = profile.agentId

        /** 稳定键（进账本/回看用）：`accept_full`／`accept_read_only`／`reject_<kind>`。 */
        fun wire(): String = when (val a = admission) {
            is AdmissionGate.Admission.Accept -> "accept_" + a.tier.name.lowercase()
            is AdmissionGate.Admission.Reject -> "reject_" + a.kind.wire
        }

        /** 一行结论（给人看）。 */
        fun line(): String = AdmissionGate.line(profile, admission, needsReprobe)

        fun toJson(): JSONObject = JSONObject()
            .put("v", WIRE_VERSION)
            .put("at", atMs)
            .put("agent", profile.agentId)
            .put("admission", wire())
            .put("needs_reprobe", needsReprobe)
            .put("evidence", evidenceOf())
            .put("recovery", recoveryOf())
            .put("trail", JSONArray(trail))
            .put("profile", profile.toJson())

        companion object {

            /** 快照格式版本：字段加减必须动它（与画像的 WIRE_VERSION 各管各的）。 */
            const val WIRE_VERSION = 1

            fun fromJson(o: JSONObject): Record {
                val wire = o.getString("admission")
                return Record(
                    atMs = o.getLong("at"),
                    profile = CapabilityProfile.fromJson(o.getJSONObject("profile")),
                    admission = admissionOf(wire, o.optString("evidence"), o.optString("recovery")),
                    needsReprobe = o.getBoolean("needs_reprobe"),
                    trail = o.optJSONArray("trail").let { a ->
                        if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }
                    },
                )
            }

            private fun admissionOf(wire: String, evidence: String, recovery: String): AdmissionGate.Admission {
                val kind = AdmissionGate.RejectKind.entries.firstOrNull { wire == "reject_" + it.wire }
                if (kind != null) return AdmissionGate.Admission.Reject(kind, evidence, recovery)
                val tier = Clearance.entries.firstOrNull { wire == "accept_" + it.name.lowercase() }
                    ?: return AdmissionGate.Admission.Reject(
                        AdmissionGate.RejectKind.INCOMPETENT,
                        "落盘里的准入结论认不出（$wire）——按门不开处理，不猜",
                        "重跑一次准入",
                    )
                return AdmissionGate.Admission.Accept(tier)
            }
        }

        private fun evidenceOf(): String = when (val a = admission) {
            is AdmissionGate.Admission.Accept -> ""
            is AdmissionGate.Admission.Reject -> a.evidence
        }

        private fun recoveryOf(): String = when (val a = admission) {
            is AdmissionGate.Admission.Accept -> ""
            is AdmissionGate.Admission.Reject -> a.recovery
        }
    }

    /** 账本文件名（与 208／偏差账同一棵目录的习惯：调用方给的根目录下）。 */
    const val LEDGER_FILE = "准入账.jsonl"

    /** 画像快照文件名前缀。 */
    const val SNAPSHOT_PREFIX = "准入画像-"

    /** 四步成链：只加调用，判定全在 345 考官与 365 画像里（本文件不重判）。 */
    fun run(req: Request): Record {
        val profile = CapabilityProfile.enroll(req.agentId, req.version, req.probedAtMs)
            .declare(req.selfReport)
            .examine(req.probeTraces)
            .conductExam(req.conductTraces)
            .refreshLedger(req.ledgerRows)
        return Record(
            atMs = req.probedAtMs,
            profile = profile,
            admission = AdmissionGate.judge(profile),
            needsReprobe = profile.needsReprobe(req.currentVersion),
            trail = trailOf(profile),
        )
    }

    /** 落盘：画像快照（覆盖）＋ 准入账（追加一行）。根目录由调用方给。 */
    fun save(root: File, record: Record): File {
        root.mkdirs()
        val snapshot = snapshotFile(root, record.agentId)
        snapshot.writeText(record.toJson().toString(2))
        File(root, LEDGER_FILE).appendText(ledgerLine(record) + "\n")
        return snapshot
    }

    /** 回看：读回画像快照（没有 ⇒ null，不造空壳）。 */
    fun load(root: File, agentId: String): Record? {
        val f = snapshotFile(root, agentId)
        if (!f.isFile) return null
        return runCatching { Record.fromJson(JSONObject(f.readText())) }.getOrNull()
    }

    /** 回看：准入账逐行（原样返回，不改笔迹）。 */
    fun history(root: File): List<String> {
        val f = File(root, LEDGER_FILE)
        if (!f.isFile) return emptyList()
        return f.readLines().filter { it.isNotBlank() }
    }

    /** 快照落点（`准入画像-<安全名>.json`）。 */
    fun snapshotFile(root: File, agentId: String): File = File(root, SNAPSHOT_PREFIX + safeName(agentId) + ".json")

    // ── 内部：留痕与命名 ────────────────────────────────────────────────────

    private fun trailOf(profile: CapabilityProfile): List<String> {
        val perf = profile.performance()
        val probesHit = ProbeId.entries.count { profile.probes[it]?.pass == true }
        val conductHit = ConductQuestionId.entries.count { q -> profile.conduct.any { it.question == q && it.pass } }
        return listOf(
            "①自报：${profile.selfReport?.claimedCapabilities?.size ?: 0} 条（只存档，不参与放行）",
            "②笔试：$probesHit/${ProbeId.entries.size} 过",
            "③品行考：$conductHit/${ConductQuestionId.entries.size} 过",
            "④绩效：账行 ${profile.ledgerRows.size} 条（成功 ${perf.successes}／退回 ${perf.returneds}／越权 ${perf.violations}）",
        )
    }

    private fun ledgerLine(record: Record): String = JSONObject()
        .put("at", record.atMs)
        .put("agent", record.agentId)
        .put("version", record.profile.vouchedVersion)
        .put("admission", record.wire())
        .put("needs_reprobe", record.needsReprobe)
        .put("evidence", record.toJson().optString("evidence").take(EVIDENCE_LIMIT))
        .toString()

    /** 文件名安全化：只留字母数字与 `-_.`，其余换 `_`（防路径穿越；空名给占位）。 */
    private fun safeName(agentId: String): String {
        val cleaned = agentId.map { c ->
            if (c.isLetterOrDigit() || c == '-' || c == '_' || c == '.') c else '_'
        }.joinToString("")
        return cleaned.ifBlank { "未命名" }
    }

    /** 账行里证据的摘录上限。 */
    private const val EVIDENCE_LIMIT = 160
}
