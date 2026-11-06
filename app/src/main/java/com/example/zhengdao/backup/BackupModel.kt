// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：第四百九十一批派工单①「一键导出 zip 到共享存储（账本 / 人设与记忆 / 技能 / 设置）」
// 与②「带 manifest.json：版本、时间、条目数、每文件 hash」。
//
// 本文件只做**形状**：分组、条目、清单，以及清单的 JSON 编解码与**校验**。不碰文件系统、
// 不碰 Context ⇒ JVM 单测直接打（本仓无 Robolectric 的老问题，见 KeyPool／DataRules 同款理由）。
package com.example.zhengdao.backup

import org.json.JSONArray
import org.json.JSONObject

/**
 * 备份分组（派工单①的四样 ＋ 可选的聊天原文）。
 *
 * [key] 同时是 zip 里的顶层目录名与清单里的 `group` 值——**一处定义两处用**，
 * 免得"目录叫 ledger、清单写账本"这种对不上的账。
 */
internal enum class BackupGroup(val key: String) {
    /** 账本：`抱朴记忆/` 下的 `.jsonl`（决策记录、御批、偏差账……）与 `.json`（漂移、名册……）。 */
    LEDGER("ledger"),

    /** 人设与记忆：`抱朴记忆/` 下其余（`人设.md`、记忆 `.md`、日记……）＋工作区 `抱朴记忆/`。 */
    MEMORY("memory"),

    /** 技能：全局 `抱朴记忆/技能/` ＋ 项目 `<工作区>/技能/`。 */
    SKILLS("skills"),

    /** 设置：`zhengdao-settings` 的白名单导出（密钥一律剔除，见 [BackupRules.isSecretPrefKey]）。 */
    SETTINGS("settings"),

    /** 聊天原文：**默认不导出**（派工单①），勾了才进 `chat/`。 */
    CHAT("chat"),
    ;

    companion object {
        fun of(key: String): BackupGroup? = values().firstOrNull { it.key == key }
    }
}

/**
 * 清单里的一条（zip 里的一个文件）。
 *
 * [path] 是**zip 内路径**（`ledger/决策记录.jsonl`），斜杠分隔、不带前导 `/`；
 * [sha256] 是 64 位小写十六进制。两个形状都由 [BackupRules.isSafeEntryPath] 与
 * [BackupManifestCodec] 在解析时重新判一遍——清单是外部输入，不能当可信。
 */
internal data class BackupEntry(
    val path: String,
    val group: BackupGroup,
    val size: Long,
    val sha256: String,
)

/**
 * 没进包的东西（连同原因）。
 *
 * 派工单没要求这一栏，但"跳过"必须留痕：`工具失败不许静默` 是同一条纪律的另一面——
 * 一个 30 MiB 的日志被悄悄漏掉，用户拿到的"完整还原"就是假的。
 */
internal data class BackupSkip(val path: String, val reason: String)

/** 一份备份的清单（`manifest.json` 的内存形态）。 */
internal data class BackupManifest(
    val appVersion: String,
    val createdAt: Long,
    val createdAtText: String,
    val includesChat: Boolean,
    val entries: List<BackupEntry>,
    val skipped: List<BackupSkip> = emptyList(),
) {
    val entryCount: Int get() = entries.size

    val totalBytes: Long get() = entries.sumOf { it.size }

    fun countIn(group: BackupGroup): Int = entries.count { it.group == group }
}

/** 清单解码结果：要么是一份过完校验的清单，要么是一句人话（**不许半信半疑地用**）。 */
internal sealed interface ManifestRead {
    data class Ok(val manifest: BackupManifest) : ManifestRead
    data class Bad(val reason: String) : ManifestRead
}

/**
 * `manifest.json` 的读写。
 *
 * 解码是**防御式**的：格式标签、版本上限、路径形状、hash 形状、条目数与字节总数
 * 全部重算比对。理由很直白——清单来自用户手机上任意一个 zip，写它的人不是我们。
 */
internal object BackupManifestCodec {

    /** 文件头标签：认错包比解错包好（把别人的 zip 当备份导入＝把数据搅烂）。 */
    const val FORMAT_TAG = "zhengdao-backup"

    /** 清单格式版本（只认 ≤ 本值的；更高的版本是"未来写的包"，不猜）。 */
    const val FORMAT_VERSION = 1

    /** 清单在 zip 里的固定名字。 */
    const val MANIFEST_NAME = "manifest.json"

