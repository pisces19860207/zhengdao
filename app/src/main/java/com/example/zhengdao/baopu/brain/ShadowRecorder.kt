// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百八十八批 · 段 B ⑤：影子账本（追加式 JSONL）。
// 行格式＝定稿 §七逐字：{round, zone, proxyTier, snnTier, agree, spikeRate, seed}
//   {"round":1234,"zone":"glans","proxyTier":2,"snnTier":2,"agree":true,"spikeRate":0.4100,"seed":24301}
//
// 为什么手拼而不用 JSONObject：账要被"逐字节复现"审（判据③）。
// JSONObject 的键序在 Android 实现里是插入序、在别处不保证——一旦换实现，同一轮账
// 就不是同一串字节了。这里键序、小数位、布尔字面量全部写死，换环境也一样。
//
// 边界：只**追加**，不读、不改、不删；一轮一物类一行，写失败只抛出给接线层兜底
// （接线层按"没有它"处理，绝不影响主链）。
package com.example.zhengdao.baopu.brain

import java.io.File
import java.util.Locale

/** 影子账本：一行一条对照记录。 */
internal class ShadowRecorder(private val file: File) {

    /** 账本文件路径（测试与接线层读它）。 */
    fun file(): File = file

    /**
     * 追加一行，返回写进去的那一串（含换行）——给测试逐字节比对用。
     * 档越界当场抛异常（判据④：只许既有档 0–3）。
     */
    fun record(
        round: Long,
        zone: String,
        proxyTier: Int,
        snnTier: Int,
        spikeRate: Double,
        seed: Long = LifParams.SEED,
    ): String {
        val text = line(round, zone, proxyTier, snnTier, spikeRate, seed) + "\n"
        file.parentFile?.mkdirs()
        file.appendText(text, Charsets.UTF_8)
        return text
    }

    companion object {

        /** 账本文件名（放在抱朴记忆根下；与"进化影子.jsonl"同级、不同文件）。 */
        const val FILE_NAME = "脑影.jsonl"

        /** 建档口：调用方给抱朴记忆根，本文件不自己找根（零 android 依赖）。 */
        fun under(globalRoot: File): ShadowRecorder = ShadowRecorder(File(globalRoot, FILE_NAME))

        /**
         * 一行账的纯文本（不落盘、无 IO）。键序与位宽即定稿 §七。
         * [agree] 由两档自行推出——不许调用方塞一个"看着像"的值进来。
         */
        fun line(
            round: Long,
            zone: String,
            proxyTier: Int,
            snnTier: Int,
            spikeRate: Double,
            seed: Long = LifParams.SEED,
        ): String {
            require(zone.isNotBlank()) { "物类 id 不能为空" }
            require(proxyTier in 0..MapToTier.TIER_MAX) { "代理档只许 0–3，收到 $proxyTier" }
            require(snnTier in 0..MapToTier.TIER_MAX) { "影子档只许 0–3，收到 $snnTier" }
            require(spikeRate.isFinite()) { "放电率必须是有限值，收到 $spikeRate" }
            return buildString {
                append("{\"round\":").append(round)
                append(",\"zone\":\"").append(escape(zone)).append('"')
                append(",\"proxyTier\":").append(proxyTier)
                append(",\"snnTier\":").append(snnTier)
                append(",\"agree\":").append(proxyTier == snnTier)
                append(",\"spikeRate\":")
                    .append(String.format(Locale.ROOT, "%.4f", spikeRate))
                append(",\"seed\":").append(seed)
                append('}')
            }
        }

        /** 最小转义：只处理 JSON 字符串里必须转义的两种字符，别的一律原样。 */
        private fun escape(text: String): String =
            text.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}
