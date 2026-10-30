// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第三百批 · 契约漂移（"别拿旧经验套新现实"）——**只做纯规则层**。
//
// 病根：网页改版／表格换表头／命令升版之后，技能还按旧契约跑，跑出来的"成功"是假的。
// 治法不是"改版就禁用"，是**先降级为需确认**——改版是常态，禁用是例外：
//
//   ① 指纹（三种目标，闭集）：
//        web ＝ 域名 ＋ 关键选择器集合（集合 hash，与书写顺序无关）
//        file＝ 扩展名 ＋ 表头/结构
//        cmd ＝ 路径 ＋ 版本串
//   ② 执行前比对：纯本地纯函数（不联网、不调模型、不读时钟），P90 远低于 50ms（`DriftCheckTest` 量）。
//        一致 ⇒ 照常；不一致 ⇒ **需确认**；连续 3 次不一致 ⇒ **自动停用** ＋ 进「未办成单」（原因＝现实漂移）。
//   ③ 重学通道：用户确认"这是新版" ⇒ 新指纹写回契约（`SkillContract.withContract` 一处写），
//        并记一条「重新校准」账。**没有确认人 ⇒ 一个字节都不改**（负例④的结构性拦截）。
//
// 四条负例各红在哪（都钉在类型/签名上，不靠调用方自觉）：
//   - 漂移直接禁用：`DriftCheck.record` 只有连续第 3 次才置 `disabledAtMs`，前两次只把闸降到 CONFIRM；
//   - 连 3 次不停用：`DISABLE_STREAK` 是常量，`record` 的返回值带 `disabledNow`，测试按它断言；
//   - 比对时联网/调模型：本文件只许四个 import（一个算 hash、三个是未办成单的类型），
//     网络/模型/时钟原语一条都不许出现（纯层判据在 `DriftCheckTest` D11 里扫源码）；
//   - 静默重写契约：`recalibrate` 的 `confirmedBy` 为空 ⇒ `Refused`，返回类型里根本没有"顺手写回"这条路。
//
// 本文件零 IO、零时钟、零 Android：parse／compare／record／recalibrate 全是纯函数，
// 时间由调用方喂（与 233／255 的纯层同一纪律）。落盘在 `DriftStore`，写正文仍只走 `SkillStore.apply`。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.scout.UnfinishedItem
import com.example.zhengdao.baopu.scout.UnfinishedOutcome
import com.example.zhengdao.baopu.scout.UnfinishedReason
import java.security.MessageDigest

/** 指纹盯的三种目标（闭集；认不得的类型 ⇒ 整行拒收，不猜）。 */
internal enum class DriftTarget(val wire: String, val cn: String) {
    WEB("web", "网页"),
    FILE("file", "文件"),
    CMD("cmd", "命令"),
    ;

