// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.preview

import java.util.UUID

/**
 * 内存预览槽（刀 4 §2.1）：**uuid → 一份 HTML ＋ 它的子资源**，全部只活在内存里。
 *
 * ## 这块要解决什么
 * 抱朴做好一张卡（HTML＋CSS＋图）要能「渲染出来给人看」。落盘再用玄览打开有两条死路：
 * ① `file://` 在 WebView 里受同源与文件访问开关约束，相对路径的子资源（css/png）口径乱；
 * ② 写盘把「预览」变成了「留存」——卡片内容（可能带用户隐私）在共享目录里躺到永远。
 * 所以做成**只活在内存里的槽**：`put` 换一个不可猜的 uuid，玄览打开
 * `http://127.0.0.1:<port>/p/<uuid>` 就能看渲染结果；**进程一死、或槽被淘汰，url 就 404**
 * ——不留盘、不进相册、不出设备。
 *
 * ## 三条硬口径（派工单 §2.1；判据 J1/J2）
 * 1. **上限 8，满了按 LRU 淘汰**。淘汰的是「最久没被访问」的那个，不是最先放进来的那个
 *    ——人会把一张卡反复刷新看，FIFO 会把他正看着的那张挤掉。
 * 2. **uuid 只是 map 的 key，永不参与文件路径**。本类与 [PreviewServer] 里没有任何按请求
 *    路径读盘的代码，J2 是**源码级断言**（不许在报告里写成「应该不会读」）。
 * 3. **没起服务就不许假装成功**：[put] 在没有绑定端口时返回 [PutResult.Failed] 并带上原因
 *    （§2.1「不许静默不启动」）。
 *
 * ## 与派工单措辞的一处对账（端口归谁）
 * §2.1 把 `put(...): PutResult` 和「返回 `http://127.0.0.1:<port>/p/<uuid>`」都写在
 * PreviewSlots 名下，而端口只有 [PreviewServer] 知道。落地拆法：
 * **PreviewServer 负责拿到端口并 [bind] 进来，PreviewSlots 负责 uuid、槽位与 url 拼装**。
 * 好处：PreviewSlots 完全不碰 socket ⇒ J1（上限/LRU）在纯 JVM 里喂一个假端口就能逐条钉死，
 * J5（端口顺延/全部被占）留在 PreviewServer 侧用真 socket 测。
 *
 * ## 线程安全（§2.1 要求写清理由）
 * 用**一把锁**（[lock]）罩住 map 的读写，没做成「不可变快照＋无锁读」——因为 LRU 的
 * **读本身就是写**：`accessOrder = true` 的 LinkedHashMap 在 get 时会把节点移到尾部。
 * 若读走无锁快照，「反复看的那张不被淘汰」这条语义就没了，J1 测不出来。
 * 槽最多 8 个、值是小 HTML 串，锁不是瓶颈；而调用方有三处（桥工作线程 / 主线程 /
 * 预览 HTTP 线程），都要看到一致的一瞬间，正合适。
 * 只有 [urlBase] 与 [offlineReason] 两个标量走 `@Volatile`：bind/release 与 put 之间
 * 只需要可见性，它们不参与 LRU，不必与 map 同锁。
 */
object PreviewSlots {

    /** 槽上限（派工单钉死 8；J1 的负例就是把这里改成 100 让门禁咬住）。 */
    const val CAPACITY = 8

    /** 服务没起时的默认原因文案（[resetForTests] 也用它复位）。 */
    private const val NOT_STARTED = "预览服务未启动（先调 PreviewServer.start()）"

    /** 一份预览内容。不可变：[assets] 的字节在 [put] 时已逐个复制，外部改不动。 */
    class Slot internal constructor(
        val id: String,
        val html: String,
        val assets: Map<String, ByteArray>,
    )

    /** `put` 的结果：成功给 id＋url（外加「这次挤掉了谁」），失败给原因。 */
    sealed class PutResult {
        /**
         * @param id 36 位随机 uuid（[java.util.UUID.randomUUID]，不可猜）
         * @param url 可直接给玄览打开的预览地址
         * @param evicted 本次因满槽被淘汰的 id（没淘汰 ⇒ null）；它从此 404
         */
        class Placed(val id: String, val url: String, val evicted: String?) : PutResult()

