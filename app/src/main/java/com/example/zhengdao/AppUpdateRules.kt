// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
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
     * - 非数字段按 0 处理（`1.x.3` ⇒ `[1,0,3]`），不抛异常。
     */
    fun isNewer(remote: String, local: String): Boolean {
        val r = remote.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        val l = local.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }
}
