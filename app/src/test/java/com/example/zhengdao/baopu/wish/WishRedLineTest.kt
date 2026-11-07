// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 第五百一十批 · 判据⑦：三类危险草稿各拒一条（生成期就拦，不是事后警告）。
package com.example.zhengdao.baopu.wish

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WishRedLineTest {

    @Test
    fun 判据7a_删原件拒生成() {
        val hits = WishRedLine.scan("步骤三：删原件，不留回收站")
        assertTrue(hits.contains(DangerKind.DELETE_ORIGINAL))
        assertNotNull(WishRedLine.refuseReason("删原件"))
    }

    @Test
    fun 判据7b_转账拒生成() {
        val hits = WishRedLine.scan("先转账到指定账户再执行")
        assertTrue(hits.contains(DangerKind.TRANSFER))
        assertNotNull(WishRedLine.refuseReason("转账"))
    }

    @Test
    fun 判据7c_外发拒生成() {
        val hits = WishRedLine.scan("把结果外发到第三方服务器")
        assertTrue(hits.contains(DangerKind.EXTERNAL_SEND))
        assertNotNull(WishRedLine.refuseReason("外发"))
    }

    @Test
    fun 改系统目录也拦() {
        val hits = WishRedLine.scan("修改系统目录下的凭据文件")
        assertTrue(hits.contains(DangerKind.SYSTEM_DIR))
    }

    @Test
    fun 正常文本放行() {
        val safe = "把工作区里的 .md 文件按标题合并成一本，输出到副本目录"
        assertTrue(WishRedLine.scan(safe).isEmpty())
        assertNull(WishRedLine.refuseReason(safe))
    }

    @Test
    fun 四类危险各命中自己的标签() {
        assertEquals(DangerKind.DELETE_ORIGINAL.label, "删原件（无回收站）")
        assertEquals(DangerKind.TRANSFER.label, "转账/付费")
        assertEquals(DangerKind.EXTERNAL_SEND.label, "对外发送")
        assertEquals(DangerKind.SYSTEM_DIR.label, "改系统目录/凭据")
    }

    @Test
    fun 多种危险同时命中() {
        val hits = WishRedLine.scan("删原件后转账并外发")
        assertEquals(3, hits.size)
    }
}