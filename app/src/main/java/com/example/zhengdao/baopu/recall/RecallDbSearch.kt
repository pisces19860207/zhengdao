// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 540 模糊唤回 · **直读兜底**（`RecallSearchPort` 的兜底实现，不是主路径）。
//
// ## 今天它是谁
// 主路径是 [RecallIndexSearch]（533/702 的会话索引）。本类只在索引**零命中**时被
// [RecallFallback] 调一次：533 的索引是增量的（水位往后补，≤500 条/轮），升级后的
// 头几次唤回只铺到最旧一批 ⇒ 只查索引会把「没铺到」说成「没找到」。这里用**同一批
// 线索**直读既有 `message`/`part`/`session` 表确认一次，两条路都空才叫没找到。
//
// ## 两条纪律（与 `PhoneIndex` 同源）
// 1. **失败按没结果**：检索坏了只 log 一句不带词的读数，唤回卡收「没找到」——
//    不许把聊天拦下来，更不许拿半截结果冒充找到；
// 2. **异常文本不进日志**：Android 的 `SQLiteException` 会把**绑定参数**抄进 message，
//    而这里绑的正是用户话里切出来的词——把 `$it` 写进日志等于把用户查的词抄进账。
package com.example.zhengdao.baopu.recall

import com.example.zhengdao.baopu.BaopuDb
import com.example.zhengdao.core.RunLog

internal class RecallDbSearch(private val db: BaopuDb) : RecallSearchPort {

    override fun search(keywords: List<String>): List<RecallHit> {
        val stmt = RecallQuery.searchStmt(keywords) ?: return emptyList()
        return runCatching {
            db.read { d ->
                d.rawQuery(stmt.sql, stmt.args).use { c ->
                    buildList {
                        while (c.moveToNext()) {
                            add(
                                RecallQuery.hitOf(
                                    id = c.getLong(0),
                                    sessionId = c.getLong(1),
                                    createdAt = c.getLong(2),
                                    payload = c.getString(3) ?: "",
                                ),
                            )
                        }
                    }
                }
            }
        }.getOrElse {
            // 不带 $it（文件头第 2 条）：只说第几组词、多少个，一个字的原话都不留
            RunLog.log("模糊唤回：检索没跑成（词 ${keywords.size} 个），按没结果处理")
            emptyList()
        }
    }
}
