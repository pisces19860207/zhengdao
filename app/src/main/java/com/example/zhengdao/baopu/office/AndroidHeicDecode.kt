// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 依据的公开接口：Android `BitmapFactory`（API 28 起内置 HEIF 解码器）、
//   `Bitmap.compress(PNG)`（平台自带的 PNG 编码器）。
//
// 第六百四十二批（2026-11-14）· 系统 HEIC 解码器（判据③的平台那一半）。
//
// 为什么放在 app 层、不放进 office 包的纯函数里：
//   `android.graphics` 是平台件（JVM 单测里是桩，调用即抛）⇒ 用 [HeicToPng] 接口把它挡在
//   纯逻辑之外：`ImageEmbed` 只管"要不要转、转出来是不是 PNG"，这里只管"怎么转"。
//
// **不做 API 版本判断**：本 App 的 `minSdk` 是 36（Android 16），而 HEIF 解码器从 API 28
// 就有了 ⇒ `SDK_INT < 28` 这一支是死代码（lint 的 ObsoleteSdkInt 会点出来，642 已删）。
// ⚠️ 谁把 `minSdk` 降到 28 以下，必须把那条判断加回来，否则老机上会拿到 null 却说不清原因。
//
// 两条硬约束：
//   1. **解不出就回 null**（不抛异常）——上层把 null 说成"解不开"的一句人话；
//   2. **超大图先降采样**：4000×3000 的整图按 ARGB 要 48MB，手机解码 HEIC 时常直接 OOM。
//      docx 里图最宽也就 14cm（≈1654px @300dpi），降到 1200 万像素仍然绰绰有余。
package com.example.zhengdao.baopu.office

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

internal object AndroidHeicDecode : HeicToPng {

    /** 解码后允许的最大像素数（超过就按 2 的幂降采样；见文件头的第 2 条约束）。 */
    private const val MAX_PIXELS = 12_000_000

    override fun toPng(bytes: ByteArray): ByteArray? {
        val sample = sampleSize(bytes)
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }
            .getOrNull() ?: return null
        return try {
            val out = ByteArrayOutputStream()
            if (bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) out.toByteArray() else null
        } catch (_: OutOfMemoryError) {
            // 转 PNG 再吃一次内存：宁可这次失败（上层报"解不开"），也不让进程崩掉
            null
        } finally {
            bitmap.recycle()
        }
    }

    /** 只读尺寸（`inJustDecodeBounds`）→ 2 的幂降采样倍数（≥ 1）。 */
    private fun sampleSize(bytes: ByteArray): Int {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds) }
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return 1
        var sample = 1
        while ((w / sample).toLong() * (h / sample).toLong() > MAX_PIXELS) sample *= 2
        return sample
    }
}
