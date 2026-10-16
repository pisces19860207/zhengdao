// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 依据的公开接口：OkHttp 官方文档（Authenticator / Response.priorResponse）、JUnit 4。
package com.example.zhengdao.oc

import android.util.Base64
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * B 步（2026-10-13，抱朴 401 自治愈）：钉住 A 步新增的 [OcAuthenticator] 的**判定分支**。
 *
 * 为什么直接构造 OkHttp 的 Request/Response：Authenticator 是纯判定
 * ——「收到 401 → 给不给重试请求」不需要真起服务，快且无网络。
 *
 * ⚠️ 本机 JVM 单测里 `android.util.Base64` 是空桩（`isReturnDefaultValues = true`），
 *    `encodeToString` 返回 null ⇒ 生产算出的头在测试里恒为 `"Basic null"`。
 *    因此：
 *    ·「新密码与旧不同」用**字面量**旧头（`Basic OLD-...`）构造，保证与桩值不同；
 *    ·「新密码与旧逐字相同」用与本类相同的 [stubHeader] 构造旧头，保证等值比较
 *      在生产同一条路径上成立（真机上是真 base64，同样成立）。
 *    即：这些用例钉的是**分支与比较逻辑**，不是 base64 内容。
 */
class OcAuthenticatorTest {

    /** 与生产同源的桩值：`"opencode:$pw"` 的 base64。 */
    private fun stubHeader(pw: String): String =
        "Basic " + Base64.encodeToString("opencode:$pw".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun request(authorization: String, accept: String? = "application/json"): Request =
        Request.Builder()
            .url("http://127.0.0.1:14000/api/session")
            .apply {
                if (accept != null) header("Accept", accept)
                header("Authorization", authorization)
                header("x-probe", "keep-me")
            }
            .build()

    private fun response(request: Request, prior: Response? = null): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .apply { if (prior != null) priorResponse(prior) }
            .build()

    @Test
    fun `没有 refresher（老调用点）时放弃重试`() {
        val req = request("Basic OLD-TO-BE-REPLACED")
        assertNull(OcAuthenticator(null).authenticate(null, response(req)))
    }

    @Test
    fun `refresher 拿不到密码时放弃重试`() {
        val req = request("Basic OLD-TO-BE-REPLACED")
        assertNull(OcAuthenticator { null }.authenticate(null, response(req)))
        assertNull(OcAuthenticator { "" }.authenticate(null, response(req)))
    }

    @Test
    fun `新密码与旧不同时返回重试请求_且其余部分原样保留`() {
        val req = request("Basic OLD-TO-BE-REPLACED")
        val retried = OcAuthenticator { "new-pass" }.authenticate(null, response(req))
        assertNotNull(retried)
        assertEquals(req.url, retried!!.url)
        assertEquals("GET", retried.method)
        assertEquals(req.header("Accept"), retried.header("Accept"))
        assertEquals("keep-me", retried.header("x-probe"))
        assertEquals("Basic null", retried.header("Authorization"))   // 桩值：见类注释
    }

    @Test
    fun `新密码与旧逐字相同时不重试`() {
        val same = "same-pass"
        val req = request(stubHeader(same))
        assertNull(OcAuthenticator { same }.authenticate(null, response(req)))
    }

    @Test
    fun `已经重试过一次就不再试_最多一次`() {
        val req = request("Basic OLD-TO-BE-REPLACED")
        val first = response(req)
        val current = response(req, prior = response(req, prior = first))
        assertNull(OcAuthenticator { "new-pass" }.authenticate(null, current))
    }

    @Test
    fun `SSE 请求跳过不重试_流式路径形状不变`() {
        val req = request("Basic OLD-TO-BE-REPLACED", accept = "text/event-stream")
        assertNull(OcAuthenticator { "new-pass" }.authenticate(null, response(req)))
    }
}
