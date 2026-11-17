// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 540 模糊唤回 · **只读适配层**（[RecallSearchPort] 的今天的生产实现）。
//
// ## 它是谁、将来是谁
// 533（会话搜索·bigram 倒排）未开工，本类按同批惯例先顶着这条缝：**只读**既有
// `message`/`part`/`session` 表，零 DDL。533 合入后由它实现 [RecallSearchPort]
// 换底——本类整个退场或留作兜底，归下一单裁；[RecallEngine] 与卡面零改动。
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
