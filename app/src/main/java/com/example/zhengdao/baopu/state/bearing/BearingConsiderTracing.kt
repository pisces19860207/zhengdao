// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百四十四批 · ConsiderLog 覆盖扩展（批次 1）：790 方位采样的留痕配置。
// 只配"记哪条/怎么降采样/原因码映射"，不碰 BearingSampler 的任何判定与选优；
// 算法文件本身只有被 [844] 标记的纯插入行（见 ConsiderWiringTest 的基线哈希断言）。
package com.example.zhengdao.baopu.state.bearing

import com.example.zhengdao.baopu.state.consider.ConsiderLog
import com.example.zhengdao.baopu.state.consider.ConsiderProbe
import com.example.zhengdao.baopu.state.consider.ConsiderStrategy
import com.example.zhengdao.baopu.state.consider.ConsiderVerdict

/**
 * 790 `Search.consider` 的留痕口（产生器自己降采样，记录层不懂业务语义）。
 *
 * 产生器口径：原始 8192 点 = [BearingGeometry.SAMPLES]；策略 stride（取样间隔 = 8192/64 = 128），
 * 单次评估硬顶 64 条 ≪ 2000 红线；每条带 sampledFrom=8192 与 strategy=stride。
 * 诊断圈（err ≤ band + margin）的候选仅在 diagnostic=true 时另记入 diag 池（strategy=diagnosticOnly），
 * 与主池两个独立实例，绝不混写；开关关 ⇒ diag 池为 null ⇒ 不产生 diag 文件。
 */
internal object BearingConsiderTracing {

    /** 产生器 id（稳定字面量，跨版本不变）。 */
    const val GENERATOR_ID = "bearing-consider"

    /** 常规探针：只写主池，不写 diag。 */
    val mainProbe = ConsiderProbe(
        generatorId = GENERATOR_ID,
        sampledFrom = BearingGeometry.SAMPLES,
        strategy = ConsiderStrategy.STRIDE,
        main = ConsiderLog.shared,
    )

    /** 诊断探针：主池照写（stride），诊断圈内候选另写独立 diag 池。 */
    val diagProbe = ConsiderProbe(
        generatorId = GENERATOR_ID,
        sampledFrom = BearingGeometry.SAMPLES,
        strategy = ConsiderStrategy.STRIDE,
        main = ConsiderLog.shared,
        diagnostic = true,
    )

    /** consider.diagnostic 开关：关 ⇒ 用常规探针（无 diag 文件）；开 ⇒ 用诊断探针。 */
    var diagnostic: Boolean = false

    /** 当前探针（每次评估构造 Search 时取值）。 */
    val probe: ConsiderProbe get() = if (diagnostic) diagProbe else mainProbe

    /**
     * 把产生器的误差读数映射成闭集原因码（只读，不参与任何判定）。
     * 记录层只拿到码，不懂 err/band 的业务语义。
     */
    fun verdictOf(err: Double, bandCm: Double): ConsiderVerdict = when {
        err > bandCm + BearingGeometry.DIAG_MARGIN_CM -> ConsiderVerdict.BELOW_BAR
        err > bandCm -> ConsiderVerdict.BLOCKED
        else -> ConsiderVerdict.ACCEPTED
    }

    /** 是否在诊断圈内（与 790 diagnose 的门槛同式，只做布尔判定不跑 GJK/EPA）。 */
    fun inDiagCircle(err: Double, bandCm: Double): Boolean =
        err <= bandCm + BearingGeometry.DIAG_MARGIN_CM

    /** 测试/会话隔离：复位两个探针计数、主池与 diag 池，并关掉诊断开关。 */
    fun reset() {
        diagnostic = false
        ConsiderLog.shared.reset()
        mainProbe.resetCounters()
        diagProbe.resetCounters()
        diagProbe.diag?.reset()
    }
}
