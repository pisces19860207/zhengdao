// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EngineSecurityConfig] 默认值门禁（第⑨刀 B）——钉死「默认 = 加固值」。
 *
 * 这些默认值是 §4.4 安全加固清单的代码化，属"硬红线区"：任何一次顺手放宽
 * （比如把 mixedContentMode 改成 ALWAYS_ALLOW、把 allowFileAccess 翻真）都会被此测试
 * 当场拦下。改默认值 = 改红线，必须走架构重评，不许静默发生。
 */
class EngineSecurityConfigDefaultsTest {

    @Test
    fun `默认值全部是加固值`() {
        val c = EngineSecurityConfig()
        assertTrue("Safe Browsing 默认开", c.safeBrowsingEnabled)
        assertEquals("混合内容默认 NEVER_ALLOW", EngineSecurityConfig.MIXED_CONTENT_NEVER_ALLOW, c.mixedContentMode)
        assertFalse("文件访问默认关", c.allowFileAccess)
        assertFalse("file:// 读文件 URL 默认关", c.allowFileAccessFromFileUrls)
        assertFalse("file:// 通配访问默认关", c.allowUniversalAccessFromFileUrls)
        assertFalse("database 默认关", c.databaseEnabled)
        assertTrue("JS 总开关默认开（浏览器核心能力）", c.javaScriptEnabled)
        assertFalse("JS 自开窗默认关（多标签由 App 接管）", c.javaScriptCanOpenWindowsAutomatically)
        assertTrue("domStorage 默认开（页面基本功）", c.domStorageEnabled)
        assertFalse("第三方 cookie 默认关（隐私取向）", c.thirdPartyCookiesEnabled)
        assertTrue("JS 接口白名单默认空（红线④：一个都不暴露）", c.allowedJsInterfaces.isEmpty())
    }

    @Test
    fun `dataDirectorySuffix 默认空_进程级后缀在 ZhengdaoApp 统一设`() {
        // 第⑨刀 B 查证（② AOSP）：setDataDirectorySuffix 是静态、每进程一次性，
        // 单进程内多标签不可能各有数据目录 ⇒ 配置里不再放 per-tab 后缀，保持 null。
        val c = EngineSecurityConfig()
        assertEquals(null, c.dataDirectorySuffix)
    }

    @Test
    fun `MIXED_CONTENT 常量与框架同值`() {
        // android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW = 2（API 21 起冻结）
        assertEquals(2, EngineSecurityConfig.MIXED_CONTENT_NEVER_ALLOW)
    }
}
