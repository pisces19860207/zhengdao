// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第八十六批 §2：候选**只存不应用**——文件是真相（与 `抱朴记忆/` 同一口径），
// 落 `<App私有>/抱朴记忆/提案候选.json`。没有新表、没有 migration、没有第二套账。
//
// 对外只有两个口：
//   - `listPending()`：唯一的读接口（82 v2 自检 / ⑤ 决策口从这里取）；
//   - `appendNew()`：生成侧追加（同 id 不重复写，跨状态都算重）。
// 本类**不碰**技能/人设文件，不调用 `SkillStore.apply` / `PersonaFiles.applyProposal`。
package com.example.zhengdao.baopu

import com.example.zhengdao.rootfs.RunLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 提案候选的 JSON 仓（版本 1）。
 *
 * 为什么用文件而不是表：派工单 §2 要求「复用现有表/JSON 存放（加表就 migration +1）」——
 * 候选与 `抱朴记忆/` 里的记忆/技能一样是「用户可看、可备份、可手工删」的产物，
 * 放文件不需要动 DB 版本，也不会和会话级 `skill_event` 账本混在一张表里。
 *
 * 写法：临时文件 + `Files.move(REPLACE_EXISTING, ATOMIC_MOVE)`。Windows/JVM 与 Android
 * 都按「要么旧档、要么新档」落地；退一步没有原子语义时才用普通替换。
 *
 * 坏档：**先改名备份成 `.bad-<stamp>` 再重写**——不许静默覆盖用户可能手改过的文件。
 * 单条候选解析失败只跳过那一条（其余照读），不在读路径上写盘。
 *
 * @param root 全局根（生产 = `filesDir/抱朴记忆`）
 * @param log 失败留痕（测试注入空实现；默认走 RunLog「只记录不阻塞」）
 */
