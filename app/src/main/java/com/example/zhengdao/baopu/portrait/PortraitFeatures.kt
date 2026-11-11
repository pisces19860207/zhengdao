// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百九十七批（2026-11-12）：立绘的**本地特征判据层**——分区域主色、宽高比、半身/全身。
//
// 单 597 判据①：只做本地特征，**不依赖任何模型**（零网络、零 ML、零 Android）；
// 主色复用 `image_ops` 的 [ImageOps.palette]（同一把尺子：量化 4bit＋桶内均值，同一张图
// 两次跑出一样的色，可断言）。
//
// 四条纪律：
// 1. **纯函数**：喂 [PortraitPixels] 出读数，零 IO、零时钟、零 Android——JVM 直测；
// 2. **判不出就如实说判不出**（extent=UNKNOWN／颜色为 null），不猜、不编；
// 3. 近似是**写明的近似**：区域几何是相对内容盒的固定比例带；extent 由头身比（脖颈线）
//    判定，extent 再决定瞳区带选哪条——夹具在测试里逐像素断言，真人图判错由
//    「草稿人批」兜底（单 597 判据③），本层不假装自己是识别模型；
// 4. **本层没有写卡路径**：[compare] 只产差异清单（判据④：一致性只报不改）。
package com.example.zhengdao.baopu.portrait

import com.example.zhengdao.baopu.agent.ImageOps
import com.example.zhengdao.baopu.agent.PaletteColor
import java.io.File
import kotlin.math.abs

/** 一张已解码的立绘：ARGB 像素（左上原点、行优先；与 [android.graphics.Bitmap.getPixels] 同序）。 */
class PortraitPixels(val width: Int, val height: Int, val argb: IntArray) {
    init {
        require(width > 0 && height > 0) { "尺寸非正：${width}x$height" }
        require(argb.size >= width * height) { "像素数组比 ${width}x$height 短" }
    }
}

/** 解码缝：生产实现用 BitmapFactory（Android），测试注入 ImageIO——本层零 Android。 */
fun interface PortraitDecoder {
    fun decode(file: File): PortraitPixels?
}

/** 半身/全身判定结论（UNKNOWN＝头身比落在两档之间，如实说判不出）。 */
enum class Extent(val label: String) { HALF("半身"), FULL("全身"), UNKNOWN("判不出") }

/** 内容盒：非背景像素的包围盒（原图像素，含端点）。 */
data class ContentBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left + 1
    val height: Int get() = bottom - top + 1
}

/** 一张立绘的本地特征读数（判据①的产出形状；internal＝主色类型跟着 ImageOps 走）。 */
internal data class PortraitRead(
    val width: Int,
    val height: Int,
    /** 约分后的宽高比，如 `2:3`。 */
    val aspect: String,
    val extent: Extent,
    /** 头高占内容盒高的比例（0–1；extent 的判据数字，落账带出去给人看）。 */
    val headFraction: Double,
    val box: ContentBox?,
    /** 发区/瞳区/服饰区主色（最多 3 个，share=占该区非透明像素比例）。 */
    val hair: List<PaletteColor>,
    val eyes: List<PaletteColor>,
    val clothing: List<PaletteColor>,
) {
    val hairColor: PaletteColor? get() = hair.firstOrNull()
    val eyeColor: PaletteColor? get() = eyes.firstOrNull()
    val clothingColor: PaletteColor? get() = clothing.firstOrNull()
}

/** 一条多图差异（判据④：只列差异，不给任何写卡动作）。 */
internal data class Difference(val kind: String, val detail: String)

internal object PortraitFeatures {

    /** 采样步长分母：长边 ≤256 时逐像素，更大的图按比例跳采样（结果仍确定）。 */
    private const val STRIDE_DIVISOR = 256

    /** 不透明背景判定：与底边主色的 RGB 差和超过它才算内容。 */
    private const val BG_TOLERANCE = 60

    /** alpha ≥ 这个值才算「画了东西」（半透明羽化边不算）。 */
    private const val CONTENT_ALPHA = 128

