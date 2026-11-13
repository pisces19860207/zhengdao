// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 622 真机验收：**端口→执行层→真 WebView** 的最后一跳。
//
// 单测（BrowserPortGateTest）证明的是"闸判得对"；本测试在真设备上证明
// "判过闸的动作真的落到玄览的真标签上"：起 MainActivity → 产品入口建标签载入
// 真实网页 → 全部经 BrowserPort 派发（观察/探针/动作/截图）→ 回执逐一断言。
// 附两个负例：越白名单导航被端口拒（页面不动）、拒点词点击被升档回闸（页面不动）。
//
// 线程说明：AndroidJUnit4 的测试方法跑在 instrumentation 线程（非主线程）——
// 与 BrowserCommands 的「不许主线程调」纪律同规，直接调即可。
package com.example.zhengdao.patrol

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.zhengdao.MainActivity
import com.example.zhengdao.bridge.BrowserPort
import com.example.zhengdao.bridge.BrowserPort.PortAction
import com.example.zhengdao.bridge.BrowserPort.PortCall
import com.example.zhengdao.bridge.BrowserPort.PortCaller
import com.example.zhengdao.bridge.BrowserPort.PortPolicy
import com.example.zhengdao.bridge.ShotCapture
import com.example.zhengdao.baopu.TeachAction
import com.example.zhengdao.baopu.TeachLocator
import com.example.zhengdao.baopu.TeachLocatorKind
import com.example.zhengdao.baopu.TeachScripts
import com.example.zhengdao.ui.browser.BrowserTabManager
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowserPortOnDeviceTest {

    private var tabId: Int? = null
    private var activity: MainActivity? = null

    @Before
    fun setUp() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        BrowserPort.install(halt = { false }, ledger = { }, nowMs = { System.currentTimeMillis() })
        activity = inst.startActivitySync(
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        Thread.sleep(1_500)
        inst.runOnMainSync {
            tabId = BrowserTabManager.newTab(activity!!, PAGE_URL)
        }
        assertTrue("建不出玄览标签", tabId != null)
        Thread.sleep(LOAD_WAIT_MS)
    }

    @After
    fun tearDown() {
        val inst = InstrumentationRegistry.getInstrumentation()
        runCatching {
            inst.runOnMainSync {
                tabId?.let { id -> runCatching { BrowserTabManager.closeTab(id) } }
            }
        }
        BrowserPort.resetForTest()
    }

    @Test
    fun `端口五件套_真标签上全走通`() {
        val tab = tabId!!.toString()

        // ① READ：观察（snapshot 经端口，回执带节点数组）
        val snap = JSONObject(BrowserPort.snapshot(PortCaller.BAOPU_AGENT, "text", null, tab, PortPolicy.OPEN))
        assertTrue("snapshot 应成功：$snap", snap.optBoolean("ok"))
        assertTrue("回执应带节点", snap.has("nodes"))

        // ② READ：固定只读探针（ TeachScripts.CURRENT_URL）——端口 PROBE 路真执行
        val probe = JSONObject(BrowserPort.probe(PortCaller.TEACH_REPLAY, TeachScripts.CURRENT_URL, tab, PortPolicy.OPEN))
        assertTrue("probe 应成功：$probe", probe.optBoolean("ok"))
        val urlNow = JSONObject(probe.optString("result")).optString("u")
        assertTrue("当前页应在目标站：$urlNow", urlNow.contains("baidu"))

        // ③ LOCAL：填入未提交（typeChecked 经端口）。第三方页面的输入框锚会漂——
        // 先用只读探针在候选锚里找一个真存在的（观察→动作，harness 口径），再填；
        // 首页探不到就去结果页（其输入框锚稳定），再不行就退到示教式点击（同样证明 LOCAL 档）。
        val candidates = listOf(
            TeachLocator(TeachLocatorKind.ELEM_ID, "kw") to "#kw",
            TeachLocator(TeachLocatorKind.INPUT_NAME, "wd") to "[name=wd]",
            TeachLocator(TeachLocatorKind.INPUT_NAME, "q") to "[name=q]",
        )

        fun probeTarget(loc: TeachLocator, css: String): String? {
            val probeHit = JSONObject(
                BrowserPort.probe(PortCaller.TEACH_REPLAY, TeachScripts.scan(loc), tab, PortPolicy.OPEN),
            )
            val hits = runCatching {
                JSONObject(probeHit.optString("result")).optJSONArray("hits")
            }.getOrNull()
            return if (hits != null && hits.length() > 0) css else null
        }

        var target = candidates.firstNotNullOfOrNull { (loc, css) -> probeTarget(loc, css) }
        if (target == null) {
            // 结果页（www 与 m 两版都常见）的输入框锚再试一轮
            val nav = JSONObject(
                BrowserPort.navigate(PortCaller.BAOPU_AGENT, RESULTS_URL, tab, PortPolicy.OPEN),
            )
            assertTrue("结果页导航应放行成功：$nav", nav.optBoolean("ok"))
            Thread.sleep(LOAD_WAIT_MS)
            target = candidates.firstNotNullOfOrNull { (loc, css) -> probeTarget(loc, css) }
        }
        if (target != null) {
            val typed = JSONObject(BrowserPort.typeInto(PortCaller.BAOPU_AGENT, target, "证道", tab, PortPolicy.OPEN))
            assertTrue("type 应成功（目标 $target）：$typed", typed.optBoolean("ok"))
        } else {
            // 首页/结果页都没有可定位的输入框（第三方页面形态漂移）——退到示教式点击
            // （TEXT 档唯一命中才动手，LOCAL 档＋记账同样被证明）。
            val act = JSONObject(
                BrowserPort.act(
                    PortCaller.TEACH_REPLAY,
                    TeachScripts.act(TeachLocator(TeachLocatorKind.TEXT, "百度一下"), TeachAction.CLICK, null),
                    listOf("百度一下"),
                    tab,
                    PortPolicy.OPEN,
                ),
            )
            assertTrue("示教式点击应成功或明确拒绝（页面摘要见前一步），实际：$act", act.optBoolean("ok"))
        }

        // ④ READ：截图经端口落盘
        val inst = InstrumentationRegistry.getInstrumentation()
        val shot = BrowserPort.screenshotRef(
            PortCaller.BAOPU_AGENT, inst.targetContext, tab, "1", null, PortPolicy.OPEN,
        )
        assertTrue("截图应成功：${(shot as? ShotCapture.Bad)?.error}", shot is ShotCapture.Ok)
        assertTrue("截图应带宿主路径", (shot as ShotCapture.Ok).ref.hostPath.isNotBlank())
    }

    @Test
    fun `负例_越白名单导航被端口拒_页面不动`() {
        val tab = tabId!!.toString()
        val policy = PortPolicy(listOf("demo.example.com")) // 只授权 demo.example.com
        val r = BrowserPort.navigate(PortCaller.TEACH_REPLAY, "https://www.example.com/", tab, policy)
        val json = JSONObject(r)
        assertFalse("越白名单必须被拒", json.optBoolean("ok"))
        assertEquals("deny", json.optString("stop"))
        assertTrue(json.optString("error").contains("不在授权域"))
        // 页面没动：当前页仍是目标站
        val probe = JSONObject(BrowserPort.probe(PortCaller.TEACH_REPLAY, TeachScripts.CURRENT_URL, tab, PortPolicy.OPEN))
        val urlNow = JSONObject(probe.optString("result")).optString("u")
        assertTrue("页面不应被导航走：$urlNow", urlNow.contains("baidu"))
    }

    @Test
    fun `负例_拒点词点击被升档回闸_页面不动`() {
        val tab = tabId!!.toString()
        val r = BrowserPort.click(
            PortCaller.TEACH_REPLAY, "#kw", tab, listOf("提交订单"), PortPolicy.OPEN,
        )
        val json = JSONObject(r)
        assertFalse("外部不可逆点击必须回闸", json.optBoolean("ok"))
        assertEquals("escalate", json.optString("stop"))
        // 页面没动（语义面命中拒点词，执行层根本没被调）
        val probe = JSONObject(BrowserPort.probe(PortCaller.TEACH_REPLAY, TeachScripts.CURRENT_URL, tab, PortPolicy.OPEN))
        val urlNow = JSONObject(probe.optString("result")).optString("u")
        assertTrue("页面不应被点走：$urlNow", urlNow.contains("baidu"))
    }

    @Test
    fun `急停中_真标签上一个动作都不发`() {
        val tab = tabId!!.toString()
        BrowserPort.install(halt = { true }, ledger = { })
        val o = BrowserPort.dispatch(PortCall(caller = PortCaller.BAOPU_AGENT, action = PortAction.SNAPSHOT, tabId = tab))
        val stop = (o as BrowserPort.PortOutcome.Stopped).stop
        assertTrue(stop.reason.contains("急停"))
    }

    private companion object {
        const val PAGE_URL = "https://www.baidu.com"
        const val RESULTS_URL = "https://www.baidu.com/s?wd=%E8%AF%81%E9%81%93"
        const val LOAD_WAIT_MS = 6_000L
    }
}
