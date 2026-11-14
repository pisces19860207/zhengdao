// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 依据的公开标准：ECMA-376（docx 只认 PNG/JPEG/WebP 这几类位图）、
//   ISO/IEC 23008-12（HEIF 容器）、RIFF/WebP（Google 公开容器文档）。
//
// 第六百四十二批（2026-11-14）· docx 插图的**入包前处理**（判据③）。
//
// 为什么要有这一层（而不是在工具里就地 if/else）：
//   · webp **能直接进 docx**（字节原样、`image/webp`），不需要解码；
//   · heic **不能直接进**——Word/WPS 打不开 HEIF 位流，必须先解成 PNG 再嵌；
//   · 解码器要 platform 的 BitmapFactory（Android 专有）⇒ 用 [HeicToPng] 把平台件挡在外面，
//     这一层就是纯函数，JVM 单测能喂假解码器、断言"最后进包的确实是 PNG"。
package com.example.zhengdao.baopu.office

/**
 * HEIC → PNG 的解码口（实现方在 Android 侧用 `BitmapFactory`；JVM 测试给假的）。
 *
 * 契约：解码不出来就回 `null`（**不抛异常**）——上层要把"解不开"说成一句人话。
 */
internal fun interface HeicToPng {
    fun toPng(bytes: ByteArray): ByteArray?
}

/** 入包前的一张图：字节 ＋ 探测到的类型/尺寸（[OfficeImage] 的装配口）。 */
internal object ImageEmbed {

    /**
     * 把"盘上那张图"变成"能进 docx 的那张图"。
     *
     * 四条出路（每条都给一句人话，**绝不静默降级**）：
     * 1. PNG / JPEG / WebP ⇒ 原字节入包（扩展名与 MIME 照实写）；
     * 2. HEIC ⇒ 用 [decoder] 解成 PNG，**并核对结果真的是 PNG**（判据③"嵌入的是 PNG"）；
     * 3. 没有解码器（Android 9 以下没 HEIC 支持）⇒ 明确说设备解不了、请先转格式；
     * 4. 别的格式（GIF/BMP/TIFF/AVIF…）⇒ 明确说只认四种，不硬塞。
     */
    fun prepare(raw: ByteArray, decoder: HeicToPng?): Step<OfficeImage> =
        when (ImageProbe.kindOf(raw)) {
            ImageKind.PNG, ImageKind.JPEG, ImageKind.WEBP -> {
                val info = ImageProbe.probe(raw)
                    ?: return Step.Bad(
                        "这张 ${ImageProbe.kindOf(raw).name.lowercase()} 图的宽高读不出来（文件头不完整？）；" +
                            "请换一张，或先转成 PNG",
                    )
                Step.Ok(OfficeImage(raw, info))
            }
            ImageKind.HEIC -> heicOf(raw, decoder)
            ImageKind.OTHER -> Step.Bad(
                "只支持 PNG / JPEG / WebP / HEIC 四种图片（这张都不是）；" +
                    "GIF、BMP、TIFF 这类请先转成 PNG 再插",
            )
        }

    /** HEIC：先解码，再**验产物是 PNG**（不是 PNG 就当场失败，别把错图塞进文档）。 */
    private fun heicOf(raw: ByteArray, decoder: HeicToPng?): Step<OfficeImage> {
        val dec = decoder ?: return Step.Bad(
            "没有可用的 HEIC 解码器（这次调用没把系统解码器接进来）；" +
                "请先把这张图转成 PNG/JPEG 再插",
        )
        val png = dec.toPng(raw) ?: return Step.Bad(
            "HEIC 解不开（文件损坏，或者其实是别的格式改了后缀）；请换一张，或先转成 PNG",
        )
        val info = ImageProbe.probe(png)
            ?: return Step.Bad("HEIC 解不开：转换出来的不是一张完整图片（读不出宽高）；请先手动转成 PNG")
        if (info.ext != "png") {
            return Step.Bad(
                "HEIC 转出来的不是 PNG（是 ${info.ext}）——docx 里 HEIC 必须换成 PNG，" +
                    "这次不落盘，避免生成一份 Word 打不开的文档",
            )
        }
        return Step.Ok(OfficeImage(png, info))
    }
}
