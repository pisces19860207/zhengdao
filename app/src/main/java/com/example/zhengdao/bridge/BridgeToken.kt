package com.example.zhengdao.bridge

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom
import java.util.Locale

/**
 * 桥的鉴权 token（设计文档 §4.5「端点与鉴权」）。
 *
 * ## 模型
 *
 * ```
 * 首次启动：SecureRandom 生成 32 字节 → 64 位 hex → 存 App 私有 SharedPreferences
 * 每次请求：Authorization: Basic base64("browser:<token>")
 * 下发方式：用户在「浏览器桥」设置项里一次性复制，粘进终端 agent 的环境变量
 * ```
 *
 * ## 为什么口令随机生成、不让用户设
 *
 * 用户设的口令有可猜性（生日、手机号），而这个 token 是**给一条能操作浏览器的通道**用的；
 * 32 字节随机 ⇒ 2^256 空间，爆破在数学上不成立。用户侧的成本只是"复制粘贴一次"。
 *
 * ## 为什么单独一个 SharedPreferences 文件（ERRATA E-129 的教训）
 *
 * E-129：上一个骨架把 token 明文存进会跟着**云备份**走的 sharedpref，而两份备份规则
 * 都没排 `domain="sharedpref"` ⇒ token 会进 Google 云备份。本实现用**独立文件**
 * `bridge_token.xml`，并在 `res/xml/backup_rules.xml` 与 `data_extraction_rules.xml`
 * 里按文件名排除 —— 只排这一个文件，不影响设置项等其他 prefs
 * （门禁：`BackupRulesGuardTest` 有一条专门钉它，见该文件 §E-129）。
 *
 * 不选 `EncryptedSharedPreferences` 的原因：那需要 `androidx.security:security-crypto`，
 * 而那个库已进入维护模式、且为 32 字节随机串引入一个依赖不划算；
 * "排备份 + 私有目录"已经堵住 E-129 指的那条外流路径。
 *
 * ## 纯函数纪律（E-130：Context-free 内核 + 注入）
 *
 * JVM 单测拿不到 Context（`android.jar` stub 构造全抛 `Stub!`）⇒
 * 所有**判定逻辑**都是顶层 internal 纯函数（[generateTokenHex] / [parseBasic] / [matches]），
 * 可注入 `SecureRandom` 与 header 串直接测；只有**存取**碰 Context（[getOrCreate] / [regenerate]），
 * 那两个不测（也测不了），由门禁在源码层钉住"只走这个文件、只走私有 prefs"。
 */
object BridgeToken {

    /** 独立的 prefs 文件名（**必须**被两份备份规则按名排除，见类 KDoc）。 */
    const val PREFS_NAME: String = "bridge_token"

    private const val KEY_TOKEN = "token"

    /** 32 字节 = 256 bit。 */
    private const val TOKEN_BYTES = 32

    // ── 纯函数（可测） ─────────────────────────────────────────────────────

    /**
     * 生成 token：32 随机字节 → 64 位小写 hex。
     *
     * @param random 可注入（测试里传种子固定的 `SecureRandom`，生产传真随机）
     */
    internal fun generateTokenHex(random: SecureRandom): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        val sb = StringBuilder(TOKEN_BYTES * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()

    /**
     * 解析 `Authorization: Basic <base64>` → `(user, pass)`；不合形返回 null。
     *
     * 容忍大小写与多余空白（HTTP header 的实际形态很杂），但不"猜"内容：
     * base64 解不出来、或解出来的串里没有 `:` ⇒ null（调用方按 401 处理）。
     */
    internal fun parseBasic(header: String?): Pair<String, String>? {
        if (header.isNullOrBlank()) return null
        val parts = header.trim().split(Regex("\\s+"), limit = 2)
        if (parts.size != 2) return null
        if (!parts[0].lowercase(Locale.ROOT).startsWith("basic")) return null
        val decoded = try {
            // ⚠️ 用 **java.util.Base64** 而不是 android.util.Base64：后者在 android.jar 里是
            // stub，JVM 单测一调就返回 null 再抛 NPE（ERRATA E-130 同款坑，本文件首版真踩了）。
            // minSdk = 36 ⇒ java.util.Base64（API 26+）可用，且让 parseBasic 保持"只吃 String"
            // 的纯函数形态（JVM 可测）。
            String(java.util.Base64.getDecoder().decode(parts[1]), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val idx = decoded.indexOf(':')
        if (idx <= 0) return null
        return decoded.substring(0, idx) to decoded.substring(idx + 1)
    }

    /**
     * 校验一次请求的 Authorization header 是否匹配期望 token。
     *
     * 判据 = **用户名固定 `browser` + 口令恒定时间比较**。用恒定时间是因为
     * `==` 会在第一个不同字符处返回，理论上可被时序攻击逐字符猜出口令
     * （环回 + 本地，实际 exploiting 极难，但口令校验用恒定时间是零成本的好习惯）。
     */
    internal fun matches(expectedToken: String, header: String?): Boolean {
        val cred = parseBasic(header) ?: return false
        if (cred.first != BridgePolicy.BASIC_USER) return false
        return constantTimeEquals(cred.second, expectedToken)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    /** 设置页/日志里显示用：前 4 + … + 后 4（token 不该整条出现在界面上被瞟到）。 */
    internal fun masked(token: String): String =
        if (token.length <= 8) "****" else token.take(4) + "…" + token.takeLast(4)

    // ── 存取（碰 Context，不测） ───────────────────────────────────────────

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 取得到就复用、取不到就生成（幂等：同一设备同一 token，重装 App 才变）。 */
    fun getOrCreate(ctx: Context): String {
        val existing = prefs(ctx).getString(KEY_TOKEN, null)
        if (!existing.isNullOrBlank()) return existing
        val fresh = generateTokenHex(SecureRandom())
        prefs(ctx).edit().putString(KEY_TOKEN, fresh).apply()
        return fresh
    }

    /** 重新生成（设置页「重置口令」用；旧 token 立即失效）。 */
    fun regenerate(ctx: Context): String {
        val fresh = generateTokenHex(SecureRandom())
        prefs(ctx).edit().putString(KEY_TOKEN, fresh).apply()
        return fresh
    }

    /** 供设置页显示的一次性复制内容（只复制 token 本身，不含用户名）。 */
    fun copyable(ctx: Context): String = getOrCreate(ctx)

    /** 设置页显示用（打码）。 */
    fun maskedForDisplay(ctx: Context): String = masked(getOrCreate(ctx))
}
