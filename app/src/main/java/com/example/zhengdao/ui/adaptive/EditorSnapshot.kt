// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.ui.adaptive

/**
 * 746 状态保持的纯契约（判据②④）。
 *
 * 覆盖四样东西：输入框文字、滚动位置、当前文档、未提交编辑。
 * serialize/deserialize 是 `rememberSaveable` 底层 Bundle save/restore 的纯函数等价：
 *   - 只存可序列化标量（String / Int），与 Android Bundle 的能力一一对应；
 *   - null 用「键是否存在」区分「值为 null」，不把 null 拍成空串（空串与未设置是两回事）。
 *
 * 为什么纯函数而不是直接测 rememberSaveable：Compose 的 Saver 必须跑在组合里，JVM 无
 * compose 宿主；把契约抽出来 ⇒ 折叠夹具（半折→展开→合上来回切换）可在 JVM 真跑逐字对拍，
 * 且宿主侧用同一个 saver 接线（见 AdaptiveStateSaver.kt），测试与生产共用同一份逻辑。
 */
data class EditorSnapshot(
    val draftText: String = "",
    val scrollOffset: Int = 0,
    val documentUri: String? = null,
    val pendingEdit: String? = null,
) {
    companion object {
        const val KEY_DRAFT = "zd746.draft"
        const val KEY_SCROLL = "zd746.scroll"
        const val KEY_DOC = "zd746.doc"
        const val KEY_PENDING = "zd746.pending"
    }
}

/**
 * 模拟 Bundle 的纯序列化器。
 *
 * 故意做成 `open class` 而非 `object`：折叠夹具测试要子类化它注入"丢一个字/挪一格"等
 * 故障，证明判据真能咬住（否则对正确实现往返断言是恒真自证）。[Real] 是生产用实例。
 */
open class EditorSnapshotCodec {
    /** 模拟 Bundle.putString/putInt：null 时不写键。 */
    open fun serialize(s: EditorSnapshot): Map<String, Any> = buildMap {
        put(EditorSnapshot.KEY_DRAFT, s.draftText)
        put(EditorSnapshot.KEY_SCROLL, s.scrollOffset)
        s.documentUri?.let { put(EditorSnapshot.KEY_DOC, it) }
        s.pendingEdit?.let { put(EditorSnapshot.KEY_PENDING, it) }
    }

    /** 模拟 Bundle.getString/getInt：缺键回默认，保留「未设置」语义。 */
    open fun deserialize(m: Map<String, Any?>): EditorSnapshot = EditorSnapshot(
        draftText = m[EditorSnapshot.KEY_DRAFT] as? String ?: "",
        scrollOffset = (m[EditorSnapshot.KEY_SCROLL] as? Number)?.toInt() ?: 0,
        documentUri = m[EditorSnapshot.KEY_DOC] as? String,
        pendingEdit = m[EditorSnapshot.KEY_PENDING] as? String,
    )

    fun roundTrip(s: EditorSnapshot): EditorSnapshot = deserialize(serialize(s))

    companion object {
        /** 生产用实例（与夹具子类共享同一份往返逻辑）。 */
        val Real = EditorSnapshotCodec()
    }
}
