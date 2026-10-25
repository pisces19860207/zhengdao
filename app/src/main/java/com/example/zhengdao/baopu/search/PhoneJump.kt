// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十八批（派工单 §1⑤）：**回跳的判据层**——一条检索命中该给哪几个动作，
// 全在这张纯函数里定完；真正执行动作的代码在 `ui/baopu/BaopuPhoneSearchCard.kt`
// （读侧内核不引 UI 符号，也不引 `BaopuRuntime`：本文件的 import 表是空的，
// `SearchReadNeverGrantsWriteTest.结构_读侧只被允许引这些baopu包根符号` 盯着这件事）。
//
// ## 三条路径（派工单 §1⑤ 原话「`artifact.session_id` → `.归属.jsonl` → 文件本身」）
// 1. **台账**：这篇文件在**主库** `artifact` 表里有一行（抱朴的产物台账，第一百九十六批口径），
//    那一行带 `session_id` ⇒ 直接跳回产生它的那个会话。这是最准的一条：谁写的、什么时候写的都有账。
// 2. **归属**：`.归属.jsonl`——**这条本批恒不出现**。它属于第一百九十九批（`docs/FEATURE-LEDGER.md:204`
//    原话「`.归属.jsonl` 记 `_id`+`sha256`+owner」，S4 的活），今天全仓没有任何一处产出或读取这个文件
//    （`SearchAdmission.kt:45` 里那个名字只是排除清单注释里的一个例子）。所以本批把这条做成
//    **类型化的挂点**：[plan] 接一个 `ownershipOwner`，非空才给 [JumpAction.OWNERSHIP]。
//    199 落地时它自己就有地方接，不用回来改判据的形状。
// 3. **文件本身**：前两条都没有 ⇒ 只能就地看——[JumpAction.PREVIEW] 复用第一百九十六批那条
//    通用只读查看器（`artifactViewOf(root, relPath)`／`BaopuArtifactViewer(root, relPath, onDismiss)`，
//    两者对 root 没有要求，外部根一样能读），加 [JumpAction.COPY_INTO_VAULT] 把正文复制进证道。
//
// ## 为什么没有「用别的 App 打开」
// 派工单 §1⑤ 那句「只给打开/预览/复制进证道」里的**打开**这一项，实测做不了而不该硬做：
// `AndroidManifest.xml` 里唯一的 FileProvider（`${applicationId}.baopu.files`）的 paths 只放
// 相机缓存目录；要外部 App 读到全盘任意一个文件，就得把这个 provider 加宽到
// `Environment.getExternalStorageDirectory()` —— 那是**安全面**的改动，不是一句文案，
// 归 S4 或单独一批裁（报告 §5 记这条，失效方向是「少给一个动作」，不是多给）。
//
// ## 一条红线（本文件的返回值为什么不含任何「写」语义）
// [JumpAction] 里没有「改这个文件」「删这个文件」。命中一条外部文件之后能做的事，与没搜索时
// 完全一样；读侧账本一张都不许变成写侧批准（派工单 §1⑥ 那条硬不变式，S2 已经用
// `SearchReadNeverGrantsWriteTest` 钉住）。[JumpAction.COPY_INTO_VAULT] 是唯一会落盘的动作为，
// 而它写的是**证道自己的私有/工作区目录**、由用户点一下触发、走写侧原有的边界判据。
package com.example.zhengdao.baopu.search

/** 一条命中可给的动作（顺序＝[JumpPlan.actions] 的展示顺序）。 */
internal enum class JumpAction {
    /** 路径①：回产生它的会话（[JumpPlan.sessionId] 必为正数）。 */
    SESSION,

    /** 路径②：回归属记录（第一百九十九批落地后才有；本批恒不产出）。 */
    OWNERSHIP,

    /** 路径③-a：就地只读预览（[com.example.zhengdao.baopu.artifactViewOf] 那套，不离开 App）。 */
    PREVIEW,

    /** 路径③-b：把正文复制进证道（唯一的落盘动作，写在 UI 侧，用户点确认才做）。 */
    COPY_INTO_VAULT,
}

/** 一条检索命中（`rootId`＋`rel_path` 就是 `doc` 表那一行的身份）。 */
internal data class JumpTarget(
    val rootId: Long,
    /** 授予根的绝对路径（已经过 [SearchScopeRules.rootKey] 归一——库里存的就是它）。 */
    val absRoot: String,
    val relPath: String,
    /** 这个根是不是当前工作区根本身（丁档那一条；外部根为 false）。 */
    val rootIsWorkspace: Boolean,
)

