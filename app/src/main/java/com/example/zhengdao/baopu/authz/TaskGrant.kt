// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百四十九批（任务级明确授权 v1）：授权**快照**的形状与校验。
//
// 派工单 §2 四要素（缺一条就不叫任务级授权）：
//   对象 = 列得出来的文件清单（不是「整个工作区」）；
//   动作 = 新建＋改正文（不含删／发／提交／执行／改权限）；
//   程度 = 对象≤10、单文件≤2MB、总写入≤5MB、动作≤30；
//   何时结束 = 任务结束／点停／到期／**进程重启**都失效。
//
// 本文件是**纯数据＋纯校验**：零 IO、零时钟、零全局状态。
// 快照发出去就不许改（[TaskGrant] 全是 val）；「能不能用」由 [TaskGrantRuntime] 的内存状态回答，
// 「放不放行」由 [TaskGrantGate] 的六步回答——三件事分在三个文件里，谁都不替另一个下结论（§4）。
package com.example.zhengdao.baopu.authz

import java.security.MessageDigest

/** 授权里能写哪些动作（**闭集**；[grantable] 是 §2 那句「不含…」的机器形态）。 */
internal enum class GrantActionType(val wire: String, val cn: String) {
    CREATE_TEXT("create_text", "新建文本"),
    EDIT_TEXT("edit_text", "改正文"),

    // 以下六种**永不可授权**：不是「这一版没做」，是 §2 点名的排除项。
    DELETE("delete", "删除"),
    SEND("send", "发送"),
    EXECUTE("execute", "执行"),
    CHANGE_PERMISSION("change_permission", "改权限"),
    GIT("git", "提交/推送"),
    OTHER("other", "其他"),
    ;

    val grantable: Boolean get() = this == CREATE_TEXT || this == EDIT_TEXT

    companion object {
        fun ofWire(wire: String): GrantActionType? = entries.firstOrNull { it.wire == wire }

        /** 可授权的全集（校验与弹卡都按它列，别处不再抄一份）。 */
        val GRANTABLE: List<GrantActionType> = entries.filter { it.grantable }
    }
}

/** 对象住哪（§2：v1 只认受控任务仓；共享目录**明确不支持**）。 */
internal enum class ObjectLocation(val wire: String, val cn: String) {
    CONTROLLED_TASK_STORE("controlled_task_store", "受控任务仓"),
    SHARED_FILE("shared_file", "共享目录文件"),
    ;

    companion object {
        fun ofWire(wire: String): ObjectLocation? = entries.firstOrNull { it.wire == wire }
    }
}

/** 快照时刻对象的状态（新建＝[Absent]，改正文＝[Present]）。 */
internal sealed interface ObjectState {

    /** 快照时还没有这个文件（新建）。 */
    data object Absent : ObjectState

    /**
     * 快照时已有：记下 sha256 与字节数。
     *
     * 为什么要哈希：只记「存在」的话，文件在两次动作之间被改过也照样对得上，
     * 「改正文」就变成了「改不知道哪一版」。
     */
    data class Present(val sha256: String, val bytes: Long) : ObjectState

    /** 审计栏/账本里的一句话（正文/路径不进这里）。 */
    val wire: String
        get() = when (this) {
            is Absent -> "absent"
            is Present -> "present:${sha256.take(8)}:$bytes"
        }

    companion object {
        fun ofWire(wire: String, sha256: String, bytes: Long): ObjectState? = when (wire) {
            "absent" -> Absent
            "present" -> if (sha256.isBlank() || bytes < 0L) null else Present(sha256, bytes)
            else -> null
        }
    }
}

/** 清单里的一个对象（对象 id ＋ 版本号 ＋ 落点 ＋ 快照状态）。 */
internal data class GrantObject(
    val objectId: String,
    val revision: Int,
    val taskId: String,
    val location: ObjectLocation = ObjectLocation.CONTROLLED_TASK_STORE,
    val state: ObjectState = ObjectState.Absent,
) {
    /** 稳定的对象句柄：`task:<taskId>:<objectId>@<revision>`（账本与勘验都认它）。 */
    val handle: String get() = handleOf(taskId, objectId, revision)

    companion object {
        fun handleOf(taskId: String, objectId: String, revision: Int): String =
            "task:$taskId:$objectId@$revision"
    }
}

/** §2 的四个数（**只许收紧**：调用方给的限额大于这里的值一律按这里）。 */
internal object GrantLimits {
    const val MAX_OBJECTS = 10
    const val MAX_BYTES_PER_FILE = 2L * 1024 * 1024
    const val MAX_BYTES_TOTAL = 5L * 1024 * 1024
    const val MAX_ACTIONS = 30

    /** 有效期上限（单调钟毫秒）＝60 分钟。**不是**被作废的那个悬挂授权窗口。 */
    const val MAX_LIFETIME_MONO_MS = 60L * 60 * 1000

