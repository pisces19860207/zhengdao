// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百八十六批 · 形轨.器官代理与腔隙碰撞：重力接触（交付⑤，准静态）。
// 口径：沿 −Y 位移，直到落在支撑面（655 Environment 的地面/沙面）或已落定邻体的包围盒顶面。
// 自下而上一趟解算，每颗只解一次：不求根、不迭代姿态，没有时间步、速度、积分器。
package com.example.zhengdao.baopu.state.cavity

import com.example.zhengdao.baopu.state.geometry.Environment
import com.example.zhengdao.baopu.state.geometry.GeometryNotes
import com.example.zhengdao.baopu.state.geometry.Vec3
import kotlin.math.max

/** 承托物类别。 */
enum class SupportKind { GROUND, SAND, PROXY }

/** 一个代理体的落定读数：位移、承托物、重叠量（都非负，整数毫米）。 */
data class RestingContact(
    val proxyId: String,
    val dropMilli: Int,
    /** 承托物：地面记 "ground"、沙面记 "sand"、邻体记其代理体名。 */
    val restsOn: String,
    val kind: SupportKind,
    /** 重力沿 −Y ⇒ 支撑反力恒沿 +Y（本单不做摩擦，故只有这一档）。 */
    val normal: NormalBucket,
    /** 起点已低于承托高度时的重叠量；按包围盒顶面口径，落定后恒零。 */
    val overlapMilli: Int,
    val assumption: String,
    val note: String,
)

/** 静止落定（纯函数：不改写入参，输出按代理体名升序）。 */
object GravityContact {

    private val ASSUMPTION =
        "准静态落定：自下而上一趟，沿 −Y 位移到支撑面（655 Environment 的地面/沙面）" +
            "或已落定邻体的包围盒顶面。顶面在凸体表面之外或之上，故落点只可能悬空、不可能向下穿透；" +
            "曲面贴合留给 655 的 GJK/EPA 判据，本单不做连续碰撞检测，无时间积分。"

    private const val GROUND_ID = "ground"
    private const val SAND_ID = "sand"

    private data class Surface(val yCm: Double, val kind: SupportKind, val restsOn: String)

    private data class Stop(val yCm: Double, val kind: SupportKind, val restsOn: String)

    private data class Rest(val proxy: OrganProxy, val contact: RestingContact)

    fun settle(proxies: List<OrganProxy>, env: Environment): List<RestingContact> = solve(proxies, env).map { it.contact }

    fun restProxies(proxies: List<OrganProxy>, env: Environment): List<OrganProxy> = solve(proxies, env).map { it.proxy }

    private fun solve(ps: List<OrganProxy>, env: Environment): List<Rest> {
        val s = surfaceOf(env)
        val seed = Stop(s.yCm, s.kind, s.restsOn)
        val done = ArrayList<Rest>()
        for (p in ps.sortedWith(compareBy({ it.lowestY() }, { it.id }))) done += dropOne(p, done.map { it.proxy }, seed)
        return done.sortedBy { it.proxy.id }
    }

    /** 沙面高于地面时以沙面为承托面（Environment 已保证沙面不低于地面）。 */
    private fun surfaceOf(env: Environment): Surface =
        if (env.sandYCm > env.groundYCm) Surface(env.sandYCm, SupportKind.SAND, SAND_ID)
        else Surface(env.groundYCm, SupportKind.GROUND, GROUND_ID)

    private fun dropOne(p: OrganProxy, done: List<OrganProxy>, seed: Stop): Rest {
        val stop = highestStop(p, done, seed)
        val lowest = p.lowestY()
        val dropCm = max(0.0, lowest - stop.yCm)
        val settled = atLowest(p, lowest - dropCm)
        return Rest(
            settled,
            RestingContact(
                proxyId = p.id,
                dropMilli = signedMilli(dropCm),
                restsOn = stop.restsOn,
                kind = stop.kind,
                normal = NormalBucket.PLUS_Y,
                overlapMilli = signedMilli(max(0.0, stop.yCm - settled.lowestY())),
                assumption = ASSUMPTION,
                note = GeometryNotes.NOT_MEASUREMENT,
            ),
        )
    }

    /** 取候选中最高的停住高度＝先碰到的那个；水平投影不重叠的邻体必拦不住，直接跳过。 */
    private fun highestStop(p: OrganProxy, done: List<OrganProxy>, seed: Stop): Stop {
        var best = seed
        for (sp in done) {
            if (!p.aabb().overlapsXz(sp.aabb())) continue
            val y = sp.highestY()
            if (y > best.yCm) best = Stop(y, SupportKind.PROXY, sp.id)
        }
        return best
    }

    private fun atLowest(p: OrganProxy, yCm: Double): OrganProxy = p.shiftedBy(Vec3(0.0, yCm - p.lowestY(), 0.0))
}
