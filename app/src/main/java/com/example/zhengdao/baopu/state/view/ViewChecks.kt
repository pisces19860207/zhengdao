// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百六十批 · 视点判定的唯一入口：四件输出在这里装配齐（判据①③④⑤）。
//
//     ① 四件齐     —— 每个取景目标恰好产出 inFrustum／distanceTier／occludedBy／screenRelative；
//     ② 挂节点     —— 视点锚点、取景目标、遮挡者全都要落在场景图上，且视点格位与锚点格位装订一致；
//     ③ 屏幕可复算 —— 屏幕九宫格只由 [Projection] 算出，本文件不自己拼格子；
//     ⑤ 禁反推     —— 本文件任何函数都不收 ScreenCell，屏幕格只出不进。
//
// 边界（写在这里，防止后续顺手扩）：**遮挡判定归 861**。本文件只接收 861 给的「谁挡了谁」
// 结论（[OcclusionClaim]），做的是**视锥与距离侧的对账**——遮挡者自己都在画面外，就挡不住，
// 这条也不许在这里算几何求交：不做射线、不做包围盒、不做三角形。
package com.example.zhengdao.baopu.state.view

import kotlin.math.abs

/** 结论三态。[severity] 越大越严；取最严时用 severity，不依赖枚举声明次序。 */
enum class ViewState(val label: String, val severity: Int) {
    PASS("通过", 0),
    HINT("提示", 1),
    RED("红", 2),
}

/** 一条结论：状态 ＋ 稳定代号 ＋ 人话细节。 */
data class ViewFinding(val state: ViewState, val code: String, val detail: String) {
    fun line(): String = "${state.label}|$code|$detail"
}

/**
 * 861 送来的遮挡结论：在 [target] 这个目标上，[blocker] 挡住了没有。
 *
 * 本包**只收不算**：`blocks` 是别人判的，收进来之后只做两件对账——遮挡者在不在画面里、
 * 在什么距离档。要改遮挡结论请去 861，别在这里加几何。
 */
data class OcclusionClaim(val target: NodeRef, val blocker: NodeRef, val blocks: Boolean)

/** 一个真的挡得住的遮挡者：谁、多远的档、贴不贴边。 */
data class OccluderNote(val blocker: NodeRef, val distance: DistanceTier, val edge: Visibility) {
    fun line(): String = "${blocker.line()}|${distance.label}|${edge.label}"
}

/**
 * 屏幕归属：算出来的九宫格格位 ＋ 在不在画面里。
 *
 * 两件分开给：视野外也照样有格位（贴边），但 [onScreen] 老实说「不在画面里」。
 * 只给格位不给这一位，就等于默许「画面外也当成画面内」。
 */
data class ScreenPlacement(val cell: ScreenCell, val onScreen: Boolean) {
    fun line(): String = cell.label + if (onScreen) "（画面内）" else "（画面外）"
}

/**
 * 四件输出（判据①）：一个取景目标的全部视野结论。
 *
 * 只有这四件，不多不少——多一件就有人拿它当第五个真相源。结构性锁见 ViewChecksTest
 * （反射数成员：1 个目标 ＋ 4 件输出）。
 */
data class ViewProjection(
    val target: NodeRef,
    val inFrustum: Visibility,
    val distanceTier: DistanceTier,
    val occludedBy: List<OccluderNote>,
    val screenRelative: ScreenPlacement,
) {
    fun line(): String = buildString {
        append(target.line())
        append("|").append(inFrustum.label)
        append("|").append(distanceTier.label)
        append("|挡=").append(if (occludedBy.isEmpty()) "无" else occludedBy.joinToString("、") { it.blocker.nodeId })
        append("|屏幕=").append(screenRelative.line())
    }
}

/** 一镜的视野报告：逐目标的四件输出 ＋ 全部结论。 */
data class ViewReport(val projections: List<ViewProjection>, val findings: List<ViewFinding>) {

    /** 最严的一态：红 > 提示 > 通过（按 severity 取，不看条数，也不看声明次序）。 */
    val overall: ViewState
        get() = findings.maxOfOrNull { it.state.severity }
            ?.let { top -> ViewState.entries.first { it.severity == top } }
            ?: ViewState.PASS

    fun line(): String = buildString {
        projections.forEach { append(it.line()).append('\n') }
        findings.forEach { append(it.line()).append('\n') }
    }
}

/** 四件输出的装配与判据①②③⑤。纯判定：无 IO、无装配、无浮点。 */
object ViewChecks {

