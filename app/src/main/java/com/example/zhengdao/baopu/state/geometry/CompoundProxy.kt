// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百六十二批 · 凸分解与复合代理：用 2–6 个凸块拼出凹形部位。
// 为什么：一根凸胶囊会把腋下/胯/颈侧/腹股沟的凹口填平，导致「探针明明在凹口里」
// 却被判成相交。本单只做规则化刚体摆放——查表、常量算术、闭式旋转，
// 不做曲面重建、不做最优分解、不做任何目标函数迭代。
// 距离唯一来源＝655 的 Gjk.distance；Gjk/Epa/Shapes 三个算法文件零改。
// 边界：不做网格化、不做接触流形、不改 686/741/742 的既有代理体。
package com.example.zhengdao.baopu.state.geometry

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 部位规格。四档位都取既有枚举的 level（整数档序，不是物理量）：
 * notchTier 0..2（凹深）、bodyFatTier 0..3（815 BodyFatTier.level）、
 * muscleTier 0..2（815 MuscleTier.level）、skeletonTier 0..2（815 SkeletonTier.level）。
 * 本单不生成档、不读 660 数值表；scaleCm 由调用方从既有形体标尺传入。
 */
data class PartSpec(
    val id: String,
    val joint: Vec3,
    val spineDir: Vec3,
    val branchDir: Vec3,
    val scaleCm: Double,
    val notchTier: Int,
    val bodyFatTier: Int,
    val muscleTier: Int,
    val skeletonTier: Int,
) {
    init {
        require(id.isNotBlank()) { "部位缺 id" }
        require(scaleCm > 0.0 && scaleCm.isFinite()) { "形体标尺须为正有限数" }
        require(spineDir.normSq() > 0.0 && branchDir.normSq() > 0.0) { "主干/分支轴不得为零向量" }
        val cosine = abs(spineDir.normalized().dot(branchDir.normalized()))
        require(cosine < 0.99) { "主干与分支轴近共线，凹口无定义" }
    }
}

/** 刚体局部变换：世界点 = offset + rotation·localPoint。无缩放。 */
data class LocalTransform(val offset: Vec3, val rotation: Rot3) {
    fun worldPoint(local: Vec3): Vec3 = offset + rotation.times(local)

    companion object {
        val IDENTITY = LocalTransform(Vec3.ZERO, Rot3.IDENTITY)
    }
}

/** 一个凸块：局部凸形状＋把它摆到世界的刚体变换。 */
data class ProxyChunk(
    val id: String,
    val shape: ConvexShape,
    val transform: LocalTransform,
)

/**
 * 支撑函数适配器：世界方向先转回局部问 base，再把支撑点转回世界。
 * 这是刚体变换下支撑函数的闭式性质，不是新的几何算法。
 */
private class TransformedChunk(
    private val base: ConvexShape,
    private val tf: LocalTransform,
) : ConvexShape {
    override fun support(dir: Vec3): Vec3 =
        tf.worldPoint(base.support(tf.rotation.transposeTimes(dir)))
}

private const val COMPOUND_ASSUMPTION =
    "复合代理＝2–6 个凸块的并集（规则化刚体摆放，无最优分解/无曲面）；" +
        "任一命中即命中，距离取各块 GJK 最近距离最小值；" +
        "GJK 容差与退化口径沿用 655/812/822，探针与标尺由调用方给"

/** 复合距离读数。witness 取最近块在 GJK 中的原像（世界坐标）。 */
data class CompoundDistance(
    val distanceCm: Double,
    val witnessA: Vec3,
    val witnessB: Vec3,
    val intersecting: Boolean,
    val iterations: Int,
    val chunkCount: Int,
    val nearestChunkId: String,
    val assumption: String = COMPOUND_ASSUMPTION,
    val note: String = GeometryNotes.NOT_MEASUREMENT,
)

/** 复合代理：块数闭集 2..6，查询只循环调用 Gjk.distance。 */
class CompoundProxy(val id: String, val chunks: List<ProxyChunk>) {

    init {
        require(id.isNotBlank()) { "复合代理缺 id" }
        require(chunks.size in CompoundDecompose.MIN_CHUNKS..CompoundDecompose.MAX_CHUNKS) {
            "复合代理块数须在 2..6，实际 ${chunks.size}"
        }
        require(chunks.map { it.id }.distinct().size == chunks.size) { "块 id 重复" }
        require(chunks.all { it.transform.rotation.let { r -> abs(r.ax.dot(r.ay)) < 1e-9 && abs(r.ay.dot(r.az)) < 1e-9 } }) {
            "块局部变换必须是正交标架"
        }
    }

    /** 任一凸块与 other 相交/接触即命中；不先取并集凸包（那会重新填平凹口）。 */
    fun anyHit(other: ConvexShape): Boolean =
        chunks.any { Gjk.distance(world(it), other).intersecting }

