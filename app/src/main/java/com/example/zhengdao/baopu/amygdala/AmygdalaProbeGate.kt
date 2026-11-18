// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第五百二十批 · 快探的三问答案（[BioAnalogGate] 本地 B 计划缝的实现件）。
//
// 三问三答，每答一段话＋真实存在的证据（测试名/断言名）。只答不判：
// 答案供评审与既有门禁核查；523 合入后本件随其正式口径回填（报告 §五 登记）。
package com.example.zhengdao.baopu.amygdala

internal object AmygdalaProbeGate : BioAnalogGate {

    override fun triple1MeasurableDifference(): BioAnalogGate.Answer = BioAnalogGate.Answer(
        question = "三问① 可测差异：装/不装快探，同一输入行为差在哪？",
        evidence = "同一事件流，快探的举手率随刺激历史变化而朴素读数恒定：" +
            "AmygdalaProbeTest.habituationLowersMarkRateAndLedgersDesensitize 钉" +
            "「同类无害刺激反复出现 ⇒ 标记率下降且账里有降敏行」，" +
            "AmygdalaProbeTest.freshProbeKeepsMarkingProvesHabituationCausesTheDrop 钉负例" +
            "「机制不在场则标记率不降」，" +
            "AmygdalaProbeTest.accidentSensitizesThenDecaysByClockFixture 钉" +
            "「一次事故后同类刺激标记率上升、N 天后按时间夹具自动衰减」。",
    )

    override fun triple2NoOverreach(): BioAnalogGate.Answer = BioAnalogGate.Answer(
        question = "三问② 不越权：快探的判定权边界在哪，怎么钉？",
        evidence = "只标记不拍板：输出 [ProbeMark] 只有读数/标签/举手三个布尔与数值字段，" +
            "无任何裁量字段；AmygdalaSourceAssertTest.noJudgementNoLedgerNoModelInCode " +
            "剥注释断言本包源码判定类禁词（判定值/授权档/权威账/模型调用）零出现；" +
            "本件未接主链路，引擎行为不因它存在而变。",
    )

    override fun triple3Fallback(): BioAnalogGate.Answer = BioAnalogGate.Answer(
        question = "三问③ 失败兜底：快探挂掉时，主链路变成什么样？",
        evidence = "快探不是主链路的一环：它未接引擎，输出不经慢层时行为与『没有 520』逐字节一致；" +
            "探针账挂掉也不添失败面——AmygdalaProbeTest.throwingAccountChangesNotOneByteOfOutput " +
            "钉「账 append 抛异常时，输出与好账逐字一致、异常不外漏」；" +
            "读数缺失即视为无威胁信号（登记表兜底列同口径）。",
    )
}
