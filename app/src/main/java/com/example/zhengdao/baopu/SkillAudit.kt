// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第五十八批（2026-10-19 用户「C 层可以让他自治」「按你的来」）：
// 项目技能自动写/改的**审计记录**——每次改动追加一行 JSON，供人查/回滚。
// 只写旁挂文件 `技能/_变更.jsonl`，不碰 SKILL.md 的既有格式。
//
// 第八十五批（2026-10-20 · §B 92 进化地基 ①）：本文件追加技能使用**效果信号**的纯逻辑层
// （`SkillOutcome`／`SkillSource`／`SkillReasonCode`／`SkillEffects`）——57 批只记「被用过」，
// 本批记「用了之后有没有用」。红线：只采数据＋只读聚合，不晋升、不退役、不做 UI、不存正文。
package com.example.zhengdao.baopu

import com.example.zhengdao.rootfs.RunLog
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal object SkillAudit {

    const val FILE_NAME = "_变更.jsonl"

    /** 技能目录下的审计文件。 */
    fun fileIn(skillDir: File): File = File(skillDir, FILE_NAME)

    /** 正文的短摘要（sha256 前 12 位；文件不存在 ⇒ "-"）。 */
    fun shaOf(file: File): String = runCatching {
        val d = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        d.joinToString("") { "%02x".format(it.toInt() and 0xff) }.take(12)
    }.getOrDefault("-")

    /**
     * 追加一条审计。**失败只吞掉**（审计不能把写入搞失败），由调用方 log。
     *
     * @param skillFile 技能正文（用来推技能目录与摘要）
     */
    fun record(
        skillFile: File,
        scope: String,
        name: String,
        action: String,
        reason: String,
        beforeSha: String,
        indexUpdated: Boolean,
        nowMs: Long,
    ): Boolean = runCatching {
        val dir = skillFile.parentFile?.parentFile ?: return@runCatching false
        dir.mkdirs()
        val o = JSONObject()
            .put("ts", nowMs)
            .put("scope", scope)
            .put("name", name)
            .put("action", action)
            .put("reason", reason)
            .put("beforeSha", beforeSha)
            .put("afterSha", shaOf(skillFile))
            .put("indexUpdated", indexUpdated)
        fileIn(dir).appendText(o.toString() + "\n", Charsets.UTF_8)
        true
    }.getOrDefault(false)
}

// ── 第八十五批 · 进化地基 ①：技能使用**效果**信号（只落数据与读接口）──────────────
//
// 第五十七批只记了「技能被用过」（`skill_event(kind='use')` 的 ok），没记「用了之后有没有用」。
// §B 92 闭环链的第一环要的就是后者：没有效果信号，评分／提案／退役／L3 都无从谈起。
// 本批只做三件事——**采回来、存下来、能按技能聚合**；不晋升、不退役、不做 UI、不接 L3 开关。
//
// 三条边界（每条都有判据，见 `SkillEffectSignalTest`）：
//   1. **只记 id／枚举／时刻**：技能名是键（本仓技能没有独立 id，名字就是键），正文只以
//      sha256 前 12 位（[SkillAudit.shaOf]）进 `skill_version`；提示词、工具参数、文件内容、
//      用户原话一个字都不落表——[SkillEffects.nameToken]／[SkillEffects.versionToken] 是这道闸的
//      **机器判据**，不是口头承诺；
//   2. **判不了就不猜**（派工单 §2 原话）：没有会话号／读不到上一条信号 ⇒ 只记 SUCCESS／FAILURE；
//   3. **一张表两种 kind**：效果信号走 `kind='effect'`；第五十七批的 `kind='use'` 与过期判据
//      一行没动 ⇒ 本批不碰晋升／退役行为。

/** 一次技能使用的效果结论（派工单 §1 的四值；库里存枚举名）。 */
enum class SkillOutcome {
    /** 这次用成了（`write_skill(action=use)` 返回 Ok）。 */
    SUCCESS,

    /** 这次用砸了（返回 Failed／Rejected，或技能压根不存在）。 */
    FAILURE,

    /** **上一条**使用被用户否定了：本轮用户在同一会话里明确说「不对／改成…」——证据是用户的话，不是模型的自评。 */
    USER_CORRECTED,

