// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第九批追加（刀 1 补丁）§1.1 建的这个弹层 ＋《第一百三十一批 · 模型选择 P0》
// ①②③ 与 11:51 补充节 §三（设计规格）。补充节那份规格在这一处体现：
// 顶栏公司名点开的选模型浮层＝提供商清单 → 模型清单（带搜索框）→ Key 池 → 免费档入口。
//
// 三条纪律（写在这儿免得下一个人重踩）：
// 1. 判据不落在 UI：过滤/显示名/该不该拉/尾 4 位怎么打码，全在 [ModelPickerRules] 与
//    [KeyPool] 那两处纯函数里，本文件只负责把它们摆上去；
// 2. 零硬编码色值（补充节 §四第 1 条那一串字面量在本文件出现 0 次）：颜色一律走 `MaterialTheme.colorScheme.*`——
//    浅青底＝`primaryContainer`、竖条与强调＝`primary`、墨＝`onSurface`、次墨＝`onSurfaceVariant`，
//    报警字色＝[com.example.zhengdao.ui.theme.zhengdaoErrorText]（`BaopuSkin` 那份映射的既有出口，
//    本批不碰视觉体系，所以不在那里另立一个 token）；
// 3. 字号只有三档（补充节 §四第 2 条）：15＝`bodyMedium`/`titleSmall`、13＝`bodySmall`、
//    10＝`labelSmall`（`Type.kt` 里这三档的实际值），本文件不出现第四档字号。
//
// 文案口径：res/** 仍是第九批那条红线（④ 的术语替换只动 strings.xml 里**已有**的条目，
// 没往 res 加新 key），所以本文件的界面文案继续写在代码里，与引擎 notice 同口径。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.zhengdao.baopu.KeyPool
import com.example.zhengdao.baopu.ModelCatalog
import com.example.zhengdao.baopu.ModelCatalogCache
import com.example.zhengdao.baopu.ModelEntry
import com.example.zhengdao.baopu.ModelPickerRules
import com.example.zhengdao.baopu.ProviderPrefs
import com.example.zhengdao.baopu.Providers
import com.example.zhengdao.ui.theme.zhengdaoErrorText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 清单一次最多摆几行（OpenRouter 全表三百多条，全摆会把「启用」挤出屏幕；搜索框就是为这个准备的）。 */
private const val MAX_VISIBLE_ROWS = 60

/** 补充节 §三：接口地址超宽走**中间省略**（保留 host）；这一格是排版预算，不是"截断显示"。 */
private const val MIDDLE_ELLIPSIS_CHARS = 34

