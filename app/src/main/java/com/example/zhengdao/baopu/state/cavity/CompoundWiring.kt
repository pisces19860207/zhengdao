// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.state.cavity

import com.example.zhengdao.baopu.state.geometry.CompoundPairs
import com.example.zhengdao.baopu.state.geometry.CompoundProxy
import com.example.zhengdao.baopu.state.geometry.ConvexShape
import com.example.zhengdao.baopu.state.geometry.Gjk
import com.example.zhengdao.baopu.state.geometry.SegmentProxy

internal data class CompoundRegistration(val partId: String, val proxy: CompoundProxy)

internal object CompoundWiring {

    private data class Config(
        val registrations: (() -> List<CompoundRegistration>)?,
        val enabled: Boolean,
        val trace: ((String) -> Unit)?,
    )

    @Volatile
    private var config: Config? = null

    fun install(
        registrations: (() -> List<CompoundRegistration>)? = null,
        enabled: Boolean = false,
        trace: ((String) -> Unit)? = null,
    ) {
        config = Config(registrations, enabled, trace)
    }

    fun detach() {
        config = null
    }

    fun compoundsFor(ids: Collection<String>): Map<String, CompoundProxy> {
        val current = config ?: return emptyMap()
        if (!current.enabled) return emptyMap()
        val wanted = ids.toSet()
        if (wanted.isEmpty()) return emptyMap()
        val selected = current.registrations?.invoke().orEmpty().filter { it.partId in wanted }
        if (selected.isEmpty()) return emptyMap()
        return ObservedCompounds(selected.associate { it.partId to it.proxy }, current.trace)
    }

    fun candidate(
        aId: String,
        bId: String,
        aShape: ConvexShape,
        bShape: ConvexShape,
    ): Boolean {
        val compounds = compoundsFor(listOf(aId, bId))
        val pair = CompoundPairs.read(SegmentProxy(aId, aShape), SegmentProxy(bId, bShape), compounds)
        return pair?.intersecting ?: Gjk.distance(aShape, bShape).intersecting
    }

    private class ObservedCompounds(
        private val selected: Map<String, CompoundProxy>,
        private val trace: ((String) -> Unit)?,
    ) : Map<String, CompoundProxy> by selected {
        override fun get(key: String): CompoundProxy? {
            val proxy = selected[key] ?: return null
            trace?.invoke("[compound] query id=$key chunks=${proxy.chunks.size}")
            return proxy
        }
    }
}
