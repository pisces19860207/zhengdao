// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百五十九批 · 双性题材 · 双套型拓扑四约束（派工单 §二·拆法二）。
// 四条约束各落一条断言（判据⑤）：
//   a) 出口各自独立 —— 两套器官不得共用同一开口；
//   b) 血供/神经不得重复占用同一段 —— 成对声明，两段系统抢同一段即红；
//   c) 上下高度**给档不给厘米** —— 类型层只许枚举档位，数值字段不存在；
//   d) 与肛门阴道型**互斥** —— 两模块同时加载即拒（显式断言，非注释）。
//
// 边界：本文件是"题材设定拓扑"，不新建解剖结构、不改 660 字段语义；
// 与事实层零引用（真实 DSD 有医学对应，那是 665 的事，本包不碰）。
package com.example.zhengdao.baopu.state.fiction

/** 双性题材的两个互斥模块（素材 06 拆出的两型；07 单只讲锁结，不在此列）。 */
internal enum class IntersexModule(val cn: String) {
    /** 双套型：上下两套完整外生殖器，出口各自独立。 */
    DUAL_SUITE("双套型"),

    /** 肛门阴道型：与双套型互斥（本包只登记互斥关系，**不实现其内容**——759 不做其细则）。 */
    ANAL_VAGINAL("肛门阴道型"),
}

/** 模块加载结果：只两态，互斥命中即拒。 */
internal sealed class ModuleLoad {
    object Loaded : ModuleLoad()
    data class Rejected(val reason: String) : ModuleLoad()
}

/**
 * 约束 d：互斥断言。同时请求两型 ⇒ 拒，理由要说清是哪条线（§八立场 d：不空拒）。
 */
internal object ModuleExclusion {
    fun load(requested: Set<IntersexModule>): ModuleLoad {
        val conflict = IntersexModule.DUAL_SUITE in requested && IntersexModule.ANAL_VAGINAL in requested
        if (conflict) {
            return ModuleLoad.Rejected(
                "判据⑤红：双套型与肛门阴道型互斥（素材 06 明说「不可同时加载」），两模块同时请求即拒",
            )
        }
        return ModuleLoad.Loaded
    }
}

/** 约束 a 的声明单位：一个出口 = 一个独立开口标识（opening 相同即视为共用开口）。 */
internal data class Outlet(val id: String, val opening: String)

/**
 * 约束 b 的声明单位：某套系统对某段血管/神经通路的占用。
 * [system] ∈ {male, female}；[channel] ∈ {vessel, nerve}；[segment] 为段名。
 */
internal data class SupplyClaim(val system: String, val channel: String, val segment: String) {
    init {
        require(system in SYSTEMS) { "占用声明的 system 只许 $SYSTEMS，得 $system" }
        require(channel in CHANNELS) { "占用声明的 channel 只许 $CHANNELS，得 $channel" }
        require(segment.isNotBlank()) { "占用声明的 segment 不得为空" }
    }

    companion object {
        val SYSTEMS = setOf("male", "female")
        val CHANNELS = setOf("vessel", "nerve")
    }
}

/** 约束 c：上下高度只给档（三档），**没有厘米字段、没有数值字段**——类型即断言。 */
internal enum class HeightBand(val cn: String) {
    UPPER("上段"),
    MIDDLE("中段"),
    LOWER("下段"),
}

/**
 * 双套型拓扑记录（全只读）。构造即校验四约束里 a/b/c 三条：
 * 出口开口互不相同（a）、同段同通道不得被两套系统重复占用（b）、
 * 两套的高度档必须不同且都是档位（c）；d 由 [ModuleExclusion] 在加载处断言。
 */
internal data class DualSuiteTopology(
    val outlets: List<Outlet>,
    val claims: List<SupplyClaim>,
    val maleBand: HeightBand,
    val femaleBand: HeightBand,
    val source: String,
    val canonVersion: Int,
    val asOfCanon: String,
) {
    init {
        // 约束 a：出口各自独立——任何两个出口不得共用同一开口。
        val openings = outlets.map { it.opening }
        require(openings.toSet().size == openings.size) {
            "判据⑤红（约束a）：双套型出口必须各自独立，出现共用开口：$openings"
        }
        require(outlets.size >= 2) { "双套型至少两套各自的出口，得 ${outlets.size}" }

        // 约束 b：血供/神经不得重复占用同一段（成对声明，负例即红）。
        val dup = claims.groupBy { it.channel to it.segment }
            .filterValues { it.map { c -> c.system }.toSet().size > 1 }
        require(dup.isEmpty()) {
            "判据⑤红（约束b）：同一段被两套系统重复占用：${dup.keys.joinToString()}"
        }

        // 约束 c：上下高度给档——两档必须不同档（上下分布），且类型层就只有枚举。
        require(maleBand != femaleBand) {
            "判据⑤红（约束c）：双套型上下必须分档，两套同为 $maleBand"
        }

        // canon 戳：与锁结设定谱同一版本口径（判据③的字段级体现）。
        require(source == FictionCanon.SOURCE_CANON) { "拓扑记录 source 恒为 canon，得 $source" }
        require(canonVersion == FictionCanon.CANON_VERSION) { "拓扑记录 canonVersion 不符" }
        require(asOfCanon == FictionCanon.AS_OF_CANON) { "拓扑记录 asOfCanon 不符" }
    }
}