    /**
     * 跑一镜。
     *
     * [targets] 是本镜要判的取景目标；[claims] 是 861 的遮挡结论（可以只给一部分，没给就当没挡）；
     * [subject] 是这一镜的**取景主体**（用来跟视点声明的取景距离对账）；不给就不对账——
     * 一镜里近处有桌、远处有门是常态，拿第一个目标去对账会凭空造出红。
     *
     * 装订不上（锚点或任一目标不在场景图上、或视点格位与锚点格位不一致）⇒ **fail closed**：
     * 四件输出一件都不给，只给红。理由很直白——位置都没落地的投影，算出来也是编的。
     */
    fun report(
        viewpoint: Viewpoint,
        scene: SceneNodes,
        targets: List<NodeRef>,
        claims: List<OcclusionClaim> = emptyList(),
        subject: NodeRef? = null,
    ): ViewReport {
        val findings = ArrayList<ViewFinding>()
        val anchorCell = bindAnchor(viewpoint, scene, findings)
        var targetOk = true
        targets.forEach { target -> targetOk = bindTarget(target, scene, findings) && targetOk }
        if (anchorCell == null || !targetOk) {
            findings += ViewFinding(ViewState.RED, "零投影", ZERO_PROJECTION)
            return ViewReport(emptyList(), findings)
        }
        val oneByOne = targets.map { projectOne(viewpoint, anchorCell, scene, it, claims) }
        findings += oneByOne.flatMap { it.second }
        findings += claimAudit(scene, claims)
        findings += fourPieces(oneByOne.map { it.first })
        focusFinding(viewpoint, subject, oneByOne.map { it.first })?.let { findings += it }
        return ViewReport(oneByOne.map { it.first }, findings)
    }

    /** 装订视点：锚点必须落在场景图上，且视点写的格位就是锚点那件的格位。返回锚点格位。 */
    private fun bindAnchor(viewpoint: Viewpoint, scene: SceneNodes, out: MutableList<ViewFinding>): Cell? {
        if (!scene.holds(viewpoint.anchor)) {
            out += ViewFinding(
                ViewState.RED, "漂浮视点",
                "视点锚在 ${viewpoint.anchor.line()}，场景图上没有这层这个 id——视点漂浮（判据②）",
            )
            return null
        }
        val cell = scene.cellOf(viewpoint.anchor)
        if (cell == null) {
            out += ViewFinding(ViewState.RED, "漂浮视点", "锚点 ${viewpoint.anchor.line()} 查不到格位")
            return null
        }
        if (cell != viewpoint.at) {
            out += ViewFinding(
                ViewState.RED, "装订不上",
                "视点写在 ${cellLabel(viewpoint.at)}，锚点 ${viewpoint.anchor.nodeId} 却在 ${cellLabel(cell)}" +
                    "——挂在这件上、站在那件的位置，照样是漂浮坐标",
            )
            return null
        }
        return cell
    }

    /** 取景目标必须落地，且不许是场景根（整场不是一件可以取景的物）。 */
    private fun bindTarget(target: NodeRef, scene: SceneNodes, out: MutableList<ViewFinding>): Boolean {
        if (!scene.holds(target)) {
            out += ViewFinding(ViewState.RED, "漂浮目标", "取景目标 ${target.line()} 不在场景图上")
            return false
        }
        if (target.layer == SceneLayer.SCENE) {
            out += ViewFinding(ViewState.RED, "取景对象太粗", "${target.nodeId} 是场景根，整场不是一件可以取景的物")
            return false
        }
        return true
    }

    /** 单个目标：投影一次，四件输出取齐（屏幕格只从 [Projection] 出）。 */
    private fun projectOne(
        viewpoint: Viewpoint,
        anchorCell: Cell,
        scene: SceneNodes,
        target: NodeRef,
        claims: List<OcclusionClaim>,
    ): Pair<ViewProjection, List<ViewFinding>> {
        val targetCell = scene.cellOf(target) ?: anchorCell
        val projected = Projection.projected(viewpoint, anchorCell, targetCell)
        val findings = ArrayList<ViewFinding>()
        val notes = occluders(viewpoint, anchorCell, scene, target, claims, findings)
        val projection = ViewProjection(
            target = target,
            inFrustum = projected.visibility,
            distanceTier = projected.distance,
            occludedBy = notes,
            screenRelative = ScreenPlacement(projected.cell, projected.visibility != Visibility.OUTSIDE),
        )
        return projection to findings
    }

