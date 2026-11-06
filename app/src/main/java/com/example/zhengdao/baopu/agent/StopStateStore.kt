// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百零七批 · 停机记录的**落盘壳**（471 的前置）：人手叫停后，重启 App/重启手机
// 仍是停的——`stopRequested` 原来是纯内存字段，进程一死就忘，这是本单要堵的洞。
//
// 落点：App 私有 `filesDir/baopu/run-stop-state.json`（机器状态，不进工作区）。
// **为什么必须是这个位置**：模型的文件工具被 PathGuard 圈在 workspace roots 里，
// App 私有目录够不着 ⇒ 模型没有任何"改掉停机记录"的通道（507 判据：模型调解除 ⇒ 拒，
// 拒得连改文件这条路都不存在）。文件不在 ＝ 没有叫停记录；读不动按没有算（不猜）。
package com.example.zhengdao.baopu.agent

import java.io.File
import org.json.JSONObject

/**
 * 一条叫停记录：叫停时正在跑的会话 id（没有会话在跑 ＝ null）＋ 时刻。
 *
 * 记会话 id 是为了分清「停的是谁」：被停的那个会话自己续跑要被闸住（507 主判据），
 * 人切到别的会话续跑则按 176 §2 的既有口径放行——跨会话的人工切换本身就是解除。
 */
internal data class StoppedRun(val sessionId: Long?, val atMs: Long)

/**
 * 停机记录的读写壳（单文件、一覆盖一读；**只认引擎一个写家**——模型与 UI 都没有口）。
 *
 * 写是同步的小文件（一行 JSON，几十字节）：叫停/解除是人的动作频次，撑不起异步账；
 * 写失败返回 false 并由调用方落日志——记账失败不许把停止/续跑主流程带崩（65/89 口径）。
 * 纯 JVM 可测（org.json 在单测里有真实现，见 app/build.gradle.kts）。
 */
internal class StopStateStore(private val file: File) {

    /** 叫停记录（覆盖写：同一时刻至多一条叫停态）。 */
    fun mark(sessionId: Long?, atMs: Long): Boolean = runCatching {
        file.parentFile?.mkdirs()
        file.writeText(
            JSONObject()
                .put("stopped", true)
                .put("sessionId", sessionId ?: -1L)
                .put("atMs", atMs)
                .toString(),
            Charsets.UTF_8,
        )
        true
    }.getOrDefault(false)

    /** 读回叫停记录；文件不在／读不动／形状不对 ＝ 没有叫停（不猜）。 */
    fun load(): StoppedRun? = runCatching {
        if (!file.isFile) return null
        val o = JSONObject(file.readText(Charsets.UTF_8))
        if (!o.optBoolean("stopped", false)) return null
        val sid = o.optLong("sessionId", -1L)
        StoppedRun(sessionId = if (sid > 0L) sid else null, atMs = o.optLong("atMs", 0L))
    }.getOrNull()

    /** 解除（人发新消息/切会话续跑时由引擎调）：删文件 ＝ 没有叫停记录。 */
    fun clear(): Boolean = runCatching {
        if (!file.isFile) return true
        file.delete()
    }.getOrDefault(false)
}
