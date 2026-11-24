// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百九十六批 · 输入场景与输出三件。why 必须同时给出在场者、私密期待与场景三项依据；
// 输出与结论都只有这一处出口，禁止上层自行拼装。
package com.example.zhengdao.baopu.state.presence

/** 一张待判的单。所有维度显式传入；没写在场者用 null 表达，不默认成独处。 */
data class PresenceScene(
    val venue: Venue,
    val privacy: PrivacyExpect,
    val sceneKind: SceneKind = SceneKind.DAILY_BODY,
    val presence: PresenceKind? = null,
    val minor: MinorPresence = MinorPresence.NONE,
    val age: AgeTier? = null,
    val blood: BloodTie = BloodTie.YES,
    val kin: KinAvoidance = KinAvoidance.NONE,
    val oppositeSexKin: Boolean = false,
    val elderPresent: Boolean = false,
    val care: CareAction = CareAction.NONE,
    val sexual: SexualSignal = SexualSignal.NONE,
    val distortion: DistortionFlag = DistortionFlag.NONE,
    val outsider: Boolean = false,
    val requestedNude: CanBeNude? = null,
    val narrativeNote: String = "",
)

/** 输出三件。outsiderConfirmed 只是结构不变量，不是第四件业务输出。 */
data class PresenceOutput(
    val canBeNude: CanBeNude,
    val canDescribe: CanDescribe,
    val why: String,
    val outsiderConfirmed: Boolean,
) {
    init {
        require(why.contains(WHERE) && why.contains(EXPECT) && why.contains(SCENE)) {
            "依据必须同时给出在场者、私密期待与场景三项"
        }
        PresenceGuard.requireNeutral(why)
        PresenceGuard.requireUnderwearEnough(canBeNude, outsiderConfirmed)
    }

    /** 唯一出口：一行给出三件。 */
    fun line(): String =
        "应对=" + canBeNude.label + "｜可写=" + canDescribe.label + "｜依据=" + why

    private companion object {
        const val WHERE = "在场者="
        const val EXPECT = "｜期待="
        const val SCENE = "｜场景="
    }
}
