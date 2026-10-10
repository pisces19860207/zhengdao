// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.terminal

/**
 * termux-proot 内置二进制的「已验证戳」（BUG-2，2026-10-10 / E-083）。
 *
 * 背景：[ProotLauncher.buildLaunchPlan] 每次开会话都要对 4 个内置资产（`tproot` / `tloader` /
 * `libtalloc.so` / `libandroid-shmem.so`，合计 313,648 B）重算一遍 SHA-256 做版本固定校验。
 * 一次不过几毫秒，但"每次点开终端都白付一遍"没有意义——`files/termux-proot/` 是 **App 私有
 * 目录，别的 App 写不进来**，要防的只是"自己释放时写坏/半截"，而那一定会改大小或 mtime。
 *
 * 做法：核对通过后把 `目标名 → 大小 + mtime + 当时钉住的 sha256` 记到
 * `files/termux-proot/.pinned-verified`；下次启动三者**全对得上**就跳过重算。
 * 任一不符（文件被动过 / APK 升版换了钉住的二进制 / 戳丢了）⇒ 照旧全量重算，绝不因为"有戳"放行。
 *
 * ⚠️ 不变量（不许简化）：只有"内容哈希确实等于 expectedSha"的那一刻才会写戳。戳是"自那次核对
 * 之后没被动过"的证明，**不是**校验的替代品——所以 [needsReverify] 里 expectedSha 一变就必须重算。
 *
 * 纯逻辑、不碰 Android API（真机行为靠 RunLog 的「跳过重算 N 项 / 实算 M 项」取证）。
 */
internal object PinnedAssets {
    /** 戳文件名（落在 `files/termux-proot/` 下，App 私有）。 */
    const val FILE_NAME = ".pinned-verified"

    /** 一条"核对通过"的记录；`sha` 是当时钉住且实测相符的那份。 */
    data class Stamp(val size: Long, val mtime: Long, val sha: String)

    /**
     * 解析戳文件：一行一条 `目标名\tsize\tmtime\tsha`。
     * 坏行一律跳过（宁可不省这一步重算，也不能拿一条读不懂的戳去放行）。
     */
    fun parse(text: String): Map<String, Stamp> {
        val out = LinkedHashMap<String, Stamp>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val f = line.split('\t')
            if (f.size != 4) continue
            val name = f[0]
            if (name.isEmpty()) continue
            val size = f[1].toLongOrNull() ?: continue
            val mtime = f[2].toLongOrNull() ?: continue
            val sha = f[3].trim().lowercase()
            if (sha.length != 64) continue
            out[name] = Stamp(size, mtime, sha)
        }
        return out
    }

    /** 序列化（按目标名排序，写出的文件稳定，便于人眼 diff）。 */
    fun format(stamps: Map<String, Stamp>): String =
        stamps.entries.sortedBy { it.key }
            .joinToString(separator = "\n", postfix = "\n") { (k, s) ->
                "$k\t${s.size}\t${s.mtime}\t${s.sha}"
            }

    /**
     * 需要重算哈希吗？
     * - 没有戳 → 要（第一次、或戳文件被删/写坏）
     * - 大小或 mtime 变了 → 要（文件被改写过）
     * - 戳里的 sha 与这次期望的不一致 → 要（APK 升版换了钉住的二进制）
     * - 三者全对 → 不用（自那次核对后没被动过）
     */
    fun needsReverify(size: Long, mtime: Long, expectedSha: String, stamp: Stamp?): Boolean {
        if (stamp == null) return true
        if (size != stamp.size) return true
        if (mtime != stamp.mtime) return true
        return !stamp.sha.equals(expectedSha.trim(), ignoreCase = true)
    }

    /** 核对通过时该记什么（sha 统一小写入盘）。 */
    fun stampFor(size: Long, mtime: Long, expectedSha: String): Stamp =
        Stamp(size, mtime, expectedSha.trim().lowercase())
}
