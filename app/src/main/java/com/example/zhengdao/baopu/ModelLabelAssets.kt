// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百四十三批：模型名表在 APK 里的读取壳。范式照 policy/PolicyAssets ——
// 这是本单唯一碰 Android 的文件，解析与判据全在 [ModelLabels]（零 Android、JVM 直测）。
//
// 没有写口、没有网络、没有缓存回写：表随包发布，改它必须重新发版。
// 读不到或读坏都**不抛**：退回空表 ⇒ 表外 ID 原样显示，绝不猜一个名字出来。
package com.example.zhengdao.baopu

import android.content.Context

internal object ModelLabelAssets {

    /** 读一次装进进程级单例（Composition 里同步调一次，不引入 State）。 */
    fun install(context: Context) {
        val text = runCatching {
            context.applicationContext.assets.open(ModelLabels.ASSET_PATH)
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull() ?: return
        runCatching { ModelLabels.install(ModelLabels.parse(text)) }
    }
}
