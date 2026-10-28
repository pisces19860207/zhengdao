// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百四十九批：开工卡上摆的**事实**（截图能对得上派工单 §2 四要素）。
// Compose 那一半靠眼睛看，这一半用断言钉住——卡上少摆一个结束条件就是少一条红线。
package com.example.zhengdao.baopu.authz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskGrantCardFactsTest {

    @Test
    fun 四要素齐全() {
        val facts = TaskGrantFacts.of(AuthzFixtures.grant())

        // 对象：列得出来的清单（不是"整个工作区"这句字）
        assertEquals(listOf("task:t1:a@0", "task:t1:b@0"), facts.objects.map { it.handle })
        assertEquals("卡上给人看的是文件名，不是账本句柄", listOf("a", "b"), facts.objects.map { it.objectId })
        assertTrue("夹具里两个对象都是新建", facts.objects.all { it.isNew })

        // 动作：只有两种文本动作
        assertEquals(
            listOf(GrantActionType.CREATE_TEXT, GrantActionType.EDIT_TEXT),
            facts.actions,
        )

        // 程度：四个数就是派工单那四个
        assertEquals(10, facts.maxObjects)
        assertEquals(30, facts.maxActions)
        assertEquals(2L * 1024 * 1024, facts.maxBytesPerFile)
        assertEquals(5L * 1024 * 1024, facts.maxBytesTotal)

        // 何时结束：四种，且**必须有进程重启**
        assertEquals(
            listOf(
                GrantEndReason.TASK_ENDED,
                GrantEndReason.USER_STOPPED,
                GrantEndReason.EXPIRED,
                GrantEndReason.PROCESS_RESTARTED,
            ),
            facts.ends,
        )
        assertTrue(
            "进程重启必须在卡上明说",
            facts.ends.contains(GrantEndReason.PROCESS_RESTARTED),
        )
    }

    @Test
    fun 卡上也摆出仍然会问的几类() {
        val facts = TaskGrantFacts.of(AuthzFixtures.grant())
        assertEquals(
            listOf(StillAsk.DESTRUCTIVE, StillAsk.PROTECTED, StillAsk.R3),
            facts.stillAsk,
        )
    }

    @Test
    fun 改正文的对象不是新建() {
        val facts = TaskGrantFacts.of(
            AuthzFixtures.grant(
                objects = listOf(AuthzFixtures.obj("a", rev = 4, state = ObjectState.Present("h", 12))),
            ),
        )
        assertEquals("task:t1:a@4", facts.objects.single().handle)
        assertFalse(facts.objects.single().isNew)
    }

    @Test
    fun 卡上的限额跟着快照走且只紧不松() {
        val tight = TaskGrantFacts.of(
            AuthzFixtures.grant(
                budget = GrantBudget(maxObjects = 3, maxActions = 5, maxBytesPerFile = 1024L, maxBytesTotal = 4096L),
            ),
        )
        assertEquals(3, tight.maxObjects)
        assertEquals(5, tight.maxActions)
        assertEquals(1024L, tight.maxBytesPerFile)
        assertEquals(4096L, tight.maxBytesTotal)

        val wide = TaskGrantFacts.of(
            AuthzFixtures.grant(
                budget = GrantBudget(
                    maxObjects = 99,
                    maxActions = 999,
                    maxBytesPerFile = 99L * 1024 * 1024,
                    maxBytesTotal = 999L * 1024 * 1024,
                ),
            ),
        )
        assertEquals("卡上不许摆出比派工单更松的数", 10, wide.maxObjects)
        assertEquals(30, wide.maxActions)
        assertEquals(2L * 1024 * 1024, wide.maxBytesPerFile)
        assertEquals(5L * 1024 * 1024, wide.maxBytesTotal)
    }

    @Test
    fun 卡上的有效期就是快照那一段() {
        val facts = TaskGrantFacts.of(
            AuthzFixtures.grant(issued = AuthzFixtures.NOW, expires = AuthzFixtures.NOW + 17 * AuthzFixtures.MIN),
        )
        assertEquals(17, facts.lifetimeMinutes)
    }
}