    fun encode(m: BackupManifest): String {
        val groups = JSONObject()
        for (g in BackupGroup.values()) groups.put(g.key, m.countIn(g))
        val arr = JSONArray()
        for (e in m.entries) {
            arr.put(
                JSONObject()
                    .put("path", e.path)
                    .put("group", e.group.key)
                    .put("size", e.size)
                    .put("sha256", e.sha256),
            )
        }
        val skipped = JSONArray()
        for (s in m.skipped) skipped.put(JSONObject().put("path", s.path).put("reason", s.reason))
        return JSONObject()
            .put("format", FORMAT_TAG)
            .put("formatVersion", FORMAT_VERSION)
            .put("appVersion", m.appVersion)
            .put("createdAt", m.createdAt)
            .put("createdAtText", m.createdAtText)
            .put("includesChat", m.includesChat)
            .put("entryCount", m.entryCount)
            .put("totalBytes", m.totalBytes)
            .put("groups", groups)
            .put("entries", arr)
            .put("skipped", skipped)
            .toString(2)
    }

    /** 解码 ＋ 校验（任何一条不过 ⇒ [ManifestRead.Bad] 并说清是哪一条）。 */
    fun decode(text: String): ManifestRead {
        val root = runCatching { JSONObject(text) }.getOrElse {
            return ManifestRead.Bad("清单不是合法 JSON：${it.javaClass.simpleName}")
        }
        val tagBad = headCheck(root)
        if (tagBad != null) return ManifestRead.Bad(tagBad)
        val entries = when (val r = readEntries(root.optJSONArray("entries"))) {
            is EntriesRead.Bad -> return ManifestRead.Bad(r.reason)
            is EntriesRead.Ok -> r.entries
        }
        val totals = totalsCheck(root, entries)
        if (totals != null) return ManifestRead.Bad(totals)
        return ManifestRead.Ok(
            BackupManifest(
                appVersion = root.optString("appVersion"),
                createdAt = root.optLong("createdAt"),
                createdAtText = root.optString("createdAtText"),
                includesChat = root.optBoolean("includesChat", false),
                entries = entries,
                skipped = readSkipped(root.optJSONArray("skipped")),
            ),
        )
    }

    /** 头三样：标签、版本、条目数组存在。返回 null ＝ 过关。 */
    private fun headCheck(root: JSONObject): String? {
        if (root.optString("format") != FORMAT_TAG) {
            return "这不是证道的备份包（清单里没有 ${FORMAT_TAG} 标记）"
        }
        val v = root.optInt("formatVersion", -1)
        if (v <= 0) return "清单缺 formatVersion"
        if (v > FORMAT_VERSION) {
            return "备份是更新版 App 写的（清单版本 $v，本机只认 $FORMAT_VERSION）——先升级再导入"
        }
        if (root.optJSONArray("entries") == null) return "清单缺 entries"
        return null
    }

    private sealed interface EntriesRead {
        data class Ok(val entries: List<BackupEntry>) : EntriesRead
        data class Bad(val reason: String) : EntriesRead
    }

    private fun readEntries(arr: JSONArray?): EntriesRead {
        if (arr == null) return EntriesRead.Bad("清单缺 entries")
        val out = ArrayList<BackupEntry>(arr.length())
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: return EntriesRead.Bad("第 ${i + 1} 条不是对象")
            val path = o.optString("path")
            if (!BackupRules.isSafeEntryPath(path)) {
                return EntriesRead.Bad("第 ${i + 1} 条路径不合法：$path")
            }
            if (!seen.add(path)) return EntriesRead.Bad("清单里有重复条目：$path")
            val group = BackupGroup.of(o.optString("group"))
                ?: return EntriesRead.Bad("第 ${i + 1} 条分组不认识：${o.optString("group")}")
            val size = o.optLong("size", -1)
            if (size < 0) return EntriesRead.Bad("第 ${i + 1} 条 size 不合法")
            val sha = o.optString("sha256")
            if (!BackupRules.isSha256Hex(sha)) return EntriesRead.Bad("第 ${i + 1} 条 sha256 不合法：$path")
            out += BackupEntry(path, group, size, sha)
        }
        return EntriesRead.Ok(out)
    }

    /** 条目数 / 字节总数必须与逐条重算的值一致（清单自相矛盾＝要么写坏要么改过）。 */
    private fun totalsCheck(root: JSONObject, entries: List<BackupEntry>): String? {
        val declaredCount = root.optInt("entryCount", -1)
        if (declaredCount != entries.size) {
            return "清单条目数对不上（写的是 $declaredCount，实际 ${entries.size} 条）"
        }
        val declaredBytes = root.optLong("totalBytes", -1)
        val real = entries.sumOf { it.size }
        if (declaredBytes != real) return "清单字节总数对不上（写的是 $declaredBytes，实际 $real）"
        return null
    }

    private fun readSkipped(arr: JSONArray?): List<BackupSkip> {
        if (arr == null) return emptyList()
        val out = ArrayList<BackupSkip>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += BackupSkip(o.optString("path"), o.optString("reason"))
        }
        return out
    }
}