    /** 脖颈线：内容行宽达到最大行宽的这个比例 ⇒ 肩线（头/身分界）。 */
    private const val NECK_WIDTH_RATIO = 0.55

    /** 头身比 ≤ 此值 ⇒ 全身（真人比例约 1/7.5≈0.13；Q 版更小）。 */
    private const val FULL_HEAD_MAX = 0.20

    /** 头身比 ≥ 此值 ⇒ 半身（胸像头部约占 1/3 以上）。 */
    private const val HALF_HEAD_MIN = 0.30

    /** 瞳色挑选：带内主色饱和度 ≥ 此值才算「有明显的瞳色」（肤色/白色不算）。 */
    private const val EYE_MIN_SAT = 0.30

    /** 两条图的代表色 RGB 差和超过此值 ⇒ 记一条差异。 */
    private const val DIFF_RGB = 60

    /** 宽高比数值差相对比例超过此值 ⇒ 记「取景框不同」。 */
    private const val ASPECT_TOLERANCE = 0.10

    // ── 区域几何（相对内容盒的比例带；写明是近似，人批兜底）──────────────────

    /** 发区 x 带（两种取景共用）；y 带按 extent 换（半身头大→带可深；全身头小→带贴顶）。 */
    private const val HAIR_X1 = 0.40; private const val HAIR_X2 = 0.60
    private const val HAIR_HALF_Y1 = 0.02; private const val HAIR_HALF_Y2 = 0.14
    private const val HAIR_FULL_Y1 = 0.00; private const val HAIR_FULL_Y2 = 0.06
    private const val HAIR_UNKNOWN_Y1 = 0.02; private const val HAIR_UNKNOWN_Y2 = 0.10

    /** 瞳区 x 带（两种取景共用）；y 带按 extent 换（见 [eyeBand]）。 */
    private const val EYE_X1 = 0.40; private const val EYE_X2 = 0.60
    private const val EYE_FULL_Y1 = 0.04; private const val EYE_FULL_Y2 = 0.10
    private const val EYE_HALF_Y1 = 0.14; private const val EYE_HALF_Y2 = 0.22

    /** 服饰区：上胸带（半身是主装、全身是上衣，都取得到）。 */
    private const val CLOTH_X1 = 0.30; private const val CLOTH_X2 = 0.70
    private const val CLOTH_Y1 = 0.55; private const val CLOTH_Y2 = 0.80

    /** 判据①主入口：一张图 → 一份本地特征读数；纯背景/无内容 ⇒ null（如实说判不出）。 */
    fun read(p: PortraitPixels): PortraitRead? {
        val box = contentBox(p) ?: return null
        val (extent, headFraction) = extentOf(p, box)
        val (hairY1, hairY2) = when (extent) {
            Extent.HALF -> HAIR_HALF_Y1 to HAIR_HALF_Y2
            Extent.FULL -> HAIR_FULL_Y1 to HAIR_FULL_Y2
            Extent.UNKNOWN -> HAIR_UNKNOWN_Y1 to HAIR_UNKNOWN_Y2
        }
        return PortraitRead(
            width = p.width,
            height = p.height,
            aspect = aspectOf(p.width, p.height),
            extent = extent,
            headFraction = headFraction,
            box = box,
            hair = regionColors(p, box, HAIR_X1, hairY1, HAIR_X2, hairY2),
            eyes = eyeColors(p, box, extent),
            clothing = regionColors(p, box, CLOTH_X1, CLOTH_Y1, CLOTH_X2, CLOTH_Y2),
        )
    }

    // ── 内容盒与背景 ────────────────────────────────────────────────────────

    /**
     * 内容盒：立绘 PNG 多为透明底（抠图，人物常裁到画布底边）⇒ **全图透明占比 ≥15%**
     * 就按透明底判（任何非透明像素都是内容）；不透明图（照片/JPG）才取底边主色，
     * 色差和超过 [BG_TOLERANCE] 的算内容。全空 ⇒ null。
     */
    fun contentBox(p: PortraitPixels): ContentBox? {
        val bg = backgroundColor(p) ?: return null
        val step = strideOf(p)
        var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = -1; var b = -1
        var y = 0
        while (y < p.height) {
            var x = 0
            while (x < p.width) {
                if (isContent(p.argb[y * p.width + x], bg)) {
                    if (x < l) l = x
                    if (x > r) r = x
                    if (y < t) t = y
                    if (y > b) b = y
                }
                x += step
            }
            y += step
        }
        return if (r < 0) null else ContentBox(l, t, r, b)
    }

