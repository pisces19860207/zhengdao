// 独立开发声明：本文件为本项目原创，不含第三方源码拷贝。
//
// 第四百三十一批 · 影子按需 ①「执行前基线指纹」。
//
// [ShadowRehearsal] 的比对只活在**执行前**那一瞬：授权闸第⑤步里副本重做一遍、判个四态、
// 账一落，真盘后来动没动、动成了什么样，影子的账从此不再过问。对不可逆动作这是个缺口——
// 真执行**之后**没有一条账能证明「当时动的是哪个对象、动之前它长什么样」：文件一删/一盖，
// 现场就永远没了。
//
// 本文件补的就是那个「当时」：不可逆动作在进沙盒**之前**（[com.example.zhengdao.baopu.inspect.ShadowAdapter]
// 的预演进门第一句，结构上早于真盘动第一根手指——预演挂在授权闸第⑤步，真执行在闸后），
// 把声明对象在**真盘**上逐个拍四格指纹——存在性、字节数、mtime、sha256；副本重做收手后
// （清副本之前）再把副本里的**预测后态**补进同一行，落进影子自己的账 `影子基线.jsonl`。
// 执行后按需复核（[ShadowOnDemand]）拿「预测后态 × 真盘现状」对表：差异 ⇒ DISAGREEMENT
// （只记一条，不阻断）；零差异 ⇒ 什么都不落。
//
// 四条判据与实现的对位：
// · **执行前必采到**——采集挂在预演进门第一句，比沙盒建副本还早；没基线的动作不进按需
//   复核（没有可比的两份清单，不猜）；
// · **mtime 只进账、不进比对**——副本落盘时刻与真盘落盘时刻天生不同，拿它当判据则
//   「零差异不报」永远做不到；它在指纹里是「这条基线什么时候采的」的取证格，
//   比对的判据格只有存在性/字节/摘要三格（见 [ShadowOnDemand.diffOf]）。
package com.example.zhengdao.baopu.inspect

import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一格对象在某一刻的指纹（431 工单点名的四格：存在性＋size＋mtime＋摘要）。
 *
 * @param sha256 小写 hex；对象不存在时为空串（"不存在"由 [exists] 说，不拿空摘要冒充"内容为空"）。
 */
internal data class ShadowFingerprint(
    val path: String,
    val exists: Boolean,
    val sizeBytes: Long,
    val mtimeMs: Long,
    val sha256: String,
)

/** 采集进内存的那一份（此刻还未落账；落账等副本重做完、预测后态补齐再一次性写）。 */
internal data class ShadowBaselineCapture(
    val atMs: Long,
    val fingerprints: List<ShadowFingerprint>,
)

/**
 * `影子基线.jsonl` 的一行：一次不可逆动作的「执行前真盘」＋「副本重做的预测后态」。
 *
 * 同一 opId 可能有多行（确认卡第二遍会重跑预演、再采一遍基线）——读侧取**最新**一行，
 * 追加式账本不回头改行。
 */
internal data class ShadowBaselineRow(
    val id: String,
    /** 基线采集时刻（不是落账时刻——落账要等副本重做完，采集是进门第一句）。 */
    val atMs: Long,
    val opId: String,
    val sessionId: Long,
    val roundId: Int,
    val step: Int,
    val tool: String,
    /** 执行前真盘的四格指纹（判据「执行前必采到」的那一份）。 */
    val baseline: List<ShadowFingerprint>,
    /** 副本重做后的预测后态（三格有效；mtime 是副本落盘时刻，取证格，不进比对）。 */
    val predicted: List<ShadowFingerprint>,
) {
    /** 副本重做交没交出预测（空表 ⇒ 按需复核验不了，原因 NO_PREDICTION）。 */
    val hasPrediction: Boolean get() = predicted.isNotEmpty()
}

/** 影子按需·基线账（431）。房型照 [ShadowLedger]：JSONL、只追加、`@Synchronized`、写失败返回 false。 */
internal object ShadowBaseline {

    /** 文件名（与影子执行/灾异事件同一棵 `抱朴记忆/`——影子的账只进影子自己的文件）。 */
    const val FILE_NAME = "影子基线.jsonl"

    /** 行 id 前缀（按动作一把：同 opId 永远同一行号，追加式不回头改）。 */
    const val ID_PREFIX = "bl:"

    fun rowIdOf(opId: String): String = "$ID_PREFIX$opId"

    /** 落点文件。 */
    fun fileIn(globalRoot: File): File = File(globalRoot, FILE_NAME)

