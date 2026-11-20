// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：765 派工单②③（G-716-4 崩溃级的坏名容错）——第 494 批 MemoryScanRules 管
// 「文件**内容**读不动」（内容层四类），本件是它的姊妹件，管「目录**列举层**的名字
// 本身有问题」。716 真机轮实测：目录里出现非法 UTF-8 截半字文件名（adb push 多文件
// 直推造成）时，java.io 的 list0 在 JNI 严格校验（CheckJNI）处直接 SIGABRT 整进程
// ——那一层 Kotlin 接不住，容错必须从「换列举原语＋名字先判后用」来做（ScanTolerance）。
package com.example.zhengdao.core.scan

import java.util.Locale

/**
 * 坏名判定（**纯函数**，无 IO）：一个**已经解码出来的**条目名，要不要跳过、按哪个原因码记。
 *
 * 原因码闭集（765 派工单③，wire 名逐字，不许开第六态）：
 *   `decode-fail` / `too-long` / `reserved` / `attr-fail` / `denied`
 *
 * 判定顺序固定（同 MemoryScanRules 的「判定顺序固定」口径，先中先记，一条只给一个原因）：
 * 1. `decode-fail`——名字里有 U+FFFD（替换符）。NIO 目录流对解不了的字节给 U+FFFD，
 *    名字本身已不可信（连字节数都是假的），必须最先判；
 * 2. `too-long`——UTF-8 超 [MAX_NAME_BYTES] 字节（255，主流文件系统的名字上限）；
 * 3. `reserved`——Windows 保留设备名（CON/PRN/AUX/NUL/COM1-9/LPT1-9，大小写不敏感，
 *    取首个点之前的主名判）**或**名字以空格/点结尾（Windows 会静默改名）——这两类都是
 *    「跨平台会出事的名字」，归同一个码；
 * 4. 其余 ⇒ null（正常名，照常列出）。
 *
 * 注意 `attr-fail` / `denied` 不在本函数的输出里：它们不是从名字判出来的，
 * 是列举/属性读的**异常**给的（见 [ScanTolerance]，注入 IO ⇒ JVM 稳定直测，
 * 同 494「Windows chmod 不作数」的口径）。
 */
internal object ScanNameRules {

    /** 名字字节上限（UTF-8；255＝ext4/FUSE/NTFS 主流上限，765 派工单②）。 */
    const val MAX_NAME_BYTES: Int = 255

    /** 原因码：名字解不出来（含 U+FFFD）。 */
    const val REASON_DECODE_FAIL: String = "decode-fail"

    /** 原因码：名字超长。 */
    const val REASON_TOO_LONG: String = "too-long"

    /** 原因码：Windows 保留设备名，或以空格/点结尾（跨平台会被改名的名字）。 */
    const val REASON_RESERVED: String = "reserved"

    /** 原因码：单条目属性读失败（IO/竞态——文件在列出与查看之间消失等）。 */
    const val REASON_ATTR_FAIL: String = "attr-fail"

    /** 原因码：权限不足（EACCES 一族）。 */
    const val REASON_DENIED: String = "denied"

    /** Windows 保留设备名（不含扩展名；大小写不敏感）。 */
    private val RESERVED_BASES: Set<String> = buildSet {
        addAll(listOf("CON", "PRN", "AUX", "NUL"))
        addAll((1..9).map { "COM$it" })
        addAll((1..9).map { "LPT$it" })
    }

    /**
     * 一个条目名的坏名判定；null ＝ 正常名（照常列出）。
     * 判定顺序固定（KDoc 1–3），一条只给一个原因。
     */
    fun badNameReasonOf(name: String): String? = when {
        name.contains('\uFFFD') -> REASON_DECODE_FAIL
        name.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES -> REASON_TOO_LONG
        name.substringBefore('.').uppercase(Locale.ROOT) in RESERVED_BASES -> REASON_RESERVED
        name.endsWith(" ") || name.endsWith(".") -> REASON_RESERVED
        else -> null
    }

    /**
     * 名字的安全转义（765 ③：「不含原始字节」）——账行/日志里出现的一律是这个形状：
     * 控制符、反斜杠、U+FFFD 各自转成 `\uXXXX`/`\\` 形式，其余（含中文）原样。
     * 目的：坏名在账里**可读、可复制、不会再把读账的人/工具弄炸**。
     */
    fun escapeName(name: String): String = buildString {
        for (ch in name) {
            when {
                ch == '\n' -> append("\\n")
                ch == '\r' -> append("\\r")
                ch == '\t' -> append("\\t")
                ch == '\\' -> append("\\\\")
                ch == '\uFFFD' -> append("\\uFFFD")
                ch < ' ' || ch == '\u007F' -> append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                else -> append(ch)
            }
        }
    }
}
