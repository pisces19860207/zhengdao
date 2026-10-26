// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第二百一十六批 · MCP OAuth 2.1：**加密的 SharedPreferences 实现**（token 的唯一落点）。
//
// 为什么不直接 `androidx.security:security-crypto` 的 EncryptedSharedPreferences：
//   · 本批负例⑦明令「不引新加密库」；
//   · 那个库已进入维护模式（README 自述），而仓里 BridgeToken 已有先例
//     （E-129 的教训：不引依赖、但必须排云备份）。
// 所以这里**自己实现等价的加密 prefs**：AndroidKeyStore 里一把不可导出的 AES-256 密钥，
// 每条值 AES/GCM 加密（随机 IV，认证加密）后再写进私有 prefs —— 语义与
// EncryptedSharedPreferences 相同，只是不依赖那个库。
//
// 红线：
// 1. 密钥**不可导出**（AndroidKeyStore + setRandomizedEncryptionRequired），不进 APK、不进代码；
// 2. 明文只在内存里存在一瞬（加密后立刻写密文）；写盘内容里没有 token 原文；
// 3. prefs 文件名 `baopu_mcp_token` 必须被两份备份规则排除（E-129 同款门禁，
//    见 res/xml/backup_rules.xml 与 data_extraction_rules.xml）。
package com.example.zhengdao.baopu.mcp

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 加密 prefs 的存储实现（[McpSecretStore] 的 Android 版）。
 *
 * 值格式：`base64(iv ‖ 密文 ‖ tag)`（GCM 的 tag 由 JCE 追加在密文尾部）。
 * 解密失败（换机、密钥被系统失效、被手改）⇒ 删掉该键返回 null：宁可让用户重新授权，
 * 也不把"解不开的东西"当 token 用。
 */
internal class PrefsMcpSecretStore(context: Context) : McpSecretStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val cipher = McpKeystoreCipher()

    override fun read(key: String): String? {
        val blob = prefs.getString(key, null) ?: return null
        return try {
            cipher.decrypt(blob)
        } catch (e: Exception) {
            // 不打印异常原文（可能带密文片段）；只记类名。
            prefs.edit { remove(key) }
            null
        }
    }

    override fun write(key: String, value: String) {
        val blob = cipher.encrypt(value)
        prefs.edit { putString(key, blob) }
    }

    override fun remove(key: String) {
        prefs.edit { remove(key) }
    }

    override fun keys(): Set<String> = prefs.all.keys.toSet()

    companion object {
        /** 独立文件（**必须**被备份规则按名排除；E-129 的教训）。 */
        const val FILE = "baopu_mcp_token"
    }
}

/**
 * AndroidKeyStore 里的一把 AES-256-GCM 密钥（不可导出）。
 *
 * 单测碰不到它（`android.jar` 是 stub），所以**判定逻辑一行都不在这里**：它只做
 * 「拿密钥 → 加/解密 → base64」，任何分支错误都以异常上抛，由 [PrefsMcpSecretStore] 兜。
 */
internal class McpKeystoreCipher {

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // 随机 IV（GCM 复用 IV 会直接毁掉认证加密的安全性）；默认就是 true，
                // 这里显式写出来，防止有人"为了可复现"把它关掉。
                .setRandomizedEncryptionRequired(true)
                // 不要求用户生物识别：MCP 工具调用可能发生在后台（模型跑工具链时），
                // 每次弹指纹会让"自动跑完一个任务"变成不可能。
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.ENCRYPT_MODE, key())
        val iv = c.iv
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        val out = ByteArray(iv.size + ct.size)
        iv.copyInto(out, 0)
        ct.copyInto(out, iv.size)
        return Base64.getEncoder().encodeToString(out)
    }

    fun decrypt(blob: String): String {
        val all = Base64.getDecoder().decode(blob)
        require(all.size > IV_BYTES) { "密文过短" }
        val iv = all.copyOfRange(0, IV_BYTES)
        val ct = all.copyOfRange(IV_BYTES, all.size)
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        return String(c.doFinal(ct), Charsets.UTF_8)
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "zhengdao_mcp_token_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        /** GCM 标准 IV 长度（96 bit）。 */
        private const val IV_BYTES = 12

        /** GCM tag 长度（128 bit）。 */
        private const val TAG_BITS = 128
    }
}
