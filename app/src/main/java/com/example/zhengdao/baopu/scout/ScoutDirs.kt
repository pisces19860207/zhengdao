// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百九十批 · 斥候账的落点解析（Context 到路径的唯一转换点）。
//
// 两本账的顺序是有语义的：**首目录＝权威**（App 私有，存储权限被关也写得进），
// 次目录＝镜像（工作区 `记忆/`，卸载重装后还能找回）。理由见 `LedgerFanout` 文件头。
//
// 本对象只**拼路径**，不读不写：读写全在 `LedgerFanout`，解析全在各账本类里。
package com.example.zhengdao.baopu.scout

import android.content.Context
import com.example.zhengdao.baopu.BaopuWorkspace
import com.example.zhengdao.baopu.MemoryLayout
import com.example.zhengdao.baopu.kernel.BaopuKernel
import java.io.File

internal object ScoutDirs {

    /** App 私有（`filesDir/抱朴记忆/斥候/`）——任何权限状态下都可写。 */
    fun privateScout(ctx: Context): File =
        File(File(ctx.filesDir, BaopuKernel.Data.DIR_GLOBAL_MEMORY), "斥候")

    /** 权威在前、工作区镜像在后（`<工作区>/记忆/`）。 */
    fun ledgerDirs(ctx: Context): List<File> =
        listOf(privateScout(ctx), MemoryLayout.memoryDir(BaopuWorkspace.hostDir(ctx)))
}
