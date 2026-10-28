// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.browser.offscreen

import android.app.Activity
import android.os.Bundle
import java.io.File

/**
 * 263 批实验的**唯一**入口：debug 源集独有（release 合并清单里根本没有这个 activity），
 * 只能显式 `am start` 拉起——这就是工单说的"显式开关"的一半，另一半是
 * [OffscreenCaptureProbe] 运行期查 FLAG_DEBUGGABLE。无界面（半透明壳），
 * 跑完自动 finish，产物落 getExternalFilesDir("offscreen_probe")。
 */
class OffscreenProbeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dir = getExternalFilesDir("offscreen_probe")
            ?: File(filesDir, "offscreen_probe")
        val second = intent?.getStringExtra(EXTRA_SECOND_URL)?.takeIf { it.isNotBlank() }
        val probe = OffscreenCaptureProbe(
            this,
            dir,
            webConfig = ProbeWebConfig(javaScriptEnabled = true),
            secondUrl = second ?: OffscreenCaptureProbe.DEFAULT_SECOND_URL,
        )
        probe.run { finish() }
    }

    companion object {
        const val EXTRA_SECOND_URL = "secondUrl"
    }
}
