// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单第一百六十五批（人设包 v1，2026-10-23）§1「启用哪一套记在设置（按工作区），
// 不写进包」。设置是 App 的事，所以落 SharedPreferences；包里永远不含「当前启用」。
package com.example.zhengdao.baopu

import android.content.Context
import androidx.core.content.edit
import java.io.File

/**
 * 人设包的启用项（按工作区）。
 *
 * 两个键：`active::<工作区绝对路径>` 与 `previous::<工作区绝对路径>`。
 * 空串是合法值，表示「抱朴默认（不套包）」——目录名不允许为空，所以不会撞名。
 * 工作区换路径＝另一份启用记录，互不影响；包本身仍在工作区文件里。
 */
internal object PersonaPackPrefs {

    private const val FILE = "baopu_persona_packs"
    private const val PREFIX_ACTIVE = "active::"
    private const val PREFIX_PREVIOUS = "previous::"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun keyOf(workspace: File): String = workspace.absolutePath

    /** 当前启用的包目录；null＝抱朴默认。 */
    fun activeDir(ctx: Context, workspace: File): String? =
        prefs(ctx).getString(PREFIX_ACTIVE + keyOf(workspace), null)?.takeIf { it.isNotBlank() }

    /** 上一套是否可回滚（包括回滚到「抱朴默认」）。 */
    fun hasPrevious(ctx: Context, workspace: File): Boolean =
        prefs(ctx).contains(PREFIX_PREVIOUS + keyOf(workspace))

    /** 上一套的包目录；null＝上一套是抱朴默认。调用前先看 [hasPrevious]。 */
    fun previousDir(ctx: Context, workspace: File): String? =
        prefs(ctx).getString(PREFIX_PREVIOUS + keyOf(workspace), null)?.takeIf { it.isNotEmpty() }

    /**
     * 换一套。只有真的换了才更新「上一套」——反复点当前这套不该把回滚目标冲掉。
     * [dirName] null＝切回抱朴默认。
     */
    fun setActive(ctx: Context, workspace: File, dirName: String?) {
        val p = prefs(ctx)
        val k = keyOf(workspace)
        val current = p.getString(PREFIX_ACTIVE + k, null)?.takeIf { it.isNotBlank() }
        val next = dirName?.takeIf { it.isNotBlank() }
        if (current == next) return
        p.edit {
            putString(PREFIX_PREVIOUS + k, current ?: "")
            if (next == null) remove(PREFIX_ACTIVE + k) else putString(PREFIX_ACTIVE + k, next)
        }
    }

    /**
     * 回滚到上一套并把它挪成「上一套」，可以再回滚回来。
     * @return 回滚后的包目录；null＝回滚到抱朴默认；没有上一套时也返回 null（调用方先问 [hasPrevious]）
     */
    fun rollback(ctx: Context, workspace: File): String? {
        val p = prefs(ctx)
        val k = keyOf(workspace)
        if (!p.contains(PREFIX_PREVIOUS + k)) return null
        val previous = p.getString(PREFIX_PREVIOUS + k, "") ?: ""
        val current = p.getString(PREFIX_ACTIVE + k, null)?.takeIf { it.isNotBlank() }
        p.edit {
            if (previous.isEmpty()) remove(PREFIX_ACTIVE + k) else putString(PREFIX_ACTIVE + k, previous)
            putString(PREFIX_PREVIOUS + k, current ?: "")
        }
        return previous.ifEmpty { null }
    }

    /** 包被删/换了工作区时清掉记录（不碰包文件）。 */
    fun clear(ctx: Context, workspace: File) {
        val p = prefs(ctx)
        val k = keyOf(workspace)
        p.edit {
            remove(PREFIX_ACTIVE + k)
            remove(PREFIX_PREVIOUS + k)
        }
    }
}