    /**
     * 执行前基线采集（真盘读，进门第一句）。
     *
     * 范围四条（任一不满足 ⇒ null，**安静地不采**）：
     * · 不可逆（[ShadowInput.reversible]——授权链那格照 237 口径恒 false，即预演链里的
     *   动作全按不可逆对待）；431 工单①的 scope 就是「不可逆动作」；
     * · 说得出声明对象（`declaredPaths` 非空——说不出对象连基线都没处拍）；
     * · 工作区根与账本根都在（账本根没装 ⇒ 影子链整体不承诺，见 ShadowInput.ledgerRoot）；
     * · 不是「无文件副作用」的工具（phone_dial/notify 拍文件指纹没有意义）。
     */
    fun capture(input: ShadowInput, nowMs: Long): ShadowBaselineCapture? {
        if (input.reversible) return null
        if (input.declaredPaths.isEmpty()) return null
        if (input.workspaceRoot.isBlank() || input.ledgerRoot.isBlank()) return null
        if (input.tool in NOT_APPLICABLE_TOOLS) return null
        val root = File(input.workspaceRoot)
        // 全有或全无：半份基线会让预测/实测两边的路径键错位（比出假差异），读不动 ⇒ 整份不采。
        val prints = input.declaredPaths.map { rel -> readFingerprint(root, rel) }
        if (prints.any { it == null }) return null
        return ShadowBaselineCapture(atMs = nowMs, fingerprints = prints.mapNotNull { it })
    }

    /**
     * 单对象四格指纹（真盘与副本共用一把尺——两把尺必打架）。
     *
     * 读不动（存在但读不出字节）⇒ null：调用方按「验不了」收，不许拿半格指纹冒充读数。
     */
    fun readFingerprint(root: File, rel: String): ShadowFingerprint? {
        val trimmed = rel.trim().replace('\\', '/').trimStart('/')
        if (trimmed.isEmpty()) return null
        val f = File(root, trimmed)
        if (!f.exists()) return ShadowFingerprint(trimmed, exists = false, sizeBytes = 0L, mtimeMs = 0L, sha256 = "")
        val bytes = runCatching { f.readBytes() }.getOrElse { return null }
        return ShadowFingerprint(
            path = trimmed,
            exists = true,
            sizeBytes = bytes.size.toLong(),
            mtimeMs = runCatching { f.lastModified() }.getOrDefault(0L),
            sha256 = sha256Hex(bytes),
        )
    }

    /** 追加一行（失败返回 false——账丢一行调用方 log，不影响判定）。 */
    @Synchronized
    fun append(globalRoot: File, row: ShadowBaselineRow): Boolean = runCatching {
        val f = fileIn(globalRoot)
        f.parentFile?.mkdirs()
        f.appendText(row.toJson().toString() + "\n", Charsets.UTF_8)
        true
    }.getOrDefault(false)

    /** 某 opId 的**最新**一行（确认卡第二遍重采 ⇒ 多行，取 atMs 最大；没有 ⇒ null）。 */
    fun latestFor(globalRoot: File, opId: String): ShadowBaselineRow? = runCatching {
        fileIn(globalRoot).takeIf { it.isFile }
            ?.readLines(Charsets.UTF_8)
            ?.mapNotNull { parseLine(it) }
            ?.filter { it.opId == opId }
            ?.maxByOrNull { it.atMs }
    }.getOrDefault(null)

    fun parseLine(raw: String): ShadowBaselineRow? {
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val op = o.optString("op")
        if (op.isEmpty()) return null
        return ShadowBaselineRow(
            id = o.optString("id", rowIdOf(op)),
            atMs = o.optLong("at", 0L),
            opId = op,
            sessionId = o.optLong("session", -1L),
            roundId = o.optInt("round", 0),
            step = o.optInt("step", 0),
            tool = o.optString("tool"),
            baseline = printsOf(o.optJSONArray("baseline")),
            predicted = printsOf(o.optJSONArray("predicted")),
        )
    }

    private fun ShadowBaselineRow.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("at", atMs)
        .put("op", opId)
        .put("session", sessionId)
        .put("round", roundId)
        .put("step", step)
        .put("tool", tool)
        .put("baseline", JSONArray().apply { baseline.forEach { put(printToJson(it)) } })
        .put("predicted", JSONArray().apply { predicted.forEach { put(printToJson(it)) } })

    private fun printToJson(p: ShadowFingerprint): JSONObject = JSONObject()
        .put("p", p.path)
        .put("exists", p.exists)
        .put("size", p.sizeBytes)
        .put("mtime", p.mtimeMs)
        .put("sha", p.sha256)

    private fun printsOf(a: JSONArray?): List<ShadowFingerprint> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            ShadowFingerprint(
                path = o.optString("p"),
                exists = o.optBoolean("exists", false),
                sizeBytes = o.optLong("size", 0L),
                mtimeMs = o.optLong("mtime", 0L),
                sha256 = o.optString("sha"),
            )
        }.filter { it.path.isNotEmpty() }
    }

    /** 摘要只此一处算（与 TaskGrantWire/ShadowAdapter 各有一份流式实现同病——本份收整字节输入）。 */
    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
