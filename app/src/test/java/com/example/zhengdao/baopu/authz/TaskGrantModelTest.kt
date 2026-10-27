// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百四十九批：授权快照的**形状**（§2 四要素的机器形态）。
// 这里只验"快照自己合不合法"，不验"这次动作放不放行"（那是 Gate 的事）。
package com.example.zhengdao.baopu.authz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskGrantModelTest {

    private val twoMb = 2L * 1024 * 1024

    @Test
    fun 合法快照一条毛病都没有() {
        assertEquals(emptyList<GrantIssue>(), TaskGrantRules.validate(AuthzFixtures.grant()))
    }

    @Test
    fun 对象是整个工作区不合法() {
        listOf("*", "工作区", "整个工作区", "/", "..", "所有文件", "a/*").forEach { bad ->
            val g = AuthzFixtures.grant(objects = listOf(AuthzFixtures.obj(bad)))
            assertTrue("「$bad」应被判成整个工作区", TaskGrantRules.validate(g).contains(GrantIssue.WHOLE_WORKSPACE))
        }
    }

    @Test
    fun 共享目录的文件不进v1支持域() {
        val g = AuthzFixtures.grant(
            objects = listOf(AuthzFixtures.obj("a", location = ObjectLocation.SHARED_FILE)),
        )
        assertTrue(TaskGrantRules.validate(g).contains(GrantIssue.SHARED_FILE))
    }

    @Test
    fun 对象超过十个不合法() {
        val g = AuthzFixtures.grant(objects = (1..11).map { AuthzFixtures.obj("o$it") })
        assertTrue(TaskGrantRules.validate(g).contains(GrantIssue.TOO_MANY_OBJECTS))
    }

    @Test
    fun 单文件超过二兆不合法() {
        val g = AuthzFixtures.grant(
            objects = listOf(AuthzFixtures.obj("a", state = ObjectState.Present("h", twoMb + 1))),
        )
        assertTrue(TaskGrantRules.validate(g).contains(GrantIssue.FILE_TOO_BIG))
    }

    @Test
    fun 总写入超过五兆不合法() {
        val g = AuthzFixtures.grant(
            objects = (1..3).map { AuthzFixtures.obj("o$it", state = ObjectState.Present("h", twoMb)) },
        )
        if (TaskGrantRules.validate(g).contains(GrantIssue.TOO_MANY_OBJECTS)) {
            throw AssertionError("三个对象不该撞对象数上限")
        }
        assertTrue(TaskGrantRules.validate(g).contains(GrantIssue.TOTAL_TOO_BIG))
    }

    @Test
    fun 有效期超过六十分钟不合法() {
        val g = AuthzFixtures.grant(expires = AuthzFixtures.NOW + 61 * AuthzFixtures.MIN)
        assertTrue(TaskGrantRules.validate(g).contains(GrantIssue.BAD_LIFETIME))
    }

    @Test
    fun 有效期非正不合法() {
        val g = AuthzFixtures.grant(expires = AuthzFixtures.NOW)
        assertTrue(TaskGrantRules.validate(g).contains(GrantIssue.BAD_LIFETIME))
    }

    @Test
    fun 风险档超过R2不合法() {
        val g = AuthzFixtures.grant(maxRiskOrdinal = 3)
        assertTrue(TaskGrantRules.validate(g).contains(GrantIssue.RISK_TOO_HIGH))
        assertFalse(TaskGrantRules.isValid(g))
    }

    @Test
    fun 混进不可授权的动作不合法() {
        val g = AuthzFixtures.grant(
            actions = listOf(GrantActionType.CREATE_TEXT, GrantActionType.DELETE),
        )
        assertTrue(TaskGrantRules.validate(g).contains(GrantIssue.ACTION_NOT_GRANTABLE))
    }

    @Test
    fun 额度只许收紧不许放宽() {
        val wide = GrantBudget(
            maxObjects = 99,
            maxActions = 999,
            maxBytesPerFile = 99L * 1024 * 1024,
            maxBytesTotal = 999L * 1024 * 1024,
        ).clamped()
        assertEquals(GrantLimits.MAX_OBJECTS, wide.maxObjects)
        assertEquals(GrantLimits.MAX_ACTIONS, wide.maxActions)
        assertEquals(GrantLimits.MAX_BYTES_PER_FILE, wide.maxBytesPerFile)
        assertEquals(GrantLimits.MAX_BYTES_TOTAL, wide.maxBytesTotal)

        val tight = GrantBudget(maxObjects = 3, maxActions = 5).clamped()
        assertEquals(3, tight.maxObjects)
        assertEquals(5, tight.maxActions)
    }

    @Test
    fun 对象句柄是稳定形状() {
        assertEquals("task:t1:a@3", AuthzFixtures.obj("a", rev = 3).handle)
        assertEquals(
            AuthzFixtures.obj("a", rev = 3),
            AuthzFixtures.grant(objects = listOf(AuthzFixtures.obj("a", rev = 3))).objectOf("task:t1:a@3"),
        )
    }

    @Test
    fun 快照哈希与书写顺序无关() {
        val a = AuthzFixtures.grant(objects = listOf(AuthzFixtures.obj("a"), AuthzFixtures.obj("b")))
        val b = AuthzFixtures.grant(objects = listOf(AuthzFixtures.obj("b"), AuthzFixtures.obj("a")))
        assertEquals(a.snapshotHash(), b.snapshotHash())
        assertEquals(64, a.snapshotHash().length)
    }

    @Test
    fun 快照哈希认得出对象换了() {
        val a = AuthzFixtures.grant(objects = listOf(AuthzFixtures.obj("a")))
        val b = AuthzFixtures.grant(objects = listOf(AuthzFixtures.obj("a", rev = 1)))
        assertFalse(a.snapshotHash() == b.snapshotHash())
    }

    @Test
    fun 动作白名单只有两种文本动作() {
        assertEquals(
            listOf(GrantActionType.CREATE_TEXT, GrantActionType.EDIT_TEXT),
            GrantActionType.GRANTABLE,
        )
        listOf(
            GrantActionType.DELETE,
            GrantActionType.SEND,
            GrantActionType.EXECUTE,
            GrantActionType.CHANGE_PERMISSION,
            GrantActionType.GIT,
            GrantActionType.OTHER,
        ).forEach { assertFalse("${it.wire} 不许可授权", it.grantable) }
    }
}
