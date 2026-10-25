// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十五批 · 落点（派工单 §0 与 §2 的两个目录）。
//
// ## 目录口径按 179 收口后的现行法
// 派工单 §0 写的是 `<工作区>/资料库/产物/物流/inbox/`，那是 179 之前的老壳；
// `8d1ff604 fix(目录): 175-S1 收口——179 多写的 资料库/ 一层拔掉` 之后，产物正位是
// `<工作区>/产物/…`，且 `BaopuKernel.Data.LEGACY_LIBRARY_DIR` 对新代码是**禁字**
// （`MemoryLayout` 每次进页面都会把 资料库/ 重新当残留迁一遍，新代码引用它等于永远清不掉那层壳）。
// 所以这里的正位是 `<工作区>/产物/物流/inbox/`，差别已在交付报告里单列一条。
//
// ## 库为什么单独一个文件
// `logistics.db` 与主库 `BaopuDb` 无关（派工单 §2 点名「不动 BaopuDb 版本号」）：
// 它是**可整包丢弃重建**的当日缓存——源文件还在 产物/物流/inbox/，重导一次就回来了。
// 放 App 私有目录（`filesDir/物流/`）而不放工作区：工作区是用户能翻的目录，
// 里面塞一个 .db 只会让她以为那是份要备份的单据。
package com.example.zhengdao.baopu.logistics

import com.example.zhengdao.baopu.kernel.BaopuKernel
import java.io.File

/** 路径只在这里算一次：查询、导入、播报、面板都从这里取，别处不许拼字符串。 */
object LogisticsPaths {

    /** 工作区里的物流目录名（派工单 §0/§4 都用这个词）。 */
    const val DIR_LOGISTICS = "物流"

    /** 用户手动导出的表放这里，App 只读不写。 */
    const val DIR_INBOX = "inbox"

    const val DB_NAME = "logistics.db"

    /** 每日台账文件名后缀：`20261025-发货台账.md`。 */
    const val LEDGER_SUFFIX = "-发货台账.md"

    /** 只认这两种后缀（派工单 §1「CSV 优先」，`.xlsx` 自己读）。 */
    val CSV_SUFFIXES = listOf(".csv")
    val XLSX_SUFFIXES = listOf(".xlsx")

    fun logisticsDirUnder(workspace: File): File =
        File(File(workspace, BaopuKernel.Data.DIR_PRODUCTS), DIR_LOGISTICS)

    fun inboxDirUnder(workspace: File): File = File(logisticsDirUnder(workspace), DIR_INBOX)

    /** 台账落点（派工单 §4：每天一份，含数据时间与来源文件）。 */
    fun ledgerFileUnder(workspace: File, dayKey: String): File =
        File(logisticsDirUnder(workspace), dayKey + LEDGER_SUFFIX)

    /** 库的绝对落点：`<filesDir>/物流/logistics.db`。纯函数 ⇒ 这一行可被单测钉住。 */
    fun dbPathUnder(filesDir: File): File = File(File(filesDir, DIR_LOGISTICS), DB_NAME)

    fun isCsv(name: String): Boolean = CSV_SUFFIXES.any { name.lowercase().endsWith(it) }

    /** `.xlsm`（带宏）不认：派工单只要 `.xlsx`，多认一种就是把没验证过的读法当成验证过的。 */
    fun isXlsx(name: String): Boolean = XLSX_SUFFIXES.any { name.lowercase().endsWith(it) }

    fun isReadable(name: String): Boolean = isCsv(name) || isXlsx(name)

    /**
     * inbox 里能读的表，按文件名排序。
     *
     * 排序是为了**幂等**：同一堆文件导入两次，处理次序必须一样（否则「后导入的覆盖先导入的」
     * 这条兜底会在两次运行里得到不同结果，用户看到「重复导入不产生重复行」却数字变了）。
     * 目录不存在时返回空列表，不创建目录——读侧不替用户建家（建目录是导入面板那一步的事）。
     */
    fun readableInbox(inboxDir: File): List<File> {
        val listed = inboxDir.listFiles() ?: return emptyList()
        return listed.filter { it.isFile && isReadable(it.name) }.sortedBy { it.name }
    }

    /** 给用户看的路径（相对工作区，正斜杠）——报告与面板都念这个，不念绝对路径。 */
    fun displayPath(workspace: File, file: File): String {
        val rel = runCatching { file.absolutePath.removePrefix(workspace.absolutePath).trimStart('/', '\\') }.getOrNull()
        return if (rel.isNullOrEmpty()) file.name else rel.replace('\\', '/')
    }
}
