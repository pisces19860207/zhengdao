// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百五十九批 · 输出层：让"设定"与"事实"可区分（派工单 §追加②，判据④）。
//
// 二选一里选的是**字段标记**（行首固定戳），不选自由文本措辞，理由两条：
//   1. 可机检——断言只扫行首前缀就能判"这行是不是设定"，文本措辞会被改写走样；
//   2. 不可漂移——前缀由本文件唯一拼出（[canonLine] 是唯一出口），
//      事实层自己的行一个字都不经过这里（事实行零触碰 ⇒ 逐字节一致天然成立）。
// 戳的内容就是三级来源命名里的 canon 三件套：source=canon、canonVersion、asOfCanon，
// 外加 reality=fiction——谁拦的、哪版、什么时点，一行看全。
package com.example.zhengdao.baopu.state.fiction

internal object FictionOutput {

    /** 设定行前缀的固定头（判据④机检就扫它；不许别处另拼一份）。 */
    const val CANON_MARK_HEAD = "〔设定·canon"

    /** 一行的完整前缀：头＋版本＋reality＋时点。 */
    fun canonMark(): String =
        "$CANON_MARK_HEAD v${FictionCanon.CANON_VERSION}｜reality=${FictionCanon.REALITY_FICTION}｜" +
            "asOf=${FictionCanon.AS_OF_CANON}｜source=${FictionCanon.SOURCE_CANON}〕"

    /** 唯一拼行出口：字段=值，行首带设定戳；值先过球状体禁令闸（判据②⑤）。 */
    fun canonLine(field: String, value: String): String {
        BulbusGuard.rejectIfBulbus(field, value)
        return "${canonMark()} $field=$value"
    }

    /** 机检口：这一行是不是设定行（前缀判定，不靠正文措辞）。 */
    fun isCanonLine(line: String): Boolean = line.startsWith(CANON_MARK_HEAD)

    /** 反向机检：设定层不许冒充事实——凡本层产出的行必须带戳，缺戳即红。 */
    fun assertAllMarked(lines: List<String>) {
        val bare = lines.filterNot { isCanonLine(it) }
        require(bare.isEmpty()) { "判据④红：题材模式产出行缺设定戳：$bare" }
    }
}