/** 定好的动作表＋要带给动作的数据（UI 只管按表渲染，不再自己判优先级）。 */
internal data class JumpPlan(
    val actions: List<JumpAction>,
    val sessionId: Long,
    val ownershipOwner: String,
) {
    val hasSession: Boolean get() = sessionId > 0L && JumpAction.SESSION in actions
}

/**
 * 回跳判据（**纯函数**）：三条路径按「有账 ⇒ 有归属 ⇒ 只有文件」的优先级排，命中即止。
 *
 * 参数为什么是这三个读数而不是一个 store：`artifact` 在**主库**、`.归属.jsonl` 在盘上，
 * 两者都不是 `search.db` 的表；本文件只吃「已经查出来的数」，查询动作留在 UI 侧，
 * 这样读侧内核一个连接都不开（`SearchDb.readable()` 只在 `search.db` 那三张表上用）。
 *
 * @param artifactSessionId 台账里那一行的 `session_id`；`null` = 这篇不是抱朴的产物。
 * @param ownershipOwner 归属文件里的 owner；`null`/空 = 没有归属记录（本批恒如此）。
 */
internal object PhoneJump {

    /**
     * 查主库 `artifact` 台账用的一条只读语句（**放在这里而不是 [SearchSql]**：
     * `SearchSqlTest` 按「四张表」逐字对账语句里的表名，`artifact` 是主库的表，混进去
     * 等于让门禁以为搜索库也有这张表）。
     *
     * 两个绑参都是「位置」而不是「内容」：workspace 是归一后的根、rel_path 是库里的原值 ⇒
     * 查询词一个字都不进这条语句。
     */
    const val SELECT_ARTIFACT_SESSION = "SELECT session_id FROM artifact WHERE workspace = ? AND rel_path = ? LIMIT 1"

    /** 台账一行（`null` = 没这一行；负数 id 当没这一行处理，`session` 表里 id 从 1 起）。 */
    fun artifactSessionIdOf(sessionIdColumn: Long?): Long? =
        if (sessionIdColumn == null || sessionIdColumn <= 0L) null else sessionIdColumn

    /**
     * 主库那行的匹配前提：`artifact.workspace` 存的是 `spillRoot().absolutePath`（RunEngine.kt:489），
     * 而 `scope.abs_root` 存的是 canonical 归一串（[SearchScopeRules.rootKey]）——**两边写法不同**。
     * 同一个目录（真机上 `hostDir` 已是 canonical）时两者相等；不相等就是「工作区换了写法」，
     * 此时按「台账没命中」处理 ⇒ 退回路径③，宁可少一个跳转按钮，不许跳到别的目录的同名文件去。
     */
    fun workspaceMatches(absRoot: String, artifactWorkspace: String): Boolean =
        absRoot.trimEnd('/') == artifactWorkspace.trimEnd('/') && absRoot.isNotBlank()

    /** 一条命中的动作表（顺序＝界面渲染顺序；[JumpAction.PREVIEW] 与 [JumpAction.COPY_INTO_VAULT] 永远给）。 */
    fun plan(
        target: JumpTarget,
        artifactSessionId: Long?,
        ownershipOwner: String? = null,
    ): JumpPlan {
        val sid = artifactSessionIdOf(artifactSessionId)
        val owner = ownershipOwner?.trim().orEmpty()
        val actions = mutableListOf<JumpAction>()
        // 路径①：台账命中 ⇒ 回会话。只给「这个根就是当前工作区」的命中——`artifact` 按 workspace 隔离，
        // 外部根（PHONE）的文件不可能有台账行，真出现了说明上面那条 `workspaceMatches` 判错了。
        if (sid != null && target.rootIsWorkspace) actions += JumpAction.SESSION
        // 路径②：没台账但有归属 ⇒ 回归属记录（本批恒不满足，见文件头第二条）。
        if (sid == null && owner.isNotEmpty()) actions += JumpAction.OWNERSHIP
        // 路径③：就地只读预览 ＋ 复制进证道——前两条成不成立都给，用户至少总能看到文件本身。
        actions += JumpAction.PREVIEW
        actions += JumpAction.COPY_INTO_VAULT
        return JumpPlan(actions.toList(), sid ?: 0L, owner)
    }

    /** 工作区根的写法（唯一真源还是 [SearchScope]，本函数只把「是不是同一个根」这句判断收在这儿）。 */
    fun isWorkspaceRoot(absRoot: String, workspaceRootKey: String): Boolean =
        absRoot.isNotBlank() && workspaceRootKey.isNotBlank() &&
            absRoot.trimEnd('/') == workspaceRootKey.trimEnd('/')
}
