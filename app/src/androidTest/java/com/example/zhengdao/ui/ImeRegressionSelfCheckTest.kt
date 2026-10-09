// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的公开资料：Android instrumented test 官方文档、InputConnection 官方文档。
package com.example.zhengdao.ui

import android.content.Intent
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 输入回归页的**真机自检**（#2，2026-10-09）。
 *
 * 守的是「IME 组合输入丢字 / 重复上屏」这条高风险链（设计方案 v3 §3）：这里绕过系统输入法，
 * 按真实的 `InputConnection` 调用序列把 [ImeProbe.SCENARIOS] 三个场景各跑一遍
 * （组合输入 / 词中删字符后重输 / 快速连续输入多词），再断言终端缓冲里出现了期望的那一行。
 * 系统输入法自己的候选窗位置与联想时序不在自检范围（那要靠页面上三个手工场景卡 + 人眼）。
 *
 * 跑法（真机，别用 `connectedDebugAndroidTest`——AGP 跑完会卸载 App，清掉整个 proot 环境）：
 * ```
 * adb install -r app/build/outputs/apk/debug/zhengdao-2.0.7-debug.apk
 * adb install -r app/build/outputs/apk/androidTest/debug/zhengdao-2.0.7-debug-androidTest.apk
 * adb shell am instrument -w -e class com.example.zhengdao.ui.ImeRegressionSelfCheckTest \
 *   com.example.zhengdao.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
class ImeRegressionSelfCheckTest {

    @Test
    fun threeScenariosReachTerminalBuffer() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val intent = Intent(inst.targetContext, ImeRegressionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = inst.startActivitySync(intent) as ImeRegressionActivity

        // Compose 的 AndroidView 工厂是异步组合出来的：等终端把 cat 会话挂上（且网格非 0×0）再跑。
        var ready = false
        val readyDeadline = System.currentTimeMillis() + 8_000
        while (!ready && System.currentTimeMillis() < readyDeadline) {
            inst.runOnMainSync { ready = activity.isTerminalReady() }
            if (!ready) Thread.sleep(100)
        }
        assertTrue("回归页的终端会话没能挂上（AndroidView 工厂没跑或被无头环境跳过）", ready)

        // ⚠️ 别把等待塞进 runOnMainSync 里：终端的输出处理要回主线程，主线程一堵，PTY 回显就
        // 永远等不到（真机踩过：同一秒里直接写 PTY 都不回显，一放开主线程整行字立刻出现）。
        // 所以每个场景都是「主线程只发（beginScenario），测试线程轮询（pollScenario）」。
        ImeProbe.clear()
        for (scenario in ImeProbe.SCENARIOS) {
            inst.runOnMainSync { activity.beginScenario(scenario.id) }
            var got: ImeProbe.Verdict? = null
            val startedAt = System.currentTimeMillis()
            while (got == null && System.currentTimeMillis() - startedAt < 12_000) {
                Thread.sleep(120)
                inst.runOnMainSync { got = activity.pollScenario(scenario.id) }
            }
            val verdict = requireNotNull(got) {
                "场景「${scenario.title}」12 秒内没有结论（pollScenario 一直返回 null）"
            }
            Log.i(
                "ZD-IME-PROBE",
                "场景 ${scenario.id}（${scenario.title}）期望「${scenario.expected}」⇒ ${verdict.status} / " +
                    "${verdict.detail}（${System.currentTimeMillis() - startedAt} ms）",
            )
            assertEquals(
                "场景「${scenario.title}」应上屏「${scenario.expected}」，实际：${verdict.detail}",
                ImeProbe.Status.PASS,
                verdict.status,
            )
        }

        // 事件流也要留痕：composition 那一下必须被观察到（否则等于没走 IME 那条路）。
        val log = ImeProbe.snapshot()
        assertTrue(
            "事件日志里应有 setComposingText / commitText，实际：$log",
            log.any { it.contains("setComposingText") } && log.any { it.contains("commitText") },
        )
    }
}