    /** 上一条没成所以又用了一次（同会话同技能、[SkillEffects.RETRY_WINDOW_MS] 内的重来）。 */
    RETRIED;

    companion object {
        /** 宽松解析：脏值／NULL ⇒ null（认不出的历史值不冒充任何一种 outcome）。 */
        fun of(raw: String?): SkillOutcome? = entries.firstOrNull { it.name == raw }
    }
}

/** 这次使用是谁发起的（派工单 §1：模型调用 = AUTO，用户点用 = MANUAL）。 */
enum class SkillSource {
    /** 模型自己调 `write_skill(action=use)`。 */
    AUTO,

    /** 用户在设置页点「用一次」。 */
    MANUAL;

    companion object {
        fun of(raw: String?): SkillSource? = entries.firstOrNull { it.name == raw }
    }
}

/**
 * 失败／纠正的**短枚举理由**（派工单 §1「不存正文」的落点）。
 *
 * 为什么只有这五个：每一个都得能被 [SkillEffects.classify] 真的产出，否则就是死码。
 * 「判不了」不占一个值——判不了时理由是 [NONE]，outcome 退回裸成败。
 */
enum class SkillReasonCode {
    /** 没有额外理由（裸的成败）。 */
    NONE,

    /** 用户明确否定上一条答复（「不对／错了／别这样」）。 */
    USER_NEGATED,

    /** 用户改写要求（「改成／换成／我是说／重新写」）。 */
    USER_REWROTE,

    /** 上一条效果是 FAILURE ⇒ 这次是重试。 */
    AFTER_FAILURE,

    /** 上一条效果是 USER_CORRECTED ⇒ 这次是改完再来一次。 */
    AFTER_CORRECTION;

    companion object {
        fun of(raw: String?): SkillReasonCode? = entries.firstOrNull { it.name == raw }
    }
}

/**
 * 效果信号的**纯逻辑层**（不碰 Android、不碰库；执行壳在 `BaopuRuntime.recordSkillEffect`）。
 *
 * 单独抽这一层的目的：判定与隐私闸门必须能在 JVM 里跑死，而不是等真机「大概没问题」。
 */
internal object SkillEffects {

    /**
     * 重试窗口（毫秒）：上一条效果信号落在这个窗口内、且它是失败／被纠正的 ⇒ 这次算 RETRIED。
     *
     * 为什么是 10 分钟：技能试用是**一轮对话里**的事——用户说一句、模型用一次技能，间隔在秒级。
     * 给到 10 分钟够跨「用户补一句再重试」这一两个回合，又不至于把「明天又用了同一条」
     * 误当成今天的重试（那是新的使用，该记裸成败）。
     */
    const val RETRY_WINDOW_MS = 10 * 60 * 1000L

    /** 纠正类标记（否定上一条）。口径与 `MemoryRefine` 的 LESSON 一类同源，两处独立维护。 */
    private val NEGATION_MARKERS = listOf(
        "不对", "错了", "别这样", "不要这样", "不是这样", "不能这", "说错", "搞错", "不行", "重说",
    )

    /** 改写类标记（没否定，但把要求改了：同样说明上一条没做到）。 */
    private val REWRITE_MARKERS = listOf(
        "改成", "换成", "我是说", "重新写", "再来一次", "应该是", "按我说的", "我的意思是",
    )

    /** 句末标点（切句用；中英混排都认，与 `MemoryRefine` 同一套）。 */
    private val SENTENCE_END = charArrayOf('。', '！', '？', '!', '?', '\n')

    /** 判纠正只认**短句**：超过这个字数的一句不再是「否定／改写」，是在讲别的事（判不了就不猜）。 */
    private const val CORRECTION_SENTENCE_MAX = 24

    /** 正文摘要的形状：`shaOf` 出来的 12 位小写十六进制（文件不在 ⇒ `"-"`）。 */
    private val SHA12 = Regex("^[0-9a-f]{12}$")

