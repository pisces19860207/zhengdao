// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百零六批（2026-11-07）· 476 可撤销凭据——把「授权挂身份下」做成一句会拒绝的判断。
//
// 派工单 §三·② 的原话：授权（租约／白名单／站点信任）挂身份下 ⇒ **身份停用即凭据全失效**。
// 这一句要落地，必须回答「凭据在哪儿记着它属于谁」。本仓现有五族凭据，**没有一族带主人**：
//   · 任务级授权租约  —— `baopu/authz/TaskGrant.kt`（挂 `taskId + processEpoch`，进程代号每次重启就换）
//   · 工具白名单/组长期同意 —— `baopu/BaopuRuntime.kt` 的 `BaopuToolPrefs`（SP 里两组集合，无主人格）
//   · 回放站点信任    —— `baopu/TeachSiteTrust.kt`（按 host 字符串，无主人格）
//   · MCP 令牌        —— `baopu/mcp/McpTokenVault.kt`（按 serverId，已有「已撤销 marker」可照抄形状）
//   · 渠道密钥池      —— `baopu/KeyPool.kt` ＋ `ProviderPrefs`（按 providerId）
// 所以本文件不假装「已经挂好了」，而是把**挂法**定成一格 `owner`，并给出一个所有人都能过的闸口 [allows]：
// 闸口只认**读回来的身份表**，不认模型自述、也不认调用方口头保证「这是本人批的」。
//
// 房型同 [IdentityRules]：零 IO、零时钟、零 Android，判据拿手搓的行直测。
//
// 一条不许弯的口径：**失效 ≠ 删除**。撤销之后凭据行还在（历史账保留，派工单 §三·③），
// 只是每一次使用都会被闸口拒。把「失效」实现成「删表」是最坏的一种实现——
// 看着干净，出事之后查不到是谁在什么时候放行的。
package com.example.zhengdao.baopu.consequence

/**
 * 凭据族（**闭集**：就是上面那五族。自造一族 ⇒ [CredentialBinding.allows] 直接拒）。
 *
 * @param wire 落进绑定表的稳定键
 * @param home 这一族今天实际住在哪个类（装配层去那儿加 `owner` 时按这个找）
 */
internal enum class CredentialKind(val wire: String, val cn: String, val home: String) {
    TASK_GRANT("task_grant", "任务授权租约", "baopu/authz/TaskGrant.kt"),
    TOOL_WHITELIST("tool_whitelist", "工具白名单/组同意", "baopu/BaopuRuntime.kt"),
    SITE_TRUST("site_trust", "回放站点信任", "baopu/TeachSiteTrust.kt"),
    MCP_TOKEN("mcp_token", "MCP 令牌", "baopu/mcp/McpTokenVault.kt"),
    KEY_POOL("key_pool", "渠道密钥池", "baopu/KeyPool.kt"),
    ;

