// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百五十九批 · 「导出上一次」的可执行触发（UI 归 U 批，本批只给一个能真跑的口）。
//
// 为什么用一次性广播而不是设置页按钮：派工单 §一④「提供一个『导出上一次』的触发
// （可执行入口即可，UI 归 U 批）」。广播是当下唯一不需要动任何 Compose 就能在真机上
// 真跑一次的入口，且验收方一条 adb 命令就能复现：
//
//   adb shell am broadcast -a com.example.zhengdao.action.EXPORT_LAST_PROMPT \
//       -p com.example.zhengdao
//
// 安全边界（为什么 `android:permission="android.permission.DUMP"`）：
// · 这个口子会往 App 自己的 cache 目录写一份提示词副本。不设权限的话，任何第三方 App
//   都能反复戳它（虽然改不了提示词，但能让磁盘反复写、也能在 debug 包里把 system 段
//   刷成"最近一次"，干扰取证）。
// · `DUMP` 是 signature|privileged 级系统权限：普通 App 拿不到，`adb shell` 恰好持有
//   （dumpsys 要它）⇒ 「验收方能跑、别的 App 跑不了」。
// · 同一条规矩也意味着**App 自身**用 sendBroadcast 调不动它（自身也没有 DUMP）——
//   U 批要做按钮时请直接调 [PromptExport.exportLast]，不要绕广播。
package com.example.zhengdao.baopu.promptexport

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.zhengdao.core.RunLog

/** 「导出上一次」的一次性触发（清单里注册，DUMP 权限保护）。 */
class PromptExportReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_EXPORT_LAST) return
        val ctx = context.applicationContext
        // 开关关着就说明白话（release 缺省关，别让验收方以为"文件没出来＝坏了"）。
        if (!PromptExport.enabledNow(ctx)) {
            RunLog.log("提示词导出：开关关着，这次不导（SP ${PromptExport.SWITCH_FILE}/${PromptExport.SWITCH_KEY}）")
            return
        }
        val path = PromptExport.exportLast(ctx)
        RunLog.log(
            if (path == null) {
                "提示词导出：还没有捕获到任何一次请求（先跑一轮对话再来导）"
            } else {
                "提示词导出：已写 $path（连同 ${PromptExport.META_FILE}）"
            },
        )
    }

    companion object {
        /** 广播 action（与 AndroidManifest 里的 intent-filter 逐字一致）。 */
        const val ACTION_EXPORT_LAST: String = "com.example.zhengdao.action.EXPORT_LAST_PROMPT"
    }
}
