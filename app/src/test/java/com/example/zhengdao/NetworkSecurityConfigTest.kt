// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 明文（cleartext）放行门禁（2026-10-12，用户交办）。
 *
 * 钉的是什么：`AndroidManifest.xml` 必须用**白名单式** `networkSecurityConfig`
 * （而不是过去的全局 `android:usesCleartextTraffic="true"`），且白名单**只放行环回**
 * （本机 oc serve 的 REST + SSE）——这是第一方代码里唯一需要明文的地方（实测 grep 全仓）。
 *
 * ⚠️ 一条常被想当然的错口径，写在这里免得后人再犯：**guest 里的 apt 不需要放行。**
 * 镜像的 apt 源确实是 `http://deb.debian.org/debian`（`rootfs/build-rootfs.sh:52` 的
 * MIRROR），但明文策略是在**平台网络栈**里生效的 —— AOSP 的 `NetworkSecurityPolicy`
 * 由 platform 组件（`HttpURLConnection`/`WebView`）与遵从它的库（OkHttp 的 AndroidPlatform）
 * 查，**不拦 bionic 的 `connect()`**；Flutter 官方为此在 Dart 层自己实现了一遍检查
 * （Flutter 2.0「Insecure HTTP connections are disabled by default」，理由正是平台策略
 * 只覆盖平台组件）。proot 的 guest 是 glibc + GnuTLS 的原生程序、走自己的 socket
 * ⇒ 与这份 config 无关。曾有一版把 `deb.debian.org` 也写进白名单（依据是"guest 与 App
 * 同 UID、同一个网络栈"），**那个依据不成立**，2026-10-12 撤掉——多出来的白名单条目
 * 不是"更保险"，而是给第一方代码多开了一个明文出口。
 *
 * 为什么值得单独钉：明文放行是**看不见的** —— 少一条白名单，表现是「本机服务连不上」，
 * 多一条（或回退成全局放行），表现是「谁也没发现」。两者都不会在编译期暴露。
 *
 * 注意：这是**文本门禁**，不是对 Android 运行时网络的验证 —— 真要验运行时，得装机后
 * 进终端跑「本机服务可用」与「guest 里 `apt-get update` 照常」两件事
 * （本仓既有做法：正式签名包 `adb install -r`，不丢数据）。
 */
class NetworkSecurityConfigTest {

    private fun appFile(rel: String): File =
        File(rel).let { if (it.exists()) it else File("app/$rel") }

    private val nsc: String by lazy {
        appFile("src/main/res/xml/network_security_config.xml").readText()
    }

    private val manifest: String by lazy { appFile("src/main/AndroidManifest.xml").readText() }

    /** 去掉 XML 注释：注释里解释「改前是什么」时会**提到**被禁的属性名，不能算命中。 */
    private fun stripComments(s: String): String = s.replace(Regex("(?s)<!--.*?-->"), "")

    /** 取出所有 `<domain-config>…</domain-config>` 片段。 */
    private fun domainConfigs(text: String): List<String> {
        val out = mutableListOf<String>()
        var i = text.indexOf("<domain-config")
        while (i >= 0) {
            val j = text.indexOf("</domain-config>", i)
            assertTrue("有 <domain-config> 没闭合", j > i)
            out += text.substring(i, j)
            i = text.indexOf("<domain-config", j)
        }
        return out
    }

