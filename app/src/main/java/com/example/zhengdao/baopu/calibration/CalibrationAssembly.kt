// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百七十四批 · 校准装配总装：660（身体设定/口径表）→ 722（三行）→ 738（注入）。
//
// 这一层只做三件事（工单边界：只做装配，不改 722 格式、不改 738 触发与预算）：
//   ① 把 738 的 provider 缝接到真源——660 BodyState 出不变量行；658 出现读才出状态读数；669 词表出词档行；
//   ② 触发式敏感层（工单追加）：只在命中 669 区名时给带 source+asOf 的 660 读数，未命中一条都不给；
//   ③ 改设定不重启即生效（追加三：「重建校准块」是内部动作，不设用户按钮）——
//      每轮按"设定文件＋口径表"的指纹现算，指纹变了就重建；状态行不参与指纹（它允许逐场变）。
//
// 不做的事：不写死任何身材数字、不给缺失状态填默认值、不进任何闸、不改判定、不新增检索实现。
package com.example.zhengdao.baopu.calibration

import android.content.Context
import com.example.zhengdao.baopu.BaopuRuntime
import com.example.zhengdao.baopu.body.AnatomyZone
import com.example.zhengdao.baopu.body.AnatomyZones
import com.example.zhengdao.baopu.state.form.BodyState
import com.example.zhengdao.baopu.state.form.FactRange
import com.example.zhengdao.baopu.state.form.GenitalZone
import com.example.zhengdao.baopu.state.readback.BodyReadback
import com.example.zhengdao.baopu.state.readback.ReadbackLines
import java.io.File
import java.util.Locale
import org.json.JSONObject

/** 装配层的现算结果：只缓存"不逐场变"的部分（660 状态 + 669 词表 + 指纹）。 */
private data class Snapshot(
    val fingerprint: String,
    val state: BodyState?,
    val setting: BodySetting?,
    val zones: List<AnatomyZone>,
    val halted: Boolean,
)

/** 660 → 722 → 738 的真装配。生产端唯一认识 Context 的地方（同 PackTools 房型）。 */
internal object CalibrationAssembly {

    /** 无设定时的不变量行口径——「未设定」，不编默认身材（判据②）。 */
    const val UNSET = "未设定"

    private const val FACTS_DIR = "docs/facts"

    /** 治理级应急关闭位（工作区文件，需 who+confirm 双字段；不是设置页里的日常开关）。 */
    const val REL_HALT = "抱朴记忆/校准/应急关闭.json"
    private const val CALIB_DIR = "抱朴记忆/校准"
    private const val PREPUCE_ZONE = "prepuce"

    const val ID_RANGE = "SENS-RANGE"
    const val ID_GIRTH = "SENS-GIRTH"
    const val ID_SHAPE = "SENS-SHAPE"
    const val ID_PREPUCE = "SENS-PREPUCE"
    const val ID_VESSEL = "SENS-VESSEL"
    const val ID_ZONE = "SENS-ZONE"

    /** 工作区根：生产由 install 注入；单测直接指临时目录。 */
    @Volatile
    var workspaceProvider: (() -> File?)? = null

    /** 脱敏回执落点（App 私有目录，append-only 单行 JSON）；null ⇒ 只留内存回执。 */
    @Volatile
    var ledgerProvider: (() -> File?)? = null

    @Volatile
    private var snapshot: Snapshot? = null

    /** 一次性把 738 的 provider 缝全部接到真源（重复调用只是重绑同一组闭包）。 */
    fun install(context: Context) {
        val app = context.applicationContext
        wire({ BaopuRuntime.workspaceRoot(app) }, { File(File(app.filesDir, LEDGER_DIR), LEDGER_NAME) })
    }

    /**
     * 真正的接线（生产与单测走同一条路，避免"测的是一套、装的是另一套"）：
     * 七根缝＝开关／词表／三行／敏感层／回执／711+719+723 取数。
     */
    fun wire(workspace: () -> File?, ledger: () -> File?) {
        workspaceProvider = workspace
        ledgerProvider = ledger
        CalibrationGovernance.traceSink = { line -> appendLedger(JSONObject().put(EVENT, line)) }
        CalibrationGovernance.fileHaltProvider = { current().halted }
        CalibrationWiring.enabled = { CalibrationGovernance.on() }
        CalibrationWiring.lexiconProvider = { lexicon() }
        CalibrationWiring.readbackProvider = { lines() }
        // ② PhysiologyProvider 的缝在 CalibrationPhysiology.sceneProvider：658 已合入本基线，
        //    但它的入参（六类逐场档位）在本单允许的数据源 660/665/719/711 里都没有 ⇒ 保持未绑，
        //    状态行按事实留"未接入＋前置单"。真源单落地时只改这一行，装配层其余不动。
        CalibrationWiring.entryProvider = { zones -> entries(zones) }
        CalibrationWiring.notesObserver = { notes -> observe(notes) }
        // ③ FactsProvider：711 索引 / 719 事实片 / 723 变体的取数（只读原文，解析在 738 那侧复用）。
        CalibrationWiring.observableProvider = { factsJson(OBSERVABLE_NAME) }
        CalibrationWiring.factsProvider = { factsJson(FACT_SLICES_NAME) }
        CalibrationWiring.variantProvider = { factsJson(VARIANTS_NAME) }
    }

