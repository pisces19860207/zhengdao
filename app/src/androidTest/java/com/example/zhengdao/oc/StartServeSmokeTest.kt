// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的公开资料：Android instrumented test 官方文档。
package com.example.zhengdao.oc

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * serve 启动端到端冒烟（App 进程内的权威路径——ProcessBuilder exec，
 * 与 run-as shell 的 exec 语义不同，后者失败不代表 App 失败）。
 *
 * 验收：startServe 返回 null（无错误）+ serveRunning() 为真 + 密码已解析（401 前置条件）。
 */
@RunWith(AndroidJUnit4::class)
class StartServeSmokeTest {

    @Test
    fun 启动serve_端到端冒烟() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("opencode 未安装，跳过", OcManager.installed(ctx))

        val err = runBlocking {
            withContext(Dispatchers.IO) { OcManager.startServe(ctx) }
        }
        Log.i(
            "BENCH",
            "OCSERVE startServe 返回=$err / running=${OcManager.serveRunning()} / 密码=${OcManager.servePassword != null}"
        )
        assertNull("startServe 报错: $err", err)
        assertTrue("serve 未就绪（端口无响应）", OcManager.serveRunning())
        assertTrue("密码未解析（UI 将 401）", OcManager.servePassword != null)
    }
}
