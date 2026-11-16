// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui

/**
 * 关于页「版本」那一行的拼装（683，2026-11-16 · 纯函数，JVM 可逐格钉）。
 *
 * 为什么抽出来：673 第⑫项的病根是「装包前后 versionCode/versionName 一模一样」，
 * 于是「装的是不是新包」只能拿 dumpsys 的 lastUpdateTime 反推。现在把三件事压进一行：
 *
 * ```
 * 2.2.6-debug+9501f76 (3631427) · 9501f76 · 2026-11-16 21:24
 *    ↑ versionName        ↑ 构建号   ↑ sha7    ↑ 构建时间
 * ```
 *
 * 三条口径（改之前想清楚，改完要动 [AboutVersionLineTest]）：
 * - **不在这里读 `BuildConfig`**：读它就没法在 JVM 上逐格钉，调用方传值进来（同
 *   `AppUpdateRules` 的做法——纯逻辑不碰 Android）。
 * - **sha7 = 短号前 7 位**，不是 8 位：派工单口径是 7（版本后缀 `-debug+<sha7>` 也是 7），
 *   两处必须同一个口径，否则「后缀里的号」与「这一行的号」会差一位，核对时白挑一遍刺。
 *   `-dirty` 后缀**保留**：561 的规矩是「脏包不许外发」，这一行就是它现形的地方。
 * - **时间只到分钟**：这一行是给人在手机上看「这是不是刚装的那个」的，秒与偏移量在
 *   `BuildAuditPack` 的 run.json 里是全的；不过这一行认不出 ISO 就**原样返回**，
 *   绝不吞掉——宁可难看，不许说谎。
 */
internal object AboutVersionLine {

    /** 一行式：`versionName (versionCode) · sha7 · 构建时间`。 */
    fun of(versionName: String, versionCode: Int, gitSha: String, buildTime: String): String {
        val name = versionName.trim().ifEmpty { "?" }
        return "$name ($versionCode) · ${shortSha(gitSha)} · ${shortTime(buildTime)}"
    }

    /** 短号取前 7 位；`-dirty` 这类后缀照原样挂在后面；空串给 `?`。 */
    fun shortSha(gitSha: String): String {
        val s = gitSha.trim()
        if (s.isEmpty()) return "?"
        val cut = s.indexOf('-')
        val head = if (cut >= 0) s.substring(0, cut) else s
        val tail = if (cut >= 0) s.substring(cut) else ""
        return head.take(7) + tail
    }

    /** ISO 带偏移（`2026-11-16T21:24:57.123+08:00`）→ `2026-11-16 21:24`；认不出原样返回。 */
    fun shortTime(iso: String): String {
        val t = iso.trim()
        if (!looksLikeIsoLocal(t)) return t
        return t.substring(0, 10) + " " + t.substring(11, 16)
    }

    /** 号不可信（空 / `local` / `-dirty`）⇒ 关于页该标红（App 只报不拦，561 的规矩）。 */
    fun suspect(gitSha: String): Boolean {
        val s = gitSha.trim()
        return s.isEmpty() || s == "local" || s.endsWith("-dirty")
    }

    /**
     * `YYYY-MM-DDTHH:MM` 这 16 个位置是不是**该有的字符**。
     * 只做位置校验（不做日历校验）：这一行的用途是"认得出这是什么时候构建的"，
     * 不是把 ISO 8601 解析器再写一遍。
     */
    private fun looksLikeIsoLocal(t: String): Boolean {
        if (t.length < 16) return false
        if (t[4] != '-' || t[7] != '-' || t[10] != 'T' || t[13] != ':') return false
        val digits = intArrayOf(0, 1, 2, 3, 5, 6, 8, 9, 11, 12, 14, 15)
        return digits.all { t[it].isDigit() }
    }
}
