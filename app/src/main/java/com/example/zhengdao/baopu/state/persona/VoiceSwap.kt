// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百一十九批 · 核心判据：台词互换测试。
// 同场 ≥2 人，把 A、B 台词互换，须有 ≥2 处明显不合（句长/用词/称呼/口头禅）；
// 读不出差异（不合 <2）⇒ 红——两人其实是一个人的嘴。
// 纯函数、无 IO、无随机、无时钟。
package com.example.zhengdao.baopu.state.persona

/** 一处明显不合：维度 + 台词值 + 对方档案值。 */
data class Mismatch(val dimension: String, val lineValue: String, val profileValue: String) {
    fun line(): String = "$dimension:台词[$lineValue]≠档案[$profileValue]"
}

/** 台词互换测试结论：[ownerName] 的台词穿到 [wornBy] 嘴里。 */
data class SwapResult(
    val ownerName: String,
    val wornBy: String,
    val mismatches: List<Mismatch>,
) {
    val distinctCount: Int get() = mismatches.size

    /** 是否达到"明显不合"下限；阈值取判据常量，避免两处各写一个数字。 */
    val passed: Boolean get() = distinctCount >= VoiceSwap.MIN_MISMATCH

    fun line(): String = "$ownerName→$wornBy 不合=$distinctCount[${mismatches.joinToString("、") { it.dimension }}]"
}

/** 台词互换判据。 */
object VoiceSwap {

    /** 判据阈值：互换后至少要 2 处明显不合。 */
    const val MIN_MISMATCH = 2

    /** 只看四维：句长/用词/称呼/口头禅（钉死，防"少比一维"）。 */
    private val DIMENSIONS = listOf("句长", "用词", "称呼", "口头禅")

    /** 把一句台词的特征放到 [profile] 嘴里，返回所有明显不合处。 */
    private fun mismatchesOf(f: LineFeatures, profile: VoiceDimensions): List<Mismatch> {
        val found = ArrayList<Mismatch>(4)
        if (f.length != profile.sentenceLength) {
            found += Mismatch("句长", f.length.label, profile.sentenceLength.label)
        }
        if (f.diction != profile.diction) {
            found += Mismatch("用词", f.diction.label, profile.diction.label)
        }
        if (f.address != profile.address) {
            found += Mismatch("称呼", f.address, profile.address)
        }
        if (f.catchphrase != null && profile.catchphrase != null && f.catchphrase != profile.catchphrase) {
            found += Mismatch("口头禅", f.catchphrase, profile.catchphrase)
        }
        return found
    }

    /** 把 [line] 换到 [wornBy] 的档案里：谁的原台词、换给谁、几处不合。 */
    fun wear(line: VoiceLine, ownerName: String, wornBy: String, profile: VoiceDimensions): SwapResult =
        SwapResult(ownerName, wornBy, mismatchesOf(line.features, profile))

    /** 两人对换：A 的台词穿 B 的档案、B 的台词穿 A 的档案。 */
    fun swapTest(a: PersonaVoice, b: PersonaVoice): Pair<SwapResult, SwapResult> = Pair(
        wear(a.lines.first(), a.name, b.name, b.dimensions),
        wear(b.lines.first(), b.name, a.name, a.dimensions),
    )

    /** 两人是否可区分：两个方向都过阈值才算"不是一张嘴"。 */
    fun distinguishable(a: PersonaVoice, b: PersonaVoice): Boolean {
        val (ab, ba) = swapTest(a, b)
        return ab.passed && ba.passed
    }

    /** 供自检用的维度清单。 */
    fun dimensions(): List<String> = DIMENSIONS.toList()
}
