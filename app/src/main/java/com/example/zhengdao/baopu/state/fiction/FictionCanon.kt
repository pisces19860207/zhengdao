// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百五十九批 · 虚构题材模式 · 锁结设定谱（canon）层。
// 命名口径（2026-11-20 用户定）：这一层叫**设定谱 canon**，不叫"作者假设"——
// 假设是猜的，设定是定的。字段带 `canonVersion`＋`asOfCanon`，引擎可引用、不可改写，
// 改须人批升版（升版由源码闸 FictionSourceAssertTest 的"内容哈希↔批准版本"对拍钉住）。
//
// 三条硬边界（派工单 759）：
// · 本包**零引用**事实层（665 facts／669 词表／660 人体模型）——物理隔离，不是纪律口号；
// · 07 自带禁令落成**断言**：人类无犬科球状体，lockSource 出现球状体/knot/鼓球即红（BulbusGuard）；
// · 机制归 743 约束档（通道/压紧），本层只给题材开关与设定谱字段，**不新建解剖结构**。
package com.example.zhengdao.baopu.state.fiction

// CANON-BEGIN —— 设定谱正文块：源码闸按"块内容哈希 ↔ 批准版本"对拍；
// 改块内任何一字而 canonVersion 不递增 ⇒ 判据③红。块外注释与说明文字不计入哈希。

/** 进入深度四阶（档位，不给厘米——派工单 §三①）。 */
internal enum class DepthStage(val stage: Int, val cn: String) {
    NOT_ENTERED(0, "未入"),
    TO_NECK_MOUTH(1, "至颈口"),
    IN_NECK_CANAL(2, "入颈管"),
    IN_CAVITY(3, "入腔"),
}

/** 卡住位置（派工单 §三②；双压＝冠+体，对齐 07「龟头体双重卡压最重」）。 */
internal enum class LockPoint(val cn: String) {
    CORONA_BARB("冠倒钩"),
    CORONAL_GROOVE("冠状沟"),
    GLANS_BODY("龟头体"),
    NECK_MOUTH("颈口"),
    NECK_CANAL("颈管"),
    CAVITY("腔"),
    DUAL_PRESS("双压(冠+体)"),
}

/** 锁定力来源三值闭集（派工单 §三③）——**排除人类球状体**由 BulbusGuard 断言兜底。 */
internal enum class LockSource(val cn: String) {
    REFLEX_CONTRACTION("反射性收缩"),
    PRESSING("压紧"),
    BOTH("两者叠加"),
}

/** 持续档（派工单 §三④前半：短/中/长，不给分钟数）。 */
internal enum class LockDuration(val cn: String) {
    SHORT("短"),
    MEDIUM("中"),
    LONG("长"),
}

/** 解锁条件＝收缩消退档（派工单 §三④后半；回退环 d「收缩消退档」）。 */
internal enum class ReleaseCondition(val cn: String) {
    SUBSIDING("收缩初退"),
    SUBSIDED("收缩消退"),
    RELEASED("消退放行"),
}

/** 锁结期间硬度分布（派工单 §三⑤）。 */
internal enum class FirmnessDuringLock(val cn: String) {
    WHOLE_SWOLLEN("整根胀满"),
    GLANS_BODY_HARDEST("龟头体最硬"),
    CORONA_HARDEST("冠最硬"),
}

// CANON-END

/**
 * 设定谱的版本与时点（唯一住处）。升版＝人批：改上方 CANON 块内容必须同时把
 * [CANON_VERSION] 递增，并由验收方更新源码闸里的批准哈希——三处不同步即红。
 */
internal object FictionCanon {
    const val CANON_VERSION = 1
    const val AS_OF_CANON = "2026-11-21"

    /** 三级来源命名（759 单 §零）：设定谱的 source 值就叫 canon，不叫"作者假设"。 */
    const val SOURCE_CANON = "canon"

    /** 现实性标记：与 723 变异谱的 Reality.FICTION 同词不同层——本包不 import 它（物理隔离）。 */
    const val REALITY_FICTION = "fiction"
}

/**
 * 07 自带禁令的**断言**实现（判据②⑤）：人类男性没有犬科球状体结构。
 * 任何要进 `lockSource`（或名义为"锁定力来源"）的文本，先过这道闸；
 * 命中禁词（含同义写法）⇒ 拒收抛错，不是警告、不是注释。
 */
internal object BulbusGuard {

    /** 禁词闭集：球状体/球状腺/鼓球/膨大结/结状物 ＋ 拉丁与英文同义写法。 */
    val FORBIDDEN_WORDS = listOf("球状体", "球状腺", "鼓球", "膨大结", "结状物", "knot", "bulbus")

    /** 命中即抛（大小写不敏感只针对拉丁词；中文词原样扫）。 */
    fun rejectIfBulbus(field: String, value: String) {
        val lower = value.lowercase()
        for (w in FORBIDDEN_WORDS) {
            if (lower.contains(w.lowercase())) {
                throw IllegalArgumentException(
                    "判据②红：$field 出现犬科球状体字样「$w」——07 禁令：人类男性没有犬科球状体结构（$value）",
                )
            }
        }
    }
}

/**
 * 一条锁结设定记录（全字段只读：`val` ＋ 无写口 ＋ 源码闸哈希）。
 *
 * 判据①：`depthStage` 与 `lockPoint` **必须成对**——只给深度不给卡点、
 * 或只给卡点不给深度，构造即抛（缺一红）。
 * 其余字段（时长/解锁/硬度）在成对锁定时必填；全字段带 canon 来源戳。
 */
internal data class LockCanon(
    val depthStage: DepthStage?,
    val lockPoint: LockPoint?,
    val lockSource: LockSource,
    val lockDuration: LockDuration,
    val releaseCondition: ReleaseCondition,
    val firmnessDuringLock: FirmnessDuringLock,
    val source: String,
    val canonVersion: Int,
    val asOfCanon: String,
) {
    init {
        // 判据①：成对约束。两边必须同时有值（锁结记录不存在"半条"）。
        require((depthStage != null) == (lockPoint != null)) {
            "判据①红：depthStage 与 lockPoint 必须成对（depth=$depthStage point=$lockPoint 缺一即拒）"
        }
        require(depthStage != null && lockPoint != null) {
            "判据①红：锁结记录必须同时给出 depthStage 与 lockPoint"
        }
        // 判据②⑤：来源三值闭集之外的文本走 BulbusGuard（枚举本身不含禁词，
        // 但输出侧与外部喂入仍逐值扫——防"把球状体塞进 cn 字样"的变异）。
        BulbusGuard.rejectIfBulbus("lockSource", lockSource.cn)
        require(source == FictionCanon.SOURCE_CANON) {
            "判据③红：设定谱字段 source 恒为 canon，得 $source"
        }
        require(canonVersion == FictionCanon.CANON_VERSION) {
            "判据③红：canonVersion 与设定谱当前版不符（记录=$canonVersion 当前=${FictionCanon.CANON_VERSION}）"
        }
        require(asOfCanon == FictionCanon.AS_OF_CANON) {
            "判据③红：asOfCanon 与设定谱时点不符（记录=$asOfCanon 当前=${FictionCanon.AS_OF_CANON}）"
        }
    }
}
