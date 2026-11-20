// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.ui.adaptive

import android.app.Activity
import android.graphics.Rect
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 真机适配层：把 `androidx.window.layout.FoldingFeature` 映射成纯值 [FoldState]。
 *
 * 这一层是 746 唯一依赖 androidx.window 的文件；判据逻辑全在 [FoldingFeaturePolicy]，
 * 夹具用纯值构造，不需要真机。缺 androidx.window 时全仓另有 `WindowSizeClasses` 兜底档位。
 *
 * @param tracker 传入以便测试替换（真机用 [WindowInfoTracker.getOrCreate]）
 */
object FoldStateAdapter {
    fun foldStates(activity: Activity): Flow<FoldState> {
        val tracker = WindowInfoTracker.getOrCreate(activity)
        return tracker.windowLayoutInfo(activity).map { from(it) }
    }

    /** 从 WindowLayoutInfo 中取第一个 FoldingFeature；没有则平展。 */
    fun from(info: WindowLayoutInfo): FoldState =
        info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()?.let(::from)
            ?: FoldState.FLAT

    fun from(f: FoldingFeature): FoldState {
        val b: Rect = f.bounds
        return FoldState(
            posture = when (f.state) {
                FoldingFeature.State.HALF_OPENED -> FoldPosture.HALF_OPENED
                else -> FoldPosture.FLAT
            },
            hinge = IntRect(b.left, b.top, b.right, b.bottom),
            orientation = when (f.orientation) {
                FoldingFeature.Orientation.HORIZONTAL -> HingeOrientation.HORIZONTAL
                else -> HingeOrientation.VERTICAL
            },
            isSeparating = f.isSeparating,
            isOccluding = f.occlusionType == FoldingFeature.OcclusionType.FULL,
        )
    }
}
