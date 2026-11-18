// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百九十四批 · 海马·情景键（六体第⑥件，登记表第二行）。
//
// 机理：情景编码——"何时何地发生了什么"的**地址**层。只做：把"这事发生在哪个
// 会话第几轮"打成一把键 {sessionId, seq, hint}。不做：不重放、不合并——回放是
// 678 的活，记什么忘什么是既有记忆分层的权；本件无状态、不查库、不比旧键，
// "上一次发生过没有"这种问题在本件里连问的入口都没有。
//
// 三条硬规矩（派工单③逐字落码）：
//   ① 键只有三样：sessionId/seq 原样回显，类型钉死 Long/Int——塞不进正文；
//   ② hint 只由**元数据**拼成：来源标＋文长档＋内容指纹（sha256 前 12 位十六进制，
//      与 SkillAudit.shaOf 同款口径）——线索正文一个字不进 hint（敏感夹具测试钉死）；
//   ③ 只 log：每次发键走 [BioLog] 一行 stdout，不落权威账。
// 指纹只认形不认义：678 拿它对"同一件事是否再发生"，比对的是单向摘要不是内容——
// 正文从指纹里读不回来，敏感夹具测试会证明输出里没有夹具的任何一个片段。
//
// 本件无判定权（523 三件套②）：不产生 RiskDecision/PROCEED/REJECT，不改档，不写权威账。
package com.example.zhengdao.baopu.bio

import java.security.MessageDigest

/** 海马·情景键（纯函数器官，零状态零落盘）。 */
@BioAnalog("海马")
internal object Hippocampus {

    /** 情景键：地址（会话＋轮次）＋一句元数据 hint。不携带任何正文。 */
    data class EpisodeKey(val sessionId: Long, val seq: Int, val hint: String)

    /** 发键主入口。同一 (sessionId, seq, cue) 调两次，[EpisodeKey] 逐字相等（判据④）。 */
    fun key(sessionId: Long, seq: Int, cue: BioEvent): EpisodeKey {
        val band = lengthBand(cue.text)
        val hint = buildString {
            append("会话 $sessionId 第 $seq 轮")
            append("·来源 ${cue.source}")
            append("·文长$band")
            append("·指纹 ${fingerprint(cue.text)}")
        }
        BioLog.line(TAG, "seq=$seq session=$sessionId band=$band")
        return EpisodeKey(sessionId = sessionId, seq = seq, hint = hint)
    }

    /** 文长档：空 / 短 / 中 / 长。只暴露量级，不暴露原文。 */
    private fun lengthBand(text: String): String = when {
        text.isEmpty() -> "空"
        text.length <= SHORT_MAX -> "短"
        text.length <= MEDIUM_MAX -> "中"
        else -> "长"
    }

    /** 内容指纹：sha256 前 12 位小写十六进制；空文记 "-"（没有内容就没有指纹）。 */
    private fun fingerprint(text: String): String {
        if (text.isEmpty()) return NO_CONTENT
        return MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            .take(FP_CHARS)
    }

    private const val TAG = "海马"

    /** 文长档门槛（字符数）：≤40 短、≤200 中、其余长。 */
    private const val SHORT_MAX = 40
    private const val MEDIUM_MAX = 200

    /** 指纹取摘要前 12 位（SkillAudit.shaOf 同款口径，两处独立维护）。 */
    private const val FP_CHARS = 12

    /** 空文的指纹占位：与"内容指纹恰好是短横线"不可能混淆（摘要只会是十六进制）。 */
    private const val NO_CONTENT = "-"
}