    /**
     * 一条效果信号（内存形态；[toStmt] 之前先过闸门）。
     *
     * @param name 技能名 = skillId（唯一能进库的「内容」，而它是目录名、不是正文）
     * @param skillVersion 正文 sha256 前 12 位（[SkillAudit.shaOf]；文件读不到 ⇒ `"-"`＝「没有版本可记」）
     * @param attemptOk 这次调用**本身**成没成（outcome 是解释，ok 是事实；两列各管一头）
     */
    data class Signal(
        val name: String,
        val skillVersion: String?,
        val outcome: SkillOutcome,
        val source: SkillSource,
        val reason: SkillReasonCode,
        val sessionId: Long?,
        val step: Int?,
        val attemptOk: Boolean,
        val at: Long,
    )

    /**
     * 这次使用该记哪个效果（**纯函数**；派工单 §2 的优先级就落在这三档里）：
     *
     * ① 本轮用户明确否定／改写上一条 ⇒ `USER_CORRECTED`（说的是「上一条没做成」）；
     * ② 否则上一条是 FAILURE／USER_CORRECTED 且在窗口内 ⇒ `RETRIED`；
     * ③ 否则按这次调用自己的成败记 `SUCCESS`／`FAILURE`。
     *
     * [prevOutcome] 为 null（同会话没有上一条信号）、或 [ageMs] 为 null／超窗／为负（跨天、时钟倒走）
     * ⇒ 一律走 ③，**绝不猜**「隔了三天大概还算重试」。
     */
    fun classify(
        prevOutcome: SkillOutcome?,
        ageMs: Long?,
        correction: SkillReasonCode?,
        ok: Boolean,
    ): Pair<SkillOutcome, SkillReasonCode> {
        correction?.let { return SkillOutcome.USER_CORRECTED to it }
        val aged = ageMs != null && ageMs in 0L..RETRY_WINDOW_MS
        when (prevOutcome) {
            SkillOutcome.FAILURE -> if (aged) return SkillOutcome.RETRIED to SkillReasonCode.AFTER_FAILURE
            SkillOutcome.USER_CORRECTED -> if (aged) return SkillOutcome.RETRIED to SkillReasonCode.AFTER_CORRECTION
            else -> Unit
        }
        return (if (ok) SkillOutcome.SUCCESS else SkillOutcome.FAILURE) to SkillReasonCode.NONE
    }

