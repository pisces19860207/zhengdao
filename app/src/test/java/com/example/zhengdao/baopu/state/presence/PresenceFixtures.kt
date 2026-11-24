// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
package com.example.zhengdao.baopu.state.presence

/** 夹具统一构造。缺省即「日常身体场景、没写在场者、非零避讳」。 */
internal fun scene(
    venue: Venue = Venue.BATHHOUSE,
    privacy: PrivacyExpect = typicalPrivacy,
    sceneKind: SceneKind = SceneKind.DAILY_BODY,
    presence: PresenceKind? = null,
    minor: MinorPresence = MinorPresence.NONE,
    age: AgeTier? = null,
    blood: BloodTie = BloodTie.YES,
    kin: KinAvoidance = KinAvoidance.NONE,
    oppositeSexKin: Boolean = false,
    elderPresent: Boolean = false,
    care: CareAction = CareAction.NONE,
    sexual: SexualSignal = SexualSignal.NONE,
    distortion: DistortionFlag = DistortionFlag.NONE,
    outsider: Boolean = false,
    requestedNude: CanBeNude? = null,
    narrativeNote: String = "",
): PresenceScene = PresenceScene(
    venue = venue,
    privacy = privacy,
    sceneKind = sceneKind,
    presence = presence,
    minor = minor,
    age = age,
    blood = blood,
    kin = kin,
    oppositeSexKin = oppositeSexKin,
    elderPresent = elderPresent,
    care = care,
    sexual = sexual,
    distortion = distortion,
    outsider = outsider,
    requestedNude = requestedNude,
    narrativeNote = narrativeNote,
)

internal val typicalPrivacy: PrivacyExpect = PrivacyExpect(
    canCloseDoor = true,
    hasPartition = false,
    suddenEntry = false,
)

/** 六类场所的日常身体场景：搓澡、泡池、换衣、走动，全部 issue 为空。 */
internal val routineScenes: List<PresenceScene> = Venue.entries.map { v ->
    scene(
        venue = v,
        presence = PresenceKind.SAME_SEX,
        care = if (v == Venue.BATHHOUSE || v == Venue.LOCKER_ROOM) CareAction.BATH else CareAction.NONE,
    )
}

/** 亲子共浴／同睡／换衣：长辈性别、血缘、避讳档全组合，issue 必须为空。 */
internal val parentChildScenes: List<PresenceScene> = buildList {
    for (venue in listOf(Venue.HOME, Venue.BATHHOUSE, Venue.DORM)) {
        for (care in listOf(CareAction.BATH, CareAction.SLEEP, CareAction.DRESS)) {
            for (opposite in listOf(false, true)) {
                for (blood in BloodTie.entries) {
                    for (kin in KinAvoidance.entries) {
                        for (age in listOf(AgeTier.YOUNG, AgeTier.SCHOOL, AgeTier.PRE_PUBERTY)) {
                            add(
                                scene(
                                    venue = venue,
                                    presence = PresenceKind.CHILD,
                                    minor = MinorPresence.ACCOMPANIED,
                                    age = age,
                                    blood = blood,
                                    kin = kin,
                                    oppositeSexKin = opposite,
                                    elderPresent = true,
                                    care = care,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 三岁前六类照护：不分长辈性别、不分血缘、不分避讳档，issue 必须为空。 */
internal val infantCareScenes: List<PresenceScene> = buildList {
    for (care in CareAction.CARES) {
        for (opposite in listOf(false, true)) {
            for (blood in BloodTie.entries) {
                for (kin in KinAvoidance.entries) {
                    for (venue in listOf(Venue.HOME, Venue.BATHHOUSE, Venue.DORM)) {
                        add(
                            scene(
                                venue = venue,
                                presence = PresenceKind.CHILD,
                                minor = MinorPresence.ACCOMPANIED,
                                age = AgeTier.UNDER_THREE,
                                blood = blood,
                                kin = kin,
                                oppositeSexKin = opposite,
                                elderPresent = true,
                                care = care,
                            ),
                        )
                    }
                }
            }
        }
    }
}

/** 哺乳（≤3 岁）：男婴女婴一律零避讳，连提示都不出。 */
internal val nursingScenes: List<PresenceScene> = listOf(false, true).flatMap { babyOpposite ->
    BloodTie.entries.map { blood ->
        scene(
            venue = Venue.HOME,
            presence = PresenceKind.CHILD,
            minor = MinorPresence.ACCOMPANIED,
            age = AgeTier.UNDER_THREE,
            blood = blood,
            oppositeSexKin = babyOpposite,
            care = CareAction.NURSE,
        )
    }
}

/** 补丁九：未成年主角＋非性化日常，同性在场零额外报警。 */
internal val minorSameSexDailyScenes: List<PresenceScene> = buildList {
    for (venue in Venue.entries) {
        for (age in listOf(AgeTier.YOUNG, AgeTier.SCHOOL, AgeTier.PRE_PUBERTY, AgeTier.OLDER)) {
            for (care in listOf(CareAction.NONE, CareAction.BATH, CareAction.WIPE)) {
                add(
                    scene(
                        venue = venue,
                        presence = PresenceKind.SAME_SEX,
                        minor = MinorPresence.UNDERAGE_LEAD,
                        age = age,
                        care = care,
                    ),
                )
            }
        }
    }
}

/** 未成年主角＋异性日常：只出倾向文字，不出 issue、不判红。 */
internal val minorOppositeSexDailyScenes: List<PresenceScene> = listOf(
    scene(
        venue = Venue.HOME,
        presence = PresenceKind.OPPOSITE_SEX,
        minor = MinorPresence.UNDERAGE_LEAD,
        age = AgeTier.SCHOOL,
        kin = KinAvoidance.YOUNG_OK_LATER,
        oppositeSexKin = true,
        elderPresent = true,
        care = CareAction.BATH,
    ),
    scene(
        venue = Venue.BATHHOUSE,
        presence = PresenceKind.OPPOSITE_SEX,
        minor = MinorPresence.UNDERAGE_LEAD,
        age = AgeTier.PRE_PUBERTY,
        kin = KinAvoidance.ALWAYS,
        blood = BloodTie.NO,
        oppositeSexKin = true,
        care = CareAction.DRESS,
    ),
)
