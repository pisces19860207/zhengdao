// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百八十五批（派工单 §2 · 出厂 C 层只读兜底）：**出厂的 C 层人设**。
//
// 三条口径（用户 2026-10-29 定）：
//   1. **单独存放、任何路径不许写**——它活在 `assets/persona/factory_c.md`，
//      assets 是 APK 内的只读区：`RunEngine` 的工具写不到、`PersonaFiles` 也不写它，
//      连"恢复出厂"也只是**把它抄进 active**，不是改它自己。
//   2. **退役但保留**——用户/模型一旦往 active 写过，出厂这份就不再生效，但永远留着。
//   3. **可校验**——[SHA256] 是把这份文件钉死的锚：测试比对字节，
//      多次改动 active 之后再比一次，仍然必须一字不差（判据①）。
//
// 为什么把锚写成常量而不是"算一次存起来"：常量是**契约**（写在源码里、进 code review），
// 算出来的值只能证明"这次读到了什么"，证明不了"它没被改过"。
package com.example.zhengdao.baopu

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * 出厂 C 层人设（只读）。
 *
 * 本对象**只读**：全仓没有任何往 [ASSET_PATH] 写入的路径，写入的是 active（`人设-本项目.md`）。
 */
internal object PersonaFactory {

    /** 出厂人设在 APK 里的位置（assets 内，运行时只读）。 */
    const val ASSET_PATH = "persona/factory_c.md"

    /**
     * 出厂人设的字节指纹（sha256）。
     *
     * **改这个常量必须同时改 `assets/persona/factory_c.md`，并且要有理由**——
     * 判据①就是靠它挡住"顺手把出厂层改成用户的东西"。
     */
    const val SHA256 = "321a8f0478cc665312fc0d31529f0f4eab02c57fec1b39430c4e70213885e53a"

    /** 出厂人设的字节数（与 [SHA256] 一起做双锚，改一个字就双双对不上）。 */
    const val BYTE_COUNT = 504

    /**
     * 读出厂人设（读不到 ⇒ null，**不抛**：assets 丢了不该让 App 起不来）。
     *
     * 注意返回值是**给 active 抄写用的内容**，不是"当前生效的 C 层"——
     * 当前生效哪一层由 [PersonaFiles.read] 按 active → 旧落点 → 出厂 的顺序决定。
     */
    /** 缓存一份（assets 读取虽小，但 `personaFiles()` 会在设置页每次重组时被调用）。 */
    @Volatile
    private var cached: String? = null

    fun read(context: Context): String? {
        cached?.let { return it }
        val text = runCatching {
            context.applicationContext.assets.open(ASSET_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull() ?: return null
        cached = text
        return text
    }

    /** 清缓存（测试专用：改完假 assets 要能让下一次读到新的）。 */
    fun resetCacheForTest() {
        cached = null
    }

    /** 出厂人设的原始字节（校验用；读不到 ⇒ null）。 */
    fun bytesOf(context: Context): ByteArray? = runCatching {
        context.applicationContext.assets.open(ASSET_PATH).use { it.readBytes() }
    }.getOrNull()

    /** 字节的 sha256（小写十六进制）。 */
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** 这段字节是不是**没被改过**的出厂人设（判据①的判定式）。 */
    fun matches(bytes: ByteArray): Boolean =
        bytes.size == BYTE_COUNT && sha256Hex(bytes) == SHA256

    /**
     * 源码树里的出厂人设文件（**测试专用**：单测跑在 JVM，没有 AssetManager）。
     *
     * 与 [ASSET_PATH] 同一份文件，不另存副本——两处内容不同就等于有两个出厂层。
     */
    /**
     * 源码树里的出厂人设文件（**测试专用**：单测跑在 JVM，没有 AssetManager）。
     *
     * 与 [ASSET_PATH] 同一份文件，不另存副本——两处内容不同就等于有两个出厂层。
     * 两个候选路径是因为单测的工作目录随 Gradle 配置不同（模块根 / 仓库根）。
     */
    fun sourceFile(repoRoot: File = File(".")): File {
        val rel = "app/src/main/assets/$ASSET_PATH"
        return listOf(File(repoRoot, rel), File(repoRoot, "src/main/assets/$ASSET_PATH"))
            .firstOrNull { it.isFile }
            ?: File(repoRoot, rel)
    }
}
