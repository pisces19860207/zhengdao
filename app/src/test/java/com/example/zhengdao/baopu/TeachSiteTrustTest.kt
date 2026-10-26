// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百二十八批 C 片 §C-2：回放的站点级信任（纯函数层）。
//
// 四条硬边界逐条有靶子：
//   ① 默认空名单 ⇒ isTrusted 恒 false（默认关，没有「默认信任」这回事）；
//   ② 主机名校验从严：localhost／IP／无点／通配符／端口／内网伪后缀一律不进名单；
//   ③ 匹配口径与 TeachSafety.domainAllowed 一致（全等或子域，a.com 不放行 a.com.evil.io）；
//   ④ 加/撤都落决策记录（SITE_TRUST），方向在 evidence 里，回滚语义＝反向文件操作。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TeachSiteTrustTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── ① 默认空 ⇒ 全拒 ─────────────────────────────────────────────────────

    @Test
    fun `默认没有名单文件_读出空_一切站点都不信任`() {
        val root = tmp.newFolder()
        assertEquals(emptyList<String>(), TeachSiteTrust.load(root))
        assertFalse("默认全拒：任何 URL 都不信任", TeachSiteTrust.isTrusted("https://m.example.com/a", TeachSiteTrust.load(root)))
        assertTrue("信任闸要拒绝并给出加白办法", TeachSiteTrust.gateError("https://m.example.com/a", emptyList(), "开始示教")!!
            .contains("teach_site_trust add"))
    }

    // ── ② 主机名校验从严 ────────────────────────────────────────────────────

    @Test
    fun `normalize_合法域名收_宽容带scheme的粘贴`() {
        assertEquals("example.com", TeachSiteTrust.normalize("Example.COM"))
        assertEquals("m.example.com", TeachSiteTrust.normalize("https://m.example.com/list?a=1"))
        assertNull("空串", TeachSiteTrust.normalize("  "))
        assertNull("localhost", TeachSiteTrust.normalize("localhost"))
        assertNull("子域 localhost", TeachSiteTrust.normalize("api.localhost"))
        assertNull("IPv4", TeachSiteTrust.normalize("192.168.1.10"))
        assertNull("无点不算域名", TeachSiteTrust.normalize("myserver"))
        assertNull("通配符", TeachSiteTrust.normalize("*.example.com"))
        assertNull("带端口", TeachSiteTrust.normalize("example.com:8080"))
        assertNull("带路径", TeachSiteTrust.normalize("example.com/a"))
        assertNull("内网伪后缀 local", TeachSiteTrust.normalize("nas.local"))
        assertNull("内网伪后缀 internal", TeachSiteTrust.normalize("nas.internal"))
        assertNull("标签以连杠开头", TeachSiteTrust.normalize("-a.example.com"))
        assertNull("超长", TeachSiteTrust.normalize("a".repeat(254) + ".com"))
    }

    // ── ③ 匹配口径：全等或子域；a.com 不放行 a.com.evil.io ──────────────────

    @Test
    fun `covers_全等与子域放行_前缀冒充不放行`() {
        val trusted = listOf("m.example.com")
        assertTrue(TeachSiteTrust.covers("m.example.com", trusted))
        assertTrue("子域算覆盖", TeachSiteTrust.covers("a.m.example.com", trusted))
        assertFalse("别的域不覆盖", TeachSiteTrust.covers("b.example.com", trusted))
        assertFalse("a.com.evil.io 冒充不了 m.example.com", TeachSiteTrust.covers("m.example.com.evil.io", trusted))
        assertTrue(TeachSiteTrust.isTrusted("https://a.m.example.com/x", trusted))
        assertFalse("取不到 host 的 URL 一律不信任", TeachSiteTrust.isTrusted("not-a-url", trusted))
        assertTrue(
            "授权域里有没信任的要点名",
            TeachSiteTrust.domainsError(listOf("m.example.com", "other.example.com"), trusted)!!
                .contains("other.example.com"),
        )
        assertNull("授权域全覆盖 ⇒ 放行", TeachSiteTrust.domainsError(listOf("m.example.com"), trusted))
    }

    // ── ④ 名单文件的增删幂等 ＋ 决策留痕与回滚语义 ──────────────────────────

    @Test
    fun `add_remove_幂等_撤了个寂寞要照实说`() {
        val root = tmp.newFolder()
        assertTrue(TeachSiteTrust.add(root, "m.example.com"))
        assertTrue("重复加是幂等成功（不是假写盘）", TeachSiteTrust.add(root, "m.example.com"))
        assertEquals(listOf("m.example.com"), TeachSiteTrust.load(root))
        assertTrue(TeachSiteTrust.remove(root, "m.example.com"))
        assertFalse("名单里没有它 ⇒ false，不许装撤成", TeachSiteTrust.remove(root, "m.example.com"))
        assertTrue("撤完名单空", TeachSiteTrust.load(root).isEmpty())
    }

    @Test
    fun `坏JSON当空名单_fail-closed`() {
        val root = tmp.newFolder()
        TeachSiteTrust.fileIn(root).writeText("这不是JSON", Charsets.UTF_8)
        assertEquals("读不出就当空名单（全拒），不抛", emptyList<String>(), TeachSiteTrust.load(root))
    }

    @Test
    fun `加与撤都落决策记录_SITE_TRUST_方向在evidence里`() {
        val root = tmp.newFolder()
        val host = "m.example.com"
        // 与接线层同一条路：文件操作＋recordTo
        assertTrue(TeachSiteTrust.add(root, host))
        val rec1 = DecisionLedger.recordTo(
            root,
            DecisionDraft(
                kind = DecisionKind.SITE_TRUST,
                actor = DecisionActor.USER,
                reason = "信任站点 $host：示教与回放可在该域自动执行",
                evidence = "site_trust_add:$host",
                before = "",
                after = "",
                atMs = 1_728_000_000_000L,
            ),
        )
        assertEquals("site_trust", rec1!!.kind.wire)
        assertEquals("site_trust_add:m.example.com", rec1.evidence)
        assertTrue("回滚通道（撤）是真文件操作", TeachSiteTrust.remove(root, host))
        val rec2 = DecisionLedger.recordTo(
            root,
            DecisionDraft(
                kind = DecisionKind.SITE_TRUST,
                actor = DecisionActor.USER,
                reason = "撤销信任 $host",
                evidence = "site_trust_remove:$host",
                before = "",
                after = "",
                atMs = 1_728_000_000_100L,
            ),
        )
        assertEquals("site_trust_remove:m.example.com", rec2!!.evidence)
        // 回滚一条「撤」＝再加回来（方向反转），与 BaopuChatController 回滚分支同一判据
        assertTrue(TeachSiteTrust.add(root, host))
        assertEquals(listOf(host), TeachSiteTrust.load(root))
    }
}
