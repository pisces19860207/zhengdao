// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.settings

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API Key 安全存储：AES-256-GCM 密钥由 Android Keystore 生成并保管
 * （硬件背钥，密钥不可导出），密文持久化在普通 SharedPreferences。
 * 提供商 → 环境变量 的映射在 ProotLauncher 注入会话时使用。
 */
object ApiKeyStore {

    private const val PREFS = "zhengdao-apikeys"
    private const val KEY_ALIAS = "zhengdao-apikey-aes"
    private const val GCM_TAG_BITS = 128

    /** 服务商 ID → 注入的环境变量名。 */
    val PROVIDERS: Map<String, String> = mapOf(
        "anthropic" to "ANTHROPIC_API_KEY",
        "deepseek" to "DEEPSEEK_API_KEY",
        "openai" to "OPENAI_API_KEY",
        "zhipu" to "ZHIPU_API_KEY",
    )

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val iv = c.iv
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return android.util.Base64.encodeToString(iv + ct, android.util.Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String? = try {
        val blob = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, blob.copyOfRange(0, 12)))
        val plain = String(c.doFinal(blob.copyOfRange(12, blob.size)), Charsets.UTF_8)
        android.util.Log.i("ApiKeyStore", "decrypt: blob${encoded.length}字符 -> 明文${plain.length}字符")
        plain
    } catch (e: Throwable) {
        android.util.Log.w("ApiKeyStore", "decrypt 失败: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    fun save(ctx: Context, provider: String, value: String) {
        prefs(ctx).edit().putString("key_$provider", encrypt(value)).apply()
    }

    fun get(ctx: Context, provider: String): String? =
        prefs(ctx).getString("key_$provider", null)?.let { decrypt(it) }

    fun clearAll(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }
}