    /** 采样步长：长边 ≤256 逐像素；更大的图 step=短边/256（至少 1）。 */
    private fun strideOf(p: PortraitPixels): Int {
        val shorter = minOf(p.width, p.height)
        return maxOf(1, shorter / STRIDE_DIVISOR)
    }

    private fun isContent(px: Int, bg: Bg): Boolean {
        if ((px ushr 24) and 0xFF < CONTENT_ALPHA) return false
        return when (bg) {
            is Bg.Transparent -> true
            is Bg.Color -> {
                val d = abs(((px ushr 16) and 0xFF) - bg.r) +
                    abs(((px ushr 8) and 0xFF) - bg.g) +
                    abs((px and 0xFF) - bg.b)
                d > BG_TOLERANCE
            }
        }
    }

    /** 背景判定：全图采样，透明占比 ≥15% ⇒ 透明底；否则底边主色（照片/JPG）。 */
    private fun backgroundColor(p: PortraitPixels): Bg? {
        val step = strideOf(p)
        var transparent = 0
        var total = 0
        var y = 0
        while (y < p.height) {
            var x = 0
            while (x < p.width) {
                total++
                if ((p.argb[y * p.width + x] ushr 24) and 0xFF < CONTENT_ALPHA) transparent++
                x += step
            }
            y += step
        }
        if (total == 0) return null
        if (transparent * 100 >= total * 15) return Bg.Transparent
        return bottomStripColor(p, step)
    }

    /** 底边 5%（至少 4 行）不透明像素的主桶均值色（天空/墙面/地面这类大色块）。 */
    private fun bottomStripColor(p: PortraitPixels, step: Int): Bg? {
        val rows = maxOf(4, p.height / 20)
        val sums = HashMap<Int, LongArray>(16)
        var y = (p.height - rows).coerceAtLeast(0)
        while (y < p.height) {
            var x = 0
            while (x < p.width) {
                val px = p.argb[y * p.width + x]
                if ((px ushr 24) and 0xFF >= CONTENT_ALPHA) {
                    val key = bucket((px ushr 16) and 0xFF, (px ushr 8) and 0xFF, px and 0xFF)
                    val acc = sums.getOrPut(key) { LongArray(4) }
                    acc[0] += (px ushr 16) and 0xFF; acc[1] += (px ushr 8) and 0xFF
                    acc[2] += px and 0xFF; acc[3] += 1
                }
                x += step
            }
            y++
        }
        if (sums.isEmpty()) return null
        return modalMean(sums)
    }

    private fun bucket(r: Int, g: Int, b: Int): Int = ((r ushr 4) shl 8) or ((g ushr 4) shl 4) or (b ushr 4)

    /** 主桶（占比最高的 4bit 桶）的均值色——比「取最后一行像素」稳（羽化/描边不吃判据）。 */
    private fun modalMean(sums: HashMap<Int, LongArray>): Bg.Color {
        val best = sums.values.maxByOrNull { it[3] } ?: LongArray(4)
        val n = maxOf(1L, best[3])
        return Bg.Color(
            r = (best[0] / n).toInt().coerceIn(0, 255),
            g = (best[1] / n).toInt().coerceIn(0, 255),
            b = (best[2] / n).toInt().coerceIn(0, 255),
        )
    }

    private sealed interface Bg {
        object Transparent : Bg
        data class Color(val r: Int, val g: Int, val b: Int) : Bg
    }

    // ── 半身/全身：头身比（脖颈线）────────────────────────────────────────