    /**
     * 收 861 的遮挡结论：只留「挡了 ＋ 遮挡者自己在画面里」的，按距离由近到远排。
     *
     * 遮挡者在视野外 ⇒ 提提示不计入：镜头没拍到它，它挡不住任何东西——这是本包对 861
     * 结论的唯一约束，不涉及几何。自己挡自己 ⇒ 结论本身不成立，提提示不算。
     */
    private fun occluders(
        viewpoint: Viewpoint,
        anchorCell: Cell,
        scene: SceneNodes,
        target: NodeRef,
        claims: List<OcclusionClaim>,
        out: MutableList<ViewFinding>,
    ): List<OccluderNote> {
        val notes = ArrayList<OccluderNote>()
        claims.asSequence()
            .filter { it.target == target && it.blocks }
            .distinctBy { it.blocker.nodeId }
            .forEach { claim ->
                if (claim.blocker == target) {
                    out += ViewFinding(ViewState.HINT, "自挡", "${target.nodeId} 被记成挡自己，结论不成立，已略过")
                    return@forEach
                }
                val cell = scene.cellOf(claim.blocker)
                if (cell == null) {
                    out += ViewFinding(
                        ViewState.RED, "漂浮遮挡者",
                        "遮挡者 ${claim.blocker.line()} 不在场景图上，挡了谁说不清",
                    )
                    return@forEach
                }
                val p = Projection.projected(viewpoint, anchorCell, cell)
                if (p.visibility == Visibility.OUTSIDE) {
                    out += ViewFinding(
                        ViewState.HINT, "视野外遮挡",
                        "${claim.blocker.nodeId} 记成挡住 ${target.nodeId}，但它自己就在视野外（判据④ 以外），不计入",
                    )
                    return@forEach
                }
                notes += OccluderNote(claim.blocker, p.distance, p.visibility)
            }
        return notes.sortedWith(compareBy({ it.distance.rank }, { it.blocker.nodeId }))
    }

    /** 遮挡结论自身的账：目标与遮挡者都得落地（与视点无关的那部分）。 */
    private fun claimAudit(scene: SceneNodes, claims: List<OcclusionClaim>): List<ViewFinding> {
        val lost = claims.filter { !scene.holds(it.target) || !scene.holds(it.blocker) }
        if (lost.isEmpty()) return emptyList()
        return listOf(
            ViewFinding(
                ViewState.RED, "遮挡结论漂浮",
                lost.size.toString() + " 条遮挡结论里有节点不在场景图上：" +
                    lost.take(3).joinToString("、") { it.target.nodeId + "←" + it.blocker.nodeId },
            ),
        )
    }

    /** 判据①的结构性结论：四件齐。 */
    private fun fourPieces(projections: List<ViewProjection>): List<ViewFinding> {
        if (projections.isEmpty()) return emptyList()
        val offScreen = projections.count { !it.screenRelative.onScreen }
        return listOf(
            ViewFinding(
                ViewState.PASS, "四件齐",
                "${projections.size} 个目标各出四件（视野/距离/遮挡/屏幕）；其中 $offScreen 个在画面外",
            ),
        )
    }

    /**
     * 取景距离对账：视点声明「这一镜拍多远的主体」与算出来的距离档比。
     * 一致＝通过；差一档＝提示（近中的边界本来就模糊）；差两档＝红（声明与算出来的事实相反）。
     */
    private fun focusFinding(viewpoint: Viewpoint, subject: NodeRef?, projections: List<ViewProjection>): ViewFinding? {
        if (subject == null) return null
        val actual = projections.firstOrNull { it.target == subject }?.distanceTier ?: return null
        val gap = abs(actual.rank - viewpoint.focusDistance.rank)
        val detail = "取景声明${viewpoint.focusDistance.label}，${subject.nodeId} 算出来是${actual.label}"
        return when {
            gap == 0 -> ViewFinding(ViewState.PASS, "取景一致", detail)
            gap == 1 -> ViewFinding(ViewState.HINT, "取景差一档", "$detail —— 档界本来就模糊，确认一下取景意图")
            else -> ViewFinding(ViewState.RED, "取景矛盾", "$detail —— 声明与算出来的事实相反")
        }
    }

    private fun cellLabel(cell: Cell): String = "格${cell.label}"

    private const val ZERO_PROJECTION: String =
        "位置没落到场景图上，投影一件都不给（fail closed）：漂浮坐标算出来的四件输出没有意义"
}
