// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.baopu

/**
 * 视觉通道的**裁图判据**（第二十四批 A 段）——纯函数，零 IO、零 Android，JVM 直测。
 *
 * ## 为什么要有这个文件
 *
 * 附件能选之后，图片只落了工作区：`LlmMessage.content` 是纯字符串，图根本发不出去，
 * 消息里只能写一句「当前通道不能看图」。本批把图送进模型，但**必须先有一道闸**：
 *
 * 1. **没视觉能力就一张都不发**——把图硬塞给不认它的通道，轻则服务端 400，
 *    重则模型把 base64 当正文瞎编（比不发更糟）。
 * 2. **发了就得说明省了几张**——静默丢图是「看起来成功了其实没看见」，
 *    用户会以为模型看过图给出的结论，这是最危险的一种假成功。
 * 3. **大小与数量上限**：base64 会膨胀 4/3，一轮塞 10 张原图能把上下文窗口打穿，
 *    而且 token 费用按图算。
 *
 * ## 边界：本文件不读文件、不碰路径
 *
 * 「把本机文件读成 base64」是 IO，归调用方（薄壳）做。这里只收**已经读好的**
 * [ImagePayload]——它刻意**不含路径字段**：payload 一路走到请求体，任何一处带上
 * 本机绝对路径都会泄漏到模型侧（判据 V6）。路径只活在读文件那一刻。
 */
object VisionRules {

    /**
     * 单图上限（**原始文件字节**，不是 base64 长度）：12 MB。
     *
     * 🛑 2026-10-18 用户口径（她逐字：「deepseek APP，其他的 APP 都是可以直接发原图的」）：
     * **2 MB 这条线是我们自己拍的，不是接口的**。官方 App 发图从不让用户先压——
     * 手机照片 3–8 MB 是常态，卡在 2 MB 等于把"正常发一张照片"变成"必须缩"，
     * 既损画质又让用户觉得功能残废。
     * ⇒ 上限抬到 12 MB：这个量级原图照发（base64 膨胀 4/3 ⇒ 约 16 MB 请求体，手机网络能扛），
     * 只有超过 12 MB 的巨图才重编码一次（见 `VisionFit`）。
     */
    const val MAX_IMAGE_BYTES: Int = 12 * 1024 * 1024

    /** 一轮最多几张：4 张。 */
    const val MAX_IMAGES: Int = 4

    /**
     * 一轮合计原始字节上限：36 MB（＝3 张满额 12 MB）。
     *
     * 为什么要小于「4 × 单图上限」：否则这条闸永远打不到（每张都能过单图闸 ⇒ 总量必 ＜ 4×12），
     * 等于写了个永不触发的判据。36 MB 让「一次塞四张满额原图」被挡在第 4 张，理由也说得清。
     */
    const val MAX_TOTAL_BYTES: Int = 36 * 1024 * 1024

    /** 可发送形态的 data URL 前缀（OpenAI 与 Anthropic 都吃这个形状）。 */
    const val DATA_URL_PREFIX: String = "data:image/"

    /**
     * 一张**已读好**的图片。
     *
     * @param dataUrl `data:image/jpeg;base64,...`（**不含**本机路径——V6 的结构性保证：
     *   这个类型里根本没有能装路径的字段）
     * @param bytes 原始文件字节数（用于 2 MB / 6 MB 判据；不是 dataUrl 长度）
     */
    data class ImagePayload(val dataUrl: String, val bytes: Int)

    /**
     * 裁剪结果。
     *
     * [note] 是**要拼进消息文本末尾**的说明，空串 = 无需说明（全发了）。
     * 四类省略分开计数，是为了让说明能讲清「为什么不发」——只说「省略了 N 张」
     * 而不说原因，用户没法判断该不该换通道或压小图。
     */
    data class Selection(
        /** 真正要发出去的 data URL（顺序保持入参顺序）。 */
        val images: List<String> = emptyList(),
        /** 没视觉能力而一张没发时，被省略的张数。 */
        val omittedNoVision: Int = 0,
        /** 单张超 2 MB 被省。 */
        val omittedTooLarge: Int = 0,
        /** 超 4 张或超 6 MB 被省。 */
        val omittedOverLimit: Int = 0,
        /** 不是合法 data URL（或字节数不可用）被省——这一条同时兜住「误传路径」。 */
        val omittedInvalid: Int = 0,
        val note: String = "",
    ) {
        /** 一共省了几张（诊断/测试用）。 */
        val omitted: Int
            get() = omittedNoVision + omittedTooLarge + omittedOverLimit + omittedInvalid

        val hasImages: Boolean get() = images.isNotEmpty()
    }

