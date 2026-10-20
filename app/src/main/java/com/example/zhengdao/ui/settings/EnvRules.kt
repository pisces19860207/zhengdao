// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui.settings

import com.example.zhengdao.ui.InstallProgress

/**
 * 设置页「环境与更新」的**纯判定**（第八十批 · §B 80 切片，2026-10-20）。
 *
 * 为什么单独有这个文件：`ui/SettingsEnvActivity.kt` 里「检查环境更新」那段是整页最长的一坨
 * ——从 `rootfs-index.json` 拿到索引之后，按「本地有没有 env 记录 / 版本指纹是否相同 /
 * 缓存里有没有整包 / 索引给不给增量」四件事组合出 8 种口径，每种口径决定三件事：
 * ①给用户看哪句话；②有没有可下载目标（有 ⇒ 弹确认框，没有 ⇒ 只 Toast）；
 * ③这次要不要先试增量。
 *
 * 这四件事全是**值**（字符串、字节数、布尔），却和网络、文件读取、`Context.getString`
 * 缠在一个 75 行的 `when` 里，于是「哪种情况该弹窗」从来没有被单测钉过 —— 2026-10-08 那次
 * 「同版本被叫成发现新版本」的真机投诉，起因正是这段判据没人守。
 *
 * 本文件只做三件事：输入普通值、输出普通值/数据类、可被 JVM 单测直接调。
 * **不碰** Compose、Context、File、网络、安装执行；页面仍是唯一的执行者与文案拼装者。
 *
 * 迁移纪律：这里每条分支都必须与迁移前页面里的分支**逐条对应**（见交付报告的分支对照表），
 * 不做"顺手修一修"。惰性探测（本地版本、缓存候选）保持页面侧的取值闭包，判定只在需要时调用，
 * 保证迁移后探测次数与迁移前一致。
 */
object EnvRules {

    // ── ① 更新判定：版本号展示 + 8 种口径 ───────────────────────────────

    /**
     * 索引里用来称呼这次更新的版本号：`version` 优先，空了退到 `distro`，都空才用
     * [fallback]（页面传 `R.string.env_version_unknown` 的实际文案）。
     *
     * [fallback] 由调用方给，是为了让本函数不依赖资源表，同时**保持迁移前的比较口径**：
     * 迁移前 `ver` 就是这个三元表达式的结果，后面还会拿它跟本地版本比字符串。
     */
    fun displayVersion(version: String, distro: String, fallback: String): String =
        version.ifBlank { distro.ifBlank { fallback } }



    /** 这次该给用户看哪一句（页面据此选 string 资源，本文件不拼文案）。 */
    enum class UpdateNotice {
        /** 标记里没有 env 行、但缓存里躺着索引那个包，且本地版本与索引版本相同。 */
        LocalEnvMissingCached,

        /** 标记里没有 env 行、缓存里有索引那个包，版本不同/未知。 */
        LocalCached,

        /** 标记里没有 env 行、缓存里没有包，本地版本与索引版本相同（只需补指纹）。 */
        LocalEnvMissingFull,

        /** 标记里没有 env 行、缓存里没有包，版本也确实是新的。 */
        NewVersionNoLocalRecord,

        /** 本地 env 指纹与索引一致 ⇒ 已是最新。 */
        UpToDate,

        /** 有新版本、缓存里已有该整包。 */
        NewVersionCached,

        /** 有新版本、索引给了增量补丁、且补丁基线正是本机 env ⇒ 先试增量。 */
        NewVersionPatch,

        /** 有新版本，走完整下载。 */
        NewVersionFull,
    }

    /**
     * 判定结果。
     *
     * @param notice 展示口径
     * @param version 称呼这次更新的版本号（已过 [displayVersion]）
     * @param sizeBytes 文案里要显示的那个字节数（缓存包/补丁/整包，随口径不同）
     * @param wantPatch 是否把索引给的补丁挂上去先试增量
     *
     * 这里**没有**「有没有下载目标」这一项：那条判据只有一处 —— [isUpToDate] 取反。
     * 迁移前它是"每个分支各自 `pendingUrl = idx.url`"，等于把同一条判据抄了六遍；
     * 收成一条之后，界面上"弹不弹确认框"只有一个来源。
     */
    data class UpdatePlan(
        val notice: UpdateNotice,
        val version: String,
        val sizeBytes: Long,
        val wantPatch: Boolean,
    )

    /**
     * 本地 env 指纹与索引给的一致 ⇒ 已是最新（不弹确认框、不产生下载目标）。
     *
     * 迁移前的判据是 `localEnv.equals(idx.env, ignoreCase = true)`。`localEnv == null`
     * （老安装缺 env 行）必须算**不**是最新 —— 那种情况要补指纹，得走一次全量。
     */
    fun isUpToDate(localEnv: String?, indexEnv: String): Boolean =
        localEnv != null && localEnv.equals(indexEnv, ignoreCase = true)