    /**
     * 头身比：内容盒从顶往下第一行「行宽 ≥ 55% 最大行宽」的行是肩线（脖子）；
     * 头高占比 ≤0.20 ⇒ 全身、≥0.30 ⇒ 半身，中间 ⇒ 判不出（不硬猜）。
     */
    fun extentOf(p: PortraitPixels, box: ContentBox): Pair<Extent, Double> {
        val step = strideOf(p)
        val bg = backgroundColor(p) ?: Bg.Transparent
        val maxWidth = maxRowWidth(p, box, step, bg)
        if (maxWidth <= 0) return Extent.UNKNOWN to 1.0
        val threshold = maxWidth * NECK_WIDTH_RATIO
        var y = box.top
        while (y <= box.bottom) {
            if (rowWidth(p, y, box, step, bg) >= threshold) {
                val headFraction = (y - box.top).toDouble() / box.height.toDouble()
                val extent = when {
                    headFraction <= FULL_HEAD_MAX -> Extent.FULL
                    headFraction >= HALF_HEAD_MIN -> Extent.HALF
                    else -> Extent.UNKNOWN
                }
                return extent to headFraction
            }
            y += step
        }
        return Extent.UNKNOWN to 1.0
    }

    private fun maxRowWidth(p: PortraitPixels, box: ContentBox, step: Int, bg: Bg): Int {
        var max = 0
        var y = box.top
        while (y <= box.bottom) {
            val w = rowWidth(p, y, box, step, bg)
            if (w > max) max = w
            y += step
        }
        return max
    }

    /** 一行的内容跨度（首个到最后一个内容像素；采样步长同全局）。 */
    private fun rowWidth(p: PortraitPixels, y: Int, box: ContentBox, step: Int, bg: Bg): Int {
        var first = -1; var last = -1
        var x = box.left
        while (x <= box.right) {
            if (isContent(p.argb[y * p.width + x], bg)) {
                if (first < 0) first = x
                last = x
            }
            x += step
        }
        return if (first < 0) 0 else last - first + 1
    }

    // ── 主色与宽高比 ────────────────────────────────────────────────────────

    /** 区域主色：比例带 → 像素（带步长）→ [ImageOps.palette]（同一把尺子）。 */
    private fun regionColors(
        p: PortraitPixels, box: ContentBox,
        fx1: Double, fy1: Double, fx2: Double, fy2: Double,
    ): List<PaletteColor> = ImageOps.palette(bandPixels(p, box, fx1, fy1, fx2, fy2), 3)

    /** 比例带内的像素（透明的不收；ImageOps.palette 自己也会跳，这里先收窄省内存）。 */
    private fun bandPixels(
        p: PortraitPixels, box: ContentBox,
        fx1: Double, fy1: Double, fx2: Double, fy2: Double,
    ): IntArray {
        val x1 = (box.left + box.width * fx1).toInt().coerceIn(0, p.width - 1)
        val x2 = (box.left + box.width * fx2).toInt().coerceIn(x1, p.width - 1)
        val y1 = (box.top + box.height * fy1).toInt().coerceIn(0, p.height - 1)
        val y2 = (box.top + box.height * fy2).toInt().coerceIn(y1, p.height - 1)
        val step = strideOf(p)
        val out = ArrayList<Int>((x2 - x1 + 1) * (y2 - y1 + 1) / maxOf(1, step * step) + 1)
        var y = y1
        while (y <= y2) {
            var x = x1
            while (x <= x2) {
                val px = p.argb[y * p.width + x]
                if ((px ushr 24) and 0xFF >= CONTENT_ALPHA) out.add(px)
                x += step
            }
            y += step
        }
        return out.toIntArray()
    }

    /**
     * 瞳区：y 带由 extent 定（全身眼睛贴近头顶 [EYE_FULL_Y1,EYE_FULL_Y2]；
     * 半身眼睛在 [EYE_HALF_Y1,EYE_HALF_Y2]）；判不出 ⇒ 两条带里**平均饱和度高**的那条
     * （虹膜比皮肤鲜），等高取前者。瞳色=带内首个饱和度 ≥[EYE_MIN_SAT] 的主色；
     * 找不到就回第一主色（如实的"这带里最像的颜色"），由人批兜底。
     */
    private fun eyeColors(p: PortraitPixels, box: ContentBox, extent: Extent): List<PaletteColor> {
        val full = bandColors(p, box, EYE_FULL_Y1, EYE_FULL_Y2)
        val half = bandColors(p, box, EYE_HALF_Y1, EYE_HALF_Y2)
        val chosen = when (extent) {
            Extent.FULL -> full
            Extent.HALF -> half
            Extent.UNKNOWN -> if (meanSaturation(half) > meanSaturation(full)) half else full
        }
        val saturated = chosen.firstOrNull { saturationOf(it.hex) >= EYE_MIN_SAT }
        return if (saturated != null) listOf(saturated) + chosen.filter { it != saturated }.take(2)
        else chosen
    }

