// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.util

import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest

/**
 * 安装包签名自检（2026-10-09，ERRATA E-065）。
 *
 * ## 为什么要有这个
 *
 * 第一方代码是 GPL-3.0：**允许**别人复制、修改、重新打包，甚至拿去卖（只要保留署名、公开改动、
 * 不给下游加额外限制）。技术上挡不住重打包，但**用户有权知道自己装的是谁签的包**：
 * 本 App 被重新签名后，签名证书和官方那把（仓库 CI 与本机共用同一把 debug keystore，
 * 见 `app/build.gradle.kts` 顶部 `zdKeystorePath` 的说明）必然不同。
 *
 * ## 只提醒，不拦功能
 *
 * [Result.UNOFFICIAL] 时只在启动时弹一次提示、在「关于」页写一行来源，**不禁用任何功能**：
 * 用户可能正是自己改过代码、自己签的名（GPL 明确允许），App 没有资格替他做决定。
 * 这与"网络自检""环境体检"的取向一致 —— 如实报告，不代替用户拍板。
 *
 * ## 它能防住什么、防不住什么
 *
 * - 能：让"下了个改名换皮的包"的用户知道来源不对；也让重打包者知道 App 会说实话。
 * - 不能：不能阻止重打包（那是许可证与法律的事），也不能替代软著登记/商标这类维权手段。
 */
object SigningCheck {

    /**
     * 官方安装包的签名证书 SHA-256（大写十六进制，无冒号）。
     *
     * 取自本机 `~/.android/debug.keystore`（alias `androiddebugkey`）：
     * `keytool -list -v -keystore ~/.android/debug.keystore -storepass android`。
     * release 与 debug 变体用的是**同一把** keystore（`signingConfig = signingConfigs.getByName("debug")`），
     * 所以这个值对 GitHub Releases 里的正式包同样成立。
     */
    const val OFFICIAL_SHA256 = "44E2FE86B1F62A9FDB2E86805FE0A4DAE7CAD0C3024DBF6AF84D0C45B5A3BE18"

    enum class Result {
        /** 签名与官方一致。 */
        OFFICIAL,

        /** 签名对不上 —— 这个包被重新签过名（重打包）。 */
        UNOFFICIAL,

        /** 读不到签名信息（正常情况下不会发生）。不误报，按"未识别"处理。 */
        UNKNOWN,
    }

    fun check(ctx: Context): Result {
        val hex = runCatching { signerSha256(ctx) }.getOrNull() ?: return Result.UNKNOWN
        return if (hex == OFFICIAL_SHA256) Result.OFFICIAL else Result.UNOFFICIAL
    }

    /** 当前安装包第一个签名证书的 SHA-256（大写十六进制）；取不到返回 null。 */
    fun signerSha256(ctx: Context): String? {
        val pm = ctx.packageManager
        // minSdk = 36 ⇒ 直接用 API 33 的 PackageInfoFlags 形式，不走已废弃的重载
        val info = pm.getPackageInfo(
            ctx.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        )
        val signer = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return null
        return hexOf(MessageDigest.getInstance("SHA-256").digest(signer.toByteArray()))
    }

    /**
     * 字节数组 → 大写十六进制（纯函数，可本机单测）。
     *
     * 刻意与 `keytool` 的输出格式对齐（大写、**不带**冒号），这样排查时能直接肉眼比对。
     */
    internal fun hexOf(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private const val HEX = "0123456789ABCDEF"
}
