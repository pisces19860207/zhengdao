// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百一十二批 · **模板注册表**（派工单 §2.5 末条的那句「新增场景＝加一份 JSON，不改组件代码」）。
//
// ## 为什么是一个类而不是 object
// object 里的缓存＝进程级可变状态：单测之间会互相看见彼此装进来的假模板，排查时极难还原。
// 这里做成「一次构造、一份缓存」的普通类，界面侧 `remember` 一份（跟着抱朴页生命周期走），
// 单测各自 new 一份——同一份逻辑，两边互不干扰。
//
// ## 为什么不持有 Context
// `Context` 进 object/长期存活对象的静态字段＝lint `StaticFieldLeak`（本仓预算闸 0 条，不许压制）。
// 所以资产读法做成 `(String) -> String?` 注入（房型照 `SeedSkills.install(readAsset=…)` 与
// policy 包那个资产壳的既有写法）：Android 那一侧传闭包，本包零 Android 依赖，
// JVM 单测直接传内存里的字符串。
//
// ⚠ 这段注释里**不许**出现 policy 包那个入口的完整调用写法：`PolicySourceGateTest` 是**原文扫全仓**的，
// 注释里出现一次就把「全仓只有一个读资产入口」判红（本批实跑踩过一次——注释也算）。
//
// ## 读坏了怎么办：说清哪一份，然后当作「没有这张卡」
// [TemplateLoad.Bad] 原样交回调用方（界面不弹空卡，只落一条不带数据的日志），
// 不抛、不猜、不拿上一份顶替——拿旧模板渲染新字段，看起来像数据错乱。
package com.example.zhengdao.baopu.cards

/** 一次构造一份缓存：`templateId → 解析结果`（Bad 也缓存，免得每次重编同一份坏文件）。 */
class ResultCardRegistry(
    private val readAsset: (String) -> String?,
) {

    private val cache = HashMap<String, TemplateLoad>()

    /** 取一份模板（首次读资产并解析，之后走缓存）。 */
    fun template(templateId: String): TemplateLoad {
        cache[templateId]?.let { return it }
        val path = assetPathOf(templateId)
        val text = runCatching { readAsset(path) }.getOrNull()
        val out = if (text == null) {
            TemplateLoad.Bad("assets/$path 读不到（新增场景要同时放模板文件）")
        } else {
            ResultCardTemplates.parse("assets/$path", text)
        }
        cache[templateId] = out
        return out
    }

    /**
     * 模板 ＋ 值 → 卡；模板缺/坏 ⇒ null（界面据此不弹卡，正文档照旧）。
     *
     * 不在这里兜一个「最小卡」：没有模板的卡就是一张空壳，弹出来只会盖住用户正在读的正文。
     */
    fun card(
        templateId: String,
        values: Map<String, String>,
        lookup: (String) -> String,
        dials: Map<String, String> = emptyMap(),
        itemCount: Int = 0,
    ): ResultCardSpec? = when (val t = template(templateId)) {
        is TemplateLoad.Ok -> ResultCardBinder.bind(t.template, values, lookup, dials, itemCount)
        is TemplateLoad.Bad -> null
    }

    companion object {
        /** 模板目录（assets 根以下）。 */
        const val ASSET_DIR = "baopu/cards"

        /** `templateId` → assets 路径。 */
        fun assetPathOf(templateId: String): String = "$ASSET_DIR/$templateId.json"
    }
}
