// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 2026-10-20 用户：「抱朴要是干活时产生的垃圾他会自己放到回收站吗？」
// 口径（2026-10-20 用户）：「只让他出最后的产物；垃圾就让他自己弄到回收站」——
// 约定 `临时/` 是抱朴的草稿区，收尾时由引擎把里面的文件**移进回收站**（可恢复，不是真删）。
package com.example.zhengdao.baopu

import java.io.File

internal object TempScratch {

    /** 临时目录名（工作区根下，可见、用户能自己清）。 */
    const val DIR = "临时"

    /** `临时/` 下的文件（递归；不存在的目录 ⇒ 空表）。 */
    fun leftover(workspaceRoot: File): List<File> {
        val dir = File(workspaceRoot, DIR)
        if (!dir.isDirectory) return emptyList()
        return dir.walkTopDown()
            .onEnter { it == dir || it.isDirectory }
            .filter { it.isFile }
            .toList()
    }

    /** 顶层名字（按路径排序，最多 [limit] 个，给提示用）。 */
    fun names(workspaceRoot: File, limit: Int = 5): List<String> =
        leftover(workspaceRoot).map { it.relativeTo(workspaceRoot).path }.sorted().take(limit)

    /**
     * 把 `临时/` 里的文件**全部移进回收站**（用户：「垃圾就让他自己弄到回收站」）。
     *
     * - 只搬文件，不搬用户的东西：约定 `临时/` 就是抱朴自己的草稿区；
     * - 进回收站 ⇒ 可反悔（用现有回收站恢复），**不是真删**；
     * - 命名与 delete_path 一致：`回收站/<时间戳>-<原名>`；重名追加 `-2`、`-3`…；
     * - 搬空后顺手删掉空目录；返回搬走的文件数。
     */
    fun reap(workspaceRoot: File, nowMs: Long): Int {
        val files = leftover(workspaceRoot)
        if (files.isEmpty()) return 0
        val trash = File(workspaceRoot, "回收站")
        trash.mkdirs()
        val stamp = com.example.zhengdao.util.TimeFormats.fileStamp(nowMs)
        var moved = 0
        for (f in files) {
            var target = File(trash, "$stamp-${f.name}")
            var n = 2
            while (target.exists()) {
                target = File(trash, "$stamp-$n-${f.name}")
                n++
            }
            if (f.renameTo(target)) moved++
        }
        File(workspaceRoot, DIR).walkBottomUp().forEach { if (it.isDirectory && it.list()?.isEmpty() == true) it.delete() }
        return moved
    }
}