    companion object {
        fun ofWire(wire: String): DriftTarget? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * 一份指纹。
 *
 * 存的是**分量**（域名/选择器/表头/路径/版本串），hash 由分量现算（[digest]）——
 * 分量与 hash 只有一份真相：手改了分量，hash 跟着变；不会出现"hash 说没变、分量变了"的两本账。
 */
internal sealed interface DriftFingerprint {

    val target: DriftTarget

    /** 归一后的规范串（集合类分量按字典序排：书写顺序变了不算漂移）。 */
    fun canonical(): String

    /** 给人看的一句（技能卡/账行用）。 */
    fun describe(): String

    /** 分量 ⇒ 短摘要（`fp-` ＋ sha256 前 16 位十六进制）。 */
    fun digest(): String = DriftCheck.digestOf(canonical())

    data class Web(val domain: String, val selectors: List<String>) : DriftFingerprint {
        override val target: DriftTarget get() = DriftTarget.WEB
        override fun canonical(): String = "web|$domain|${selectors.sorted().joinToString(",")}"
        override fun describe(): String =
            "网页 $domain（关键选择器 ${selectors.size} 个：${selectors.joinToString("、")}）"
    }

    data class FileShape(val ext: String, val headers: List<String>) : DriftFingerprint {
        override val target: DriftTarget get() = DriftTarget.FILE
        override fun canonical(): String = "file|$ext|${headers.sorted().joinToString(",")}"
        override fun describe(): String = "文件 .$ext（表头 ${headers.size} 列：${headers.joinToString("、")}）"
    }

    data class Command(val path: String, val version: String) : DriftFingerprint {
        override val target: DriftTarget get() = DriftTarget.CMD
        override fun canonical(): String = "cmd|$path|$version"
        override fun describe(): String = "命令 $path（版本串「$version」）"
    }
}

/**
 * 一次执行的**现场读数**（由执行壳喂进来；本层不自己去取，也不许去取）。
 *
 * 每个字段都可空：空读数在 [DriftCheck.compare] 里判 [DriftVerdict.NoData]，
 * **绝不当成"一致"**——"判不了"与"对得上"是两回事。
 */
internal sealed interface DriftObservation {

    val target: DriftTarget

    data class Web(val url: String?, val selectors: List<String>) : DriftObservation {
        override val target: DriftTarget get() = DriftTarget.WEB
    }

    data class FileShape(val name: String?, val headers: List<String>) : DriftObservation {
        override val target: DriftTarget get() = DriftTarget.FILE
    }

    data class Command(val path: String?, val version: String?) : DriftObservation {
        override val target: DriftTarget get() = DriftTarget.CMD
    }
}

/** 比对结论（三态；[NoData] 不算一致，也不算漂移——它不推进"连续 3 次"的计数）。 */
internal sealed interface DriftVerdict {

    /** 对得上：照常跑。 */
    data object Consistent : DriftVerdict

    /** 对不上：降级为需确认。[missing] 是契约里记着、现场却不见了的分量。 */
    data class Drifted(
        val missing: List<String>,
        val extra: List<String>,
        val why: String,
    ) : DriftVerdict

    /** 读数不全，判不了（fail-closed：不放过，也不冤枉）。 */
    data class NoData(val why: String) : DriftVerdict
}

/** 执行前的那道闸（[DISABLED] 只有"人确认新版并重新校准"能解开）。 */
internal enum class DriftGate(val cn: String) {
    RUN("照常"),
    CONFIRM("需确认"),
    DISABLED("已停用"),
    ;
}

/** 执行前的判定（闸 ＋ 一句人话 ＋ 这次的比对结论；没有指纹时 [verdict] 为 null）。 */
internal data class DriftPreflight(
    val gate: DriftGate,
    val reason: String,
    val verdict: DriftVerdict?,
    val stored: DriftFingerprint? = null,
)

/** 一条技能的漂移状态（`技能/<名>/漂移.json`；只存事件推不出来的东西）。 */
internal data class DriftState(
    /** 连续不一致次数（一致就归零；"连续"二字全靠这一格）。 */
    val streak: Int = 0,
    /** 最近一次结论的线值：`consistent`／`drifted`／`nodata`／空（还没比过）。 */
    val lastVerdict: String = "",
    val lastWhy: String = "",
    val lastAtMs: Long = 0L,
    /** 最近一次校准写回契约的指纹摘要（留证据，不当第二份契约）。 */
    val lastDigest: String = "",
    val disabledAtMs: Long? = null,
    val disabledWhy: String? = null,
    /** 重新校准次数（每次都是人点的"这是新版"）。 */
    val recalibrations: Int = 0,
    val lastRecalibratedAtMs: Long? = null,
)

/** 记一次比对的结论（纯函数产物；落盘由 [DriftStore] 照单写）。 */
internal data class DriftUpdate(
    val state: DriftState,
    val disabledNow: Boolean,
    val notes: List<String>,
)

/** 重新校准的两种结局：要么给出新正文（由写口落盘），要么拒（并说明为什么）。 */
internal sealed interface RecalibrationResult {

    data class Planned(
        /** 新 SKILL.md 正文（新指纹已写回契约，其余字节原样）。 */
        val body: String,
        val fingerprint: DriftFingerprint,
        val state: DriftState,
        val note: String,
    ) : RecalibrationResult

    data class Refused(val reason: String) : RecalibrationResult
}

internal object DriftCheck {

    /** 连续几次不一致 ⇒ 自动停用（派工单写死 3，不是可调参数）。 */
    const val DISABLE_STREAK = 3

    const val WIRE_OK = "consistent"
    const val WIRE_DRIFT = "drifted"
    const val WIRE_NODATA = "nodata"

    /** 指纹行里允许出现的字段（闭集；多一个都拒收）。 */
    private val FIELDS = mapOf(
        DriftTarget.WEB to setOf("domain", "selectors"),
        DriftTarget.FILE to setOf("ext", "headers"),
        DriftTarget.CMD to setOf("path", "version"),
    )

    /** 分量 ⇒ 短摘要（sha256 前 16 位；纯计算，不碰盘）。 */
    fun digestOf(canonical: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return "fp-" + d.take(8).joinToString("") { "%02x".format(it) }
    }

    // ── 读与写（契约行：`<!-- contract fingerprint web domain=… selectors=… -->`）──────

    /**
     * 读一行 `fingerprint <类型> <键=值>…`（[SkillContract.parse] 把段名剥掉后调这里）。
     *
     * 错误逐行记进 [errors]（不在第一处返回）；只要有一条 ⇒ 整份契约拒收（由 [SkillContract.parse] 定）。
     */
    fun parseLine(
        no: Int,
        rest: String,
        out: MutableList<DriftFingerprint>,
        errors: MutableList<String>,
    ) {
        val tokens = tokenize(rest)
        if (tokens == null) {
            errors += "第 $no 行：引号没闭合（值里有空格就用成对引号包起来）"
            return
        }
        if (tokens.isEmpty()) {
            errors += "第 $no 行：指纹行是空的（写法：<!-- contract fingerprint web domain=example.com " +
                "selectors=#a,#b -->）"
            return
        }
        val target = DriftTarget.ofWire(tokens[0])
        if (target == null) {
            errors += "第 $no 行：不认得的指纹类型「${tokens[0]}」（只许 ${DriftTarget.entries.joinToString("／") { it.wire }}）"
            return
        }
        val pairs = keyValues(tokens.drop(1))
        if (pairs == null) {
            errors += "第 $no 行：指纹的每个字段都要写成 键=值（例：domain=example.com）"
            return
        }
        val unknown = pairs.keys - FIELDS.getValue(target)
        if (unknown.isNotEmpty()) {
            errors += "第 $no 行：${target.cn}指纹不认得字段 ${unknown.joinToString("、")}" +
                "（只许 ${FIELDS.getValue(target).joinToString("／")}）"
            return
        }
        if (out.any { it.target == target }) {
            errors += "第 $no 行：${target.cn}指纹已经有一条了（同一类只许一条，免得比对时不知道认哪条）"
            return
        }
        if (pairs.values.any { it.contains(SkillContract.COMMENT_CLOSE) }) {
            errors += "第 $no 行：指纹值里不许出现 ${SkillContract.COMMENT_CLOSE}（会把整行注释拆断）"
            return
        }
        when (target) {
            DriftTarget.WEB -> webOf(no, pairs, out, errors)
            DriftTarget.FILE -> fileOf(no, pairs, out, errors)
            DriftTarget.CMD -> cmdOf(no, pairs, out, errors)
        }
    }

    /** 一份指纹 ⇒ 一行契约（**与 [parseLine] 互为逆运算**；含空格的值得加引号）。 */
    fun renderLine(fp: DriftFingerprint): String {
        val head = "<!-- ${SkillContract.MARK} ${SkillContract.SECTION_FINGERPRINT}"
        val body = when (fp) {
            is DriftFingerprint.Web ->
                "web domain=${fp.domain} selectors=${fp.selectors.joinToString(",")}"
            is DriftFingerprint.FileShape ->
                "file ext=${fp.ext} headers=${fp.headers.joinToString(",")}"
            is DriftFingerprint.Command ->
                "cmd path=${quoted(fp.path)} version=${quoted(fp.version)}"
        }
        return "$head $body -->"
    }

    private fun quoted(v: String): String = if (v.any { it.isWhitespace() }) "\"$v\"" else v

    // ── 比对（纯本地；不联网、不调模型、不读时钟）────────────────────────────────

    /**
     * 契约里的指纹 vs 现场读数。
     *
     * 口径：
     * - **少了就漂**（契约记着、现场不见）；多了不算漂（页面多一块不算改版），只记进 `extra`；
     * - 域名／路径／版本串是**整串相等**（子域变了也算漂——那正是"要人确认一下"的事）；
     * - 读不到（空读数）⇒ [DriftVerdict.NoData]，**不放过也不记漂移**。
     */
    fun compare(stored: DriftFingerprint, live: DriftObservation?): DriftVerdict {
        if (live == null) return DriftVerdict.NoData("没有现场读数，判不了")
        return when (stored) {
            is DriftFingerprint.Web ->
                if (live is DriftObservation.Web) compareWeb(stored, live) else targetMismatch(stored)
            is DriftFingerprint.FileShape ->
                if (live is DriftObservation.FileShape) compareFile(stored, live) else targetMismatch(stored)
            is DriftFingerprint.Command ->
                if (live is DriftObservation.Command) compareCmd(stored, live) else targetMismatch(stored)
        }
    }

    private fun targetMismatch(stored: DriftFingerprint): DriftVerdict =
        DriftVerdict.NoData("读数是别的类型，和${stored.target.cn}指纹对不上号")

    private fun compareWeb(stored: DriftFingerprint.Web, live: DriftObservation.Web): DriftVerdict {
        val host = SkillApplicability.hostOf(live.url)
            ?: return DriftVerdict.NoData("没有当前页面地址，判不了域名")
        val seen = live.selectors.map { it.trim() }.filter { it.isNotEmpty() }
        if (seen.isEmpty()) return DriftVerdict.NoData("页面上一个关键选择器都没读到，判不了")
        val missing = stored.selectors.filterNot { it in seen }
        if (host != stored.domain) {
            return DriftVerdict.Drifted(
                missing = listOf(stored.domain) + missing,
                extra = emptyList(),
                why = "域名对不上：契约记的是 ${stored.domain}，现在是 $host",
            )
        }
        if (missing.isNotEmpty()) {
            return DriftVerdict.Drifted(missing, emptyList(), "选择器不见了：${missing.joinToString("、")}")
        }
        return DriftVerdict.Consistent
    }

    private fun compareFile(stored: DriftFingerprint.FileShape, live: DriftObservation.FileShape): DriftVerdict {
        val name = live.name?.trim().orEmpty()
        if (name.isEmpty()) return DriftVerdict.NoData("没有文件名读数，判不了扩展名")
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext != stored.ext) {
            return DriftVerdict.Drifted(listOf(".${stored.ext}"), listOf(".$ext"), "扩展名变了：.$ext（契约记的是 .${stored.ext}）")
        }
        val seen = live.headers.map { it.trim() }.filter { it.isNotEmpty() }
        if (seen.isEmpty()) return DriftVerdict.NoData("没读到表头，判不了结构")
        val missing = stored.headers.filterNot { it in seen }
        if (missing.isNotEmpty()) {
            return DriftVerdict.Drifted(missing, emptyList(), "表头不见了：${missing.joinToString("、")}")
        }
        return DriftVerdict.Consistent
    }

    private fun compareCmd(stored: DriftFingerprint.Command, live: DriftObservation.Command): DriftVerdict {
        val path = live.path?.trim().orEmpty()
        if (path.isEmpty()) return DriftVerdict.NoData("没有命令路径读数，判不了")
        if (path != stored.path) {
            return DriftVerdict.Drifted(listOf(stored.path), listOf(path), "命令路径变了：$path（契约记的是 ${stored.path}）")
        }
        val version = live.version?.trim().orEmpty()
        if (version.isEmpty()) return DriftVerdict.NoData("没读到版本串，判不了")
        if (version != stored.version) {
            return DriftVerdict.Drifted(
                listOf(stored.version), listOf(version),
                "版本串变了：「$version」（契约记的是「${stored.version}」）",
            )
        }
        return DriftVerdict.Consistent
    }

    // ── 执行前的闸（把"结论"与"历史"合成一个动作）────────────────────────────────

    /**
     * 执行前判一次：**先看停用，再比指纹**。
     *
     * 停用是硬状态（只有重新校准能解），所以排在最前——否则"这次刚好又对上了"会把停用悄悄解开，
     * 那就成了"漂移自己恢复"，正是本批要防的假话。
     */
    fun preflight(
        fingerprints: List<DriftFingerprint>,
        live: DriftObservation,
        state: DriftState,
    ): DriftPreflight {
        state.disabledAtMs?.let {
            return DriftPreflight(
                DriftGate.DISABLED,
                "已停用（连续 $DISABLE_STREAK 次指纹对不上）：${state.disabledWhy ?: "现实漂移"}——" +
                    "确认这是新版后重新校准才恢复",
                null,
            )
        }
        val stored = fingerprints.firstOrNull { it.target == live.target }
            ?: return DriftPreflight(DriftGate.RUN, "这条技能没有为${live.target.cn}留指纹，不做漂移比对", null)
        val verdict = compare(stored, live)
        val gate = if (verdict is DriftVerdict.Consistent) DriftGate.RUN else DriftGate.CONFIRM
        return DriftPreflight(gate, describeVerdict(verdict), verdict, stored)
    }

    /** 单次结论 ⇒ 一句话（技能卡/卡上那行用）。 */
    fun describeVerdict(verdict: DriftVerdict): String = when (verdict) {
        DriftVerdict.Consistent -> "指纹对得上，照常跑"
        is DriftVerdict.Drifted -> "指纹对不上（${verdict.why}）——先问一句再动手"
        is DriftVerdict.NoData -> "指纹判不了（${verdict.why}）——先问一句再动手"
    }

    // ── 记一次比对（连续 3 次 ⇒ 停用）──────────────────────────────────────────

    /**
     * 把这次结论记进状态。
     *
     * 三条口径：
     * 1. **一致归零**（streak=0）——"连续"才算数，隔一次一致就重新数；
     * 2. **判不了不推进**（NoData 不动 streak）——读数缺了不该算到技能头上；
     * 3. **第 3 次才停用**（前两次只把闸降到 CONFIRM）——改版是常态，禁用是例外。
     */
    fun record(state: DriftState, verdict: DriftVerdict, atMs: Long): DriftUpdate {
        val stamped = state.copy(lastAtMs = atMs)
        return when (verdict) {
            DriftVerdict.Consistent -> DriftUpdate(
                stamped.copy(streak = 0, lastVerdict = WIRE_OK, lastWhy = ""),
                disabledNow = false,
                notes = listOf("指纹对得上，连续计数归零"),
            )
            is DriftVerdict.NoData -> DriftUpdate(
                stamped.copy(lastVerdict = WIRE_NODATA, lastWhy = verdict.why),
                disabledNow = false,
                notes = listOf("指纹判不了（${verdict.why}）——不计入连续次数，本次需确认"),
            )
            is DriftVerdict.Drifted -> onDrift(stamped, verdict, atMs)
        }
    }

    private fun onDrift(state: DriftState, verdict: DriftVerdict.Drifted, atMs: Long): DriftUpdate {
        val streak = state.streak + 1
        val next = state.copy(streak = streak, lastVerdict = WIRE_DRIFT, lastWhy = verdict.why)
        if (state.disabledAtMs != null || streak < DISABLE_STREAK) {
            return DriftUpdate(
                next,
                disabledNow = false,
                notes = listOf("指纹对不上（连续第 $streak 次）：${verdict.why} ⇒ 降级为需确认"),
            )
        }
        return DriftUpdate(
            next.copy(disabledAtMs = atMs, disabledWhy = verdict.why),
            disabledNow = true,
            notes = listOf(
                "连续 $DISABLE_STREAK 次指纹对不上 ⇒ 自动停用（技能正文一个字没动）：${verdict.why}",
                "已进「未办成单」（原因：现实漂移）——确认这是新版后重新校准才恢复",
            ),
        )
    }

    /** 状态 ⇒ 闸（界面/执行壳只认这一个口，别自己拆 lastVerdict）。 */
    fun gate(state: DriftState): DriftGate = when {
        state.disabledAtMs != null -> DriftGate.DISABLED
        state.lastVerdict == WIRE_DRIFT || state.lastVerdict == WIRE_NODATA -> DriftGate.CONFIRM
        else -> DriftGate.RUN
    }

    /** 停用 ⇒ 未办成单的一行（原因＝现实漂移；`reasonRef` 指回技能卡）。 */
    fun unfinishedItem(name: String, state: DriftState): UnfinishedItem = UnfinishedItem(
        intent = "用「$name」这条技能干活",
        outcome = UnfinishedOutcome.NOT_DONE,
        reason = UnfinishedReason.REALITY_DRIFT,
        reasonDetail = "连续 ${state.streak} 次指纹对不上（${state.disabledWhy ?: "现实漂移"}）",
        reasonRef = "skill:$name",
        fixable = true,
        fixHint = "确认这是新版后重新校准（把新指纹写回契约）",
        atMs = state.disabledAtMs ?: state.lastAtMs,
    )

    // ── 重新校准（必须人确认；没有确认人 ⇒ 一个字节都不改）──────────────────────

    /**
     * 用户点了"这是新版"之后，把现场指纹写回契约。
     *
     * **负例④的结构性拦截**：[confirmedBy] 空 ⇒ 直接 [RecalibrationResult.Refused]。
     * 返回值里只有"新正文"这一条路，且它必须交给写口（`SkillStore.apply`）才会落盘——
     * 本函数自己连一个 `File` 都碰不到，所以"静默重写契约"在这条路上无处发生。
     */
    fun recalibrate(
        body: String,
        live: DriftObservation,
        confirmedBy: String,
        state: DriftState,
        nowMs: Long,
    ): RecalibrationResult {
        if (confirmedBy.isBlank()) {
            return RecalibrationResult.Refused(
                "重新校准必须由人确认（\"这是新版\"）——机器不许自己改写契约；本次一个字节都没写",
            )
        }
        val contract = when (val parsed = SkillContract.parse(body)) {
            is SkillContract.Parsed.Contracted -> parsed.contract
            SkillContract.Parsed.Absent -> return RecalibrationResult.Refused("正文里没有契约，先补契约再校准")
            is SkillContract.Parsed.Invalid ->
                return RecalibrationResult.Refused("契约读不成：${parsed.errors.joinToString("；")}")
        }
        val fresh = fingerprintOf(live)
            ?: return RecalibrationResult.Refused(
                "现场读数不全，算不出新指纹（${describeVerdict(compareAny(contract.fingerprints, live))}）",
            )
        val first = contract.fingerprints.none { it.target == fresh.target }
        val next = contract.copy(
            fingerprints = contract.fingerprints.filterNot { it.target == fresh.target } + fresh,
        )
        val newState = state.copy(
            streak = 0,
            lastVerdict = WIRE_OK,
            lastWhy = "",
            lastAtMs = nowMs,
            lastDigest = fresh.digest(),
            disabledAtMs = null,
            disabledWhy = null,
            recalibrations = state.recalibrations + 1,
            lastRecalibratedAtMs = nowMs,
        )
        val note = (if (first) "首次写入指纹：" else "重新校准：") + fresh.describe() + "（确认人：$confirmedBy）"
        return RecalibrationResult.Planned(SkillContract.withContract(body, next), fresh, newState, note)
    }

    /** 从现场读数造指纹；读数不全 ⇒ null（不拿半份读数当新契约）。 */
    fun fingerprintOf(live: DriftObservation): DriftFingerprint? = when (live) {
        is DriftObservation.Web -> {
            val host = SkillApplicability.hostOf(live.url)
            val seen = live.selectors.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (host == null || seen.isEmpty()) null else DriftFingerprint.Web(host, seen)
        }
        is DriftObservation.FileShape -> {
            val name = live.name?.trim().orEmpty()
            val seen = live.headers.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (name.isEmpty() || seen.isEmpty()) null else DriftFingerprint.FileShape(name.substringAfterLast('.', "").lowercase(), seen)
        }
        is DriftObservation.Command -> {
            val path = live.path?.trim().orEmpty()
            val version = live.version?.trim().orEmpty()
            if (path.isEmpty() || version.isEmpty()) null else DriftFingerprint.Command(path, version)
        }
    }

    /** 同一类目标的第一份指纹拿来比（校准失败时用来说"缺什么"；没有 ⇒ NoData）。 */
    private fun compareAny(fingerprints: List<DriftFingerprint>, live: DriftObservation): DriftVerdict {
        val stored = fingerprints.firstOrNull { it.target == live.target }
            ?: return DriftVerdict.NoData("契约里还没有${live.target.cn}指纹")
        return compare(stored, live)
    }

    // ── 解析小工具（都纯）──────────────────────────────────────────────────────

    /**
     * 按空白切词，**成对引号里的空白不切**（版本串「git version 2.42.0」这类值要靠它）。
     * 引号没闭合 ⇒ null（整行拒收，不猜）。
     */
    private fun tokenize(raw: String): List<String>? {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var close: Char? = null
        for (ch in raw) {
            when {
                close != null -> if (ch == close) close = null else sb.append(ch)
                ch == '"' -> close = '"'
                ch == '“' -> close = '”'
                ch == '「' -> close = '」'
                ch.isWhitespace() -> if (sb.isNotEmpty()) {
                    out += sb.toString()
                    sb.clear()
                }
                else -> sb.append(ch)
            }
        }
        if (close != null) return null
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    /** `键=值` 列表 ⇒ 表；格式不对或键重复 ⇒ null。 */
    private fun keyValues(tokens: List<String>): Map<String, String>? {
        val map = LinkedHashMap<String, String>()
        for (token in tokens) {
            val eq = token.indexOf('=')
            if (eq <= 0) return null
            val key = token.substring(0, eq).trim()
            if (key.isEmpty() || map.containsKey(key)) return null
            map[key] = token.substring(eq + 1).trim()
        }
        return map
    }

    /** 逗号分隔的集合类分量（去空、去重；**保留书写顺序**，排序只发生在 [DriftFingerprint.canonical]）。 */
    private fun splitList(raw: String?): List<String> =
        raw.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    private fun webOf(no: Int, pairs: Map<String, String>, out: MutableList<DriftFingerprint>, errors: MutableList<String>) {
        val domain = pairs["domain"].orEmpty().trim().lowercase()
        if (!SkillContract.looksLikeDomain(domain)) {
            errors += "第 $no 行：web 指纹的 domain「$domain」不像域名（例：example.com）"
            return
        }
        val selectors = splitList(pairs["selectors"])
        if (selectors.isEmpty()) {
            errors += "第 $no 行：web 指纹至少要一个关键选择器（selectors=#a,#b）"
            return
        }
        out += DriftFingerprint.Web(domain, selectors)
    }

    private fun fileOf(no: Int, pairs: Map<String, String>, out: MutableList<DriftFingerprint>, errors: MutableList<String>) {
        val ext = pairs["ext"].orEmpty().trim().removePrefix(".").lowercase()
        if (ext.isEmpty() || !ext.all { it.isLetterOrDigit() }) {
            errors += "第 $no 行：file 指纹的 ext「$ext」不像扩展名（例：xlsx）"
            return
        }
        val headers = splitList(pairs["headers"])
        if (headers.isEmpty()) {
            errors += "第 $no 行：file 指纹至少要一个表头（headers=单号,客户,数量）"
            return
        }
        out += DriftFingerprint.FileShape(ext, headers)
    }

    private fun cmdOf(no: Int, pairs: Map<String, String>, out: MutableList<DriftFingerprint>, errors: MutableList<String>) {
        val path = pairs["path"].orEmpty().trim()
        val version = pairs["version"].orEmpty().trim()
        if (path.isEmpty()) {
            errors += "第 $no 行：cmd 指纹的 path 是空的"
            return
        }
        if (version.isEmpty()) {
            errors += "第 $no 行：cmd 指纹的 version 是空的（版本串原样抄，别只写主版本号）"
            return
        }
        out += DriftFingerprint.Command(path, version)
    }
}
