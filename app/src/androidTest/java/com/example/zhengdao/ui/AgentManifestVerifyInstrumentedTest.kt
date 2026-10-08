// 真机 + 真签名验收：用仓库里**真实**的 rootfs/agents.json 与 .sig 过 App 的验签入口
// （Rust 优先 + 平台对拍）。logcat 里的
//   «验签走 Rust 核心（与平台对拍一致）：true»
// 就是"新路径真的在跑、且与旧路径结论一致"的凭据（见 ERRATA E-051）。
// 网络不可达时跳过（不是失败）——它验的是签名链路，不是网络。
package com.example.zhengdao.ui

import com.example.zhengdao.rootfs.RootfsDownloader
import com.example.zhengdao.rust.CoreNative
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class AgentManifestVerifyInstrumentedTest {

    @Test
    fun 真实清单_过App验签入口() {
        assumeTrue("Rust 核心不可用，跳过", CoreNative.isRustAvailable())
        val u = "https://raw.githubusercontent.com/pisces19860207/zhengdao/main/rootfs/agents.json"
        val body = RootfsDownloader.fetchText(u, trimEnds = false)
        val sig = RootfsDownloader.fetchText("$u.sig")
        assumeTrue("网络不可达，跳过", body != null && sig != null)
        assertTrue(
            "真实 agents.json 必须验签通过（否则固化公钥/签名/实现三者有一处不对）",
            AgentManifest.verify(body!!.toByteArray(Charsets.UTF_8), sig!!),
        )
    }
}
