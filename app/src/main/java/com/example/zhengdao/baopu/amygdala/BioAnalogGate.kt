// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第五百二十批 · BioAnalogGate **本地 B 计划缝**（三问入口）。
//
// 派工单原文：523 在做仿生件三件套门禁（可测差异/不越权/失败兜底）。523 合入前，
// 仿生件连"被问三问"的入口都没有——本文件就是这个入口的最小本地实现：
// 谁都可以拿着接口问一个仿生件三句：装你和不装你差在哪？你越了什么权？你挂了怎么办？
// 只问不判：答案是一段带证据引用的话，裁决（收不收）仍是评审与既有门禁的事。
//
// ⚠️ 523 合入后回填（报告 §五 已登记）：本接口届时替换/对齐为 523 的正式三件套口径，
// AmygdalaProbeGate 的三段答案原文随迁，不改判据、不改证据。
package com.example.zhengdao.baopu.amygdala

/** 三问入口。每问一个 [Answer]：问句原文＋一段引证据的话（测试名/断言名，须真实存在）。 */
internal interface BioAnalogGate {

    /** 一问一答。evidence 里引用的测试名必须真实存在（AmygdalaProbeGateTest 钉死这一点）。 */
    data class Answer(val question: String, val evidence: String)

    /** 三问① 可测差异：同一输入，装/不装本件行为不同——差在哪、由哪个测试钉住。 */
    fun triple1MeasurableDifference(): Answer

    /** 三问② 不越权：本件无判定权的声明与机器断言（不产生判定值、不改档、不写权威账）。 */
    fun triple2NoOverreach(): Answer

    /** 三问③ 失败兜底：本件挂掉/读数缺失时，行为与"没有它"逐字一致——由哪个测试钉住。 */
    fun triple3Fallback(): Answer
}