    /**
     * 认「用户纠正」（派工单 §2：按现有消息角色能判则判，判不了就 null，**别猜**）。
     *
     * 口径：**逐句**扫本轮用户那句话，只认短句里的否定／改写标记——长句里冒出一个「不对」
     * 多半在讲别的事（「不对，你先看看这个新文件里……」），那不是对上一条技能的否定。
     * 先扫否定再扫改写（否定更硬）。
     *
     * ⚠️ 文本只在**内存里过一遍**，认完就丢；进库的只有 [SkillReasonCode]（红线 §1）。
     */
    fun correctionCode(userText: String?): SkillReasonCode? {
        if (userText.isNullOrBlank()) return null
        val sentences = userText.split(*SENTENCE_END)
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.length <= CORRECTION_SENTENCE_MAX }
        if (sentences.any { s -> NEGATION_MARKERS.any { s.contains(it) } }) return SkillReasonCode.USER_NEGATED
        if (sentences.any { s -> REWRITE_MARKERS.any { s.contains(it) } }) return SkillReasonCode.USER_REWROTE
        return null
    }

    /**
     * 技能名闸门：必须**原样等于** [SkillStore.sanitizeName] 的输出（且不超过名字上限）。
     *
     * 这一条把「顺手把正文／用户原话塞进 name 列」彻底堵死：正文里有空白、标点、
     * 路径字符，`sanitizeName` 会把它们剥掉 ⇒ 剥完不相等 ⇒ null ⇒ 整条信号不写。
     */
    fun nameToken(raw: String?): String? {
        val t = raw ?: return null
        if (t.length > SkillStore.NAME_MAX_CHARS) return null
        return if (SkillStore.sanitizeName(t) == t) t else null
    }

    /** 版本闸门：只认 12 位小写十六进制或 `"-"`（正文摘要）；别的形状一律不写。 */
    fun versionToken(sha12: String?): String? = when {
        sha12 == null -> null
        sha12 == "-" -> "-"
        else -> sha12.takeIf { SHA12.matches(it) }
    }

    /**
     * 信号 ⇒ 写库语句（**先过闸门再拼 SQL**）：任何一个字段不合格 ⇒ null，调用方据此**不写**并 log。
     *
     * 效果行**不进 `detail`**——那一列是第五十七批留给「丢弃理由」的，本批不许往它写用户原话。
     */
    fun toStmt(s: Signal): BaopuSql.Stmt? {
        val name = nameToken(s.name) ?: return null
        val version = if (s.skillVersion == null) null else versionToken(s.skillVersion) ?: return null
        return BaopuSql.insertSkillEffect(
            name = name,
            sessionId = s.sessionId,
            step = s.step,
            outcome = s.outcome.name,
            source = s.source.name,
            skillVersion = version,
            reasonCode = s.reason.name,
            ok = s.attemptOk,
            nowMs = s.at,
        )
    }

    /**
     * outcome 字面值 ⇒ 次数（**只读聚合，不做晋升判断**）。
     *
     * 四个键恒在（没记过就是 0）：调用方拿到的是「这条技能每种效果各几次」，不是「库里有哪几种」——
     * 省得每个调用方自己补零，也免得各处补出分歧。认不出的脏值丢掉（不冒充 outcome，也不让总数虚高）。
     */
    fun tally(rows: List<String?>): Map<SkillOutcome, Int> {
        val counts = LinkedHashMap<SkillOutcome, Int>()
        SkillOutcome.entries.forEach { counts[it] = 0 }
        rows.forEach { raw -> SkillOutcome.of(raw)?.let { counts[it] = (counts[it] ?: 0) + 1 } }
        return counts
    }

    /**
     * 按技能聚合效果信号（派工单 §2 点名的读接口）。
     *
     * 只读、只数数：**不许**在这里长出门槛／评分／退役判据（§B 92 的②③④⑤归后面的批次）。
     * 读失败 ⇒ 全 0 加一条 log（读接口不许把调用方带崩，也不许静默）。
     */
    fun outcomesFor(db: BaopuDb, name: String): Map<SkillOutcome, Int> =
        tally(readOutcomes(db, name))

    /** GROUP BY 出来的 outcome 列（NULL 与脏值原样带回，由 [tally] 决定认不认）。 */
    private fun readOutcomes(db: BaopuDb, name: String): List<String?> {
        val stmt = BaopuSql.skillOutcomeCounts(name)
        return runCatching {
            db.read { d ->
                d.rawQuery(stmt.sql, stmt.args).use { c ->
                    (0 until c.count).map { c.moveToPosition(it); c.getString(0) }
                }
            }
        }.getOrElse {
            RunLog.log("抱朴：skill_effect 聚合读失败（按零处理）${it.javaClass.simpleName}: ${it.message}")
            emptyList()
        }
    }

    /** 上一条效果信号（同会话同技能）的 outcome 与时刻；读不到 ⇒ `null to null`（判不了就不猜）。 */
    fun lastEffect(db: BaopuDb, name: String, sessionId: Long): Pair<SkillOutcome?, Long?> {
        val stmt = BaopuSql.lastSkillEffect(name, sessionId)
        return runCatching {
            db.read { d ->
                d.rawQuery(stmt.sql, stmt.args).use { c ->
                    if (!c.moveToFirst()) null to null
                    else SkillOutcome.of(c.getString(0)) to (if (c.isNull(1)) null else c.getLong(1))
                }
            }
        }.getOrElse {
            RunLog.log("抱朴：skill_effect 上一条读失败（按没有处理）${it.javaClass.simpleName}: ${it.message}")
            null to null
        }
    }

    /**
     * 上一条信号之后、本轮用户说的话（**只在内存里过一遍**喂给 [correctionCode]，认完就丢）。
     * 读失败 ⇒ null（判不了就只记 SUCCESS／FAILURE）。
     */
    fun userTextAfter(db: BaopuDb, sessionId: Long, sinceMs: Long): String? {
        val stmt = BaopuSql.userTextAfter(sessionId, sinceMs)
        return runCatching {
            db.read { d ->
                d.rawQuery(stmt.sql, stmt.args).use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }
        }.getOrElse {
            RunLog.log("抱朴：本轮用户消息读不出（不猜纠正）${it.javaClass.simpleName}: ${it.message}")
            null
        }
    }
}
