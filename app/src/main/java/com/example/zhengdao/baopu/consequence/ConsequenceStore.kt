// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百零六批（2026-11-07）· 476 的**落盘壳**（纯判据在 [IdentityRules]／[CredentialBinding]）。
//
// 本文件是全包唯一碰盘的一处。判据层零 IO，这里零判据——
// 两件事分开的理由不是"好看"：判据要能手搓行直测（不需要造一台文件系统），
// 而落盘必须能被判据钉死（盘上出现半条 JSON 时，读侧走 [IdentityStore.load] 的 fail-closed，不猜）。
//
// 落点为什么是 `filesDir/执行体/` 而**不是** `filesDir/抱朴记忆/`：
// 那棵记忆树是**会被备份也被会还原**的（`MemoryBackup`、197/198 的读侧、自由文本导入都走它）。
// 身份一旦进了可还原的那棵树，「还原一份旧备份」就等于把撤销掉的身份复活——
// 判据④「撤销后凭据全失效」当场失效，而且失效得悄无声息：盘上一个字节都没报错。
// 所以这一族单独一棵树，**不在任何备份路由里**（本批也不给它做备份：可还原的身份不是稳定身份）。
//
// 三条纪律：
// 1. **写盘不写半条**：临时文件转正（同 `DriftStore.save`），改失败就维持旧表；
// 2. **读不动 ⇒ 空表**，而空表在判据层的意思是「没有在用的身份」⇒ 凭据一律拒 + 首启重新生成，
//    不会把「读不出」洗成「全都还有效」；
// 3. **熵与时刻都由调用方给**（[IdentityStore.ensureFirstRun] 的默认熵才是 `UUID.randomUUID()`），
//    所以判据①「首启生成」在测试里是确定性的。
package com.example.zhengdao.baopu.consequence

import com.example.zhengdao.core.RunLog
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** 后果治理这族的私有目录与文件名（稳定键：改字面量＝老身份读不出来，用户会被当成新机器）。 */
internal object ConsequenceDirs {

    /** 身份目录（`filesDir/执行体/`）：**故意放在 `抱朴记忆/` 之外**，理由见本文件头。 */
    const val DIR_EXECUTOR: String = "执行体"

    const val IDENTITY_FILE: String = "身份.json"
    const val CREDENTIAL_FILE: String = "凭据绑定.json"

    /** 行格式版本（与 `DecisionLedger.VERSION` 无关：这是文件格式，不是账本结构）。 */
    const val SCHEMA: String = "zd-executor/1"

    fun root(filesDir: File): File = File(filesDir, DIR_EXECUTOR)

    fun identityFile(filesDir: File): File = File(root(filesDir), IDENTITY_FILE)

    fun credentialFile(filesDir: File): File = File(root(filesDir), CREDENTIAL_FILE)
}

/** 一次改身份动作的结局（落盘之后）。 */
internal sealed interface IdentityOutcome {

    /** 盘上换成新表了：新旧 id ＋ 该落的那条账（没落账＝这次改动没人知道）。 */
    data class Done(
        val rows: List<IdentityRow>,
        val previousId: String,
        val newId: String,
        val event: IdentityEvent,
    ) : IdentityOutcome

    /** 没换成：盘上一个字节都没动，理由原样给出去（这句话要能落日志）。 */
    data class Refused(val reason: String) : IdentityOutcome
}

/**
 * 身份表落盘。
 *
 * 表里**只增行、不改历史行的 id**：撤销与轮换改的是 `state`，
 * 于是「撤销后历史账照读」在身份这一侧也有同一句话——旧 id 一直在表里，[IdentityRules.wasKnown] 查得到。
 */
internal object IdentityStore {

    /** 读表（没有／读不出／schema 不对 ⇒ 空表）。空表＝「没有在用的身份」，不是「全都有效」。 */
    fun load(filesDir: File): List<IdentityRow> {
        val f = ConsequenceDirs.identityFile(filesDir)
        if (!f.isFile) return emptyList()
        val text = runCatching { f.readText(Charsets.UTF_8) }.getOrNull() ?: return emptyList()
        return fromJson(text)
    }

