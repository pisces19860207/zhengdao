// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百八十批 · 判决经验跨重装副本（用户 2026-10-29：重装清了私有数据，判决历史全没了）。
//
// 判决历史住在 `<App私有>/抱朴记忆/` 的三本账里（御批/意见判定/追认队列）——都是
// **append-only**（"改了不覆盖"，回滚=追加一条 rollback 行），重装一次就全没了。
// 本对象把它们**逐行镜像**到工作区 `<工作区>/判决经验/`（默认即 `Download/证道/判决经验/`，
// `BaopuWorkspace` 的地盘）——那是用户自己的目录，跟着人走，不随卸载消失。
//
// 为什么是"逐行镜像"而不是新格式（先查重，再动手）：
//   - 三本账的行格式各有主（277/258/278 各自的 parse），本对象**不复制任何 parse**——
//     只按稳定键（`id`，或 `op+at`，都认不出就整行哈希）去重，字节原样搬运；
//     行格式将来变了，这里照样搬，不用跟。
//   - append-only ⇒ **双向并集**是安全的：没有"删除"这个动作，并集不会复活被删的东西。
//
// `sync()` 的两个方向（幂等，可反复跑）：
//   - **导出**：私有账里公共副本没有的行 → 追加到公共副本（日常保副本新）；
//   - **回灌**：私有账里公共副本有的行 → 追加回私有账（重装后第一次冷启动跑，历史就回来了）。
//
// 黑箱边界：画像（`UserProfileBlackbox`）才是黑箱；判决账本是"用户可看可备份"的留痕
// （208 原话），镜像到用户自己的目录不越界——**这是留痕，不是画像**。
//
// 零 Android 依赖（只 file＋org.json）；写失败不抛，只记日志（账本纪律同款）。
package com.example.zhengdao.baopu.profile

import com.example.zhengdao.core.RunLog
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

internal object VerdictExperienceStore {

    /** 工作区里的副本目录名（`<工作区>/判决经验/`）。 */
    const val DIR_NAME = "判决经验"

    /**
     * 要镜像的三本判决账（文件名与各自的主对象逐字一致，别处改了名这里要跟——
     * 各自主对象都是 `const val FILE_NAME`，编译器盯不住跨文件字符串，测试里有一致性闸）。
     */
    val MIRRORED = listOf("御批.jsonl", "意见判定.jsonl", "追认队列.jsonl")

    @Volatile
    private var publicRoot: File? = null

    @Volatile
    private var privateRoot: File? = null

    /** 最近的读写失败（给调用方/测试看的一句话；null＝没失败过。房型照 DecisionLedger）。 */
    @Volatile
    var lastError: String? = null
        private set

    /** 装配：公共副本根（工作区）＋私有账根（`抱朴记忆/`）。私有根可为 null（没装配就只读公共侧）。 */
    @Synchronized
    fun install(workspaceRoot: File, memoryRoot: File?) {
        // 同一棵目录时镜像没有意义（自己拷自己），宁可空转也别写出双份行。
        // 判断要用**传入的根**；公共副本永远在 `<工作区>/判决经验/` 这一层。
        val pubDir = File(workspaceRoot, DIR_NAME)
        publicRoot = if (memoryRoot != null && samePath(workspaceRoot, memoryRoot)) null else pubDir
        privateRoot = memoryRoot
    }

    fun installed(): Boolean = publicRoot != null

    /** 一次同步的结账（行数都是"这次实际追加的"）。 */
    data class SyncResult(val exported: Int, val restored: Int) {
        fun toLine(): String = "判决经验同步：导出=$exported 回灌=$restored"
    }

    /**
     * 双向并集同步（幂等）：导出＋回灌，两侧都不重排、不覆盖、只追加缺的行。
     *
     * 冷启动装机时由装配层跑一次；之后挂接点（M7/283 的行为路径）可再跑，代价只是几行 IO。
     */
    @Synchronized
    fun sync(): SyncResult {
        val pub = publicRoot
        val mem = privateRoot
        if (pub == null || mem == null) return SyncResult(0, 0)
        var exported = 0
        var restored = 0
        for (name in MIRRORED) {
            val pubFile = File(pub, name)
            val memFile = File(mem, name)
            val pubRows = readRows(pubFile)
            val memRows = readRows(memFile)
            val pubKeys = pubRows.map { it.first }.toSet()
            val memKeys = memRows.map { it.first }.toSet()
            exported += appendMissing(pubFile, memRows.filter { it.first !in pubKeys })
            restored += appendMissing(memFile, pubRows.filter { it.first !in memKeys })
        }
        if (exported > 0 || restored > 0) RunLog.log("判决经验${"同步"}：导出=$exported 回灌=$restored")
        return SyncResult(exported, restored)
    }

