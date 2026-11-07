// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百零六批（2026-11-07）· 476 可撤销凭据——把「授权挂身份下」做成一句会拒绝的判断。
//
// 第五百一十七批（Q1 拍板落地）· 老凭据 **grandfather**：
// 506 留给用户拍板的那条（老凭据要不要 grandfather）在本单有了答案——**给 30 天迁移期**：
//   · 没有主人格的老凭据（`owner=UNBOUND`，身份制度之前签发的）不直接判死，打上
//     `legacy=true ＋ 绝对到期日`（默认签发迁移当天起 30 天）继续可用；
//   · 到期**自动失效**（[CredentialBinding.allows] 带时钟判），并**提示一次**
//     （提示落没落过由绑定表自己的 `legacyNoticeAt` 记账，见 [CredentialStore]）；
//   · 到期日是**绝对时刻**（epoch 毫秒落表），不是"30 天后"——时刻词禁令（U5）对表也一样生效。
// 判据⑪的假时钟测试钉的就是这三句。
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
 * @param owner 签发时盖章的身份 id；空串＝身份制度之前签发的历史凭据（Q1：给 30 天迁移期）
 * @param legacy Q1 迁移标记：true＝老凭据进了迁移期，`expiresAtMs` 就是它的到期日（**绝对时刻**）
 * @param expiresAtMs 迁移期到期日（epoch 毫秒；0＝没有到期日——正式凭据不设限期）
 */
internal data class Credential(
    val kind: CredentialKind,
    val handle: String,
    val owner: String,
    val issuedAtMs: Long,
    val legacy: Boolean = false,
    val expiresAtMs: Long = 0L,
) {
    /** 这条是不是在迁移期里的老凭据（打了标且有到期日才算数——半条标记不认）。 */
    val isLegacy: Boolean get() = legacy && expiresAtMs > 0L
}

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
 * 四句话：
 *   1. **没主人 ⇒ 看迁移期**（Q1，517 拍板）：打了 legacy 标且没到期 ⇒ 放行（grandfather）；
 *      没打标的老凭据 ⇒ 拦住点名要人看一眼（迁移 sweep 没跑到它——不静默放，也不静默杀）。
 *      这是 506 留给用户拍板那条的最终答案，替换掉「一律 NeedsHuman」的旧写法。
 *   2. **主人不在用 ⇒ 不放行**，理由按主人现在的状态分句（撤销／已轮换／查无此人），
 *      因为「为什么这条路走不通」是审计要读的东西，不该糊成一句「不行」。
 *   3. **legacy 到期 ⇒ 自动失效**（判据⑪）：带时钟判（时刻由调用方传，本层零时钟），
 *      拒因里带**绝对时刻**到期日（U5）。
 *   4. **本闸口不删任何东西**。撤销与到期都不删行——历史账要查得到是谁在什么时候放行的。
 */
internal object CredentialBinding {

    /** 没有主人时 [Credential.owner] 的字面值（稳定键：绑定表里就写这一串）。 */
    const val UNBOUND: String = ""

    /** 这条凭据现在能不能用（[rows]＝读回来的身份表，**顺序即时间序**，末条最新）。 */
    fun allows(cred: Credential, rows: List<IdentityRow>, nowMs: Long): FenceVerdict {
        if (cred.handle.isBlank()) {
            return FenceVerdict.Denied("凭据缺 handle（${cred.kind.wire}）——不认半条凭据")
        }
        if (cred.owner == UNBOUND) {
            // Q1：迁移期里的老凭据没到期就放行；到了期就失效，拒因带绝对时刻到期日。
            if (cred.isLegacy) {
                return if (nowMs >= cred.expiresAtMs) {
                    FenceVerdict.Denied(
                        "legacy 凭据已到期（到期=${AbsoluteTimeGuard.iso(cred.expiresAtMs)}）：迁移期结束，" +
                            "请用在用身份重新签发（${cred.kind.wire}/${cred.handle}）",
                    )
                } else {
                    FenceVerdict.Allowed
                }
            }
            return FenceVerdict.NeedsHuman(
                "这条 ${cred.kind.cn} 签发时还没身份制度且未进迁移期，说不清是谁批的（handle=${cred.handle}）",
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
    fun permitted(cred: Credential, rows: List<IdentityRow>, nowMs: Long): Boolean =
        allows(cred, rows, nowMs) is FenceVerdict.Allowed

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
    fun deniedCountAfterRevocation(creds: List<Credential>, rows: List<IdentityRow>, nowMs: Long): Int =
        creds.count { !permitted(it, rows, nowMs) }

    /** 轮换：老主人在用凭据 ⇒ 新主人的绑定行（逐条换，不批量放行）。 */
    fun rebind(creds: List<Credential>, previousId: String, newId: String, atMs: Long): List<Credential> =
        creds.map { c -> if (c.owner == previousId) c.copy(owner = newId, issuedAtMs = atMs) else c }
}

/**
 * 老凭据迁移（Q1，517）：把「没有主人格」的历史凭据收进**30 天迁移期**。
 *
 * 判据层三件（零 IO 零时钟）：
 *   · [grandfather]：给没打标的老凭据打 `legacy ＋ 绝对到期日`（sweep 只打标不删行）；
 *   · [expiredCount]：数到期的（提示与失效共用同一个口径，时刻由调用方传）；
 *   · 到期后的**失效判定**住在 [CredentialBinding.allows]（闸口只判一次，不另立第二把尺）。
 *
 * 「提示一次」的账面（落没落过提示）住在绑定表根上的 `legacyNoticeAt`
 * （见 [CredentialStore] 的读写）；本层只算「现在该不该提示」。
 */
internal object LegacyCredentials {

    /** 迁移期默认 30 天（派工单 Q1 原话「默认 30 天」；调用方要改只能改这里，不许逐条另定）。 */
    const val DEFAULT_TTL_MS: Long = 30L * 24L * 3_600_000L

    /**
     * 给没打标的老凭据（`owner=UNBOUND` 且没 legacy 标）打迁移标记。
     *
     * 到期日＝**本次 sweep 的时刻**＋默认期（绝对时刻落表）。已经打过标的**不动**——
     * 到期日不许因为多 sweep 几次而往后漂（漂了就不是限期了）。
     *
     * @return 打完标的全表 ＋ 本次新打标条数（0＝没有新迁移，装配层就别记一笔空账）
     */
    fun grandfather(
        creds: List<Credential>,
        nowMs: Long,
        ttlMs: Long = DEFAULT_TTL_MS,
    ): Pair<List<Credential>, Int> {
        var stamped = 0
        val out = creds.map { c ->
            if (c.owner == CredentialBinding.UNBOUND && !c.legacy && c.expiresAtMs == 0L) {
                stamped += 1
                c.copy(legacy = true, expiresAtMs = nowMs + ttlMs)
            } else {
                c
            }
        }
        return out to stamped
    }

    /** 已经到期的条数（提示与「该重签」读数共用）。 */
    fun expiredCount(creds: List<Credential>, nowMs: Long): Int =
        creds.count { it.isLegacy && nowMs >= it.expiresAtMs }
}