    /** 写表（临时文件转正）。失败只 log 不抛：身份表写不下去 ⇒ 调用方拿 [load] 读到旧表，仍是安全侧。 */
    fun save(filesDir: File, rows: List<IdentityRow>): Boolean = runCatching {
        val f = ConsequenceDirs.identityFile(filesDir)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "${ConsequenceDirs.IDENTITY_FILE}.tmp")
        tmp.writeText(toJson(rows).toString(), Charsets.UTF_8)
        if (f.exists() && !f.delete()) return@runCatching false
        val ok = tmp.renameTo(f)
        if (!ok) RunLog.log("身份表改名失败（盘上仍是旧表）")
        ok
    }.getOrElse {
        RunLog.log("身份表写失败：${it.javaClass.simpleName}")
        false
    }

    /**
     * 首启生成（派工单 §三·①）。
     *
     * @param entropy 一次随机串的形状来源；测试传定值，默认才是 `UUID.randomUUID()`
     * @return 新生成的行；已经有在用行 or 盘写不下去 ⇒ null（**没有"每次启动都生成"这条路**）
     */
    fun ensureFirstRun(
        filesDir: File,
        nowMs: Long,
        entropy: () -> String = { UUID.randomUUID().toString() },
    ): IdentityRow? {
        val rows = load(filesDir)
        val fresh = IdentityRules.firstRunRow(rows, entropy(), nowMs) ?: return null
        if (!save(filesDir, rows + fresh)) return null
        return fresh
    }

    /** 在用的 id（撤销／已轮换／没表 ⇒ 空串）。这就是盖进账行 [com.example.zhengdao.baopu.DecisionRecord.executor] 的那一串。 */
    fun activeId(filesDir: File): String = IdentityRules.activeId(load(filesDir))

    /**
     * 改身份（轮换／撤销／解除撤销）**的唯一写口**。
     *
     * 顺序写死：先问判据 → 判据拒就一个字节不写 → 算出新表 → 先落盘 → 盘写成了才交出「该落的账」。
     * 为什么先盘后账：判据⑤要的是「模型改不动」，而账是**读侧**唯一的核对物；
     * 反过来（先账后盘）会出现「账上写着已撤销、盘上身份还在用」——那比没账更糟。
     * 写盘失败时这里返回 Refused，调用方照 [IdentityEvent] 无草稿可产，落不出半条账。
     */
    fun mutate(filesDir: File, m: IdentityMutation, nowMs: Long, entropy: () -> String): IdentityOutcome {
        IdentityRules.refusalOf(m)?.let { return IdentityOutcome.Refused(it) }
        val rows = load(filesDir)
        val current = IdentityRules.activeId(rows)
        val step = when (m.op) {
            IdentityRules.OP_ROTATE -> rotateStep(rows, m, current, nowMs, entropy)
            IdentityRules.OP_REVOKE -> revokeStep(rows, m, current, nowMs)
            else -> unrevokeStep(rows, m)
        }
        step.refused?.let { return IdentityOutcome.Refused(it) }
        if (!save(filesDir, step.rows)) return IdentityOutcome.Refused("身份表写盘失败，这次改动没生效")
        return IdentityOutcome.Done(
            rows = step.rows,
            previousId = step.previousId,
            newId = step.targetId,
            event = IdentityEvent(
                op = step.op,
                actor = m.actor,
                targetId = step.targetId,
                previousId = step.previousId,
            ),
        )
    }

    private data class Step(
        val rows: List<IdentityRow>,
        val refused: String?,
        val previousId: String,
        val targetId: String,
        val op: IdentityOp,
    )

    private fun rotateStep(
        rows: List<IdentityRow>,
        m: IdentityMutation,
        current: String,
        nowMs: Long,
        entropy: () -> String,
    ): Step = when (
        val r = IdentityRules.rotateOutcome(rows, m, m.targetId.ifBlank { entropy() }, nowMs)
    ) {
        is IdentityRules.Rotation.Rejected -> Step(rows, r.reason, current, "", IdentityOp.ROTATE)
        is IdentityRules.Rotation.Accepted -> Step(r.rows, null, r.previousId, r.newId, IdentityOp.ROTATE)
    }

    private fun revokeStep(rows: List<IdentityRow>, m: IdentityMutation, current: String, nowMs: Long): Step {
        val (next, refuse) = IdentityRules.revokeRows(rows, m, nowMs)
        return Step(next, refuse, current, current, IdentityOp.REVOKE)
    }

    private fun unrevokeStep(rows: List<IdentityRow>, m: IdentityMutation): Step {
        val target = rows.lastOrNull { it.state == IdentityState.REVOKED }
        val (next, refuse) = IdentityRules.unrevokeRows(rows, m)
        return Step(next, refuse, "", target?.id ?: "", IdentityOp.UNREVOKE)
    }

    // ── 序列化（固定键；半条行**跳过**而不是补默认值）───────────────────────

    fun toJson(rows: List<IdentityRow>): JSONObject = JSONObject().apply {
        put("schema", ConsequenceDirs.SCHEMA)
        put("rows", JSONArray().apply {
            rows.forEach { r ->
                put(
                    JSONObject().apply {
                        put("id", r.id)
                        put("state", r.state.wire)
                        put("at", r.createdAtMs)
                        if (r.rotatedFrom.isNotBlank()) put("from", r.rotatedFrom)
                        if (r.revokedAtMs > 0L) put("revokedAt", r.revokedAtMs)
                    },
                )
            }
        })
    }

    /** 读不出（schema 不对／id 或 state 认不出）⇒ **整表作废返回空**：宁可当首启，也不拿半份身份表放行凭据。 */
    fun fromJson(text: String): List<IdentityRow> = runCatching {
        val o = JSONObject(text)
        if (o.optString("schema") != ConsequenceDirs.SCHEMA) return@runCatching emptyList()
        val arr = o.optJSONArray("rows") ?: return@runCatching emptyList()
        val out = mutableListOf<IdentityRow>()
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: return@runCatching emptyList()
            val id = r.optString("id")
            val state = IdentityState.ofWire(r.optString("state")) ?: return@runCatching emptyList()
            if (!IdentityFormat.isWellFormed(id)) return@runCatching emptyList()
            out += IdentityRow(
                id = id,
                state = state,
                createdAtMs = r.optLong("at", 0L),
                rotatedFrom = r.optString("from"),
                revokedAtMs = r.optLong("revokedAt", 0L),
            )
        }
        out
    }.getOrElse {
        RunLog.log("身份表读失败：${it.javaClass.simpleName}（按没有在用身份处理）")
        emptyList()
    }
}

