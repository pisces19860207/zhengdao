// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百四十八批 · 外部 agent 的**身份名册**（`<工作区>/.agents.jsonl`，append-only）。
//
// 三条规矩（派工单 548 判据①⑤ + 467 同款账纪律）：
// · 同一 endpoint 重新注册**不加新行**（id 稳定 = endpoint 规范化哈希；无实质变化就无行）；
// · 断连/删除只标 `removed`，**不硬删**（留痕；有效态 = 每个 id 的最后一行说了算）；
// · 行里只有**指纹**没有凭据本体（本体在 McpVault；grep 断言在测试里钉死）。
package com.example.zhengdao.baopu.peer

import com.example.zhengdao.core.RunLog
import java.io.File
import org.json.JSONObject

internal object AgentRegistry {

    /** 落在工作区**根**（与 `产物/.mcp-peers.json`、`产物/.leases.jsonl` 区分开）。 */
    const val FILE_NAME = ".agents.jsonl"

    /** 行 kind 词表（稳定机器键）。 */
    const val KIND_REGISTER = "register"
    const val KIND_REMOVED = "removed"

    @Volatile
    private var provider: (() -> File?)? = null

    fun installRoot(p: (() -> File?)?) {
        provider = p
    }

    fun root(): File? = provider?.invoke()

    fun fileIn(root: File): File = File(root, FILE_NAME)

    /**
     * 注册（或更新）一台外部 agent。
     *
     * 幂等口径：id 相同且 name/form/两把指纹都没变 ⇒ **不加行**，原行原样返回；
     * 有实质变化 ⇒ 追加一行新的 register（同一个 id，账面上看得到「何时变的」）。
     * 曾被标 removed 的 ⇒ 追加 register 行即恢复在册。
     *
     * @return 记录本身；`appended=false` 表示这次没有加行（幂等命中）。
     */
    @Synchronized
    fun register(
        name: String,
        form: AgentForm,
        endpoint: String,
        credentialFingerprint: String,
        capabilityFingerprint: String,
        atMs: Long = System.currentTimeMillis(),
    ): Registered {
        val id = AgentIds.stableId(endpoint)
        val current = effective().entries[id]
        val unchanged = current != null && !current.removed &&
            current.name == name && current.form == form &&
            current.credentialFingerprint == credentialFingerprint &&
            current.capabilityFingerprint == capabilityFingerprint
        if (unchanged && current != null) return Registered(current, appended = false)

        val addedAt = current?.addedAtMs ?: atMs
        val record = AgentRecord(
            id = id,
            name = name,
            form = form,
            endpoint = endpoint.trim(),
            credentialFingerprint = credentialFingerprint,
            capabilityFingerprint = capabilityFingerprint,
            addedAtMs = addedAt,
            updatedAtMs = atMs,
            removedAtMs = null,
        )
        val row = record.toJson().put("kind", KIND_REGISTER)
        append(row)
        return Registered(record, appended = true)
    }

    /** 标记移除（断连/删服务器）：追加一行 `removed`，不硬删。没有在册记录 ⇒ false。 */
    @Synchronized
    fun markRemoved(endpoint: String, form: AgentForm, why: String, atMs: Long = System.currentTimeMillis()): Boolean {
        val id = AgentIds.stableId(endpoint)
        val current = effective().entries[id] ?: return false
        if (current.removed) return false
        append(
            JSONObject()
                .put("v", AgentRecord.VERSION)
                .put("kind", KIND_REMOVED)
                .put("id", id)
                .put("form", form.wire)
                .put("endpoint", endpoint.trim())
                .put("why", why)
                .put("at", atMs),
        )
        return true
    }

    /** 当前在册（未 removed）的全部记录（endpoint → 记录）。 */
    @Synchronized
    fun active(): List<AgentRecord> =
        effective().entries.values.filter { !it.removed }.sortedBy { it.addedAtMs }

    /** 有效态 = 每个 id 的最后一行（register 覆盖、removed 标记）。 */
    internal data class Effective(val entries: Map<String, AgentRecord>)

    @Synchronized
    internal fun effective(): Effective {
        val entries = LinkedHashMap<String, AgentRecord>()
        forEachRow { row ->
            val kind = row.optString("kind")
            val id = row.optString("id")
            if (id.isEmpty()) return@forEachRow
            when (kind) {
                KIND_REGISTER -> AgentRecord.fromJson(row)?.let { entries[id] = it }
                KIND_REMOVED -> entries[id]?.let { prev ->
                    entries[id] = prev.copy(removedAtMs = row.optLong("at", prev.updatedAtMs))
                }
            }
        }
        return Effective(entries)
    }

    private inline fun forEachRow(block: (JSONObject) -> Unit) {
        val root = root() ?: return
        val f = fileIn(root)
        if (!f.isFile) return
        runCatching {
            f.readLines(Charsets.UTF_8).forEach { line ->
                if (line.isBlank()) return@forEach
                runCatching { JSONObject(line) }.getOrNull()?.let(block)
            }
        }.onFailure { RunLog.log("抱朴名册：读不出（${it.javaClass.simpleName}），按空册处理") }
    }

    private fun append(row: JSONObject): Boolean {
        val root = root() ?: run {
            RunLog.log("抱朴名册：没有落点，这行没写上（${row.optString("id")}）")
            return false
        }
        return runCatching {
            val f = fileIn(root)
            f.parentFile?.mkdirs()
            f.appendText(row.toString() + "\n", Charsets.UTF_8)
            true
        }.getOrElse {
            RunLog.log("抱朴名册：写失败——${it.javaClass.simpleName}")
            false
        }
    }

    /** 注册结果（记录 ＋ 这次有没有加行）。 */
    internal data class Registered(val record: AgentRecord, val appended: Boolean)

    /** 清空落点（**测试专用**）。 */
    @Synchronized
    fun resetForTest() {
        provider = null
    }
}