    /** 授权能覆盖的最高风险档（R2；R3 永远逐次人批）。 */
    const val MAX_RISK_ORDINAL = 2
}

/** 一张授权的额度（可以比 [GrantLimits] 更紧，不能更松）。 */
internal data class GrantBudget(
    val maxObjects: Int = GrantLimits.MAX_OBJECTS,
    val maxActions: Int = GrantLimits.MAX_ACTIONS,
    val maxBytesPerFile: Long = GrantLimits.MAX_BYTES_PER_FILE,
    val maxBytesTotal: Long = GrantLimits.MAX_BYTES_TOTAL,
) {
    /** 只许收紧：超上限的一律按上限（负例「把限额调大」不成立）。 */
    fun clamped(): GrantBudget = GrantBudget(
        maxObjects = maxObjects.coerceIn(0, GrantLimits.MAX_OBJECTS),
        maxActions = maxActions.coerceIn(0, GrantLimits.MAX_ACTIONS),
        maxBytesPerFile = maxBytesPerFile.coerceIn(0L, GrantLimits.MAX_BYTES_PER_FILE),
        maxBytesTotal = maxBytesTotal.coerceIn(0L, GrantLimits.MAX_BYTES_TOTAL),
    )
}

/** 一张授权的**不可变快照**（§4：发出去就是事实，改它只能作废重发）。 */
internal data class TaskGrant(
    val grantId: String,
    val sessionId: Long,
    val taskId: String,
    /** 发出授权那一刻的进程代号（**重启即变**⇒老快照装不进来，§2 第四要素）。 */
    val processEpoch: String,
    /** 计划契约的哈希（批的是这份计划，不是「另一个任务」）。 */
    val contractHash: String,
    val objects: List<GrantObject>,
    val actions: List<GrantActionType>,
    /** 授权覆盖的最高风险档（0..3；只许 ≤ R2）。 */
    val maxRiskOrdinal: Int = GrantLimits.MAX_RISK_ORDINAL,
    val budget: GrantBudget = GrantBudget(),
    /** 发授时刻（单调钟毫秒）：判过期一律用它，不看墙钟。 */
    val issuedMonoMs: Long = 0L,
    /** 到期时刻（单调钟毫秒）。 */
    val expiresMonoMs: Long = 0L,
    /** 发授时的墙钟（**只给人看**，不参与任何判定）。 */
    val issuedWallMs: Long = 0L,
    /** 批准这件事在 208 的那条 id（勘验要按它对账）。 */
    val approvalEventId: String = "",
    /** 用户卡上看到的那份视图的哈希（「批的就是看到的」）。 */
    val presentedPayloadHash: String = "",
) {
    /** 生效的风险上限（快照自己再压一次，别处不用再看 3）。 */
    val maxRisk: Int get() = maxRiskOrdinal.coerceAtMost(GrantLimits.MAX_RISK_ORDINAL)

    val objectCount: Int get() = objects.size

    val objectIds: List<String> get() = objects.map { it.objectId }

    /** 按对象 id 或句柄取（账本与勘验两种写法都认）。 */
    fun objectOf(idOrHandle: String): GrantObject? =
        objects.firstOrNull { it.objectId == idOrHandle || it.handle == idOrHandle }

    fun allowsAction(action: GrantActionType): Boolean = action.grantable && action in actions

    /**
     * 快照哈希（不可变部分的规范化摘要；同一份授权永远同一个值）。
     *
     * 为什么要它：`presentedPayloadHash` 只证明「卡上显示的那份」；这一条证明
     * 「内存里这张快照」——两者对不上就是发授后被改过。
     */
    fun snapshotHash(): String = sha256Hex(
        buildString {
            append(grantId).append('|')
            append(sessionId).append('|')
            append(taskId).append('|')
            append(contractHash).append('|')
            append(maxRisk).append('|')
            append(budget.maxObjects).append('/').append(budget.maxActions).append('/')
            append(budget.maxBytesPerFile).append('/').append(budget.maxBytesTotal).append('|')
            objects.sortedBy { it.objectId }.forEach { o ->
                append(o.objectId).append('@').append(o.revision).append(':')
                append(o.location.wire).append(':').append(o.state.wire).append(';')
            }
            append('|')
            actions.sortedBy { it.wire }.forEach { append(it.wire).append(',') }
            append('|').append(issuedMonoMs).append('|').append(expiresMonoMs)
            append('|').append(approvalEventId).append('|').append(presentedPayloadHash)
        },
    )
}

