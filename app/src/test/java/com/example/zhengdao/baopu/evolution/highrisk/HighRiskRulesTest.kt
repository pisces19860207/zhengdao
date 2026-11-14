// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百六十八批 C · 域①方向判定纯单测（零 IO、零时钟）：收窄/加宽/未变三态，
// 加宽优先（既加又删按加宽走准入），编解码往返幂等。
package com.example.zhengdao.baopu.evolution.highrisk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HighRiskRulesTest {

    @Test
    fun 只删不加是收窄() {
        assertEquals(WhitelistDirection.NARROW, WhitelistRules.directionOf(setOf("a", "b"), setOf("a")))
    }

    @Test
    fun 只加不删是加宽() {
        assertEquals(WhitelistDirection.WIDEN, WhitelistRules.directionOf(setOf("a"), setOf("a", "b")))
    }

    @Test
    fun 既加又删仍按加宽_保守口径堵住换键提权() {
        assertEquals(
            "删一个换一个＝加宽（必须过准入），不许借收窄之名提权",
            WhitelistDirection.WIDEN,
            WhitelistRules.directionOf(setOf("a", "b"), setOf("a", "c")),
        )
    }

    @Test
    fun 一个键没动是未变() {
        assertEquals(WhitelistDirection.NOOP, WhitelistRules.directionOf(setOf("a", "b"), setOf("b", "a")))
        assertEquals(WhitelistDirection.NOOP, WhitelistRules.directionOf(emptySet(), emptySet()))
    }

    @Test
    fun 空集到非空是加宽_非空到空集是收窄() {
        assertEquals(WhitelistDirection.WIDEN, WhitelistRules.directionOf(emptySet(), setOf("a")))
        assertEquals(WhitelistDirection.NARROW, WhitelistRules.directionOf(setOf("a"), emptySet()))
    }

    @Test
    fun 编解码往返幂等_排序稳定() {
        val keys = setOf("web", "bash", "skill")
        val encoded = WhitelistRules.encode(keys)
        assertEquals("排序相连（稳定串）", "bash,skill,web", encoded)
        assertEquals(keys, WhitelistRules.decode(encoded))
    }

    @Test
    fun 空串解码为空集_空白格滤掉() {
        assertTrue(WhitelistRules.decode("").isEmpty())
        assertTrue(WhitelistRules.decode("  ").isEmpty())
        assertEquals(setOf("a", "b"), WhitelistRules.decode("a, ,b,"))
    }

    @Test
    fun 方向线值闭集_认不出返回null() {
        assertEquals(WhitelistDirection.NARROW, WhitelistDirection.ofWire("narrow"))
        assertEquals(WhitelistDirection.WIDEN, WhitelistDirection.ofWire("widen"))
        assertEquals(WhitelistDirection.NOOP, WhitelistDirection.ofWire("noop"))
        assertEquals(null, WhitelistDirection.ofWire("自造方向"))
    }
}