    /** 真源原文：文件不在/读不动 ⇒ 空串（解析端拿到空表，不编条目）。 */
    fun factsJson(name: String): String {
        val root = workspaceProvider?.invoke() ?: return ""
        val file = File(File(root, FACTS_DIR), name)
        return runCatching { if (file.isFile) file.readText() else "" }.getOrDefault("")
    }

    /** 「重建校准块」：丢掉现算缓存。内部动作，没有按钮、不需要重启（追加三）。 */
    fun rebuild() {
        snapshot = null
    }

    /** 测试收尾：回到"未装配"，与"没有本单"逐字节一致（判据⑥）。 */
    fun resetForTest() {
        snapshot = null
        workspaceProvider = null
        ledgerProvider = null
        CalibrationPhysiology.sceneProvider = null
        CalibrationGovernance.resetForTest()
        CalibrationWiring.resetForTest()
    }

    // ── ① 三行（722 是唯一格式源）────────────────────────────────────────

    /** 三行现算：不变量行吃 660，状态行吃 658（缺位＝722 的留位），词档行吃 669。 */
    fun lines(): ReadbackLines {
        val s = current()
        return ReadbackLines(
            invariant = invariantLine(s),
            status = CalibrationPhysiology.statusLine(),
            vocab = vocabLine(s),
        )
    }

    private fun invariantLine(s: Snapshot): String {
        val state = s.state ?: return "${BodyReadback.INVARIANT_TAG} $UNSET（660无设定）"
        return ReadbackLines.clip(BodyReadback.invariantLine(state))
    }

    private fun vocabLine(s: Snapshot): String {
        if (s.zones.isEmpty()) return "${BodyReadback.VOCAB_TAG} ${ReadbackLines.UNWIRED}（669缺失）"
        return ReadbackLines.clip(BodyReadback.vocabLine(s.zones))
    }

    /** 触发词表：区名一律取 669 词表，词表读不到才回落 738 的内置兜底表。 */
    fun lexicon(): CalibrationLexicon {
        val names = current().zones.map { it.name }.filter { it.isNotBlank() }
        return if (names.isEmpty()) CalibrationLexicon.MINIMAL else CalibrationLexicon.MINIMAL.copy(zoneNames = names)
    }

    // ── ② 触发式敏感层（命中 669 区名才给，每条自带 source+asOf）──────────

    /** [matchedZones] 为空 ⇒ 一条都不给：未命中零注入，也不写回执（工单追加·纪律①③）。 */
    fun entries(matchedZones: List<String>): List<SourceEntry> {
        if (matchedZones.isEmpty()) return emptyList()
        val s = current()
        val g = s.state?.genital ?: return emptyList()
        val meta = s.setting ?: return emptyList()
        val out = ArrayList<SourceEntry>()
        out += rangeEntry(ID_RANGE, "勃起长度区间", g.lengthRangeMm)
        out += rangeEntry(ID_GIRTH, "直径区间", g.diameterRangeMm)
        out += textEntry(ID_SHAPE, "形态${g.shape}·弯曲${g.bendDirection}·表面${g.surfaceGrade}", meta)
        out += textEntry(ID_VESSEL, "青筋${g.vesselVisibility}", meta)
        out += g.zones.filter { it.zoneId == PREPUCE_ZONE }.map { zonePrepuceEntry(it) }
        out += g.zones.filter { it.name in matchedZones }.map { zoneEntry(it) }
        return out
    }

    private fun rangeEntry(id: String, label: String, r: FactRange): SourceEntry = SourceEntry(
        id = id, text = "$label ${num(r.low)}–${num(r.high)}${r.unit}", source = r.source, asOf = r.asOf,
    )

    private fun textEntry(id: String, text: String, meta: BodySetting): SourceEntry =
        SourceEntry(id = id, text = text, source = meta.source, asOf = meta.asOf)

    private fun zoneEntry(z: GenitalZone): SourceEntry = SourceEntry(
        id = "$ID_ZONE-${z.zoneId}",
        text = "${z.name}半径${num(z.radiusMm.low)}–${num(z.radiusMm.high)}${z.radiusMm.unit}·表面${z.surfaceGrade}",
        source = z.radiusMm.source,
        asOf = z.radiusMm.asOf,
    )

