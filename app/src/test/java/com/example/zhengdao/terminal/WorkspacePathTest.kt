// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「这档工作区算不算共享存储」判据（[Workspace.isSharedPath]）的边界单测。
 *
 * 背景（B-补-1 / ERRATA E-094）：原判据是 `hostDir().absolutePath.startsWith("/storage/emulated/0")`，
 * 而「仅私有」档的目录 = `getExternalFilesDir(null)/workspace`，**本身就带这个前缀** ⇒
 * 「仅私有」永远被判成共享，`/sdcard` 照旧 bind 进 guest、设置页文案还说成「卸载保留」。
 *
 * 这几条把判据钉死：App 外部目录（含它带 `/storage/emulated/0` 前缀这件事）必须判**非共享**；
 * 默认档与自定义档（真·共享存储）必须判共享。写反了 = 隐私承诺失效；写宽了 = 默认档不再 bind
 * `/sdcard`，用户的资料库/工作区在 guest 里就看不见了。
 */
class WorkspacePathTest {

    // 真机形态：/storage/emulated/0/Android/data/<包名>/files/workspace
    private val appExternal = "/storage/emulated/0/Android/data/com.example.zhengdao/files/workspace"
    private val defaultDir = "/storage/emulated/0/Download/证道"
    private val customDir = "/storage/emulated/0/Download/男性"

    @Test
    fun `仅私有档 —— 宿主目录就是 App 外部目录，判非共享（B-补-1 回归正身）`() {
        // 旧实现因为 appExternal 以 /storage/emulated/0 开头而把它判成共享，这条就是那颗钉子。
        assertFalse(Workspace.isSharedPath(appExternal, appExternal))
    }

    @Test
    fun `默认档 Download 证道 —— 判共享`() {
        assertTrue(Workspace.isSharedPath(defaultDir, appExternal))
    }

    @Test
    fun `自定义档 Download 男性 —— 判共享`() {
        assertTrue(Workspace.isSharedPath(customDir, appExternal))
    }

    @Test
    fun `无存储权限时宿主目录回落到 App 外部目录 —— 判非共享（基础功能不受损）`() {
        assertFalse(Workspace.isSharedPath(appExternal, appExternal))
    }

    @Test
    fun `尾部斜杠差异不当作两种目录 —— 仍判非共享`() {
        assertFalse(Workspace.isSharedPath("$appExternal/", appExternal))
        assertFalse(Workspace.isSharedPath(appExternal, "$appExternal/"))
    }

    @Test
    fun `把 App 外部目录的子目录选成自定义档 —— 判共享（已知边界，非缺陷）`() {
        // setCustom 只要求路径以 /storage/emulated/0 开头，所以理论上能把 App 外部目录的子目录
        // 选成自定义档；那一档仍会 bind /sdcard（用户显式选的路径，按共享对待，见 E-094 备注）。
        assertTrue(Workspace.isSharedPath("$appExternal/sub", appExternal))
    }
}
