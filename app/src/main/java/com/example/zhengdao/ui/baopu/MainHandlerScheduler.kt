// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.ui.baopu

import android.os.Handler
import android.os.Looper
import com.example.zhengdao.baopu.voice.Cancellable
import com.example.zhengdao.baopu.voice.VoiceScheduler

/**
 * 第六百八十批 · 定时器端口的唯一生产实现。
 *
 * 挂主线程 Looper：超时回调要改 Compose 状态（「正在听」归零、失败文案落地），
 * 换别的线程会撞 snapshot 检查。会话层只认 [VoiceScheduler]，不认识 Handler。
 */
internal class MainHandlerScheduler(
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : VoiceScheduler {

    override fun post(delayMs: Long, task: () -> Unit): Cancellable {
        val runnable = Runnable { task() }
        handler.postDelayed(runnable, delayMs)
        return Cancellable { handler.removeCallbacks(runnable) }
    }
}