    private fun zonePrepuceEntry(z: GenitalZone): SourceEntry = SourceEntry(
        id = "$ID_PREPUCE-${z.zoneId}",
        text = "${z.name}${if (z.retractable) "可回缩" else "常态覆盖"}",
        source = z.radiusMm.source,
        asOf = z.radiusMm.asOf,
    )

    private fun num(v: Double): String =
        if (!v.isFinite()) ReadbackLines.UNWIRED else String.format(Locale.ROOT, "%.1f", v)

    // ── ③ 指纹现算：改设定不重启即生效；状态行不参与（逐场变）────────────

    private fun current(): Snapshot {
        val root = workspaceProvider?.invoke()
        val stamp = fingerprintOf(root)
        val cached = snapshot
        if (cached != null && cached.fingerprint == stamp) return cached
        return build(root, stamp).also { fresh ->
            val before = cached?.halted
            if (before != null && before != fresh.halted) CalibrationGovernance.traceHalt(fresh.halted)
            snapshot = fresh
        }
    }

    private fun build(root: File?, stamp: String): Snapshot = Snapshot(
        fingerprint = stamp,
        setting = CalibrationBodySource.readSetting(root),
        state = CalibrationBodySource.loadState(root),
        zones = if (root == null) emptyList() else runCatching { AnatomyZones.load(root) }.getOrDefault(emptyList()),
        halted = haltFileOf(root)?.let { CalibrationGovernance.sealOk(it) } == true,
    )

    /**
     * 治理级应急关闭位（工作区文件，重启仍生效）：`{who,confirm}` 两个字段都对上治理口径才算按下去。
     * 这不是日常开关——设置页里没有它，写它的人必须是治理角色，且每次生效都留痕（判据：追加三）。
     */
    private fun haltFileOf(root: File?): String? {
        if (root == null) return null
        val file = File(root, REL_HALT)
        return runCatching { if (file.isFile) file.readText() else null }.getOrNull()
    }

    /** 指纹＝设定文件、开关位与口径表每张表的名字＋大小＋改动时间（只 stat，不读内容）。 */
    private fun fingerprintOf(root: File?): String {
        if (root == null) return NO_ROOT
        val parts = ArrayList<String>()
        CalibrationBodySource.specFile(root)?.let { parts += stampOf(it) }
        File(root, CALIB_DIR).listFiles()?.sortedBy { it.name }?.forEach { parts += stampOf(it) }
        File(root, FACTS_DIR).listFiles()?.sortedBy { it.name }?.forEach { parts += stampOf(it) }
        return parts.joinToString("|")
    }

    private fun stampOf(f: File): String = "${f.name}:${f.length()}:${f.lastModified()}"

    // ── ④ 回执（脱敏：只有条数与类别，没有身体数值）──────────────────────

    private fun observe(notes: List<String>) {
        CalibrationGovernance.setReceipt(CalibrationGovernance.receiptOf(notes))
        val receipt = CalibrationGovernance.lastReceipt ?: return
        appendLedger(
            JSONObject()
                .put("entries", receipt.entryCount)
                .put(CATEGORIES, receipt.categories.joinToString(",")),
        )
    }

    private fun appendLedger(payload: JSONObject) {
        val file = ledgerProvider?.invoke() ?: return
        runCatching {
            file.parentFile?.mkdirs()
            file.appendText(payload.put("atMs", System.currentTimeMillis()).toString() + "\n")
        }
    }

    /** 治理只读摘要：只有原因与计数，不含任何身体数值。 */
    fun statusLineSummary(): String {
        val s = current()
        val body = if (s.state == null) "身体$UNSET（${CalibrationBodySource.degradeReason(workspaceProvider?.invoke())}）"
        else "身体已装配"
        val phys = "状态:${CalibrationPhysiology.wiredCount()}/4项带真读数"
        val zones = if (s.zones.isEmpty()) ReadbackLines.UNWIRED else "${s.zones.size}区"
        val gate = if (s.halted) " · 应急关闭中" else ""
        return "$body · $phys · 词档$zones$gate"
    }

    private const val LEDGER_DIR = "calibration"
    private const val LEDGER_NAME = "注入账.jsonl"
    private const val EVENT = "event"
    private const val CATEGORIES = "categories"
    private const val NO_ROOT = "no-root"

    /** 711 / 719 / 723 三张真源表在 docs/facts 下的文件名。 */
    private const val OBSERVABLE_NAME = "observable-index.json"
    private const val FACT_SLICES_NAME = "fact-slices.json"
    private const val VARIANTS_NAME = "variants.json"
}
