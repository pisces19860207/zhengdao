// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 540 模糊唤回 · **时刻补全壳**（`RecallQuery.timestampsStmt` 的唯一执行点）。
//
// 只给索引适配层用：533 的命中不带时刻，540 的组内排序要 `atMs`。查询是主键点查
// （`id IN (?,…)`），参数只有消息 id——不含任何用户词。失败按「一条也没补到」返回空表：
// 调用方（`RecallIndexSearch.hydrate`）据此丢命中，方向是「少给一条」不是「乱给一条」。
package com.example.zhengdao.baopu.recall

import com.example.zhengdao.baopu.BaopuDb

internal object RecallTimes {

    /** 消息 id → 原文时刻；查不到 id 不在结果里（=> 调用方丢这条）。 */
    fun of(db: BaopuDb, msgIds: List<Long>): Map<Long, Long> {
        val stmt = RecallQuery.timestampsStmt(msgIds) ?: return emptyMap()
        return runCatching {
            db.read { d ->
                d.rawQuery(stmt.sql, stmt.args).use { c ->
                    buildMap {
                        while (c.moveToNext()) put(c.getLong(0), c.getLong(1))
                    }
                }
            }
        }.getOrElse { emptyMap() }
    }
}