    /** 逐块 GJK，取最近距离；只读各块结果，不重算、不加权。 */
    fun minDistance(other: ConvexShape): CompoundDistance {
        var best: GjkResult? = null
        var bestId = ""
        for (chunk in chunks) {
            val reading = Gjk.distance(world(chunk), other)
            if (best == null || reading.distanceCm < best.distanceCm) {
                best = reading
                bestId = chunk.id
            }
        }
        val chosen = best ?: error("复合代理块数不可能为空")
        return CompoundDistance(
            chosen.distanceCm, chosen.witnessA, chosen.witnessB, chosen.intersecting,
            chosen.iterations, chunks.size, bestId,
        )
    }

    private fun world(chunk: ProxyChunk): ConvexShape =
        TransformedChunk(chunk.shape, chunk.transform)
}

/**
 * 规则化分解：从 PartSpec 的档位查表生成凸块。
 * 块数＝2（主干/分支）＋凹深档 0..2＋体脂高档 1＋肌量高档 1 ⇒ 恒在 2..6。
 * 局部标架：+Y＝主干轴，+X 落在主干-分支平面内指向分支，+Z＝两轴叉积。
 */
object CompoundDecompose {

    const val MIN_CHUNKS = 2
    const val MAX_CHUNKS = 6

    // 规则系数（相对形体标尺 s；档序→系数表，无数值拟合、无目标函数）。
    private val FRAME_RADIUS = doubleArrayOf(0.085, 0.100, 0.115)
    private val MUSCLE_SPINE_LEN = doubleArrayOf(0.52, 0.60, 0.68)
    private const val BRANCH_LEN = 0.45
    private const val LIP_OFFSET = 0.12
    private const val LIP_LEN = 0.15
    private const val LIP_RADIUS = 0.055
    private const val FAT_OFFSET = 0.06
    private const val FAT_RADIUS = 0.070
    private const val MUSCLE_OFFSET = 0.35
    private const val MUSCLE_SIDE = 0.08
    private const val MUSCLE_RADIUS = 0.080

    init {
        require(FRAME_RADIUS.size == 3 && MUSCLE_SPINE_LEN.size == 3)
        require(MIN_CHUNKS + 2 + 1 + 1 == MAX_CHUNKS) { "块数闭集上界不成立" }
    }

    /** 档位规则化生成：同输入逐位一致；越界档位 coerceIn 到闭集。 */
    fun decompose(spec: PartSpec): CompoundProxy {
        val s = spec.scaleCm
        val u = spec.spineDir.normalized()
        val v = spec.branchDir.normalized()
        val side = u.cross(v).normalized()
        val ax = u.cross(side)
        val frame = Rot3(ax, u, side)
        val cosine = u.dot(v).coerceIn(-1.0, 1.0)
        val sine = sqrt(maxOf(0.0, 1.0 - cosine * cosine))
        // 局部 +X 轴取 u×side，故分支轴 v = cosθ·u − sinθ·局部X（符号由叉积定，不靠目测）。
        val branchLocal = Vec3(-sine, cosine, 0.0)
        val bisLocal = (branchLocal + Vec3.UNIT_Y).normalized()
        val radius = FRAME_RADIUS[spec.skeletonTier.coerceIn(0, 2)] * s
        val spineLen = MUSCLE_SPINE_LEN[spec.muscleTier.coerceIn(0, 2)] * s
        val chunks = mutableListOf<ProxyChunk>()
        chunks += chunk("spine", Capsule(Vec3.ZERO, Vec3.UNIT_Y * spineLen, radius), frame, spec.joint)
        chunks += chunk("branch", Capsule(Vec3.ZERO, branchLocal * (BRANCH_LEN * s), radius), frame, spec.joint)
        val notch = spec.notchTier.coerceIn(0, 2)
        if (notch >= 1) {
            chunks += chunk(
                "lip-spine",
                Capsule(Vec3.ZERO, Vec3.UNIT_Y * (LIP_LEN * s), LIP_RADIUS * s),
                frame, spec.joint + frame.times(Vec3.UNIT_Y * (LIP_OFFSET * s)),
            )
        }
        if (notch >= 2) {
            chunks += chunk(
                "lip-branch",
                Capsule(Vec3.ZERO, branchLocal * (LIP_LEN * s), LIP_RADIUS * s),
                frame, spec.joint + frame.times(branchLocal * (LIP_OFFSET * s)),
            )
        }
        if (spec.bodyFatTier.coerceIn(0, 3) >= 2) {
            chunks += chunk(
                "fat-pad",
                Sphere(Vec3.ZERO, FAT_RADIUS * s),
                frame, spec.joint + frame.times(bisLocal * (FAT_OFFSET * s)),
            )
        }
        if (spec.muscleTier.coerceIn(0, 2) >= 2) {
            chunks += chunk(
                "muscle-belly",
                Capsule(Vec3.ZERO, Vec3.UNIT_Y * (LIP_LEN * s), MUSCLE_RADIUS * s),
                frame, spec.joint + frame.times(Vec3(0.0, MUSCLE_OFFSET * s, MUSCLE_SIDE * s)),
            )
        }
        return CompoundProxy("compound:${spec.id}", chunks)
    }

    private fun chunk(
        id: String,
        shape: ConvexShape,
        rotation: Rot3,
        worldOffset: Vec3,
    ) = ProxyChunk(id, shape, LocalTransform(worldOffset, rotation))
}
