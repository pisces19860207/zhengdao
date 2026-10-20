// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单《第七十七批 · 结构拆分第六阶段：Settings 存储/数据页纯逻辑抽取》§2
// （每个抽出的纯函数 ≥3 例：正常/边界/空值/组合）。
//
// 口径来源 = 抽取前 `ui/SettingsDataActivity.kt` 的内联实现（等值搬移，
// 见交付报告「diff 等值证据」）：五条判据一条不改，负例口径见文件末注释。
package com.example.zhengdao.ui.settings

import com.example.zhengdao.terminal.KnowledgeBase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataRulesTest {

    // ── shouldConfirmWorkspaceChange：换工作区（资料库迁移）前置条件 ────

    @Test
    fun `换工作区_资料库有文件才先问`() {
        assertTrue(DataRules.shouldConfirmWorkspaceChange(kbFiles = 1))
        assertTrue(DataRules.shouldConfirmWorkspaceChange(kbFiles = 2000))
    }

    @Test
    fun `换工作区_空资料库或还没读到状态都不问`() {
        assertFalse(DataRules.shouldConfirmWorkspaceChange(kbFiles = 0))
        // 状态未读到（null）按 0 算——没有就一定不用问
        assertFalse(DataRules.shouldConfirmWorkspaceChange(kbFiles = null))
    }

    // ── isSharedStoragePath：工作区路径合法性 ──────────────────────────

    @Test
    fun `工作区路径_共享存储根下才算合法`() {
        assertTrue(DataRules.isSharedStoragePath("/storage/emulated/0"))
        assertTrue(DataRules.isSharedStoragePath("/storage/emulated/0/Download/男性"))
    }

    @Test
    fun `工作区路径_根前缀之外的都拒绝`() {
        assertFalse(DataRules.isSharedStoragePath("/sdcard/Download"))
        assertFalse(DataRules.isSharedStoragePath("/data/user/0/com.example.zhengdao"))
        assertFalse(DataRules.isSharedStoragePath(""))
    }

    @Test
    fun `工作区路径_锁现状_纯字符串前缀匹配不顺手修严`() {
        // 抽取前就是 `path.startsWith("/storage/emulated/0")`：前缀后面的字符不校验
        // （/storage/emulated/01 也判合法）。等值搬移 ⇒ 原样锁住，不许"顺手修"。
        assertTrue(DataRules.isSharedStoragePath("/storage/emulated/01"))
    }

    // ── shouldShowKbPath：资料库路径行显不显 ───────────────────────────

    @Test
    fun `路径行_四种已挂载相关的状态都展示`() {
        listOf(
            KnowledgeBase.State.NOT_MOUNTED,
            KnowledgeBase.State.MOUNTED,
            KnowledgeBase.State.BUSY,
            KnowledgeBase.State.ORGANIZED,
        ).forEach { st ->
            assertTrue("$st 时路径是用户放资料的线索，必须展示", DataRules.shouldShowKbPath(st))
        }
    }

    @Test
    fun `路径行_开关关掉或状态未读都不展示`() {
        assertFalse(DataRules.shouldShowKbPath(KnowledgeBase.State.DISABLED))
        assertFalse(DataRules.shouldShowKbPath(null))
    }

    // ── shouldOfferOtherKbCopy：别处资料库提示 ─────────────────────────

    @Test
    fun `别处资料库_未挂载且别处真有才提示`() {
        assertTrue(
            DataRules.shouldOfferOtherKbCopy(KnowledgeBase.State.NOT_MOUNTED, otherCount = 3),
        )
    }

    @Test
    fun `别处资料库_已挂载_别处没有_状态未读都不提示`() {
        assertFalse(
            DataRules.shouldOfferOtherKbCopy(KnowledgeBase.State.MOUNTED, otherCount = 3),
        )
        assertFalse(
            DataRules.shouldOfferOtherKbCopy(KnowledgeBase.State.NOT_MOUNTED, otherCount = 0),
        )
        assertFalse(DataRules.shouldOfferOtherKbCopy(null, otherCount = 3))
        assertFalse(
            DataRules.shouldOfferOtherKbCopy(KnowledgeBase.State.DISABLED, otherCount = 3),
        )
    }

    // ── capReached：扫描上限提示 ───────────────────────────────────────

    @Test
    fun `扫描上限_达到才提示_差一个都不提示`() {
        assertTrue(DataRules.capReached(KnowledgeBase.MAX_SCAN))
        assertTrue(DataRules.capReached(KnowledgeBase.MAX_SCAN + 100))
        assertFalse(DataRules.capReached(KnowledgeBase.MAX_SCAN - 1))
        assertFalse(DataRules.capReached(0))
    }
}

// 负例口径（交付报告里逐条实跑）：
// ① shouldConfirmWorkspaceChange 的 `> 0` 改成 `>= 0` ⇒ 「空资料库不问」红；
// ② isSharedStoragePath 的前缀去掉尾部的 0 ⇒ 「根前缀之外的都拒绝」红；
// ③ shouldOfferOtherKbCopy 的状态判成 `!= MOUNTED` ⇒ 「已挂载不提示」红；
// ④ capReached 的 `>=` 改成 `>` ⇒ 「达到才提示」红。
