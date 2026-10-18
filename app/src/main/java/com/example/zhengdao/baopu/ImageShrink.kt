// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第二十四批 C 段追加 · 把超限的大图**缩进上限**（真解码 + 重编码，要 Android Bitmap）。
//
// 判据在 `VisionFit`（纯函数），本文件只做三件事：解码 → 等比缩到目标长边 → 按质量阶梯压 JPEG。
// 三件都失败就**如实回一个原因**（解不开 / 压了还超限），由调用方写进本轮输入的说明里——
// 静默失败正是这一批要修的病（用户发 3 MB 照片，模型连"没发出去"都不知道）。
package com.example.zhengdao.baopu

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File

object ImageShrink {

    /**
     * 把 [file] 缩成 ≤ [VisionFit.Plan.capBytes] 的 JPEG 字节。
     *
     * 只用于**已经超限**的图（`VisionFit.planFor` 返回非 null 时）；正常尺寸的图不要过这里，
     * 重编码是信息损失，没必要。
     */
    fun toJpeg(
        file: File,
        plan: VisionFit.Plan = VisionFit.Plan(
            VisionFit.MAX_EDGE,
            VisionFit.QUALITY_LADDER,
            VisionRules.MAX_IMAGE_BYTES,
        ),
    ): VisionFiles.ShrinkOutcome {
        // 只读bounds：不解码像素就能拿到原始宽高（50 MB 的图也不会因此爆内存）
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(file.absolutePath, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return VisionFiles.ShrinkOutcome.Failed("解不出尺寸（可能不是图片或已损坏）")
        }
        val sample = VisionFit.sampleSizeFor(bounds.outWidth, bounds.outHeight, plan.maxEdge)
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = runCatching { BitmapFactory.decodeFile(file.absolutePath, options) }.getOrNull()
            ?: return VisionFiles.ShrinkOutcome.Failed("解码失败")
        var bmp: Bitmap = decoded
        try {
            val (w, h) = VisionFit.scaledSize(bmp.width, bmp.height, plan.maxEdge)
            if (w != bmp.width || h != bmp.height) {
                val scaled = runCatching { Bitmap.createScaledBitmap(bmp, w, h, true) }.getOrNull()
                if (scaled != null && scaled !== bmp) {
                    bmp.recycle()
                    bmp = scaled
                }
            }
            for (q in plan.qualities) {
                val out = ByteArrayOutputStream()
                val ok = runCatching { bmp.compress(Bitmap.CompressFormat.JPEG, q, out) }.getOrDefault(false)
                if (!ok) continue
                val bytes = out.toByteArray()
                if (bytes.size <= plan.capBytes) return VisionFiles.ShrinkOutcome.Ok(bytes)
            }
            // 阶梯试到底还是超限：不许假装成功（更不许发出去赌服务端收）
            return VisionFiles.ShrinkOutcome.Failed(
                "缩到长边 ${plan.maxEdge} px、质量试到 ${plan.qualities.last()} 仍超" +
                    "${plan.capBytes / 1024 / 1024} MB",
            )
        } finally {
            runCatching { bmp.recycle() }
        }
    }
}
