// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百三十九批（2026-11-09）· 「今天干过什么」的**清空水位**（只清投影，不删账）。
//
// 用户原话：「可清空：**只清投影**（本地水位），**不删账本原文**」。
// 所以「清空」在这里只有一件事：把"这道线以下都当没看见"的时刻写进**本屏自己的**一个小文件，
// 账本（run_log／追认队列／修复记录／进化提案）一个字节都不碰——判据②就是钉这个的。
//
// 为什么水位落在 `filesDir` 而不是账本那棵目录：
//   · 它是**这一屏的显示状态**，不是账的一部分（账是真相，水位是"我看过/我清过"）；
//   · 放进账本目录会诱使后来的人"顺手把它也当账读"，两本混一起；
//   · `filesDir` 跟着 App 私有数据走——卸载即清，正是"投影"该有的生命周期。
//
// 三条纪律：
//   1. **只写这一件**（没有第二本账的写口）；
//   2. **水位只往前**（没有"恢复到没清过"的入口——所以清空前要确认，见屏幕层）；
//   3. **写失败不抛**：回 false，界面下一轮重读仍是旧水位（不会假装清掉了）。
package com.example.zhengdao.ui.baopu

import java.io.File
import org.json.JSONObject

internal object ActivityBoardStore {

    /** 水位文件（与小黑板/账本都不是同一件东西，名字直说用途）。 */
    const val FILE_NAME = "看板水位.json"

    /** 文件格式版本（改形状才 +1）。 */
    const val VERSION = 1

    /** 字段名：清空那一刻（毫秒）。 */
    const val KEY_CLEARED_AT = "clearedAt"

    fun fileIn(root: File): File = File(root, FILE_NAME)

    /** 读水位（文件没有／坏行 ⇒ 0 ＝没清过；**不抛**）。 */
    fun readClearedAt(root: File): Long = runCatching {
        val f = fileIn(root)
        if (f.isFile) JSONObject(f.readText(Charsets.UTF_8)).optLong(KEY_CLEARED_AT, 0L) else 0L
    }.getOrDefault(0L)

    /** 写水位（返回有没有落上；**不抛**）。 */
    fun writeClearedAt(root: File, atMs: Long): Boolean = runCatching {
        val f = fileIn(root)
        f.parentFile?.mkdirs()
        f.writeText(JSONObject().put("v", VERSION).put(KEY_CLEARED_AT, atMs).toString(), Charsets.UTF_8)
        true
    }.getOrDefault(false)

    /** 「清空」＝把水位抬到现在（账本一个字都不动）。 */
    fun clear(root: File, nowMs: Long = System.currentTimeMillis()): Boolean =
        writeClearedAt(root, nowMs)
}
