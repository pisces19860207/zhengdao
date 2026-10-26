// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 判据：派工单-2026-10-24-第一百八十二批 §0.5（不做后台无脑全扫；不赌 FileObserver；
// 手动＋进前台一次增量；周期兜底仅前台）。
package com.example.zhengdao.baopu.knowledge

import com.example.zhengdao.baopu.knowledge.KnowledgeRefreshRules.Trigger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeRefreshRulesTest {

    @Test
    fun `后台不扫_除启动与手动外的触发一律拒`() {
        for (t in listOf(
            Trigger.FOREGROUND, Trigger.IMPORT_DONE, Trigger.MEDIASTORE, Trigger.PERIODIC,
        )) {
            assertFalse("$t 在后台不许跑", KnowledgeRefreshRules.gate(t, false, true, 0, 1).run)
        }
        // 启动是一次性增量（那一刻可能还没有 Activity started），允许；手动是用户在界面上按的。
        assertTrue(KnowledgeRefreshRules.gate(Trigger.START, false, true, 0, 1).run)
        assertTrue(KnowledgeRefreshRules.gate(Trigger.MANUAL, false, true, 0, 1).run)
    }

    @Test
    fun `自动刷新关掉_只剩手动`() {
        assertTrue(KnowledgeRefreshRules.gate(Trigger.MANUAL, true, false, 0, 1).run)
        assertFalse(KnowledgeRefreshRules.gate(Trigger.START, true, false, 0, 1).run)
        assertFalse(KnowledgeRefreshRules.gate(Trigger.FOREGROUND, true, false, 0, 1).run)
        assertFalse(KnowledgeRefreshRules.gate(Trigger.PERIODIC, true, false, 0, 1).run)
    }

    @Test
    fun `周期兜底_前台且到点才跑`() {
        val now = 1_000_000L
        assertTrue(KnowledgeRefreshRules.gate(Trigger.PERIODIC, true, true, 0, now).run)
        assertFalse(
            "没到 10 分钟不跑",
            KnowledgeRefreshRules.gate(Trigger.PERIODIC, true, true, now - 1_000, now).run,
        )
        assertTrue(
            "到 10 分钟就跑",
            KnowledgeRefreshRules.gate(
                Trigger.PERIODIC, true, true,
                now - KnowledgeRefreshRules.PERIOD_MS, now,
            ).run,
        )
    }

    @Test
    fun `前台增量_关掉自动也不放行`() {
        assertTrue(KnowledgeRefreshRules.gate(Trigger.FOREGROUND, true, true, 0, 1).run)
        assertFalse(KnowledgeRefreshRules.gate(Trigger.FOREGROUND, true, false, 0, 1).run)
    }

    @Test
    fun `进度话术_零不显示除零`() {
        assertTrue(KnowledgeRefreshRules.progressLine(0, 0).isEmpty())
        assertTrue(KnowledgeRefreshRules.progressLine(3, 10).contains("3 / 10"))
        assertTrue(KnowledgeRefreshRules.progressLine(10, 10).contains("完成"))
    }
}
