// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第七百四十一批 · 形轨.空间容纳与受限姿态：一档站位的「四项合法」台账。
// 四项＝限位／可达／自碰撞／穿地（与 686 的四项同义），每项三态：过／不过／未证明。
// **本文件只装结论**：不装量值、不装坐标、不装自由度——判据④要求产物里一个数都没有。
package com.example.zhengdao.baopu.state.containment

/** 四项合法。次序即台账次序（枚举序数用于确定性排序）。 */
enum class AuditItem(val label: String) {
    LIMIT("限位"),
    REACH("可达"),
    SELF_COLLISION("自碰撞"),
    GROUND("穿地"),
}

/** 单项三态：过／不过／未证明。未证明≠不过，也≠过。 */
enum class AuditState(val label: String) {
    PASS("过"),
    FAIL("不过"),
    UNPROVEN("未证明"),
}

/** 一项的读数：项＋三态＋一句人话（只含字词，不含数值）。 */
data class AuditReading(
    val item: AuditItem,
    val state: AuditState,
    val detail: String,
)

/**
 * 一档站位的四项台账。
 *
 * 构造即断言「四项齐备且各一条」：少一项就不许参加判定——否则缺的那项会被上层当成「过」，
 * 这正是最危险的假绿。四档站位各持一份台账，判定只认台账，不自己去猜。
 */
data class StanceAudit(
    val stance: Stance,
    val readings: List<AuditReading>,
) {
    init {
        require(readings.size == AuditItem.entries.size) {
            "四项必须齐备：既有 ${readings.map { it.item.label }}"
        }
        require(readings.map { it.item }.toSet() == AuditItem.entries.toSet()) {
            "四项必须各一条：重复或缺失都不许"
        }
        require(readings.all { it.detail.isNotBlank() }) { "每项都要给一句话，空着等于没判" }
    }

    fun readingOf(item: AuditItem): AuditReading = readings.first { it.item == item }

    fun stateOf(item: AuditItem): AuditState = readingOf(item).state

    /** 不过的项（按枚举次序，确定性）。 */
    val failures: List<AuditItem> get() = AuditItem.entries.filter { stateOf(it) == AuditState.FAIL }

    /** 未证明的项（按枚举次序）。 */
    val unproven: List<AuditItem> get() = AuditItem.entries.filter { stateOf(it) == AuditState.UNPROVEN }

    /** 四项都真有证据说过：这才是「过了 655 四项」。 */
    val allPass: Boolean get() = failures.isEmpty() && unproven.isEmpty()
}
