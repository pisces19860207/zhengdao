// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百五十三批 · **任务态 vs 闲聊态**的边界探针（判据出处：238 派工单 §3.9.0，
// 2026-10-27 用户定案「闲扯淡这类不算」）。
//
// 法典原文（逐字判据，不许改口径）：
//   任务态 = 本轮至少命中一条：①有工具调用或执行意图（"帮我改/查/装"）
//            ②有**事实主张**（数字、结论、文件内容、谁对谁错）③在收尾一个已开工的活
//   闲聊态 = 纯情绪、玩笑、吐槽、爱称、感叹，**无事实主张也无执行意图**
//   混合   = 闲聊里夹一句事实主张 ⇒ 只对**那一句**进事实态
//
// 为什么这条边界必须先钉（派工单 253 L3 的硬要求）：三条禁令只管任务态；
// **态判错，禁令全废**——把闲聊误判成任务态，禁令就会"满屏误报"（派工单负例⑥）。
//
// ── 第五百批（2026-11-06）：本文件不再是副本，而是一层**转发** ──────────────────
// 253 起这里是词表与判定的**第二份拷贝**（文件头旧版写着「词表逐字照抄 253 探针，改一个词
// 就是改判据」）。493 只补词表补不动英文（子串／大小写／习语／请求形态四处缺口），于是同一
// 判据有了两套结构：生产走 :policy，探针停在 253 的老结构上——两套都会绿，但绿的不是同一件事。
// 本批按派工单「253 探针副本与生产词表同源」把拷贝删干净：词表与 classify 全部转发给
// `com.example.zhengdao.baopu.TaskModeBoundary`（:policy 生产判据），**一处改两处生效**。
// L1／L3 的调用面（TaskMode／classify／classifySentences／isMixed／emotionLexicon）一字没改，
// 所以那两个门禁测试文件本次没被碰。
package com.example.zhengdao.baopu.constitution

/** 判态结论＝生产口径（:policy），本文件不再自带第二份枚举。 */
internal typealias TaskMode = com.example.zhengdao.baopu.TaskMode

/** 253 探针：判据的**转发口**（口径见文件头，实现在 :policy 的 MediatorJudge.kt）。 */
internal object TaskModeBoundary {

    fun classify(text: String): TaskMode =
        com.example.zhengdao.baopu.TaskModeBoundary.classify(text)

    fun splitSentences(text: String): List<String> =
        com.example.zhengdao.baopu.TaskModeBoundary.splitSentences(text)

    fun classifySentences(text: String): List<Pair<String, TaskMode>> =
        com.example.zhengdao.baopu.TaskModeBoundary.classifySentences(text)

    fun isMixed(text: String): Boolean =
        com.example.zhengdao.baopu.TaskModeBoundary.isMixed(text)

    /** 闲聊词表（只用于测试钉边界；判闲聊靠的是"没命中任务态"）。 */
    fun emotionLexicon(): List<String> =
        com.example.zhengdao.baopu.TaskModeBoundary.emotionLexicon()
}
