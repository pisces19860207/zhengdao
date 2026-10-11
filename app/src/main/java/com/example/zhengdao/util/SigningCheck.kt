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
 * 第一方代码自 2026-10-11 起为「保留所有权利」（历史版本 ≤ v2.0.15 为 GPL-3.0，见仓库根 LICENSE）：
 * 无论哪一种，技术上都会有人重打包 —— 保留所有权利**不允许**未经许可的再分发，但这仍是许可证与
 * 法律层面的事，App 管不了。能做的、也该做的是：**用户有权知道自己装的是谁签的包**：
 * 本 App 被重新签名后，签名证书和官方那两把（正式包的 release 钥匙、历史包的 debug 钥匙，
 * 见下面两个常量与 `app/build.gradle.kts` 顶部 `zdKeystorePath` 的说明）必然不同。
 *
 * ## 只提醒，不拦功能
 *
 * [Result.UNOFFICIAL] 时只在启动时弹一次提示、在「关于」页写一行来源，**不禁用任何功能**：
 * 用户可能正是自己改过代码、自己签的名（现行许可允许为评估而本地构建运行，历史 GPL 版本更是明确允许），
 * App 没有资格替他做决定。
 * 这与"网络自检""环境体检"的取向一致 —— 如实报告，不代替用户拍板。
 *
 * ## 它能防住什么、防不住什么
 *
 * - 能：让"下了个改名换皮的包"的用户知道来源不对；也让重打包者知道 App 会说实话。
 * - 不能：不能阻止重打包（那是许可证与法律的事），也不能替代软著登记/商标这类维权手段。
 */
object SigningCheck {

    /**
     * 官方**正式包**的签名证书 SHA-256（大写十六进制，无冒号）。
     *
     * 取自 `C:\Users\guoli\.zhengdao-keys\zhengdao-release.jks`（alias `zhengdao`，RSA 4096，
     * 有效期至 2056-10；2026-10-11 启用，见 ERRATA E-099）：
     * `keytool -list -v -keystore zhengdao-release.jks -storepass <口令>`。
     * 从这一版起，GitHub Releases 里的正式包与 CI 的 release 变体都用它签。
     */
    const val OFFICIAL_RELEASE_SHA256 = "B93169F211C6B3D9FDAE36B2C614C23BEF28EAE3582E4D8BCA0657C27AC86EC2"

    /**
     * 历史官方签名 —— v2.0.12 及更早的 release / debug 包都签自这把 debug keystore
     * （alias `androiddebugkey`，本机 `~/.android/debug.keystore`）。
     *
     * ⚠️ 换了正式钥匙之后**仍留在白名单里**：用户手机上装着的旧包正是它签的，它当然是官方包，
     * 不该被自己的 App 报成"非官方"；开发者本地 `assembleDebug`（AGP 内建 debug 签名）同理。
     * 见 ERRATA E-099。
     */
    const val OFFICIAL_LEGACY_DEBUG_SHA256 = "44E2FE86B1F62A9FDB2E86805FE0A4DAE7CAD0C3024DBF6AF84D0C45B5A3BE18"

    /** 全部官方指纹（新 release 钥匙 + 历史 debug 钥匙）。 */
    val OFFICIAL_SHA256S = setOf(OFFICIAL_RELEASE_SHA256, OFFICIAL_LEGACY_DEBUG_SHA256)

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
        return if (hex in OFFICIAL_SHA256S) Result.OFFICIAL else Result.UNOFFICIAL
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
