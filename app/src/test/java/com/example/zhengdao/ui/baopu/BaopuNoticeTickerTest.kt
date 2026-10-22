// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：2026-10-20 真机 UI-D1（单条标签被复制第二份 ⇒ 视口露一截＝重影）；
// 第一百三十八批（2026-10-22）跑马灯改「逐条切换」⇒ 旧的 `tickerLoop` 复制方案整体退役。
//
// 本文件只钉**渲染层结构**（组件怎么用纯函数、浮层怎么摆）；
// 两态判定/节拍/暂停/排序/池交集在 `BaopuTickerRulesTest`。
package com.example.zhengdao.ui.baopu

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BaopuNoticeTickerTest {

    private fun tickerSource(): String {
        val candidates = listOf(
            File("src/main/java/com/example/zhengdao/ui/baopu/BaopuNoticeTicker.kt"),
            File("app/src/main/java/com/example/zhengdao/ui/baopu/BaopuNoticeTicker.kt"),
        )
        return candidates.first { it.exists() }.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    /**
     * R1 旧实现整体退役：不许再「复制两份 ＋ 每帧 scrollBy」。
     *
     * 红＝有人把连续滚动改回来（就是那条「字一直在爬、3 秒读不完」的老路）。
     */
    @Test
    fun `R1_不再复制条目_不再连续滚动`() {
        val src = tickerSource()
        assertFalse("tickerLoop（复制两份首尾相接）已退役", src.contains("tickerLoop("))
        assertFalse("不许 items + items 复制", src.contains("items + items"))
        assertFalse("不许每帧 scrollBy", src.contains("scrollBy("))
    }

    /**
     * R2 组件吃 `state`（两态的唯一开关），并且走纯函数判据、不自己造规则。
     *
     * 红＝有人在组件里写 `when (state) { ... }` 自查一遍态（第二份实现，与纯函数漂移）。
     */
    @Test
    fun `R2_组件吃state且只用纯函数判据`() {
        val src = tickerSource()
        assertTrue("签名必须带 state", src.contains("state: RunState"))
        assertTrue("态判定走 tickerModeOf", src.contains("tickerModeOf(state)"))
        assertTrue("节拍走 tickerShouldCycle", src.contains("tickerShouldCycle("))
        assertTrue("推进走 nextTickerIndex", src.contains("nextTickerIndex("))
        assertTrue("取条走 tickerEntryIndex", src.contains("tickerEntryIndex("))
        assertTrue("锁定文案走 tickerLockedLabel", src.contains("tickerLockedLabel(state)"))
        assertFalse("组件里不许自写 when(state) 状态机", src.contains("when (state)"))
    }

    /**
     * R3 空闲才可点：锁定态点不出详情（那里没有条目，只有当下状态）。
     *
     * 红＝有人把 clickable 的 enabled 写死 true（锁定态点一下弹空浮层）。
     */
    @Test
    fun `R3_只有空闲态可点开详情`() {
        val src = tickerSource()
        assertTrue(
            "可点条件必须绑在「空闲 ∧ 有条目」上",
            src.contains("clickable(enabled = mode == TickerMode.IDLE && entryIndex >= 0)"),
        )
    }
}