/** 快照哪里不合法（**闭集**；一句人话给审计栏，不带路径）。 */
internal enum class GrantIssue(val wire: String, val cn: String) {
    EMPTY_FIELD("empty_field", "必填字段为空"),
    NO_OBJECTS("no_objects", "对象清单为空"),
    WHOLE_WORKSPACE("whole_workspace", "对象是整个工作区（不是可点的文件清单）"),
    SHARED_FILE("shared_file", "共享目录文件不在 v1 支持域"),
    TOO_MANY_OBJECTS("too_many_objects", "对象超过 10 个"),
    DUPLICATE_OBJECT("duplicate_object", "对象重复"),
    FOREIGN_OBJECT("foreign_object", "对象不属于这张任务"),
    BAD_REVISION("bad_revision", "对象版本号不合法"),
    FILE_TOO_BIG("file_too_big", "单文件超过 2MB"),
    TOTAL_TOO_BIG("total_too_big", "总写入超过 5MB"),
    ACTION_NOT_GRANTABLE("action_not_grantable", "授权里混进不可授权的动作"),
    BAD_LIFETIME("bad_lifetime", "有效期超过 60 分钟或非正"),
    RISK_TOO_HIGH("risk_too_high", "最高风险档超过 R2"),
}

/** 快照校验（纯函数：给定快照，要么空表，要么逐条说不合法在哪）。 */
internal object TaskGrantRules {

    /** 说「整个工作区」的那几种写法（§2 第一要素的负例判据本体）。 */
    private val WHOLE_WORKSPACE = setOf(
        "", "*", "**", "/", "\\", ".", "..", "workspace", "工作区", "整个工作区", "所有文件", "全部",
    )

    /** 这个「对象 id」是不是在说「整个工作区」。 */
    fun isWholeWorkspaceObject(objectId: String): Boolean {
        val t = objectId.trim().lowercase()
        if (t in WHOLE_WORKSPACE) return true
        if (t.startsWith("/") || t.startsWith("\\")) return true
        if (t.endsWith("/") || t.endsWith("\\")) return true
        if (t.contains("..")) return true
        if (t.contains('*')) return true
        return false
    }

    fun validate(grant: TaskGrant): List<GrantIssue> = buildList {
        if (grant.grantId.isBlank() || grant.taskId.isBlank() || grant.processEpoch.isBlank() ||
            grant.contractHash.isBlank() || grant.approvalEventId.isBlank() ||
            grant.presentedPayloadHash.isBlank() || grant.sessionId <= 0L
        ) {
            add(GrantIssue.EMPTY_FIELD)
        }
        if (grant.actions.isEmpty()) add(GrantIssue.ACTION_NOT_GRANTABLE)
        if (grant.actions.any { !it.grantable }) add(GrantIssue.ACTION_NOT_GRANTABLE)
        if (grant.maxRiskOrdinal > GrantLimits.MAX_RISK_ORDINAL) add(GrantIssue.RISK_TOO_HIGH)

        val budget = grant.budget.clamped()
        val lifetime = grant.expiresMonoMs - grant.issuedMonoMs
        if (lifetime <= 0L || lifetime > GrantLimits.MAX_LIFETIME_MONO_MS) add(GrantIssue.BAD_LIFETIME)

        if (grant.objects.isEmpty()) add(GrantIssue.NO_OBJECTS)
        if (grant.objects.size > budget.maxObjects) add(GrantIssue.TOO_MANY_OBJECTS)
        if (grant.objects.map { it.objectId }.distinct().size != grant.objects.size) add(GrantIssue.DUPLICATE_OBJECT)

        var total = 0L
        grant.objects.forEach { o ->
            if (o.objectId.isBlank() || o.taskId.isBlank()) add(GrantIssue.EMPTY_FIELD)
            if (isWholeWorkspaceObject(o.objectId)) add(GrantIssue.WHOLE_WORKSPACE)
            if (o.location != ObjectLocation.CONTROLLED_TASK_STORE) add(GrantIssue.SHARED_FILE)
            if (o.taskId != grant.taskId) add(GrantIssue.FOREIGN_OBJECT)
            if (o.revision < 0) add(GrantIssue.BAD_REVISION)
            val s = o.state
            if (s is ObjectState.Present) {
                if (s.sha256.isBlank() || s.bytes < 0L) add(GrantIssue.EMPTY_FIELD)
                if (s.bytes > budget.maxBytesPerFile) add(GrantIssue.FILE_TOO_BIG)
                total += s.bytes
            }
        }
        if (total > budget.maxBytesTotal) add(GrantIssue.TOTAL_TOO_BIG)
    }.distinct()

    fun isValid(grant: TaskGrant): Boolean = validate(grant).isEmpty()
}

/** 8 位十六进制摘要（快照哈希/显示用；不可逆、够区分）。 */
internal fun sha256Hex(text: String): String = runCatching {
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}.getOrDefault("")

/** 短摘要（8 位；显示用）。 */
internal fun sha8(text: String): String = sha256Hex(text).take(8)
