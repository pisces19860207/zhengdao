// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百八十二批 · 三件挂载：六维 [SixDims]、环境 [EnvProfile]、着装 [AttireProfile]、负荷 [BurdenProfile]。
//
// 判据③：具体工种必须能挂到「环境／着装／负荷」三样上——只列名字不给这三样即提示。
// 三件的字段**全部可空**是故意的：生产表全填，但把任意一格改成 null，检查器必须当场判出来。
//
// 着装这一组与 881 的分工：本包只登记「这一身对应哪种开合形态、解手便利到哪一档」，
// **门襟档本身、内衣层有没有穿、磨损档本身都归 881**；[Closure]／[Reach] 是交接给它的输入，不是它的档。
package com.example.zhengdao.baopu.state.occupation

/** 六维②–⑥（行业轴在 [OccupationRow.industry] 上，合起来正是六维）。 */
data class SixDims(
    val posture: Posture?,
    val dirt: Dirt?,
    val dress: Dress?,
    val sched: Sched?,
    val risk: Risk?,
) {
    /** 六维里除行业外的五格：按位报「缺哪一格」，不只报少了几个。 */
    fun missingNames(): List<String> {
        val slots: List<Pair<String, Any?>> = listOf(
            "体态负荷" to posture, "附着暴露" to dirt, "着装类别" to dress,
            "作息形态" to sched, "危险暴露" to risk,
        )
        return slots.filter { it.second == null }.map { it.first }
    }
}

/** 第一期②环境细节六格。 */
data class EnvProfile(
    val place: Place?,
    val noise: Noise?,
    val dust: Dust?,
    val temp: Temp?,
    val moist: Moist?,
    val chem: Chem?,
) {
    fun missingNames(): List<String> {
        val slots: List<Pair<String, Any?>> = listOf(
            "室内外" to place, "噪声" to noise, "粉尘种别" to dust,
            "温区" to temp, "湿度" to moist, "化学" to chem,
        )
        return slots.filter { it.second == null }.map { it.first }
    }
}

/**
 * 第一期③着装与防护件：派工单点名的七件，加「连体工装」单独一件
 * （工装裤与连体工装是两种开合形态，混成一件就没法跟 881 交代）。
 */
enum class Wear(val label: String) {
    HELMET("安全帽"),
    REFLECTIVE("反光衣"),
    WORK_PANTS("工装裤"),
    COVERALL("连体工装"),
    GLOVES("手套"),
    MASK("口罩"),
    WHITE_COAT("白大褂"),
    CHEF_JACKET("厨师服"),
    ;

    companion object {
        const val COUNT = 8
    }
}

/**
 * 开合形态档（交接给 881，不是 881 的门襟档）：
 * [COVERALL_NO_FLY] 即派工单写死的「连体工装＝无门襟」；[HARNESS_BACK] 是背带式（军警消装备侧）。
 * 四档各有 [reach]，所以解手便利只有两档（便利／更麻烦）——造一个永远取不到的中间档，
 * 等于给后人留一个「反正有这一档」的想当然，宁可不造。
 */
enum class Closure(val label: String, val reach: Reach) {
    FLY_OPEN("常规有门襟", Reach.EASY),
    COVERALL_NO_FLY("连体＝无门襟", Reach.HARD),
    HARNESS_BACK("背带式", Reach.HARD),
    NONE_FIXED("无固定着装", Reach.EASY),
    ;

    companion object {
        const val COUNT = 4
    }
}

/**
 * 解手便利档：纯结构事实（要多解几件、要不要脱肩带），**不含任何评价**。
 * 派工单 ERRATA 的「连体／背带式装备＝解手更麻烦」落在这两个档上。
 */
enum class Reach(val label: String) {
    EASY("便利"),
    HARD("更麻烦"),
    ;

    companion object {
        const val COUNT = 2
    }
}

/** 第一期③着装与防护。 */
data class AttireProfile(
    val items: List<Wear>?,
    val closure: Closure?,
) {
    /** 背带式／连体必须走 STRUCTURE_FLY_881 这条交接（判据③「与 881 联动写清」）。 */
    fun needsFlyHandoff(): Boolean = closure == Closure.COVERALL_NO_FLY || closure == Closure.HARNESS_BACK

    fun hasNoFly(): Boolean = closure == Closure.COVERALL_NO_FLY || closure == Closure.HARNESS_BACK
}

/** 第一期④劳动负荷四格（作息形态在六维里，这里给「怎么个倒法」）。 */
data class BurdenProfile(
    val carry: Carry?,
    val repeats: List<Joint>?,
    val standing: Stand?,
    val shift: Shift?,
) {
    fun missingNames(): List<String> {
        val slots: List<Pair<String, Any?>> = listOf(
            "搬抬档" to carry, "重复部位" to repeats, "连续站立档" to standing, "作息细档" to shift,
        )
        return slots.filter { it.second == null }.map { it.first }
    }
}