    /**
     * 更新判定的纯内核。调用前提：索引取到了、且 `indexEnv` 非空（否则页面走老 manifest 降级路径）。
     *
     * [localVersion] / [cachedBytes] 是**取值闭包**而不是先算好的值 —— 迁移前这两项只在需要它们
     * 的分支里才去读盘（`RootfsCache.currentVersion` 读一个文件、`localCandidateFor` 列一次目录），
     * 传闭包才能保证迁移后"已是最新"这条主路径上的读盘次数与迁移前一模一样。
     *
     * @param localEnv 标记里的 env 指纹；null = 老安装（标记缺 env 行）
     * @param localVersion 本地缓存里的发行版号（可能空串）
     * @param cachedBytes 缓存里那个整包的字节数；null = 没缓存
     * @param patchFrom 索引给的补丁基线 env；null = 索引没给补丁
     */
    fun planUpdate(
        localEnv: String?,
        indexEnv: String,
        indexVersion: String,
        indexSize: Long,
        localVersion: () -> String,
        cachedBytes: () -> Long?,
        patchFrom: String?,
        patchSize: Long,
    ): UpdatePlan {
        val ver = indexVersion
        if (localEnv == null) {
            val localVer = localVersion()
            val cached = cachedBytes()
            val sameVer = localVer.isNotBlank() && localVer.equals(ver, ignoreCase = true)
            return when {
                cached != null && sameVer -> UpdatePlan(
                    UpdateNotice.LocalEnvMissingCached, ver, cached, wantPatch = false,
                )
                cached != null -> UpdatePlan(
                    UpdateNotice.LocalCached, ver, cached, wantPatch = false,
                )
                sameVer -> UpdatePlan(
                    UpdateNotice.LocalEnvMissingFull, ver, indexSize, wantPatch = false,
                )
                else -> UpdatePlan(
                    UpdateNotice.NewVersionNoLocalRecord, ver, indexSize, wantPatch = false,
                )
            }
        }
        if (isUpToDate(localEnv, indexEnv)) {
            return UpdatePlan(UpdateNotice.UpToDate, ver, indexSize, wantPatch = false)
        }
        val cached = cachedBytes()
        return when {
            cached != null -> UpdatePlan(
                UpdateNotice.NewVersionCached, ver, cached, wantPatch = false,
            )
            patchFrom != null && patchFrom.equals(localEnv, ignoreCase = true) -> UpdatePlan(
                UpdateNotice.NewVersionPatch, ver, patchSize, wantPatch = true,
            )
            else -> UpdatePlan(
                UpdateNotice.NewVersionFull, ver, indexSize, wantPatch = false,
            )
        }
    }

    // ── ② 老 manifest（降级路径）的字段解析 ──────────────────────────────

    /** 老 manifest 里我们认得的三件东西；解析不出来的那一项为 null。 */
    data class LegacyManifest(
        val version: String?,
        val url: String?,
        val sha256: String?,
    )

    /**
     * 从老 manifest 的原文里抠 `version` / `url` / `sha256`。
     *
     * 该文件已无人维护（ERRATA E-033），但索引取不到时仍是唯一的降级来源，所以解析规则
     * 原样搬过来：三条独立正则、抠不到就是 null，**不猜、不补默认值**。
     */
    fun parseLegacyManifest(text: String): LegacyManifest = LegacyManifest(
        version = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1),
        url = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1),
        sha256 = Regex("\"sha256\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1),
    )

    // ── ③ 安装状态 → 展示与可点性 ───────────────────────────────────────

    /**
     * 一条安装状态该长什么样。
     *
     * @param icon 阶段图标（迁移前是 `"⏳" to ...` 那三对里的左边）
     * @param isError 用不用错误色（迁移前是选 `colorScheme.error` 还是 `primary`）
     * @param showCloseRow 要不要出收尾那一行（迁移前判据：`phase != Running`）
     * @param showRetry 收尾行里给不给「重试」入口（迁移前判据：`phase == Failed`）
     */
    data class PhaseView(
        val icon: String,
        val isError: Boolean,
        val showCloseRow: Boolean,
        val showRetry: Boolean,
    )

    fun phaseView(phase: InstallProgress.Phase): PhaseView = when (phase) {
        InstallProgress.Phase.Running -> PhaseView("⏳", isError = false, showCloseRow = false, showRetry = false)
        InstallProgress.Phase.Done -> PhaseView("✅", isError = false, showCloseRow = true, showRetry = false)
        InstallProgress.Phase.Failed -> PhaseView("⚠️", isError = true, showCloseRow = true, showRetry = true)
    }

    // ── ④ 修复环境的本地包合格条件 ───────────────────────────────────────

    /**
     * 「修复环境」只认 ≥100MB 的本地包（迁移前的字面量 `100_000_000L`）。
     *
     * 这条门槛存在的理由是防呆：`Download/证道` 里躺着的 `.zst`、`.sha256` 小文件、
     * 下了一半的碎片都不该被当成安装包去解压。**严格大于**：迁移前写的是 `>`，
     * 恰好 100_000_000 字节的包不会被选中。
     */
    const val REPAIR_MIN_ARCHIVE_BYTES: Long = 100_000_000L

    /** 候选是否够格当修复用的本地包（名字不参与判定，只看"是文件"和"够大"）。 */
    fun isRepairCandidate(isFile: Boolean, sizeBytes: Long): Boolean =
        isFile && sizeBytes > REPAIR_MIN_ARCHIVE_BYTES

    // ── ⑤ 下载进度节流 ───────────────────────────────────────────────────

    /**
     * 下载回调该不该往状态行推一条进度，该推就给出百分比。
     *
     * 迁移前是 `if (total > 0 && done * 100 / total % 10 == 0L)`：每 10% 一档，
     * 免得每读一个 8KB 块就刷一次通知栏。整数除法与取模顺序照搬，**不改精度**。
     *
     * @return 该推送时的整数百分比；[total] ≤ 0（长度未知）或没踩到 10% 档时返回 null
     */
    fun progressPercent(done: Long, total: Long): Int? {
        if (total <= 0L) return null
        val pct = done * 100L / total
        return if (pct % 10L == 0L) pct.toInt() else null
    }
}
