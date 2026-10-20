// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AppUpdateRules] 的判据用例（第七十六批 · §B 80 MainActivity 纯逻辑抽取）。
 *
 * 覆盖三件事：① 版本比较的**数值语义与容错**（不是字典序）；② tag 解析的**取首个匹配**语义；
 * ③ 现状里两处"不对称"行为（只剥 remote 的 v、非数字段按 0）——它们是**故意**的，用例负责防止被"顺手修掉"。
 */
class AppUpdateRulesTest {

    @Test
    fun `版本比较_远端严格大于才算有更新`() {
        assertTrue(AppUpdateRules.isNewer("v2.2.0", "2.1.1"))
        assertFalse(AppUpdateRules.isNewer("v2.1.1", "2.1.1"))
        assertFalse(AppUpdateRules.isNewer("v2.0.9", "2.1.1"))
    }

    @Test
    fun `版本比较_按数值不按字典序`() {
        // 字典序会判 "1.2.10" < "1.2.9"（'1' < '9'），这条用例就是钉死不许退回字符串比较
        assertTrue(AppUpdateRules.isNewer("1.2.10", "1.2.9"))
        assertFalse(AppUpdateRules.isNewer("1.2.9", "1.2.10"))
    }

    @Test
    fun `版本比较_缺失段按零补齐`() {
        assertFalse(AppUpdateRules.isNewer("1.2", "1.2.0"))
        assertTrue(AppUpdateRules.isNewer("1.2.1", "1.2"))
        assertTrue(AppUpdateRules.isNewer("2", "1.9.9"))
    }

    @Test
    fun `版本比较_非数字段按零且不抛异常`() {
        // r = [1,0,3]，l = [1,2,9] ⇒ 第二段 0 < 2
        assertFalse(AppUpdateRules.isNewer("1.x.3", "1.2.9"))
        assertTrue(AppUpdateRules.isNewer("2.x", "1.9.9"))
    }

    @Test
    fun `版本比较_空串按零处理`() {
        assertFalse(AppUpdateRules.isNewer("", "0"))
        assertTrue(AppUpdateRules.isNewer("1.0.0", ""))
    }

    @Test
    fun `版本比较_本地不剥v前缀的现状语义`() {
        // 现状：只对 remote 剥 v。local 若带 v（BuildConfig.VERSION_NAME 本不该带）
        // 会被当 [0,0,0] ⇒ 任何 1.x 的远端都算"更新"。这是故意留的暴露面，不许改成对称。
        assertTrue(AppUpdateRules.isNewer("1.0.0", "v1.0.0"))
    }

    @Test
    fun `tag解析_从响应体取出tag_name`() {
        val body = """{"url":"https://api.github.com/…","tag_name":"v2.2.0","name":"2.2.0"}"""
        assertEquals("v2.2.0", AppUpdateRules.parseLatestTag(body))
    }

    @Test
    fun `tag解析_没有tag_name返回null`() {
        assertNull(AppUpdateRules.parseLatestTag("""{"message":"Not Found"}"""))
        assertNull(AppUpdateRules.parseLatestTag(""))
    }

    @Test
    fun `tag解析_容忍冒号两侧空格`() {
        assertEquals("v9", AppUpdateRules.parseLatestTag("""{ "tag_name" : "v9" }"""))
    }

    @Test
    fun `tag解析_多次出现取第一个匹配`() {
        // GitHub 的 releases/latest 里 tag_name 只出现一次；若将来出现第二个，
        // 按现状取首个（不改成"取最后一个"）。
        assertEquals("v1", AppUpdateRules.parseLatestTag("""{"tag_name":"v1","other":"tag_name":"v2"}"""))
    }
}