/**
 * 选模型浮层（第一百三十一批 ①②③ ＋ 补充节 §三）。
 *
 * 自上而下四段：**提供商** → **模型**（搜索框＋清单＋手填兜底）→ **Key**（多把、状态、加删）
 * → **免费档入口**（限额提示＋两条玄览跳转），底部「取消／启用」。
 *
 * 三处与旧版不同的口径，逐条对应派工单：
 * · 旧「免费通道」那条整条 403 的死路已删 ⇒ 默认是 OpenRouter 免费档，没 Key 时说的是
 *   「去 OpenRouter 注册填 Key」＋可点跳转，**不是**「当前不可用（403）」；
 * · 旧版把已存的 Key 回填进输入框 ⇒ 现在输入框只用于**加**一把，已存的在 Key 段里以尾 4 位
 *   列出（补充节：永不显示全 key）；
 * · 清单不再只有自定义通道能拉 ⇒ 预设也拉（③），停手 0.6s 自动拉、拉取中缓存先行、免费档每次重拉。
 *
 * @param onSave 提交：(选中的提供商 id, 待加的新 Key, 自定义接口地址, 自定义模型名,
 *   自定义协议, 预设上点定的模型)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BaopuChannelSheet(
    currentChannelId: String,
    customBaseUrl: String?,
    customModel: String?,
    customProtocol: String?,
    onDismiss: () -> Unit,
    onSave: (
        channelId: String,
        apiKey: String,
        baseUrl: String?,
        model: String?,
        protocol: String?,
        picked: ModelPickerRules.ModelOverride?,
    ) -> Unit,
) {
    val ctx = LocalContext.current
    // 起稿只在这一处判一次：[ModelSheetDraft.showing]＝"用户上次没关掉抽屉"。跳玄览会把抱朴页
    // 整个带离组合（MainActivity 的 `when (tab)`），所以恢复必须由进程级草稿来兑现，
    // 不能靠 remember——补充节那句「返回后保持跳转前的公司名与已填内容」就是这一行代码。
    val restored = remember { ModelSheetDraft.showing }

    /** 某一家此刻该显示的模型名（预设＝覆盖格里的值，没覆盖过就用预设自带的那条）。 */
    fun modelOf(id: String): String =
        ProviderPrefs.modelOverride(ctx, id)?.id
            ?: Providers.of(id)?.model
            ?: customModel.orEmpty()

    /** 某一家此刻的接口地址（预设＝写死的那条，自定义＝SP 里用户填的）。 */
    fun baseUrlOf(id: String): String =
        Providers.of(id)?.baseUrl ?: customBaseUrl.orEmpty()

    /** 某一家此刻的协议（只有自定义有得选；预设用自己的协议，草稿位留空）。 */
    fun protocolOf(id: String): String =
        if (id == ProviderPrefs.CHANNEL_CUSTOM) ProviderPrefs.protocolKeyOf(customProtocol) else ""

    var selected by remember { mutableStateOf(if (restored) ModelSheetDraft.channelId.ifBlank { currentChannelId } else currentChannelId) }
    var addKeyText by remember { mutableStateOf(if (restored) ModelSheetDraft.addKey else "") }
    var baseUrlText by remember { mutableStateOf(if (restored) ModelSheetDraft.baseUrl else baseUrlOf(currentChannelId)) }
    var protocolText by remember {
        mutableStateOf(
            if (restored) ModelSheetDraft.protocol.ifBlank { protocolOf(currentChannelId) }
            else protocolOf(currentChannelId),
        )
    }
    var modelText by remember { mutableStateOf(if (restored) ModelSheetDraft.model else modelOf(currentChannelId)) }
    var query by remember { mutableStateOf(if (restored) ModelSheetDraft.query else "") }
    var picked by remember { mutableStateOf(if (restored) ModelSheetDraft.picked else ProviderPrefs.modelOverride(ctx, currentChannelId)) }
    var pickedFor by remember { mutableStateOf(if (restored) ModelSheetDraft.pickedFor else currentChannelId) }

    // 模型清单四态：条目 / 拉取中 / 被挡的理由 / 拉取失败原文。
    var entries by remember { mutableStateOf<List<ModelEntry>>(emptyList()) }
    var fetching by remember { mutableStateOf(false) }
    var pullNote by remember { mutableStateOf<String?>(null) }
    var fetchError by remember { mutableStateOf<String?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var seenTick by remember { mutableIntStateOf(0) }
    // Key 池每次增删都当场写 SP；挪这个数让本段重新读池子（不在 Compose 里缓存池子）。
    var poolEpoch by remember { mutableIntStateOf(0) }
    var addKeyMsg by remember { mutableStateOf<String?>(null) }

    val isCustom = selected == ProviderPrefs.CHANNEL_CUSTOM
    // 第一百三十六批：自定义 Anthropic 没有清单接口 ⇒ 即使换了 base/Key 也不发这次请求。
    val manualModelOnly = isCustom && protocolText == ProviderPrefs.PROTOCOL_ANTHROPIC
    val preset = Providers.of(selected)
    val effectiveBase = (if (isCustom) baseUrlText else baseUrlOf(selected)).trim()
    // 拉清单带哪把 Key：输入框里正在打的那把优先（用户正盯着它），否则池子里此刻该带出去的。
    // 原文只进这一次函数调用，不进日志、不进 state 之外的任何地方（红线）。
    val keyForFetch = addKeyText.trim().ifEmpty { ProviderPrefs.storedKey(ctx, selected).orEmpty() }
    val isFreeLane = preset?.builtinFree == true
    val company = preset?.label ?: ModelPickerRules.companyOfHost(effectiveBase)
    val customValid = !isCustom || (effectiveBase.startsWith("https://") && modelText.trim().isNotEmpty())

    /** 换一家：草稿跟着走，输入位与清单全部重取（不把上一家的选择串到这一家）。 */
    fun selectProvider(id: String) {
        ModelSheetDraft.moveTo(id, baseUrlOf(id), modelOf(id), protocolOf(id))
        ModelSheetDraft.picked = ProviderPrefs.modelOverride(ctx, id)
        ModelSheetDraft.pickedFor = id
        selected = id
        baseUrlText = ModelSheetDraft.baseUrl
        protocolText = ModelSheetDraft.protocol
        modelText = ModelSheetDraft.model
        picked = ModelSheetDraft.picked
        pickedFor = id
        addKeyText = ""
        addKeyMsg = null
        query = ""
        entries = emptyList()
        fetchError = null
        pullNote = null
    }

    // 草稿回写：每一项改动都立刻同步（中途跳玄览也不会丢正在打的字）。
    LaunchedEffect(selected, addKeyText, baseUrlText, protocolText, modelText, query, picked, pickedFor) {
        ModelSheetDraft.showing = true
        ModelSheetDraft.channelId = selected
        ModelSheetDraft.addKey = addKeyText
        ModelSheetDraft.baseUrl = baseUrlText
        ModelSheetDraft.protocol = protocolText
        ModelSheetDraft.model = modelText
        ModelSheetDraft.query = query
        ModelSheetDraft.picked = picked
        ModelSheetDraft.pickedFor = pickedFor
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LaunchedEffect(selected, effectiveBase, keyForFetch, refreshTick, manualModelOnly) {
        if (manualModelOnly) {
            // Anthropic 没有 /v1/models：不硬拉，直接说「手填模型名」（136 单 §1）。
            entries = emptyList()
            fetching = false
            fetchError = null
            pullNote = ModelPickerRules.ANTHROPIC_MANUAL_MODEL_HINT
            return@LaunchedEffect
        }
        val neverCache = isFreeLane
        val cached = ModelCatalogCache.cached(effectiveBase, neverCache)
        if (effectiveBase.isEmpty()) {
            entries = emptyList(); fetching = false; pullNote = null; fetchError = null
            return@LaunchedEffect
        }
        // 只有"用户点了刷新"那一刻算 force：seenTick 记着已经处理到哪一次。
        val force = refreshTick != seenTick
        seenTick = refreshTick
        val block = ModelPickerRules.pullBlock(
            baseUrl = effectiveBase,
            hasKey = keyForFetch.isNotBlank(),
            openRouterPublic = ModelPickerRules.isOpenRouterPublicCatalog(effectiveBase),
        )
        if (block != null) {
            // 不发 doomed 请求，但把"为什么不发"说出来（不静默）；清单用手头这份缓存顶着。
            entries = cached
            fetching = false
            pullNote = ModelPickerRules.pullHint(block, isFreeLane)
            fetchError = null
            return@LaunchedEffect
        }
        if (ModelPickerRules.shouldSkipFetch(cached, force)) {
            entries = cached
            fetching = false; pullNote = null; fetchError = null
            return@LaunchedEffect
        }
        entries = cached // ③「拉取中缓存先行」：等网络这段时间不许空一屏
        pullNote = null
        delay(ModelPickerRules.DEBOUNCE_MS) // 停手 0.6 秒再发，别每敲一个字符打一次
        fetching = true
        val (got, err) = withContext(Dispatchers.IO) { ModelCatalog.fetchEntries(effectiveBase, keyForFetch) }
        if (got.isNotEmpty()) {
            ModelCatalogCache.store(effectiveBase, got, neverCache)
            entries = got
            fetchError = null
        } else {
            // 拉砸了：有缓存就继续用缓存（不许把已经看见的模型抹没），没有就手填兜底那句话。
            fetchError = err
        }
        fetching = false
    }

    val filtered = ModelPickerRules.filter(query, entries)
    val visible = filtered.take(MAX_VISIBLE_ROWS)
    val pool = remember(poolEpoch, selected) { ProviderPrefs.keyPool(ctx, selected) }
    val now = System.currentTimeMillis()
    val activeKeyIndex = KeyPool.activeIndex(pool, ProviderPrefs.keyCursor(ctx, selected), now)

    ModalBottomSheet(
        onDismissRequest = { ModelSheetDraft.clear(); onDismiss() },
        sheetState = sheetState,
        // 补充节 §三：浮层圆角 24dp（`check-radius-tokens.py` 的合法档位里有 24）。
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = BAO_LIST_INSET_DP.dp)
                .padding(bottom = 24.dp)
                .imePadding(),
        ) {
            Text(
                "选模型",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )

            // ── 一、提供商 ──────────────────────────────────────────────────
            GroupLabel("提供商")
            Providers.all().forEach { p ->
                PickerRow(
                    title = p.label,
                    sub = ModelPickerRules.middleEllipsis(p.baseUrl, MIDDLE_ELLIPSIS_CHARS),
                    selected = selected == p.id,
                    onClick = { selectProvider(p.id) },
                )
            }
            PickerRow(
                title = "自定义接口",
                sub = if (isCustom && effectiveBase.isNotEmpty()) {
                    ModelPickerRules.middleEllipsis(effectiveBase, MIDDLE_ELLIPSIS_CHARS)
                } else {
                    "填接口地址和模型名"
                },
                selected = isCustom,
                onClick = { selectProvider(ProviderPrefs.CHANNEL_CUSTOM) },
            )
            if (isCustom) {
                // 第一百三十六批 · 自定义行展开后的第一项：协议二选一。
                // 只换协议形状（端点/请求头由 LlmWire 按 Provider.protocol 走），不新写请求层。
                GroupLabel("协议")
                PickerRow(
                    title = "OpenAI 兼容",
                    sub = "默认 · /v1/chat/completions",
                    selected = protocolText == ProviderPrefs.PROTOCOL_OPENAI_COMPAT,
                    onClick = { protocolText = ProviderPrefs.PROTOCOL_OPENAI_COMPAT },
                )
                PickerRow(
                    title = "Anthropic",
                    sub = "Claude · /v1/messages",
                    selected = protocolText == ProviderPrefs.PROTOCOL_ANTHROPIC,
                    onClick = { protocolText = ProviderPrefs.PROTOCOL_ANTHROPIC },
                )
                OutlinedTextField(
                    value = baseUrlText,
                    onValueChange = { baseUrlText = it },
                    // ④ 术语：对用户说「接口地址」，不说 baseUrl（技术注释里仍叫 baseUrl）。
                    label = { Text("接口地址（https:// 开头，如 https://api.deepseek.com）") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = BAO_ROW_MIN_HEIGHT_DP.dp),
                )
                if (manualModelOnly) {
                    // Anthropic 的版本前缀是 baseUrl 的一部分（/v1/messages 由它拼出来）。
                    Footnote("Anthropic 的接口地址以 /v1 结尾，例如 https://api.anthropic.com/v1")
                }
            }

            // ── 二、模型（搜索框 ＋ 清单 ＋ 手填兜底；③＋补充节 §一） ────────
            GroupLabel("模型")
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                // 输入即过滤：没有"搜索"按钮，也不该有（补充节 §一：不是可选项）。
                label = { Text("搜索模型（输入 free 只看免费）") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = BAO_ROW_MIN_HEIGHT_DP.dp),
            )
            when {
                entries.isEmpty() && fetching -> Footnote("正在拉取模型列表…")
                entries.isEmpty() && pullNote != null -> Footnote(pullNote)
                entries.isEmpty() && fetchError != null -> Footnote(
                    "没拉到模型列表（$fetchError）——可以在下面直接手填模型名",
                    error = true,
                )
                entries.isEmpty() -> Footnote("这条模型还没有清单——可以在下面直接手填模型名，或点下面的刷新")
                filtered.isEmpty() -> Footnote(ModelPickerRules.SEARCH_EMPTY_NOTICE)
                else -> {
                    visible.forEach { e ->
                        PickerRow(
                            // 显示名（四级规则在第 1 级到第 4 级里取，ID 不上这一行）。
                            title = ModelPickerRules.displayName(e),
                            sub = ModelPickerRules.modelSubLine(e, company),
                            selected = pickedFor == selected && picked?.id == e.id,
                            onClick = {
                                picked = ModelPickerRules.ModelOverride(
                                    id = e.id,
                                    contextWindow = e.contextWindow,
                                    maxOutput = e.maxOutput,
                                )
                                pickedFor = selected
                                modelText = e.id
                            },
                        )
                    }
                    if (filtered.size > visible.size) {
                        Footnote("共 ${filtered.size} 条，这里只列前 ${visible.size} 条——用上面的搜索框缩小范围")
                    }
                }
            }
            if (fetching && entries.isNotEmpty()) Footnote("正在更新模型列表…")
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmallAction("刷新") { refreshTick++ }
                // ①「跳转链接（玄览内打开）」：只走 openInXuanlan 那一条 B 路径
                // （pendingUrl ＋ requestBrowserTab），不新开 WebView、不落系统浏览器。
                SmallAction("看全部免费模型") { openInXuanlan(ModelPickerRules.OPENROUTER_FREE_MODELS_URL) }
            }
            OutlinedTextField(
                value = modelText,
                onValueChange = {
                    modelText = it
                    // 手填也算"点了这条模型"：覆盖格跟着走（清空＝回到预设自带的那条）。
                    picked = if (it.isBlank()) null else ModelPickerRules.ModelOverride(it.trim())
                    pickedFor = selected
                },
                label = {
                    Text(
                        if (manualModelOnly) "模型名（必填，如 claude-sonnet-4-5）"
                        else if (isCustom) "模型名（必填，如 deepseek-chat）"
                        else "模型名（手填，覆盖上面选的那条）",
                    )
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = BAO_ROW_MIN_HEIGHT_DP.dp),
            )

            // ── 三、Key（多把轮询；②） ──────────────────────────────────────
            GroupLabel("Key（最多 ${KeyPool.MAX_KEYS} 把，轮询使用）")
            if (pool.isEmpty()) {
                Footnote(
                    if (isFreeLane) ModelPickerRules.NEED_FREE_KEY_HINT
                    else "这条模型还没有 Key：在下面填一把加进来。",
                )
            } else {
                pool.forEachIndexed { i, e ->
                    PickerRow(
                        // 只显示尾 4 位（[KeyPool.mask]）；当前生效那把用浅青底顶出来，不画绿点红灯。
                        title = KeyPool.mask(e.key),
                        sub = KeyPool.statusOf(e, now) + if (i == activeKeyIndex) " · 当前生效" else "",
                        selected = i == activeKeyIndex,
                        trailing = {
                            SmallAction(if (e.disabled) "解禁" else "禁用") {
                                ProviderPrefs.setKeyDisabled(ctx, selected, i, !e.disabled)
                                poolEpoch++
                            }
                            SmallAction("删除") {
                                ProviderPrefs.removeKey(ctx, selected, e.key)
                                poolEpoch++
                            }
                        },
                    )
                }
                // ②「全部不可用 ⇒ 可读提示，不静默重试」：13sp 报警字色，不铺 errorContainer 大色块。
                if (activeKeyIndex < 0) {
                    Text(
                        KeyPool.allUnavailableNotice(pool, now),
                        style = MaterialTheme.typography.bodySmall,
                        color = zhengdaoErrorText(),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            OutlinedTextField(
                value = addKeyText,
                onValueChange = { addKeyText = it },
                label = { Text("加一把 Key（只存本机，不进日志）") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                textStyle = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = BAO_ROW_MIN_HEIGHT_DP.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmallAction("加一把") {
                    if (ProviderPrefs.addKey(ctx, selected, addKeyText)) {
                        addKeyText = ""
                        addKeyMsg = null
                        poolEpoch++
                    } else {
                        addKeyMsg = addKeyRejectReason(addKeyText, pool.size)
                    }
                }
                addKeyMsg?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = zhengdaoErrorText())
                }
            }

            // ── 四、免费档入口（①：App 不兜底、不内置，只把人话和跳转给到位） ──
            if (isFreeLane) {
                GroupLabel("OpenRouter 免费档")
                // ①「免费限额 UI 明示」；这句话自带"不阻塞使用"，13sp 次墨（不用琥珀）。
                Footnote(Providers.FREE_QUOTA_HINT)
                SmallAction("去 OpenRouter 注册并拿 Key") { openInXuanlan(ModelPickerRules.OPENROUTER_KEYS_URL) }
                Footnote(ModelPickerRules.SEARCH_FREE_TIP)
            }

            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            ) {
                TextButton(onClick = { ModelSheetDraft.clear(); onDismiss() }) { Text("取消") }
                Button(
                    onClick = {
                        val usePicked = if (pickedFor == selected && !isCustom) picked else null
                        onSave(
                            selected,
                            addKeyText.trim(),
                            if (isCustom) effectiveBase else null,
                            if (isCustom) modelText.trim() else null,
                            if (isCustom) protocolText else null,
                            usePicked,
                        )
                    },
                    enabled = customValid,
                    // §十三 按钮三档：主按钮一屏一个、显式 48dp，不靠 M3 默认的 40。
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .height(BAO_BUTTON_MIN_HEIGHT_DP.dp),
                ) { Text("启用此模型") }
            }
        }
    }
}

