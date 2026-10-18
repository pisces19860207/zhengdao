// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第十七批 §1.2（B 段：计划必须落库，且能与某个会话的某一轮对上）。
// 房型照 PendingStore / CompressionStore：接口留 baopu 包（内核零 Android 可
// JVM 直测），Android 侧实现 [SessionPlanStore]（薄壳，走 BaopuDb 单写者）。
package com.example.zhengdao.baopu

/**
 * 工作计划（计划卡）的存取（第十七批 B 段）。
 *
 * 存储形态：`part` 表新增一种 kind（`kind='plan'`，payload = 完整计划 JSON）——
 * **不改表结构**（kind 是自由文本列；没有迁移、没有版本号变动，派工单 §1.2 首选）。
 * 每条计划快照落成「一条 assistant 消息 ＋ 一个 plan part」：消息序即轮次
 * （轮 = 两条 user 消息之间），回读时按会话取，天然对得上「某一轮」。
 */
internal interface PlanStore {

    /**
     * 落一条计划快照（新建 assistant 消息 ＋ `kind='plan'` part）。
     *
     * 同一轮多次 update_plan ⇒ 多条 plan part **全部保留**，取用方取最后一条
     * （与 tool part「一次调用一条」的追加形态一致；删旧行会丢「计划怎么变的」）。
     */
    fun insert(sessionId: Long, payload: String)

    /** 该会话最后一条计划 payload（没有 ⇒ null）。续跑回种与 J4 回读用。 */
    fun latest(sessionId: Long): String?
}

/**
 * [PlanStore] 的 BaopuDb 实现（第十七批 · 薄壳，照 [SessionCompressionStore] 房型）。
 *
 * 写走 [BaopuDb.write] 的 Future 并 `.get()` 等真落库（与 pending/compression
 * 同纪律：Future 不 get 等于没写）；读走调用方线程（WAL 下不挡写）。
 *
 * 消息 seq 用「MAX(seq)+1」子查询在一条 INSERT 里算（与引擎 nextSeq 同口径）：
 * 拆成先查后插会把「同会话两条消息同 seq」的窗口露出来（单写者线程内虽无并发，
 * 但 SQL 自己算更稳，也不需要把引擎的私有 helper 搬出来）。
 */
internal class SessionPlanStore(private val db: BaopuDb) : PlanStore {

    override fun insert(sessionId: Long, payload: String) {
        runCatching {
            db.write { d ->
                d.beginTransaction()
                try {
                    val now = System.currentTimeMillis()
                    val m = BaopuSql.insertPlanMessage(sessionId, now)
                    d.execSQL(m.sql, m.args)
                    val msgId = d.rawQuery("SELECT last_insert_rowid()", null).use { c ->
                        if (c.moveToFirst()) c.getLong(0) else -1L
                    }
                    val p = BaopuSql.insertPart(msgId, 0, "plan", payload, now)
                    d.execSQL(p.sql, p.args)
                    d.setTransactionSuccessful()
                } finally {
                    d.endTransaction()
                }
            }.get() // 等真落库（Future 不 get 等于没写）
        }.onFailure {
            com.example.zhengdao.rootfs.RunLog.log(
                "抱朴计划：落库失败 ${it.javaClass.simpleName}: ${it.message}",
            )
        }
    }

    override fun latest(sessionId: Long): String? = db.read { d ->
        val stmt = BaopuSql.planPayloadsBySession(sessionId)
        d.rawQuery(stmt.sql, stmt.args).use { c ->
            // 正序查全部 ⇒ 移到最后一条（最后落的那版计划）
            if (!c.moveToLast()) null else c.getString(0)
        }
    }
}
