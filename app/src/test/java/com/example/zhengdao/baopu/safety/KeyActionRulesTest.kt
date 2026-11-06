// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 504 判据②的判则钉子：不可逆/批量/连败≥3 三格。 */
class KeyActionRulesTest {

    @Test
    fun `不可逆_R3档算关键动作`() {
        assertEquals(KeyActionKind.IRREVERSIBLE, KeyActionRules.kindOf("empty_trash", "R3", 1))
        // R3 优先于批量（一格动作只有一个格子）
        assertEquals(KeyActionKind.IRREVERSIBLE, KeyActionRules.kindOf("organize", "R3", 9))
    }

    @Test
    fun `批量_四件套与一次删多个`() {
        assertEquals(KeyActionKind.BULK, KeyActionRules.kindOf("bulk_move", "R1", 1))
        assertEquals(KeyActionKind.BULK, KeyActionRules.kindOf("bulk_copy", "R1", 2))
        assertEquals(KeyActionKind.BULK, KeyActionRules.kindOf("organize", "R2", 1))
        assertEquals(KeyActionKind.BULK, KeyActionRules.kindOf("empty_trash", "R2", 0))
        assertEquals(KeyActionKind.BULK, KeyActionRules.kindOf("delete_path", "R1", 3)) // 一次删 3 个
        assertNull(KeyActionRules.kindOf("delete_path", "R1", 1)) // 单个普通删除不算
    }

    @Test
    fun `普通读写不算关键动作`() {
        assertNull(KeyActionRules.kindOf("read_file", "R0", 1))
        assertNull(KeyActionRules.kindOf("write_file", "R1", 1))
    }

    @Test
    fun `连败第3次报_之后每3次再报_成功清零由装配层管`() {
        // 第 1、2 次连败不报；第 3 次报；4、5 不报；6 再报
        assertFalse(KeyActionRules.streakShouldNotify(0))
        assertFalse(KeyActionRules.streakShouldNotify(1))
        assertTrue(KeyActionRules.streakShouldNotify(2))
        assertFalse(KeyActionRules.streakShouldNotify(3))
        assertFalse(KeyActionRules.streakShouldNotify(4))
        assertTrue(KeyActionRules.streakShouldNotify(5))
    }
}
