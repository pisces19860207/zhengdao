// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：第二十六批 §3.6「配置留痕」——用户改**内核项**（工作区／压缩阈值／通道）时写一条
// `[kernel] …` 到 RunLog，设置页在这几项下面加一行小字「改这里会记日志」。
//
// ⚠️ 本文件**只产出那一行文本**，不写盘、不 import Android（内核包的 K1 判据管着）。
// 真正落盘的是调用方：`rootfs/RunLog.log(...)`。这样内核保持纯函数，能被 JVM 单测直接断言，
// 也不必为了「留痕」把 I/O 引进内核。
package com.example.zhengdao.baopu.kernel

/**
 * 内核项变更的留痕行。
 *
 * 格式钉死成 `[kernel] <项>改为 <新值>（原 <旧值>）`：
 * `[kernel]` 前缀是**可 grep 的锚**（`grep '\[kernel\]'` 就能捞出所有改过底线的动作）；
 * 「原 <旧值>」必须带 —— 只写新值等于把「从哪来」丢了，回滚时没有依据。
 */
object KernelAudit {

    /** 日志锚：所有内核项变更行都以它开头。 */
    const val TAG = "[kernel]"

    /** 工作区变更。 */
    fun workspace(newPath: String, oldPath: String): String =
        line("工作区", newPath, oldPath)

    /** 压缩阈值变更（当前设置页不暴露这个数，留好口子等它上界面）。 */
    fun compressThreshold(newValue: String, oldValue: String): String =
        line("压缩阈值", newValue, oldValue)

    /** 通道（provider）变更。 */
    fun channel(newName: String, oldName: String): String =
        line("通道", newName, oldName)

    /**
     * 通用拼装：`[kernel] <项>改为 <新值>（原 <旧值>）`。
     *
     * 值里的换行会被压成空格 —— 日志是**按行**读的，一个换行就能把一条留痕劈成两条。
     */
    fun line(item: String, newValue: String, oldValue: String): String =
        "$TAG ${item}改为 ${flat(newValue)}（原 ${flat(oldValue)}）"

    private fun flat(v: String): String = v.replace('\n', ' ').replace('\r', ' ').trim()
}
