// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百二十五批 · 进程内一行字槽与「问才回响」信箱。
//
// 点开是唯一的放行口（[SilentMailbox.offer] 全仓只许控制器调一次）：不点 ⇒ 信箱空 ⇒
// 引擎拼提示词那一串纯追加的 extraNotes 与接线前逐字节相同。同一行字只放行一次
// （取走即清 + 开过标记），重复点只重看已经显示的文字，不再往上下文塞东西。
package com.example.zhengdao.baopu.subconscious

/** 一行字的进程内槽（页签读它；一轮一 publish）。 */
internal object SilentLineStore {

    @Volatile
    private var latestLine: SilentLine? = null

    /** 本行是否已放行过（publish 新的一行时清零）。 */
    @Volatile
    private var openedKey: String? = null

    fun publish(line: SilentLine?) = synchronized(this) {
        latestLine = line
        openedKey = null
    }

    fun latest(): SilentLine? = latestLine

    /** 首次点开返回本行并置标记；同一行再点返回 null（不重放）。 */
    fun consumeOpen(): SilentLine? = synchronized(this) {
        val line = latestLine ?: return@synchronized null
        val key = "${line.dayKey}:${line.ledgerId}"
        if (key == openedKey) null else {
            openedKey = key
            line
        }
    }

    /** 测试专用：谁污染谁还原。 */
    fun clearForTest() = synchronized(this) {
        latestLine = null
        openedKey = null
    }
}

/** 点开放行的附注信箱（不点为空；drain 取走即清）。 */
internal object SilentMailbox {

    private var pending: String? = null

    fun offer(text: String) = synchronized(this) { pending = text }

    fun drain(): List<String> = synchronized(this) {
        val text = pending
        pending = null
        if (text.isNullOrBlank()) emptyList() else listOf(text)
    }

    fun clear() = synchronized(this) { pending = null }
}
