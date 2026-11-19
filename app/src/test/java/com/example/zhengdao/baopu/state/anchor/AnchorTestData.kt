// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
//
// 第七百四十七批 · 测试共享夹具。
// complete() 就是规格 §四 用法 A 那段 yaml 例子的机器化（编号 001／薄肌·露头·早泄／178cm·66kg…），
// 用规格自己的例子当夹具，避免"测试自己造一档合法数据"这种自证。
package com.example.zhengdao.baopu.state.anchor

internal object AnchorTestData {

    /** 七维齐全的一张卡（判据① 的正例）。 */
    fun complete(id: String = "001"): AnchorProfile = AnchorProfile(
        id = id,
        labels = listOf("薄肌", "露头", "早泄"),
        values = mapOf(
            "face.eyes" to listOf("凤眼"),
            "face.lips" to listOf("上薄下厚"),
            "face.voice" to listOf("低"),
            "body.frame" to listOf("窄"),
            "body.muscle" to listOf("薄"),
            "body.fat" to listOf("低"),
            "posture.standing" to listOf("含胸"),
            "posture.habit" to listOf("摸脖子"),
            "posture.scent" to listOf("淡", "皂"),
            "g.meatusForm" to listOf("纵缝"),
            "g.prepuce" to listOf("未割半包"),
            "g.testisVolume" to listOf("中"),
            "g.pubicHairSpread" to listOf("浓密"),
            "g.nippleSize" to listOf("中"),
            "g.lactation" to listOf("未哺乳"),
            "t.gaze" to listOf("躲"),
            "t.sweat" to listOf("易"),
            "t.speechPace" to listOf("停顿多"),
            "a.rigidity" to listOf("3级"),
            "a.refractory" to listOf("短"),
            "a.sensitivity" to listOf("高"),
            "a.control" to listOf("早泄"),
        ),
        numbers = mapOf("body.height" to 178.0, "body.weight" to 66.0, "a.stamina" to 3.0),
        texts = mapOf("g.shaft" to "青筋细浮起，弯曲上翘", "g.overall" to "勃起14/直径3.8"),
        kinks = mapOf("k.play.1" to 3, "k.play.2" to 4, "k.power.1" to 2, "k.sense.1" to 1),
        freeNote = "左肋有块疤，是被打的",
    )

    /** 只填了三维的卡（判据① 缺维反例由它派生）。 */
    fun sparse(): AnchorProfile = AnchorProfile(
        id = "稀疏卡",
        values = mapOf("face.eyes" to listOf("杏眼"), "body.frame" to listOf("中"), "t.gaze" to listOf("散")),
    )

    /** 一条最短的合法跨格证据（670 的边 guard ＋ §五 的触发牌）。 */
    val TRIGGER_WINE = "酒"
}