        /** 失败必须带原因（§2.1「不许静默不启动」）。 */
        class Failed(val reason: String) : PutResult()
    }

    /** 观测读数：容量、当前占用、**累计被淘汰次数**（clear 不把它归零——那是历史，不是现状）。 */
    class Stats(val capacity: Int, val used: Int, val evicted: Int)

    private val lock = Any()

    /** accessOrder = true ⇒ 迭代顺序＝访问顺序，**首位就是最久未访问**（淘汰对象）。 */
    private val slots = LinkedHashMap<String, Slot>(16, 0.75f, true)

    private var evictedCount = 0

    /** bind 成功后由 [PreviewServer] 注入（形如 `http://127.0.0.1:7000`）；null＝服务没起来。 */
    @Volatile
    private var urlBase: String? = null

    /** 起不来 / 已停服时，[put] 要原样回给调用方的原因。 */
    @Volatile
    private var offlineReason: String = NOT_STARTED

    // ── 端口注入（只由 PreviewServer 调） ───────────────────────────────────

    /** 服务起来了：把 base url 交给槽，[put] 才有 url 可拼。 */
    fun bind(urlBase: String) {
        this.urlBase = urlBase
        offlineReason = NOT_STARTED
    }

    /** 服务起不来或已停止：[put] 转为明确失败，并带上原因。 */
    fun release(reason: String) {
        urlBase = null
        offlineReason = reason
    }

    /** 当前 base（null＝未绑定）。给测试与回执看。 */
    fun currentUrlBase(): String? = urlBase

    // ── 五个动作 ────────────────────────────────────────────────────────────

    /**
     * 放一份预览，返回可直接打开的 url。满 [CAPACITY] ⇒ 淘汰最久未访问的一个
     * （被淘汰的 id 从此取不到，url 变 404）。
     *
     * @param assets 子资源（key＝页面里写的相对名，如 `app.css`、`logo.png`）；允许为空。
     */
    fun put(html: String, assets: Map<String, ByteArray> = emptyMap()): PutResult {
        val base = urlBase ?: return PutResult.Failed(offlineReason)
        val id = UUID.randomUUID().toString()
        val copy = HashMap<String, ByteArray>(assets.size)
        for ((key, bytes) in assets) copy[key] = bytes.copyOf()
        var evicted: String? = null
        synchronized(lock) {
            if (slots.size >= CAPACITY) {
                evicted = slots.keys.firstOrNull()
                if (evicted != null) {
                    slots.remove(evicted)
                    evictedCount++
                }
            }
            slots[id] = Slot(id, html, copy)
        }
        return PutResult.Placed(id, "$base/p/$id", evicted)
    }

    /**
     * 取内容。**取本身就是一次访问**（LRU 里挪到最近）。
     * 不存在或已被淘汰 ⇒ null（对应 url 404，两者口径一致、不可区分）。
     */
    fun get(id: String): Slot? = synchronized(lock) { slots[id] }

    /** 显式记一次访问（给「页面开着但没再发请求」那种续命场景留着）。存在与否给布尔。 */
    fun touch(id: String): Boolean = synchronized(lock) { slots.containsKey(id) }

    /** 手动撤掉一份（卡删了 / 用户关了预览）。删掉与否给布尔。 */
    fun remove(id: String): Boolean = synchronized(lock) { slots.remove(id) != null }

    /** 清空槽位。**不动** [Stats.evicted] 的累计数，也不动绑定状态。 */
    fun clear() {
        synchronized(lock) { slots.clear() }
    }

    /** 当前读数。 */
    fun stats(): Stats = synchronized(lock) { Stats(CAPACITY, slots.size, evictedCount) }

    /**
     * 单测复位（槽是进程级单例，JUnit 同一 JVM 跑多个类会串味 ⇒ 每个用例开头调一次）。
     * internal：生产代码不许调，清场只有测试需要。
     */
    internal fun resetForTests() {
        synchronized(lock) {
            slots.clear()
            evictedCount = 0
        }
        release(NOT_STARTED)
    }
}
