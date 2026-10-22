// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单《第一百三十一批 · 模型选择 P0》③「拉取中缓存先行；OpenRouter 免费档不缓存、每次重拉」。
//
// 为什么只有**进程内**一份、不落盘：
// 1. 清单是**服务端事实**，落盘就有了第二真相（离线时显示一份过期的"可用模型"比不显示更坏）；
// 2. 抽屉活着的时长本来就是"这次会话里反复开关"，进程级缓存足够兑现"缓存先行"；
// 3. 本仓无 Robolectric ⇒ 落盘要新增 SP 格，而这一格没有任何判据可测。
// 因此本文件是**纯内存 ＋ 可注入时钟**，JVM 直测；重启 App 后第一次开抽屉重新拉，这是设计而非 bug。
package com.example.zhengdao.baopu

/**
 * 模型清单的进程内缓存（第一百三十一批 ③）。
 *
 * 一条纪律：这里存的**只有模型 id 与公开元数据**，没有 Key、没有地址之外的任何凭据。
 * 键是接口地址（[ModelPickerRules.cacheUsable] 用同一个口径判"这份缓存还算不算数"）。
 */
internal object ModelCatalogCache {

    /** 一份缓存的保质期（重构方案没给数；取 10 分钟——比一次会话长，比"过期一天"诚实）。 */
    const val TTL_MS = 10 * 60_000L

    private data class Bucket(val entries: List<ModelEntry>, val fetchedAt: Long)

    private val buckets = mutableMapOf<String, Bucket>()

    /** 命中就给表（**免费档永远不命中**：它的清单常变，③ 明写"每次重拉"）。 */
    fun cached(
        baseUrl: String,
        neverCache: Boolean,
        now: Long = System.currentTimeMillis(),
    ): List<ModelEntry> {
        if (neverCache) return emptyList()
        val b = buckets[baseUrl.trim()] ?: return emptyList()
        return if (now - b.fetchedAt <= TTL_MS) b.entries else emptyList()
    }

    fun put(baseUrl: String, entries: List<ModelEntry>, now: Long = System.currentTimeMillis()) {
        val key = baseUrl.trim()
        if (key.isEmpty() || entries.isEmpty()) return
        buckets[key] = Bucket(entries, now)
    }

    /** 这份表是不是免费档刚拉回来的（决定"要不要落缓存"——③ 那条规则的落点）。 */
    fun store(
        baseUrl: String,
        entries: List<ModelEntry>,
        neverCache: Boolean,
        now: Long = System.currentTimeMillis(),
    ) {
        if (neverCache) return
        put(baseUrl, entries, now)
    }

    /** 清空（用户改了地址/Key 之后手动刷新用；单测也靠它复位，别让用例互相污染）。 */
    fun clear() {
        buckets.clear()
    }

    /** 已经为哪些地址存过表（只暴露地址，不暴露内容；调试与单测看状态用）。 */
    fun keys(): Set<String> = buckets.keys.toSet()
}