/** 组标签（补充节 §三：13sp 次墨）。 */
@Composable
private fun GroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
}

/** 脚注（13sp；`error = true` 只换字色不铺色块——补充节 §三钉的就是这一条）。 */
@Composable
private fun Footnote(text: String?, error: Boolean = false) {
    val t = text ?: return
    Text(
        t,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) zhengdaoErrorText() else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp, bottom = 2.dp),
    )
}

/** 次档按钮（§十三：描边或文字钮；这里全用文字钮，同屏同档）。 */
@Composable
private fun SmallAction(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * 一条可点行（三处共用：提供商 / 模型 / Key）。
 *
 * 命中区 ≥48dp（补充节 §三）；选中态**只许**这一种样式——浅青底 `primaryContainer`
 * ＋左侧 2dp `primary` 竖条（[com.example.zhengdao.ui.baopu.BaopuDrawer] 里条目选中已有先例，
 * 不新造第三种）。`onClick = null` ⇒ 这一行本身不可点（Key 行的动作在右边两颗文字钮上）。
 */
@Composable
private fun PickerRow(
    title: String,
    sub: String?,
    selected: Boolean,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(BAO_CHIP_RADIUS_DP.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = BAO_ROW_MIN_HEIGHT_DP.dp)
            .clip(shape)
            .then(if (selected) Modifier.background(MaterialTheme.colorScheme.primaryContainer) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        if (selected) {
            Box(
                Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                if (sub != null) {
                    Text(
                        sub,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            trailing?.invoke()
        }
    }
}

/**
 * 「加一把」没加成的人话（[KeyPool.add] 只回 false，理由由这三条判据现算）。
 *
 * 三条都可能撞：空串／这把已在池里／到 [KeyPool.MAX_KEYS] 上限——不猜顺序，逐条比。
 */
private fun addKeyRejectReason(input: String, poolSize: Int): String = when {
    input.isBlank() -> "Key 是空的，先把它填进来。"
    poolSize >= KeyPool.MAX_KEYS -> "一条模型最多存 ${KeyPool.MAX_KEYS} 把，先删一把再加。"
    else -> "这把已经在这条模型里了（尾号相同），不用重复加。"
}
