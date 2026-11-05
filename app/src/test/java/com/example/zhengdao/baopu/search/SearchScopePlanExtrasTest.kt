// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：492 A 段①「根清单」与读侧账本的接线——附加根按 HOME 档入账（[SearchScopeRules.plan]
// 的 extras 形参）。这里只测纯判据；「单根 0 回归」由缺省形参保证（存量三参调用一个字不改）。
package com.example.zhengdao.baopu.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchScopePlanExtrasTest {

    private val home = "/storage/emulated/0/Download/证道"
    private val phone = "/storage/emulated/0"

    @Test
    fun `A4_缺省extras_计划与旧算式逐字一致`() {
        val granted = SearchScopeRules.plan(PhoneAccess.GRANTED, home, phone)
        assertEquals(listOf(home to ScopeKind.HOME, phone to ScopeKind.PHONE), granted.map { it.absRoot to it.kind })
        val denied = SearchScopeRules.plan(PhoneAccess.NO_PERMISSION, home, phone)
        assertEquals(listOf(home to ScopeKind.HOME), denied.map { it.absRoot to it.kind })
    }

    @Test
    fun `附加根_HOME档_排HOME后PHONE前_保清单序`() {
        val extras = listOf(
            ScopeDraft("/storage/emulated/0/资料", ScopeKind.HOME),
            ScopeDraft("/storage/emulated/0/文档", ScopeKind.HOME),
        )
        val drafts = SearchScopeRules.plan(PhoneAccess.GRANTED, home, phone, extras)
        assertEquals(
            listOf(home, "/storage/emulated/0/资料", "/storage/emulated/0/文档", phone),
            drafts.map { it.absRoot },
        )
        assertTrue("附加根与主根同档（HOME）", drafts.drop(1).take(2).all { it.kind == ScopeKind.HOME })
    }

    @Test
    fun `附加根_与主根或手机根同一个物理盘_不重复入账`() {
        val dupHome = SearchScopeRules.plan(
            PhoneAccess.GRANTED, home, phone,
            listOf(ScopeDraft(home, ScopeKind.HOME)),
        )
        assertEquals(2, dupHome.size)
        val dupPhone = SearchScopeRules.plan(
            PhoneAccess.GRANTED, home, phone,
            listOf(ScopeDraft(phone, ScopeKind.HOME)),
        )
        assertEquals("手机根已有 PHONE 行，附加条目不另立 HOME 行", 2, dupPhone.size)
        assertEquals(listOf(home, phone), dupPhone.map { it.absRoot })
    }

    @Test
    fun `附加根_没授权也照落_HOME档不需要授予`() {
        val drafts = SearchScopeRules.plan(
            PhoneAccess.NO_PERMISSION, home, phone,
            listOf(ScopeDraft("/storage/emulated/0/资料", ScopeKind.HOME)),
        )
        assertEquals(listOf(home, "/storage/emulated/0/资料"), drafts.map { it.absRoot })
    }
}
