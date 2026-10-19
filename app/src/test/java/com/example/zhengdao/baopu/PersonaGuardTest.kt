// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：第三十七批 A 段（账 docs/FEATURE-LEDGER.md §A #106）。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [PersonaGuard] 纯函数单测（第三十七批 A 段，判据 L1/L2 的判据层）。
 *
 * 钉三件事：
 * 1. **人设与技能索引只能走提案或设置页** —— C 层 `抱朴记忆/人设-本项目.md`、B 层
 *    `抱朴记忆/全局/人设.md` 都拦，话术一字不差（话术只有一个真相：[PersonaGuard.REJECT_PREFIX]）；
 * 2. **变体写法一个都绕不过**（`./`、`a/../`、反斜杠、重复斜杠、盘符、大小写）；
 * 3. **不许过度拦截** —— 同前缀兄弟目录（`抱朴记忆2/…`）与别处的同名文件不拦；
 *    这两条正是「改成字符串前缀比较」会红的反例（判据 L2 的负例）。
 */
class PersonaGuardTest {

    private val prefix = PersonaGuard.REJECT_PREFIX

    // ── ① 正身被拦，话术一字不差 ────────────────────────────────────────

    @Test
    fun `C层人设_正身被拦且话术一字不差`() {
        val p = "抱朴记忆/人设-本项目.md"
        assertEquals(prefix + p, PersonaGuard.blocked(p))
    }

    @Test
    fun `C层人设_工作区根直放也被拦`() {
        // 派工单字面写的是「工作区里的 人设-本项目.md」——真身在 抱朴记忆/ 下，两种都收
        val p = "人设-本项目.md"
        assertEquals(prefix + p, PersonaGuard.blocked(p))
    }

    @Test
    fun `B层人设_派工单写法与代码真身两种都拦`() {
        val a = "抱朴记忆/人设.md"
        val b = "抱朴记忆/全局/人设.md"
        assertEquals(prefix + a, PersonaGuard.blocked(a))
        assertEquals(prefix + b, PersonaGuard.blocked(b))
    }

    // ── ② 变体绕不过（L2）────────────────────────────────────────────────

    @Test
    fun `变体_点斜杠与点点回退绕不过`() {
        assertEquals(prefix + "./抱朴记忆/人设-本项目.md", PersonaGuard.blocked("./抱朴记忆/人设-本项目.md"))
        assertEquals(prefix + "a/../抱朴记忆/人设-本项目.md", PersonaGuard.blocked("a/../抱朴记忆/人设-本项目.md"))
        assertEquals(
            prefix + "x/y/../../抱朴记忆/人设-本项目.md",
            PersonaGuard.blocked("x/y/../../抱朴记忆/人设-本项目.md"),
        )
    }

    @Test
    fun `变体_反斜杠与重复斜杠绕不过`() {
        assertEquals(prefix + "抱朴记忆\\人设-本项目.md", PersonaGuard.blocked("抱朴记忆\\人设-本项目.md"))
        assertEquals(prefix + "//抱朴记忆//人设-本项目.md", PersonaGuard.blocked("//抱朴记忆//人设-本项目.md"))
    }

    @Test
    fun `变体_大小写与绝对路径绕不过`() {
        assertEquals(prefix + "抱朴记忆/人设-本项目.MD", PersonaGuard.blocked("抱朴记忆/人设-本项目.MD"))
        assertEquals(prefix + "抱朴记忆/全局/人设.MD", PersonaGuard.blocked("抱朴记忆/全局/人设.MD"))
        // 绝对路径（模型把工作区的绝对位置整条贴进来）也认得出来
        assertEquals(
            prefix + "C:/工作区/抱朴记忆/人设-本项目.md",
            PersonaGuard.blocked("C:/工作区/抱朴记忆/人设-本项目.md"),
        )
    }

    // ── ③ 不许过度拦截（前缀比较会红的两条）────────────────────────────

    @Test
    fun `同前缀兄弟目录_不误伤`() {
        assertNull("抱朴记忆2 是人设之外的普通目录", PersonaGuard.blocked("抱朴记忆2/人设-本项目.md"))
        assertNull("抱朴记忆备份 同理", PersonaGuard.blocked("抱朴记忆备份/人设.md"))
    }

    @Test
    fun `别处同名文件_不拦`() {
        assertNull("技能目录下的同名文件不是人设", PersonaGuard.blocked("抱朴记忆/技能/人设.md"))
        assertNull("深层目录里的同名文件不是人设", PersonaGuard.blocked("资料库/整理/人设-本项目.md"))
    }

    @Test
    fun `普通文件与空串_放行`() {
        assertNull(PersonaGuard.blocked("notes/随手记.md"))
        assertNull(PersonaGuard.blocked("抱朴记忆/记忆-2026-10-19.md"))
        assertNull(PersonaGuard.blocked("  "))
        assertNull(PersonaGuard.blocked(""))
    }

    @Test
    fun `越界的点点_交给越界判据不管`() {
        // 闸只管人设；「../ 逃出工作区」是既有越界判据的活，这里不抢（返回 null）
        assertNull(PersonaGuard.blocked("../../抱朴记忆/人设-本项目.md"))
    }

    @Test
    fun `归一_段序列正确`() {
        assertEquals(listOf("抱朴记忆", "人设-本项目.md"), PersonaGuard.normalize("./抱朴记忆/人设-本项目.md"))
        assertEquals(listOf("抱朴记忆", "人设.md"), PersonaGuard.normalize("a/../抱朴记忆\\人设.md"))
        assertNull(PersonaGuard.normalize("../../../x"))
    }
}
