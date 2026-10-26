// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 依据的公开接口：Android 官方文档（共享存储访问、应用外部目录语义）。
package com.example.zhengdao.terminal

import android.content.Context
import java.io.File
import com.example.zhengdao.core.ZhengdaoDirs

/**
 * 工作区解析器的**终端侧门面**（第二百四十批：终端解耦第一刀）。
 *
 * 实现已整体下沉到 [com.example.zhengdao.core.ZhengdaoDirs] §B（`hostDir` / `prevHostDir` /
 * `defaultDir` / `privateDir` / `isShared` / `isPrivate` / 三个 setter，逐字搬、路径与 prefs 键
 * 一个字节都没改）。本文件只剩**转发**：终端侧与 UI 的三十余处调用点因此零改动。
 *
 * 为什么保留这层壳而不是直接把调用点改成 `ZhengdaoDirs`：
 * 1. 本刀是纯搬家，调用点改动越少越容易对账；
 * 2. 下一刀（`:terminal-core`）搬走终端代码时，这份门面跟着终端走，而真源留在 `:core`
 *    —— 两边都不需要再改一次。
 *
 * ⚠️ 别在这里加逻辑：任何新判据都该加在 [com.example.zhengdao.core.ZhengdaoDirs]。
 */
object Workspace {

    /**
     * 共享存储可读写判定——委托 [com.example.zhengdao.core.ZhengdaoDirs.storageGranted]，
     * 最终落到 `EnvironmentScope.hasAllFilesAccess`（全仓唯一判据，v1.3 E2 消双判）。
     */
    fun storageGranted(ctx: Context): Boolean =
        com.example.zhengdao.core.ZhengdaoDirs.storageGranted(ctx)

    /** 宿主侧工作区目录（bind / 软链 / 脚本落点的唯一来源）。保证目录存在。 */
    fun hostDir(ctx: Context): File = com.example.zhengdao.core.ZhengdaoDirs.hostDir(ctx)

    /** 当前工作区是否位于共享存储（决定 /sdcard bind、软链与界面文案）。 */
    fun isShared(ctx: Context): Boolean = com.example.zhengdao.core.ZhengdaoDirs.isShared(ctx)

    /** 是否为仅私有模式（App 外部目录，随卸载删除）。 */
    fun isPrivate(ctx: Context): Boolean = com.example.zhengdao.core.ZhengdaoDirs.isPrivate(ctx)

    fun defaultDir(ctx: Context): File = com.example.zhengdao.core.ZhengdaoDirs.defaultDir(ctx)

    fun privateDir(ctx: Context): File = com.example.zhengdao.core.ZhengdaoDirs.privateDir(ctx)

    /** 上一次用过的工作区宿主目录（没有记录、或者记录的正是当前这个 ⇒ null）。 */
    fun prevHostDir(ctx: Context): File? = com.example.zhengdao.core.ZhengdaoDirs.prevHostDir(ctx)

    /** 设置为默认工作区（Download/证道）。 */
    fun setDefault(ctx: Context) = com.example.zhengdao.core.ZhengdaoDirs.setDefault(ctx)

    /** 设置为自定义工作区（任意共享存储路径；非法路径拒绝）。 */
    fun setCustom(ctx: Context, path: String): Boolean =
        com.example.zhengdao.core.ZhengdaoDirs.setCustom(ctx, path)

    /** 设置为仅私有。 */
    fun setPrivate(ctx: Context) = com.example.zhengdao.core.ZhengdaoDirs.setPrivate(ctx)
}