    companion object {
        /** 线值 ⇒ 族；认不出 ⇒ null（不静默当别族）。 */
        fun ofWire(wire: String): CredentialKind? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * 一条凭据绑定（`owner` 就是「挂在谁名下」那格）。
 *
 * @param handle 这一族里的稳定标识（host／serverId／providerId／grantId／组 key），**不是内容**
 * @param owner 签发时盖章的身份 id；空串＝身份制度之前签发的历史凭据（见 [CredentialBinding] 的 legacy 口径）
 */
internal data class Credential(
    val kind: CredentialKind,
    val handle: String,
    val owner: String,
    val issuedAtMs: Long,
)

/** 一次「能不能用这条凭据」的判定结果。 */
internal sealed interface FenceVerdict {

    /** 放行：主人就是在用的那个身份。 */
    data object Allowed : FenceVerdict

    /** 拦住，并给一句为什么（这句话要能落账，不许是空串）。 */
    data class Denied(val reason: String) : FenceVerdict

    /** 拦住且**点名要人看一眼**：撤销发生了，但这条凭据连主人都没有，说不清是谁批的。 */
    data class NeedsHuman(val reason: String) : FenceVerdict
}

/**
 * 凭据闸口。
 *
 * 三句话：
 *   1. **没主人 ⇒ 不放行**（fail-closed）。这是本批留给用户拍板的一条：老凭据要不要 grandfather，
 *      见交付报告 §口径 Q1——代码里先按「不 grandfather」写，因为「凭据全失效」不许有暗例外。
 *   2. **主人不在用 ⇒ 不放行**，理由按主人现在的状态分句（撤销／已轮换／查无此人），
 *      因为「为什么这条路走不通」是审计要读的东西，不该糊成一句「不行」。
 *   3. **本闸口不删任何东西**。撤销的落点只有身份表那一格状态。
 */
internal object CredentialBinding {

    /** 没有主人时 [Credential.owner] 的字面值（稳定键：绑定表里就写这一串）。 */
    const val UNBOUND: String = ""

    /**
     * 这条凭据现在能不能用（[rows]＝读回来的身份表，**顺序即时间序**，末条最新）。
     *
     * 注意它收的是表、不是一个 id：判「主人还在用」必须看主人的**状态**，
     * 只看「id 在表里」会把已撤销的身份读成有效——那正是判据②要钉死的那种假绿。
     */
    fun allows(cred: Credential, rows: List<IdentityRow>): FenceVerdict {
        if (cred.handle.isBlank()) {
            return FenceVerdict.Denied("凭据缺 handle（${cred.kind.wire}）——不认半条凭据")
        }
        if (cred.owner == UNBOUND) {
            return FenceVerdict.NeedsHuman(
                "这条 ${cred.kind.cn} 签发时还没身份制度，说不清是谁批的（handle=${cred.handle}）",
            )
        }
        val owner = rows.firstOrNull { it.id == cred.owner }
            ?: return FenceVerdict.Denied("凭据的主人查无此人（owner=${cred.owner}）")
        return when (owner.state) {
            IdentityState.ACTIVE -> FenceVerdict.Allowed
            IdentityState.REVOKED -> FenceVerdict.Denied(
                "身份已撤销 ⇒ 名下凭据全失效（owner=${owner.id}）",
            )
            IdentityState.ROTATED_OUT -> FenceVerdict.Denied(
                "身份已轮换到别条 ⇒ 老凭据不跟着走（owner=${owner.id}）",
            )
        }
    }

    /** 放行与否的短答（装配层只想要一个布尔时用它；理由仍走 [allows]）。 */
    fun permitted(cred: Credential, rows: List<IdentityRow>): Boolean =
        allows(cred, rows) is FenceVerdict.Allowed

    /**
     * 签发一条绑定（身份必须在用，否则签出来的凭据一落地就是死的——那不如不签）。
     *
     * @return null＝没在用身份，拒签（不给「先签了再说」留路）
     */
    fun issue(kind: CredentialKind, handle: String, rows: List<IdentityRow>, nowMs: Long): Credential? {
        val active = IdentityRules.activeId(rows)
        if (active.isEmpty() || handle.isBlank()) return null
        return Credential(kind = kind, handle = handle, owner = active, issuedAtMs = nowMs)
    }

    /**
     * 撤销之后还剩几条凭据被这张闸拦住（**含查无此人的**，一条都不许悄悄漏）。
     *
     * 这一句是给「撤销即全失效」做**可核对**用的：装配层撤销完要能回答「拦住了几条」，
     * 回答不出来就等价于没人验过。
     */
    fun deniedCountAfterRevocation(creds: List<Credential>, rows: List<IdentityRow>): Int =
        creds.count { !permitted(it, rows) }

    /** 轮换：老主人在用凭据 ⇒ 新主人的绑定行（逐条换，不批量放行）。 */
    fun rebind(creds: List<Credential>, previousId: String, newId: String, atMs: Long): List<Credential> =
        creds.map { c -> if (c.owner == previousId) c.copy(owner = newId, issuedAtMs = atMs) else c }
}