    /**
     * 勘验出口：只出计数（每本账公共/私有两侧行数），**不出任何一行内容**。
     */
    fun conclusion(): String {
        val pub = publicRoot
        val mem = privateRoot
        if (pub == null) return "判决经验：未装配"
        val parts = MIRRORED.map { name ->
            val p = readRows(File(pub, name)).size
            val m = mem?.let { readRows(File(it, name)).size } ?: 0
            "${shortName(name)} 公$p/私$m"
        }
        return "判决经验：${parts.joinToString("，")}"
    }

    /** 某本账在公共副本里的文件（勘验/后续批对账用；未装配 ⇒ null）。 */
    fun publicFileIn(name: String): File? = publicRoot?.let { File(it, name) }

    /** 读写重试次数与间隔（Windows 实时扫描的短暂共享冲突，毫秒级就过去）。 */
    const val READ_ATTEMPTS = 3
    const val READ_RETRY_MS = 60L

    // ── 内部：读行、稳定键、追加 ────────────────────────────────────────────

    /**
     * 读行（带重试）：**刚写完就读**在 Windows 上会撞上实时扫描的短暂共享冲突
     * （首次实测：读侧吞成空表 ⇒ 同一行反复导出）。文件在而读失败重试 3 次；
     * 还失败就记 [lastError]、按空表走（写失败不抛的账本纪律）。
     */
    private fun readRows(f: File): List<Pair<String, String>> {
        if (!f.isFile) return emptyList()
        var last: Throwable? = null
        repeat(READ_ATTEMPTS) { attempt ->
            runCatching {
                // 对偶一律是 **(键, 行)**：去重按 first（键），落盘按 second（行）——
                // 上一版 here 造反过一次，公共副本里写进了键字符串，测试当场抓出来。
                return f.readLines(Charsets.UTF_8)
                    .filter { it.isNotBlank() }
                    .map { stableKey(it) to it }
            }.onFailure {
                last = it
                if (attempt < READ_ATTEMPTS - 1) Thread.sleep(READ_RETRY_MS)
            }
        }
        lastError = "判决经验：${f.name} 读失败——${last?.javaClass?.simpleName}: ${last?.message}"
        RunLog.log(lastError!!)
        return emptyList()
    }

    private fun appendMissing(target: File, rows: List<Pair<String, String>>): Int {
        if (rows.isEmpty()) return 0
        var last: Throwable? = null
        repeat(READ_ATTEMPTS) { attempt ->
            runCatching {
                target.parentFile?.mkdirs()
                target.appendText(rows.joinToString("") { it.second + "\n" }, Charsets.UTF_8)
                return rows.size
            }.onFailure {
                last = it
                if (attempt < READ_ATTEMPTS - 1) Thread.sleep(READ_RETRY_MS)
            }
        }
        lastError = "判决经验：${target.name} 追加失败——${last?.javaClass?.simpleName}: ${last?.message}"
        RunLog.log(lastError!!)
        return 0
    }

    /**
     * 一行的稳定键（去重用）：`id` 优先（意见判定/追认队列都有）；否则 `op+at`（御批）；
     * 都认不出（坏行）⇒ 整行哈希——坏行也要镜像（留痕的意义就是"当时是这么写的"）。
     */
    internal fun stableKey(line: String): String {
        val o = runCatching { JSONObject(line) }.getOrNull()
        if (o != null) {
            val id = o.optString("id")
            if (id.isNotEmpty()) return "i:$id"
            val op = o.optString("op")
            val at = o.optLong("at", 0L)
            if (op.isNotEmpty() && at > 0L) return "o:$op:$at"
        }
        return "r:" + sha8(line)
    }

    private fun samePath(a: File, b: File): Boolean = runCatching {
        a.canonicalFile == b.canonicalFile
    }.getOrDefault(a == b)

    private fun shortName(name: String): String = name.removeSuffix(".jsonl")

    private fun sha8(s: String): String = runCatching {
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            .take(8)
    }.getOrDefault("00000000")
}