    @Test
    fun `base 禁止明文 只有白名单例外`() {
        assertTrue(
            "network_security_config.xml 的 base-config 必须是 cleartextTrafficPermitted=\"false\"",
            nsc.contains("""<base-config cleartextTrafficPermitted="false"""")
        )
        assertFalse(
            "白名单不许 includeSubdomains=\"true\"（那会把整个域及其子域一起放行）",
            nsc.contains("""includeSubdomains="true"""")
        )
        val outsideConfigs = stripComments(nsc).replace(Regex("(?s)<domain-config.*?</domain-config>"), "")
        assertFalse(
            "cleartextTrafficPermitted=\"true\" 只能出现在 <domain-config> 里，不许出现在 base-config 或别处",
            outsideConfigs.contains("""cleartextTrafficPermitted="true"""")
        )
        val allowed = domainConfigs(nsc).filter { it.contains("""cleartextTrafficPermitted="true"""") }
        assertEquals(
            "放行段数变了（现有 = 只有环回这一段）。要加第二段，先在注释里写清「第一方代码哪一行" +
                "要连这个明文主机」——guest 里的原生程序（apt 等）不算理由：明文策略不拦原生 socket，" +
                "多一条白名单只是给第一方多开一个明文出口",
            1,
            allowed.size
        )
    }

    @Test
    fun `白名单里只能有环回`() {
        val nscNoComments = stripComments(nsc)
        val domains = Regex("""<domain[^>]*>([^<]+)</domain>""")
            .findAll(nscNoComments)
            .map { it.groupValues[1].trim() }
            .toList()
        assertTrue("这份 config 里一个 <domain> 都没有，环回至少要在", domains.isNotEmpty())
        val unexpected = domains.filter { it != "127.0.0.1" && it != "localhost" }
        assertTrue(
            "白名单里出现了非环回主机：${unexpected.joinToString("；")}。本 App 第一方代码只用环回明文" +
                "（旧抱朴线那三处调用者已于 2026-10-16 删除，改由「预览槽」等本地服务；" +
                "外部主机要么改成 https，要么先想清楚" +
                "「这条明文是哪个第一方组件在用、为什么不能上 https」再动这条断言",
            unexpected.isEmpty()
        )
    }

    @Test
    fun `环回在白名单里`() {
        val loopback = domainConfigs(nsc).firstOrNull { it.contains("<domain includeSubdomains=\"false\">127.0.0.1</domain>") }
        assertNotNull(
            "127.0.0.1 必须有一段明文放行 —— App 自己的本地服务（旧 oc serve 已删，后续预览槽仍走它）",
            loopback
        )
        assertTrue(
            "127.0.0.1 那段必须 cleartextTrafficPermitted=\"true\"，否则本机服务连不上",
            loopback!!.contains("""cleartextTrafficPermitted="true"""")
        )
    }

    @Test
    fun `manifest 用白名单 不再全局放行`() {
        assertTrue(
            "AndroidManifest 少了 android:networkSecurityConfig=\"@xml/network_security_config\"",
            manifest.contains("""android:networkSecurityConfig="@xml/network_security_config"""")
        )
        assertFalse(
            "android:usesCleartextTraffic 还在 —— 有 networkSecurityConfig 时它会被系统忽略，" +
                "留着就是「看着全局放行、其实是白名单」的双口径，删掉",
            stripComments(manifest).contains("usesCleartextTraffic")
        )
    }

    @Test
    fun `第一方源码里的明文 URL 只能是环回`() {
        val root = appFile("src/main/java/com/example/zhengdao")
        assertTrue("找不到第一方源码目录：${root.absolutePath}", root.isDirectory)
        val re = Regex("\"http://([A-Za-z0-9._\\-]+)")
        val offenders = mutableListOf<String>()
        root.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .forEach { f ->
                f.readLines().forEachIndexed { idx, line ->
                    re.findAll(line).forEach { m ->
                        val host = m.groupValues[1]
                        if (host != "127.0.0.1" && host != "localhost") {
                            offenders += "${f.name}:${idx + 1} → $host"
                        }
                    }
                }
            }
        assertTrue(
            "这些明文 URL 不在白名单里，改了 NSC 之后它们会被系统拦掉：" +
                offenders.joinToString("；") + "（要么改成 https，要么把域名连同理由加进白名单）",
            offenders.isEmpty()
        )
    }

    /**
     * 记录一条**容易想当然**的口径：guest 里 apt 走的是明文 http，但它**不需要**进白名单。
     * 依据：明文策略只在平台网络栈生效（HttpURLConnection/WebView/OkHttp），不拦 bionic 的
     * `connect()`；proot 的 guest 是 glibc + GnuTLS 原生程序 ⇒ 与 network_security_config.xml 无关
     * （Flutter 2.0 自己在 Dart 层实现了一遍检查，理由正是平台策略只覆盖平台组件）。
     *
     * 这条测试同时是**扳机**：哪天 build-rootfs.sh 的镜像源换成 https（或换了主机），
     * 它会红，逼着人回来把这段记录性的口径改对——而不是让注释悄悄过期。
     */
    @Test
    fun `guest 的明文 apt 源不进白名单`() {
        val build = File("../rootfs/build-rootfs.sh")
            .let { if (it.exists()) it else File("rootfs/build-rootfs.sh") }
        assertTrue("找不到 rootfs/build-rootfs.sh（口径一致性的另一半）", build.exists())
        val m = Regex("MIRROR=\"(https?)://([^/\"]+)").find(build.readText())
        assertNotNull("rootfs/build-rootfs.sh 里找不到 MIRROR=\"…\"", m)
        val scheme = m!!.groupValues[1]
        val host = m.groupValues[2]
        assertEquals(
            "镜像的 apt 源不再是明文 http 了（现在是 $scheme://$host）——请顺手更新本用例与 " +
                "network_security_config.xml 顶部那段注释：它们是配套的记录",
            "http",
            scheme
        )
        assertFalse(
            "白名单里出现了 guest 的 apt 主机 $host —— 这说明有人按「guest 与 App 同 UID、同一个网络栈」" +
                "推了一把，但那个前提不成立：明文策略不拦原生 socket（proot 的 guest 是 glibc + GnuTLS，" +
                "走自己的 socket），所以 apt 本来就不需要放行。多这一条只会给第一方代码多开一个明文出口",
            stripComments(nsc).contains("<domain includeSubdomains=\"false\">$host</domain>")
        )
    }
}
