// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百九十七批：立绘夹具——**逐像素定义**的三张图（判据①的靶子）。
//
// 为什么不用真图：判据①要的是"主色/宽高比/半身全身判定**正确**"——只有逐像素造的图
// 才有唯一正确答案；真人图的"正确"是主观的。三张夹具的几何与 PortraitFeatures 的
// 采样带逐一对齐（对齐过程写在各 builder 的注释里），断言的是判据层的**文档化行为**。
//
// ImageIO 只活在测试 JVM；生产解码走 BitmapFactory（AndroidPortraitDecoder），本文件
// 提供的 ImageIoDecoder 只是把同一份判据接到"真 PNG 文件"上，证明管线（文件→解码→判据）通。
package com.example.zhengdao.baopu.portrait

import com.example.zhengdao.testkit.TempSandbox
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

internal object PortraitFixtures {

    /** 透明底半身像 256×384（判 2:3 · 半身 · 发紫/瞳绿/服蓝）。 */
    val HALF: PortraitPixels = halfBody()

    /** 透明底全身像 256×512（判 1:2 · 全身 · 发黑/瞳红/服绿）。 */
    val FULL: PortraitPixels = fullBody()

    /** 不透明底全身远景 384×256（判 3:2 · 全身 · 发棕/瞳红/服蓝；照片/JPG 一路）。 */
    val SCENE: PortraitPixels = scene()

    /** JVM 解码器：PNG 文件 → PortraitPixels（生产走 BitmapFactory，这条只进测试）。 */
    val IMAGE_IO_DECODER: PortraitDecoder = PortraitDecoder { file ->
        runCatching {
            val img = ImageIO.read(file) ?: return@PortraitDecoder null
            val w = img.width
            val h = img.height
            val px = IntArray(w * h)
            img.getRGB(0, 0, w, h, px, 0, w)
            // ImageIO 的 getRGB 出 ARGB（与 Bitmap.getPixels 同序同格式）
            PortraitPixels(w, h, px)
        }.getOrNull()
    }

    /** 把像素写成真 PNG（测试里经 TempSandbox 落盘，用完随沙盒删）。 */
    fun writePng(pixels: PortraitPixels, file: File) {
        val img = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, pixels.width, pixels.height, pixels.argb, 0, pixels.width)
        check(ImageIO.write(img, "png", file)) { "PNG 编码失败：${file.name}" }
    }

    private fun argb(r: Int, g: Int, b: Int, a: Int = 255): Int = (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun fill(p: IntArray, w: Int, x1: Int, y1: Int, x2: Int, y2: Int, color: Int) {
        for (y in y1..y2) for (x in x1..x2) p[y * w + x] = color
    }

    /**
     * 半身像（256×384，透明底）。几何⇄采样带对齐：
     * 内容盒=(8,45)-(248,383)；最大行宽 241（服饰 165..383）→ 肩线 y=165，
     * 头身比 120/339=0.354 ≥0.30 ⇒ 半身；瞳区带 [0.14,0.22]→y[92,119] 正好罩住虹膜 98..110；
     * 发带 [0.02,0.14]→y[51,92] 大头是紫发；服带 [0.55,0.80]→y[231,316] 全是蓝衣。
     */
    private fun halfBody(): PortraitPixels {
        val w = 256
        val h = 384
        val px = IntArray(w * h) // 默认全 0 = 全透明背景
        fill(px, w, 8, 165, 248, 383, argb(40, 60, 160)) // 服饰：深蓝
        fill(px, w, 113, 145, 143, 164, argb(250, 220, 190)) // 脖颈：肤色
        fill(px, w, 83, 45, 173, 144, argb(250, 220, 190)) // 头：肤色
        fill(px, w, 83, 45, 173, 85, argb(150, 60, 180)) // 头发：紫（盖在头顶）
        fill(px, w, 116, 96, 150, 112, argb(255, 255, 255)) // 眼白
        fill(px, w, 120, 98, 132, 110, argb(30, 180, 90)) // 左虹膜：绿
        fill(px, w, 134, 98, 146, 110, argb(30, 180, 90)) // 右虹膜：绿
        return PortraitPixels(w, h, px)
    }

    /**
     * 全身像（256×512，透明底）。内容盒=(40,20)-(216,505)；最大行宽 177（75..460）→
     * 肩线 y=75，头身比 55/486=0.113 ≤0.20 ⇒ 全身；瞳区带（全身档）[0.04,0.10]→y[39,68]
     * 罩住虹膜 44..54；发带 y[29,88] 里黑发 29..40 是最大桶；服带 y[287,408] 全绿衣。
     */
    private fun fullBody(): PortraitPixels {
        val w = 256
        val h = 512
        val px = IntArray(w * h)
        fill(px, w, 40, 75, 216, 460, argb(40, 160, 70)) // 上衣/裙：绿
        fill(px, w, 80, 460, 176, 505, argb(250, 220, 190)) // 腿：肤色（脚到 505，底边留 7px 透明）
        fill(px, w, 118, 50, 138, 74, argb(250, 220, 190)) // 脖颈
        fill(px, w, 108, 20, 148, 49, argb(250, 220, 190)) // 头
        fill(px, w, 108, 20, 148, 40, argb(20, 20, 20)) // 头发：黑
        fill(px, w, 112, 44, 144, 56, argb(255, 255, 255)) // 眼白
        fill(px, w, 116, 44, 126, 54, argb(200, 40, 40)) // 左虹膜：红
        fill(px, w, 130, 44, 140, 54, argb(200, 40, 40)) // 右虹膜：红
        return PortraitPixels(w, h, px)
    }

    /**
     * 远景全身（384×256，不透明草地底 ⇒ 走"底边主色"那一路）。人物：头 x180..196
     * y60..82（棕发 60..76＋肤色 77..82）、身 x162..222 y82..220（蓝衣）。
     * 最大行宽 61 → 肩线 y=82，头身比 22/160=0.1375 ⇒ 全身；瞳区带（全身档）y[66,76]×x[186,198]
     * 里虹膜 80px 是最大桶（发 41、草 22）；服带 y[148,188]×x[180,204] 全蓝。
     */
    private fun scene(): PortraitPixels {
        val w = 384
        val h = 256
        val grass = argb(60, 160, 70)
        val px = IntArray(w * h) { grass }
        fill(px, w, 162, 82, 222, 220, argb(40, 60, 160)) // 身：蓝衣
        fill(px, w, 180, 60, 196, 82, argb(110, 70, 40)) // 头：棕发（Q 版大头）
        fill(px, w, 180, 77, 196, 82, argb(250, 220, 190)) // 脸：肤色
        fill(px, w, 186, 67, 190, 74, argb(200, 40, 40)) // 左虹膜：红
        fill(px, w, 192, 67, 196, 74, argb(200, 40, 40)) // 右虹膜：红
        return PortraitPixels(w, h, px)
    }
}