    private fun bandColors(p: PortraitPixels, box: ContentBox, fy1: Double, fy2: Double): List<PaletteColor> =
        regionColors(p, box, EYE_X1, fy1, EYE_X2, fy2)

    fun aspectOf(width: Int, height: Int): String {
        if (width <= 0 || height <= 0) return "判不出"
        val g = gcd(width, height)
        return "${width / g}:${height / g}"
    }

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    // ── 多图一致性（判据④：只列差异）────────────────────────────────────

    /**
     * 两条读数的差异清单（顺序固定：发色/瞳色/取景/宽高比）。
     * 一方判不出（null）不算差异——「不知道」不该冒充「不同」。
     */
    fun compare(a: PortraitRead, b: PortraitRead): List<Difference> = buildList {
        colorDiff(a.hairColor, b.hairColor, "发色")?.let { add(it) }
        colorDiff(a.eyeColor, b.eyeColor, "瞳色")?.let { add(it) }
        if (a.extent != b.extent) add(Difference("取景", "${a.extent.label}↔${b.extent.label}"))
        aspectDiff(a.aspect, b.aspect)?.let { add(Difference("宽高比", it)) }
    }

    private fun colorDiff(x: PaletteColor?, y: PaletteColor?, kind: String): Difference? {
        if (x == null || y == null) return null
        val d = rgbDistance(x.hex, y.hex)
        return if (d > DIFF_RGB) Difference(kind, "${x.hex}↔${y.hex}") else null
    }

    private fun aspectDiff(a: String, b: String): String? {
        val ra = ratioOf(a) ?: return null
        val rb = ratioOf(b) ?: return null
        val bigger = maxOf(ra, rb)
        return if (bigger > 0 && (bigger - minOf(ra, rb)) / bigger > ASPECT_TOLERANCE) "$a↔$b" else null
    }

    private fun ratioOf(aspect: String): Double? {
        val parts = aspect.split(":")
        if (parts.size != 2) return null
        val w = parts[0].toIntOrNull() ?: return null
        val h = parts[1].toIntOrNull() ?: return null
        if (w <= 0 || h <= 0) return null
        return w.toDouble() / h.toDouble()
    }

    // ── 颜色小工具（hex ↔ 饱和度/距离；只服务本层判据）────────────────────

    /** 饱和度 HSV.S=(max-min)/max（max=0 视为 0）。hex 形如 `#RRGGBB`。 */
    fun saturationOf(hex: String): Double {
        val v = hex.removePrefix("#").toLongOrNull(16) ?: return 0.0
        val r = ((v shr 16) and 0xFF).toDouble()
        val g = ((v shr 8) and 0xFF).toDouble()
        val b = (v and 0xFF).toDouble()
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        return if (max <= 0.0) 0.0 else (max - min) / max
    }

    private fun rgbDistance(a: String, b: String): Int {
        val va = a.removePrefix("#").toLongOrNull(16) ?: return 0
        val vb = b.removePrefix("#").toLongOrNull(16) ?: return 0
        val dr = abs(((va shr 16) and 0xFF) - ((vb shr 16) and 0xFF))
        val dg = abs(((va shr 8) and 0xFF) - ((vb shr 8) and 0xFF))
        val db = abs((va and 0xFF) - (vb and 0xFF))
        return (dr + dg + db).toInt()
    }

    /** 带内平均饱和度（按 share 加权）——UNKNOWN 时选瞳区带用。 */
    private fun meanSaturation(colors: List<PaletteColor>): Double =
        colors.sumOf { it.share * saturationOf(it.hex) }
}