internal class ProposalCandidateStore(
    private val root: File,
    private val log: (String) -> Unit = { RunLog.log(it) },
) {

    fun file(): File = File(root, FILE_NAME)

    /** 唯一的读接口：只出 PENDING（按 createdAt、id 稳定排序）。坏档 ⇒ 空表（不改盘）。 */
    @Synchronized
    fun listPending(): List<ProposalCandidate> =
        readAll()
            ?.filter { it.status == ProposalStatus.PENDING }
            ?.sortedWith(compareBy({ it.createdAt }, { it.id }))
            .orEmpty()

    /**
     * 追加入库：同 [ProposalCandidate.id] 不重复（PENDING 与将来任何状态都算重），
     * 空 id／空正文跳过。返回真正写进去的条数；写盘失败返回 0 并留痕。
     */
    @Synchronized
    fun appendNew(
        candidates: List<ProposalCandidate>,
        stamp: Long = System.currentTimeMillis(),
    ): Int {
        if (candidates.isEmpty()) return 0
        val existing = readForAppend(stamp) ?: return 0
        val seen = existing.mapTo(mutableSetOf()) { it.id }
        val added = ArrayList<ProposalCandidate>()
        for (c in candidates) {
            if (c.id.isBlank() || c.payload.isBlank()) continue
            if (!seen.add(c.id)) continue
            added += c
        }
        if (added.isEmpty()) return 0
        return runCatching {
            writeAll(existing + added)
            added.size
        }.getOrElse {
            log("抱朴：提案候选写入失败（${it.javaClass.simpleName}）")
            0
        }
    }

    // ── 编解码（JVM 可直测） ─────────────────────────────────────────────────

    internal fun encodeAll(list: List<ProposalCandidate>): String {
        val arr = JSONArray()
        for (c in list) {
            arr.put(
                JSONObject()
                    .put("id", c.id)
                    .put("kind", c.kind.name)
                    .put("target", c.target)
                    .put("payload", c.payload)
                    .put(
                        "evidence",
                        JSONObject()
                            .put("run_log_ids", JSONArray(c.evidence.runLogIds))
                            .put("skill_event_ids", JSONArray(c.evidence.skillEventIds))
                            .put("at_ms", c.evidence.atMs),
                    )
                    .put("status", c.status.name)
                    .put("created_at", c.createdAt),
            )
        }
        return JSONObject()
            .put("version", VERSION)
            .put("candidates", arr)
            .toString(2) + "\n"
    }

    /** 返回 null = 整份坏档（调用方备份后重写）；单条坏行跳过。 */
    internal fun decodeAll(text: String): List<ProposalCandidate>? {
        val rootObj = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (rootObj.optInt("version", -1) != VERSION) return null
        val arr = rootObj.optJSONArray("candidates") ?: return null
        val out = ArrayList<ProposalCandidate>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: continue
            val kind = enumOf<ProposalKind>(o.optString("kind")) ?: continue
            val status = enumOf<ProposalStatus>(o.optString("status")) ?: continue
            val ev = o.optJSONObject("evidence")
            out += ProposalCandidate(
                id = id,
                kind = kind,
                target = o.optString("target"),
                payload = o.optString("payload"),
                evidence = ProposalEvidence(
                    runLogIds = longList(ev?.optJSONArray("run_log_ids")),
                    skillEventIds = longList(ev?.optJSONArray("skill_event_ids")),
                    atMs = ev?.optLong("at_ms", 0L) ?: 0L,
                ),
                status = status,
                createdAt = o.optLong("created_at", 0L),
            )
        }
        return out
    }

    // ── 内部 ─────────────────────────────────────────────────────────────────

    /** 读全部（含非 PENDING；去重口径要跨状态）。null = 坏档。 */
    private fun readAll(): List<ProposalCandidate>? {
        val f = file()
        if (!f.isFile) return emptyList()
        return runCatching { decodeAll(f.readText(Charsets.UTF_8)) }.getOrNull()
    }

    /** 追加前读：坏档先备份再当空表；备份失败 ⇒ null（宁可不写，也不覆盖）。 */
    private fun readForAppend(stamp: Long): List<ProposalCandidate>? {
        val parsed = readAll()
        if (parsed != null) return parsed
        val f = file()
        if (!f.isFile) return emptyList()
        val bad = File(root, "$FILE_NAME.bad-$stamp")
        return runCatching<List<ProposalCandidate>?> {
            Files.move(f.toPath(), bad.toPath(), StandardCopyOption.REPLACE_EXISTING)
            log("抱朴：提案候选坏档已备份为 ${bad.name}，按空表重建")
            emptyList()
        }.getOrElse {
            log("抱朴：提案候选坏档备份失败（${it.javaClass.simpleName}），本轮不写")
            null
        }
    }

    private fun writeAll(list: List<ProposalCandidate>) {
        root.mkdirs()
        val target = file()
        val tmp = File(root, "$FILE_NAME.tmp")
        tmp.writeText(encodeAll(list), Charsets.UTF_8)
        try {
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: Exception) {
            // 少数文件系统不支持原子替换；退一步用普通替换（仍先写完整临时文件）。
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun longList(arr: JSONArray?): List<Long> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            when (val v = arr.opt(i)) {
                is Number -> v.toLong()
                is String -> v.toLongOrNull()
                else -> null
            }
        }
    }

    private inline fun <reified T : Enum<T>> enumOf(raw: String?): T? =
        raw?.takeIf { it.isNotEmpty() }?.let { name ->
            enumValues<T>().firstOrNull { it.name == name }
        }

    companion object {
        /** 文件格式版本；升级要能读旧档（本批只有 1）。 */
        const val VERSION = 1

        const val FILE_NAME = "提案候选.json"

        /** 生产落点：`<App私有>/抱朴记忆/提案候选.json`（与记忆、技能同一棵目录）。 */
        fun under(globalRoot: File): ProposalCandidateStore = ProposalCandidateStore(globalRoot)
    }
}
