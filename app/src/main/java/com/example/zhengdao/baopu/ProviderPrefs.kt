// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第九批追加（2026-10-17 刀 1 补丁）§1.1——provider 从硬编码挪到可改的地方，
// 选型 (b)（抱朴页「换通道」入口）；硬约束：API Key 不进日志、不进库、不进崩溃上报。
// 存储选 SharedPreferences（派工单 §5：先落 SP 就够，加密归设置体系）。
// 项目惯例：`zhengdao-*` 一个用途一个 SP 文件（AppearancePrefs / TerminalPrefs 同款）。
package com.example.zhengdao.baopu

import android.content.Context

/**
 * 抱朴通道选择（刀 1 补丁）——用户选了哪个通道、填了什么 Key 的**唯一真相**。
 *
 * ## 为什么判据抽成 Context-free 内核
 * 本仓无 Robolectric ⇒ SharedPreferences 在 JVM 单测里实例化不了（BaopuDb 同款教训）。
 * [resolve] / [fallbackFrom] 只吃字符串与 lambda，JVM 直测；薄壳只做 SP 读写。
 *
 * ## Key 的纪律
 * Key 只进 SP（App 私有目录），不进 RunLog、不进 baopu.db、不进崩溃上报。
 * 通道切换时引擎通知里只出现通道 id 与 label，永不出现 Key。
 */
internal object ProviderPrefs {

    private const val FILE = "zhengdao-baopu-provider"
    private const val KEY_CHANNEL = "channel_id"
    private const val KEY_CUSTOM_BASE = "custom_base_url"
    private const val KEY_CUSTOM_MODEL = "custom_model"

    /** 自定义通道（OpenAI 兼容形状；baseUrl/model/key 三件齐才可用）。 */
    const val CHANNEL_CUSTOM = "custom"

    private fun keyOf(id: String) = "user_key_$id"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 当前通道（BaopuRuntime.provider() 的实现细节都委托到这里）。 */
    fun current(context: Context): Provider = resolve(
        channelId = prefs(context).getString(KEY_CHANNEL, null),
        keyFor = { id -> prefs(context).getString(keyOf(id), null) },
        customBase = prefs(context).getString(KEY_CUSTOM_BASE, null),
        customModel = prefs(context).getString(KEY_CUSTOM_MODEL, null),
    )

    /** 当前通道 id（UI 弹层初始选中态用；不算 provider 解析，不算第二入口）。 */
    fun currentChannelId(context: Context): String =
        prefs(context).getString(KEY_CHANNEL, null) ?: Providers.BUILTIN_FREE.id

    /**
     * 只翻「当前通道」选择位（第六十四批安全点切换的落点）。
     *
     * 与 [save] 的分工：[save] 是设置页「启用此通道」（Key 与自定义三件一起写）；
     * 本方法**只写 KEY_CHANNEL**——Key / custom_base / custom_model 原样保留。
     * 解析后的校验与失败回滚由调用方（BaopuRuntime 装配层）负责：
     * 解析没落到目标 ⇒ 回滚到 oldId，ProviderPrefs 永远与引擎看到的一致。
     */
    fun selectChannel(context: Context, channelId: String) {
        prefs(context).edit().putString(KEY_CHANNEL, channelId).apply()
    }

    /** 某通道的用户 Key（BaopuRuntime 组装 channelFallback 闭包用）。 */
    fun storedKey(context: Context, id: String): String? = prefs(context).getString(keyOf(id), null)

    /** 自定义通道的 baseUrl / model（同上）。 */
    fun storedCustom(context: Context): Pair<String?, String?> = prefs(context).let {
        it.getString(KEY_CUSTOM_BASE, null) to it.getString(KEY_CUSTOM_MODEL, null)
    }

    /** 保存选择（UI「启用此通道」落点；保存即生效——调用方随后重建引擎）。 */
    fun save(
        context: Context,
        channelId: String,
        apiKey: String,
        baseUrl: String? = null,
        model: String? = null,
    ) {
        prefs(context).edit()
            .putString(KEY_CHANNEL, channelId)
            .putString(keyOf(channelId), apiKey)
            .putString(KEY_CUSTOM_BASE, baseUrl)
            .putString(KEY_CUSTOM_MODEL, model)
            .apply()
    }

    // ── Context-free 判据内核（JVM 直测） ─────────────────────────────────

    /**
     * 解析当前通道。
     * - 自定义通道：三件（base/model）齐 + URL 过得了许可判据 ⇒ 自定义 Provider；
     *   缺件或非法 ⇒ 回退内置免费通道（**不静默装好**——UI 保存时已拦截，这里是兜底）。
     * - 预设通道：填了 Key 就套上（[Providers.withUserKey]：只换 key，其余保留）。
     * - 认不出的 id（含 null/脏数据）⇒ 内置免费通道（第一次装起来就能点的默认）。
     */
    internal fun resolve(
        channelId: String?,
        keyFor: (String) -> String?,
        customBase: String? = null,
        customModel: String? = null,
    ): Provider {
        if (channelId == CHANNEL_CUSTOM) {
            val base = customBase?.trim().orEmpty()
            val model = customModel?.trim().orEmpty()
            val key = keyFor(CHANNEL_CUSTOM)?.trim().orEmpty()
            if (base.isNotEmpty() && model.isNotEmpty() && Provider.isPermittedBaseUrl(base)) {
                return runCatching {
                    Provider(
                        id = CHANNEL_CUSTOM,
                        label = "自定义通道",
                        protocol = LlmProtocol.OPENAI_COMPAT,
                        baseUrl = base,
                        model = model,
                        apiKey = key,
                    )
                }.getOrDefault(Providers.BUILTIN_FREE)
            }
            return Providers.BUILTIN_FREE
        }
        val chosen = Providers.of(channelId ?: "") ?: Providers.BUILTIN_FREE
        val userKey = keyFor(chosen.id)?.trim().orEmpty()
        return if (userKey.isEmpty()) chosen else Providers.withUserKey(chosen, userKey)
    }

    /**
     * 熔断后的下一个候选通道（纯判据）。
     *
     * 候选序 = Providers.all() 顺序里的预设，但**两条排除**：
     * - 排除当前通道（熔断的就是它，切回去等于没切）；
     * - 排除内置免费通道（2026-10-17 真机实测整条 403 RegionError，它是"没得选才用"的
     *   默认值，不是降级去向；切回它只会再吃一次 403）。
     * 预设必须已填 Key；都不行再看自定义通道（三件齐才可用）。
     * 全没有 ⇒ null（调用方走 ask_user，不抛异常）。
     */
    internal fun fallbackFrom(
        currentId: String,
        keyFor: (String) -> String?,
        customBase: String? = null,
        customModel: String? = null,
    ): Provider? {
        val preset = Providers.all().firstOrNull {
            it.id != currentId && !it.builtinFree && !keyFor(it.id).isNullOrBlank()
        }
        if (preset != null) {
            return Providers.withUserKey(preset, keyFor(preset.id)!!.trim())
        }
        if (currentId != CHANNEL_CUSTOM) {
            val p = resolve(CHANNEL_CUSTOM, keyFor, customBase, customModel)
            if (p.id == CHANNEL_CUSTOM) return p
        }
        return null
    }
}