/**
 * 凭据绑定表落盘（476② 的「挂身份下」今天长在哪儿）。
 *
 * 口径先说白：**本批只把绑定表建起来，五族凭据各自的写侧还没往这张表里投行**
 * （五族的 home 见 [CredentialKind]，每一族都在别的批次的租约里）。
 * 表建好、闸口建好、判据建好 ⇒ 任何一族接上 `issue` 之后立刻受这张闸管；
 * 没接上之前，[CredentialBinding.allows] 对查不到的 handle 一律拒，不存在"表是空的所以都放行"。
 * 这一步没做完写在交付报告 §遗留，不写在代码里当已完成。
 */
internal object CredentialStore {

    fun load(filesDir: File): List<Credential> {
        val f = ConsequenceDirs.credentialFile(filesDir)
        if (!f.isFile) return emptyList()
        val text = runCatching { f.readText(Charsets.UTF_8) }.getOrNull() ?: return emptyList()
        return runCatching {
            val arr = JSONObject(text).optJSONArray("creds") ?: return@runCatching emptyList()
            val out = mutableListOf<Credential>()
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val kind = CredentialKind.ofWire(c.optString("kind")) ?: continue
                val handle = c.optString("handle")
                if (handle.isBlank()) continue
                out += Credential(kind, handle, c.optString("owner"), c.optLong("at", 0L))
            }
            out
        }.getOrDefault(emptyList())
    }

    fun save(filesDir: File, creds: List<Credential>): Boolean = runCatching {
        val f = ConsequenceDirs.credentialFile(filesDir)
        f.parentFile?.mkdirs()
        val body = JSONObject().apply {
            put("schema", ConsequenceDirs.SCHEMA)
            put(
                "creds",
                JSONArray().apply {
                    creds.forEach { c ->
                        put(
                            JSONObject().apply {
                                put("kind", c.kind.wire)
                                put("handle", c.handle)
                                put("owner", c.owner)
                                put("at", c.issuedAtMs)
                            },
                        )
                    }
                },
            )
        }.toString()
        val tmp = File(f.parentFile, "${ConsequenceDirs.CREDENTIAL_FILE}.tmp")
        tmp.writeText(body, Charsets.UTF_8)
        if (f.exists() && !f.delete()) return@runCatching false
        tmp.renameTo(f)
    }.getOrElse {
        RunLog.log("凭据绑定表写失败：${it.javaClass.simpleName}")
        false
    }

    /** 闸口（读身份表＋读绑定表，再问判据；装配层只要一个布尔时用这个）。 */
    fun permitted(filesDir: File, cred: Credential): Boolean =
        CredentialBinding.permitted(cred, IdentityStore.load(filesDir))
}
