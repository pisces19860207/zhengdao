// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao

/**
 * 应用自更新的两个**纯判据**（第七十六批 · §B 80 `MainActivity` 纯逻辑抽取）。
 *
 * 搬移来源：`MainActivity.checkAppUpdateInBackground` 里的 tag 解析与 `isNewerVersion`。
 * 抽出的理由不是「好看」，而是这两条规则一旦写错，症状都不易察觉：
 * - 版本比较错 ⇒ 该提示的更新不提示（用户以为已是最新），或旧版本反复弹窗；
 * - tag 解析错 ⇒ 拿到 null 直接静默返回，等同于**永不检查更新**。
 * 两者都能在 JVM 上逐格钉住，所以不留在 Activity 里靠真机碰。
 *
 * 零 Android 依赖：不碰 `BuildConfig`、不碰网络，调用方负责取当前版本号。
 */
internal object AppUpdateRules {

    /**
     * 从 GitHub Releases 响应体里取 `tag_name` 的正则。
     *
     * 2026-10-20 从 `MainActivity` 原样搬来（字面量未改一个字符）。语义要点：
     * **取第一个匹配**——GitHub 的 `releases/latest` 响应里 `tag_name` 只出现一次；
     * 若将来响应体里出现第二个，行为按现状取首个，不改成「取最后一个」。
     */
    private val TAG_NAME_RE = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"")

    /** 响应体 → 版本 tag（如 `v2.2.0`）；找不到返回 null（调用方据此静默跳过）。 */
    fun parseLatestTag(body: String): String? = TAG_NAME_RE.find(body)?.groupValues?.get(1)

    /**
     * 语义化版本比较：remote **严格大于** local 才算有更新。
     *
     * 容错与「不对称」都是现状语义，不许顺手改：
     * - 只对 **remote** 剥 `v` 前缀；`local` 不剥 —— local 来自 `BuildConfig.VERSION_NAME`，
     *   本来就没有 `v`，剥了反而掩盖「有人给 versionName 加了 v」这种真问题；
     * - 缺失段按 0 补齐（`1.2` 与 `1.2.0` 相等）；
     * - **段首非数字按 0**（`1.x.3` ⇒ `[1,0,3]`），不抛异常。
     *
     * 683（2026-11-16）：段的解析从「整段必须是数字」放宽成「**取段首连续数字**」。
     * 起因是 debug 包的 versionName 变成 `2.2.6-debug+9501f76`（versionNameSuffix）：
     * 老写法把第三段判成非数字 ⇒ local=[2,2,0] ⇒ 远端 `v2.2.6` 永远"比本地新"
     * ⇒ 开发版每次启动都弹一次更新提示。放宽后第三段读到 6，两个 2.2.6 相等、不弹。
     * ⚠️ 这条放宽**没有**改变「段首非数字仍按 0」的语义（`1.x.3` 的 `x` 还是 0）。
     */
    fun isNewer(remote: String, local: String): Boolean {
        val r = segments(remote.removePrefix("v"))
        val l = segments(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** 按 `.` 切段，每段取**段首连续数字**（`6-debug+x` ⇒ 6，`x1` ⇒ 0）。 */
    private fun segments(v: String): List<Int> =
        v.split('.').map { seg -> seg.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
}