    /**
     * 裁图（**唯一判据**）。
     *
     * @param payloads 本轮附件里**是图片**的那些（调用方已筛过类型；非图片文件不进这里）
     * @param visionSupported 当前通道有没有视觉能力——**必须来自 `Provider.vision`**
     *   （V5：组装层不许自己猜模型名）
     */
    fun select(payloads: List<ImagePayload>, visionSupported: Boolean): Selection {
        if (payloads.isEmpty()) return Selection()

        // 没有视觉能力：一张都不发，并且**明说**省了几张（V3，不许静默丢）。
        if (!visionSupported) {
            return Selection(
                images = emptyList(),
                omittedNoVision = payloads.size,
                note = "（当前通道不能看图，已省略 ${payloads.size} 张图片）",
            )
        }

        val picked = mutableListOf<String>()
        var total = 0
        var tooLarge = 0
        var overLimit = 0
        var invalid = 0

        for (p in payloads) {
            if (!isSendableDataUrl(p.dataUrl) || p.bytes <= 0) {
                invalid++
                continue
            }
            if (p.bytes > MAX_IMAGE_BYTES) {
                tooLarge++
                continue
            }
            if (picked.size >= MAX_IMAGES || total + p.bytes > MAX_TOTAL_BYTES) {
                overLimit++
                continue
            }
            picked += p.dataUrl
            total += p.bytes
        }

        return Selection(
            images = picked,
            omittedTooLarge = tooLarge,
            omittedOverLimit = overLimit,
            omittedInvalid = invalid,
            note = noteOf(tooLarge, overLimit, invalid),
        )
    }

    /** 省略说明（空串 = 全发了，不需要解释）。 */
    private fun noteOf(tooLarge: Int, overLimit: Int, invalid: Int): String {
        if (tooLarge == 0 && overLimit == 0 && invalid == 0) return ""
        val capMb = MAX_IMAGE_BYTES / 1024 / 1024
        val totalMb = MAX_TOTAL_BYTES / 1024 / 1024
        val reasons = mutableListOf<String>()
        if (tooLarge > 0) reasons += "$tooLarge 张超过单张 $capMb MB 上限"
        if (overLimit > 0) reasons += "$overLimit 张超出数量上限（最多 $MAX_IMAGES 张、合计 $totalMb MB）"
        if (invalid > 0) reasons += "$invalid 张无法读取"
        return "（已省略 ${tooLarge + overLimit + invalid} 张图片：" + reasons.joinToString("；") + "）"
    }

    /**
     * 能不能当图片块发出去（**V6 的第二道锁**）。
     *
     * 只认 `data:image/...;base64,` 形态。本机绝对路径、`file://`、裸 base64 一律不算
     * ——裸 base64 在 OpenAI 形状里也能用，但那样调用方就有机会把别的东西（比如路径
     * 字符串）塞进来，不如强制统一走 data URL。
     */
    fun isSendableDataUrl(url: String): Boolean =
        url.startsWith(DATA_URL_PREFIX) && url.contains(";base64,")

    /**
     * 把 data URL 拆成 `(media_type, base64)`——Anthropic 的图片块要分开给
     * （`{"type":"image","source":{"type":"base64","media_type":…,"data":…}}`）。
     *
     * 拆不出来返回 null（调用方按「不可发」跳过，不抛）。
     */
    fun splitDataUrl(url: String): Pair<String, String>? {
        if (!isSendableDataUrl(url)) return null
        val comma = url.indexOf(',')
        if (comma <= 0 || comma == url.length - 1) return null
        val head = url.substring(0, comma)          // data:image/jpeg;base64
        val data = url.substring(comma + 1)
        val mediaType = head.removePrefix("data:").substringBefore(';')
        if (mediaType.isEmpty()) return null
        return mediaType to data
    }
}
