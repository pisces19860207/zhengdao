// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第九批追加（2026-10-17 刀 1 补丁）§1.1——provider 从硬编码挪到可改的地方，
// 选型 (b)（抱朴页「换通道」入口）；硬约束：API Key 不进日志、不进库、不进崩溃上报。
// 存储选 SharedPreferences（派工单 §5：先落 SP 就够，加密归设置体系）。
// 项目惯例：`zhengdao-*` 一个用途一个 SP 文件（AppearancePrefs / TerminalPrefs 同款）。
package com.example.zhengdao.baopu

import android.content.Context
import androidx.core.content.edit

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
    // 第九十三批 · 模型路由：AUTO_ROUTE 是**显式开关**，默认关（派工单 §2「不默认改变当前通道」）。
    // 关着的时候本文件行为与开关存在之前逐字节一致，见 routeCandidates / fallbackFrom 的注释。
    private const val KEY_AUTO_ROUTE = "auto_route"
    private const val KEY_ROUTE_PREFERENCE = "route_preference"

    /** 自定义通道（OpenAI 兼容形状；baseUrl/model/key 三件齐才可用）。 */
    const val CHANNEL_CUSTOM = "custom"

    private fun keyOf(id: String) = "user_key_$id"

    // 第一百三十一批 ②：一条通道从「一把 Key」变成「一池 Key」。
    // 新增两格：`user_keys_$id`（池子本体，JSON）＋ `user_key_cursor_$id`（轮询走到哪一把）。
    // 老格 `user_key_$id` **保留只读**做迁移（见 KeyPool.migrate），首次写池子时清掉，
    // 免得"一格一池"两份真相同时存在。
    private fun keysOf(id: String) = "user_keys_$id"
    private fun cursorOf(id: String) = "user_key_cursor_$id"

    // 第一百三十一批 ③：预设也能选模型 ⇒ 每个提供商名下存一格"用户选定的模型"（连同那一刻的
    // 元数据）。**自定义不用这格**——它的模型名早就存在 `custom_model` 里，再存一份就是第二真相。
    private fun modelOf(id: String) = "user_model_$id"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 当前通道（BaopuRuntime.provider() 的实现细节都委托到这里）。 */
    fun current(context: Context): Provider = resolve(
        channelId = prefs(context).getString(KEY_CHANNEL, null),
        keyFor = { id -> storedKey(context, id) },
        customBase = prefs(context).getString(KEY_CUSTOM_BASE, null),
        customModel = prefs(context).getString(KEY_CUSTOM_MODEL, null),
        presetModelFor = { id -> modelOverride(context, id) },
    )

    /**
     * 某个**预设**被用户选定的模型（第一百三十一批 ③；自定义不走这里，见 [storedCustom]）。
     * 没选过 / 存坏了 ⇒ null（照预设自带的模型跑，**不猜**）。
     */
    fun modelOverride(context: Context, id: String): ModelPickerRules.ModelOverride? =
        ModelPickerRules.decodeOverride(prefs(context).getString(modelOf(id), null))

    /**
     * 写下用户在预设上选定的模型（连同那一刻从清单里拿到的元数据）。
     *
     * 传 null 或空 id = **删掉这一格**（"恢复预设默认模型"）；不许传空串假装改好了。
     */
    fun saveModelOverride(
        context: Context,
        id: String,
        override: ModelPickerRules.ModelOverride?,
    ) {
        val text = ModelPickerRules.encodeOverride(override)
        prefs(context).edit {
            if (text.isEmpty()) remove(modelOf(id)) else putString(modelOf(id), text)
        }
    }

    /** 当前通道 id（UI 弹层初始选中态用；不算 provider 解析，不算第二入口）。 */
    fun currentChannelId(context: Context): String =
        prefs(context).getString(KEY_CHANNEL, null) ?: Providers.OPENROUTER_FREE.id

    /**
     * 只翻「当前通道」选择位（第六十四批安全点切换的落点）。
     *
     * 与 [save] 的分工：[save] 是设置页「启用此通道」（Key 与自定义三件一起写）；
     * 本方法**只写 KEY_CHANNEL**——Key / custom_base / custom_model 原样保留。
     * 解析后的校验与失败回滚由调用方（BaopuRuntime 装配层）负责：
     * 解析没落到目标 ⇒ 回滚到 oldId，ProviderPrefs 永远与引擎看到的一致。
     */
    fun selectChannel(context: Context, channelId: String) {
        prefs(context).edit { putString(KEY_CHANNEL, channelId) }
    }

    /**
     * 某通道**此刻该带出去的那把** Key（BaopuRuntime 组装候选闭包、UI 回填都用它）。
     *
     * 第一百三十一批 ②：一格变一池——池子非空时从 `cursor` 起取第一把「没禁用、没在冷却」的；
     * 池子空但老格 `user_key_$id` 有货 ⇒ 照旧给老格（迁移前的单 Key 形态，行为不变）。
     * 整池都在冷却／禁用 ⇒ **null**：调用方给可读提示，不许静默退回第一把硬试（派工单 ②）。
     */
    fun storedKey(context: Context, id: String): String? {
        val p = prefs(context)
        val pool = KeyPool.migrate(p.getString(keysOf(id), null), p.getString(keyOf(id), null))
        if (pool.isEmpty()) return null
        return KeyPool.activeKey(pool, p.getInt(cursorOf(id), 0), System.currentTimeMillis())
    }

    /** 某通道的 Key 池（选模型抽屉列 Key 用；给的是原文，**界面自己 [KeyPool.mask]**）。 */
    fun keyPool(context: Context, id: String): List<KeyPool.Entry> {
        val p = prefs(context)
        return KeyPool.migrate(p.getString(keysOf(id), null), p.getString(keyOf(id), null))
    }

    /** 轮询走到哪一把（0 = 从头）。 */
    fun keyCursor(context: Context, id: String): Int = prefs(context).getInt(cursorOf(id), 0)

    /**
     * 写池子：写新格、**清掉老格**（一格一池两份真相就是漂移的开始），并把游标夹回范围内。
     * 空池 ⇒ 新格写 `"[]"`（"删光了"与"从来没填过"在读取端仍是两件事，见 KeyPool.encode）。
     */
    fun saveKeyPool(context: Context, id: String, entries: List<KeyPool.Entry>, cursor: Int = 0) {
        val p = prefs(context)
        val kept = entries.take(KeyPool.MAX_KEYS)
        p.edit {
            putString(keysOf(id), KeyPool.encode(kept))
            putInt(cursorOf(id), if (kept.isEmpty()) 0 else cursor.mod(kept.size))
            remove(keyOf(id))
        }
    }

    /**
     * 加一把 Key（② 用户自管；App 不自动创建/购买、不预填、不读剪贴板）。
     *
     * @return false = 空串／这把已在池里／池子到 [KeyPool.MAX_KEYS] 上限（一个字都不写）
     */
    fun addKey(context: Context, id: String, key: String): Boolean {
        val pool = keyPool(context, id)
        val next = KeyPool.add(pool, key)
        if (next.size == pool.size) return false
        saveKeyPool(context, id, next, keyCursor(context, id))
        return true
    }

    /** 删一把（按原文精确匹配）。 */
    fun removeKey(context: Context, id: String, key: String): Boolean {
        val pool = keyPool(context, id)
        val next = KeyPool.remove(pool, key)
        if (next.size == pool.size) return false
        saveKeyPool(context, id, next, keyCursor(context, id))
        return true
    }

    /**
     * 禁用／解禁一把（**只有用户点**：401 自动置位的禁用，引擎永不自己解开）。
     * 解禁顺带清掉它的冷却——用户既然说"这把还能用"，就别再拿旧冷却挡它。
     */
    fun setKeyDisabled(context: Context, id: String, index: Int, disabled: Boolean): Boolean {
        val pool = keyPool(context, id)
        if (index !in pool.indices) return false
        val next = KeyPool.setDisabled(pool, index, disabled)
            .mapIndexed { i, e -> if (i == index && !disabled) e.copy(cooldownUntil = 0L) else e }
        saveKeyPool(context, id, next, keyCursor(context, id))
        return true
    }

    /**
     * 失败一次之后换下一把 Key（第一百三十一批 ② 的落点；由 BaopuRuntime 的候选闭包调，
     * 引擎只看到 [KeyRotation] 这个结果，不持 context、不写 SP——与 [storedKey] 同一纪律）。
     *
     * 三种结论：
     * - [KeyRotation.NotKeyRelated]：这次失败与 Key 无关（超时/断流/5xx）⇒ **一个字都不写**，
     *   交回通道级策略（第五十四批 P1-2 的同一条教训：别把不相干的失败记到凭据头上）；
     * - [KeyRotation.Rotated]：处罚这把（429 冷却 60s／403·额度 5min／401 禁用）＋给出下一把；
     * - [KeyRotation.Exhausted]：处罚完这把之后没有可用的了 ⇒ 带一句可读提示（不静默重试）。
     */
    fun rotateAfterFailure(context: Context, id: String, usedKey: String, rawError: String): KeyRotation {
        val outcome = KeyPool.classify(rawError)
        if (outcome == KeyPool.Outcome.NOT_ABOUT_KEY) return KeyRotation.NotKeyRelated
        val p = prefs(context)
        val pool = KeyPool.migrate(p.getString(keysOf(id), null), p.getString(keyOf(id), null))
        if (pool.isEmpty()) return KeyRotation.NotKeyRelated
        val now = System.currentTimeMillis()
        // 失败的就是"刚刚带出去的那把"：按原文定位；对不上（用户刚改过池子）⇒ 用当前游标那把。
        val byKey = pool.indexOfFirst { it.key == usedKey.trim() }
        val index = if (byKey >= 0) byKey else KeyPool.activeIndex(pool, p.getInt(cursorOf(id), 0), now)
        if (index < 0) return KeyRotation.Exhausted(KeyPool.allUnavailableNotice(pool, now))
        val (punished, next, key) = KeyPool.rotate(pool, index, outcome, now)
        if (next < 0 || key == null) {
            saveKeyPool(context, id, punished, index)
            return KeyRotation.Exhausted(KeyPool.allUnavailableNotice(punished, now))
        }
        saveKeyPool(context, id, punished, next)
        return KeyRotation.Rotated(key)
    }

    /** 换 Key 的三种结论（见 [rotateAfterFailure]；`Rotated` 里是**原文** Key，只在内存里传）。 */
    sealed interface KeyRotation {
        /** 这次失败不是这把 Key 的锅 ⇒ 池子不动。 */
        data object NotKeyRelated : KeyRotation

        /** 已处罚这把并换到下一把。 */
        data class Rotated(val key: String) : KeyRotation

        /** 全池不可用：带一句给用户的人话（不含任何 Key 原文）。 */
        data class Exhausted(val notice: String) : KeyRotation
    }

    /** 自定义通道的 baseUrl / model（同上）。 */
    fun storedCustom(context: Context): Pair<String?, String?> = prefs(context).let {
        it.getString(KEY_CUSTOM_BASE, null) to it.getString(KEY_CUSTOM_MODEL, null)
    }

    /**
     * 保存选择（UI「启用此通道」落点；保存即生效——调用方随后重建引擎）。
     *
     * 第一百三十一批 ②：Key 那一格改成进**池子**——填进来的这把追加到池尾（已在池里就原样不动），
     * 空串不写（不许把用户已经存好的池子清空）。老格式那条 `user_key_$id` 由
     * [saveKeyPool] 顺手清掉，避免一格一池两份真相。
     *
     * ③「预设也拉、预设也能选模型」**不在本方法里落**：预设上选定的模型走
     * [saveModelOverride]（它带得下元数据那一截，本方法的 `model` 只是一个字符串）。
     */
    fun save(
        context: Context,
        channelId: String,
        apiKey: String,
        baseUrl: String? = null,
        model: String? = null,
    ) {
        val key = apiKey.trim()
        val pool = keyPool(context, channelId)
        val next = if (key.isEmpty()) pool else KeyPool.add(pool, key)
        val cursor = if (next.isEmpty()) 0 else keyCursor(context, channelId).mod(next.size)
        prefs(context).edit()
            .putString(KEY_CHANNEL, channelId)
            .putString(KEY_CUSTOM_BASE, baseUrl)
            .putString(KEY_CUSTOM_MODEL, model)
            .apply()
        if (next.isNotEmpty()) saveKeyPool(context, channelId, next, cursor)
    }

    // ── 模型路由开关（第九十三批，SP 薄壳；判据本体在 ModelRouter） ─────────────

    /** AUTO_ROUTE 现在开没开（薄壳只读 SP，判据走内核：没存过 ⇒ false）。 */
    fun autoRouteEnabled(context: Context): Boolean =
        autoRouteEnabled(prefs(context).getBoolean(KEY_AUTO_ROUTE, false))

    /** 存的排序偏好；没存过 / 脏值 ⇒ null（**不猜默认偏好**）。 */
    fun routePreference(context: Context): ModelRouter.Preference? =
        ModelRouter.preferenceOf(prefs(context).getString(KEY_ROUTE_PREFERENCE, null))

    /**
     * 写路由开关。本批**没有 UI 消费者**（派工单红线：不做 UI）——入口先立住，
     * 设置页批接一个开关即可。与第六十四批 `channelSwitchStatus` 同口径：
     * 「已暴露但暂无消费者」是派工单要求，不是死代码。
     *
     * 开启必须同时给一个认得出来的偏好（"cheap"/"fast"/"quality"）；
     * 脏偏好 ⇒ 一个字都不写、返回 false（宁可不开，也不替用户猜一个偏好）。
     * 关闭只翻开关，偏好留着——下次打开还是他上次选的那个。
     */
    fun setAutoRoute(context: Context, enabled: Boolean, preference: String?): Boolean {
        val p = ModelRouter.preferenceOf(preference)
        if (enabled && p == null) return false
        prefs(context).edit {
            putBoolean(KEY_AUTO_ROUTE, enabled)
            if (p != null) putString(KEY_ROUTE_PREFERENCE, ModelRouter.keyOf(p))
        }
        return true
    }

    // ── Context-free 判据内核（JVM 直测） ─────────────────────────────────

    /**
     * 「存出来的开关」→「生效开关」（Context-free，JVM 直测）。
     * 只有 `true` 算开：null / 脏值 ⇒ **关**（派工单「不默认改变当前通道」的落点）。
     */
    internal fun autoRouteEnabled(stored: Boolean?): Boolean = stored == true

    /**
     * 解析当前通道。
     * - 自定义通道：三件（base/model）齐 + URL 过得了许可判据 ⇒ 自定义 Provider；
     *   缺件或非法 ⇒ 回退免费车道（**不静默装好**——UI 保存时已拦截，这里是兜底）。
     * - 预设通道：填了 Key 就套上（[Providers.withUserKey]：只换 key，其余保留）；
     *   第一百三十一批 ③ 再叠一层"用户在这条预设上选定的模型"（[presetModelFor]），
     *   模型名与它的元数据**一起换**——留着上一个模型的窗口数就是假数据。
     * - 认不出的 id（含 null/脏数据）⇒ 免费车道（第一百三十一批 ①：OpenRouter 免费档）。
     */
    internal fun resolve(
        channelId: String?,
        keyFor: (String) -> String?,
        customBase: String? = null,
        customModel: String? = null,
        presetModelFor: (String) -> ModelPickerRules.ModelOverride? = { null },
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
                }.getOrDefault(Providers.OPENROUTER_FREE)
            }
            return Providers.OPENROUTER_FREE
        }
        val chosen = Providers.of(channelId ?: "") ?: Providers.OPENROUTER_FREE
        val userKey = keyFor(chosen.id)?.trim().orEmpty()
        val withKey = if (userKey.isEmpty()) chosen else Providers.withUserKey(chosen, userKey)
        val picked = presetModelFor(chosen.id) ?: return withKey
        val name = picked.id.trim()
        if (name.isEmpty() || name == withKey.model) return withKey
        return withKey.copy(model = name, contextWindow = picked.contextWindow, maxOutput = picked.maxOutput)
    }

    /**
     * 熔断 / 路由**共用的一套候选规则**（第九十三批从 [fallbackFrom] 里原样抽出：
     * 一条判据都没改，只是从「当场取第一个」变成「先把名单列出来」，好让排序也吃同一份名单）。
     *
     * 排除规则照旧：
     * - 排除当前通道（熔断的就是它，切回去等于没切）；
     * - 排除免费车道（第一百三十一批 ① 之后它是 OpenRouter 免费档，**要 Key 也能干活**，
     *   但它仍是"没得选才用"的默认值而不是降级去向：免费档有 50 次/天的额度上限，
     *   把它当降级去向等于拿用户的免费额度去兜另一条通道的故障）；
     * - 预设必须已填 Key。
     * 都不行再看自定义通道（三件齐才可用）；顺序照旧（Providers.all() 的预设序，自定义在最后）。
     * 全没有 ⇒ 空表（调用方走 ask_user，不抛异常）。
     */
    internal fun routeCandidates(
        currentId: String,
        keyFor: (String) -> String?,
        customBase: String? = null,
        customModel: String? = null,
    ): List<Provider> {
        val out = mutableListOf<Provider>()
        Providers.all().forEach { p ->
            if (p.id != currentId && !p.builtinFree) {
                val key = keyFor(p.id)?.trim().orEmpty()
                if (key.isNotEmpty()) out += Providers.withUserKey(p, key)
            }
        }
        if (currentId != CHANNEL_CUSTOM) {
            val custom = resolve(CHANNEL_CUSTOM, keyFor, customBase, customModel)
            if (custom.id == CHANNEL_CUSTOM) out += custom
        }
        return out
    }

    /**
     * 熔断后的下一个通道（纯判据；第九十三批在这里挂路由，**默认关 ⇒ 行为与挂之前一致**）。
     *
     * - `autoRoute = false`（默认，也是 SP 的默认）⇒ 取 [routeCandidates] 的第一个：
     *   与刀 1 补丁 / 第六十四批时代的 `fallbackFrom` 同序同值，行为一个字节都没改；
     * - `autoRoute = true` ⇒ 把**同一份名单**交给 [ModelRouter.plan] 按偏好排序后取第一个。
     *   排不出（能力不满足 / 那一维全表 UNKNOWN / 名单空）⇒ 返回 **null**，
     *   调用方走既有 ask_user 出口——这就是派工单 §2「没有满足能力的模型 ⇒ 明确报错，不静默降级」。
     *
     * 本方法不写存储、不碰状态机：切不切、什么时候切仍是第六十四批的三个安全点说了算
     * （§2「回退复用 64，不新建第二套状态」；「当前模型」的真相仍然只在 SP 的 channel_id 那一处）。
     * `need` 缺省为空：熔断回退发生在轮内，本批没有一处现成的能力需求真相可传（空 need =
     * 不做能力过滤 ⇒ 开 auto 也只改「谁排第一」，不改「谁能进名单」）。
     */
    internal fun fallbackFrom(
        currentId: String,
        keyFor: (String) -> String?,
        customBase: String? = null,
        customModel: String? = null,
        autoRoute: Boolean = false,
        preference: ModelRouter.Preference? = null,
        need: ModelRouter.Need = ModelRouter.Need(),
    ): Provider? {
        val candidates = routeCandidates(currentId, keyFor, customBase, customModel)
        if (!autoRouteEnabled(autoRoute)) return candidates.firstOrNull()
        val plan = ModelRouter.plan(
            autoEnabled = true,
            preference = preference,
            need = need,
            current = resolve(currentId, keyFor, customBase, customModel),
            candidates = candidates,
        )
        return (plan as? ModelRouter.Plan.Routed)?.picked
    }
}
