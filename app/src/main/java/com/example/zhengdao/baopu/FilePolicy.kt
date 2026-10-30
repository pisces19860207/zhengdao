// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百八十七批（2026-10-30 用户口径）：「应该和电脑的 agent 一样」——
// **干活全盘读写，产物落工作区**。工作区从「权限边界」降级为「默认落点」：
// 相对路径 / 不给路径 ⇒ 解析到工作区；绝对路径 ⇒ 全盘可读写（本文件的拒绝清单除外）。
//
// 本文件是**唯一**的「哪些地方一律不碰」判据，也是三道硬下限里的第三条：
// ① 破坏性动作必须确认（工具 `ToolPermission.CONFIRM` ＋ 确认卡文案）
// ② 覆盖前自动备份（`WriteFileTool`／`EditFileTool`，上限 [BACKUP_MAX_BYTES]）
// ③ **系统与凭据目录一律拒**（本文件）
//
// 刻意**没有**权限档、没有沙箱、没有开关（派工单 287「明确不做」那一节）：
// 抱朴本来就跑在 Android 应用沙箱里，无 root 碰不到系统区、碰不到别的应用的私有数据，
// 再叠一层"外部沙箱"在手机上既拉不起来、也不必要。
package com.example.zhengdao.baopu

import com.example.zhengdao.BuildConfig
import com.example.zhengdao.core.PathGuard
import java.io.File

/**
 * 全盘可读写（287）的唯一拒绝清单（**纯函数**：不碰 Android API、不读全局、不写盘）。
 *
 * 为什么只留这一张清单：安全不靠开关，靠三条默认行为；清单越短越不会误伤——
 * 用户要的是「像电脑上的 agent 一样」，不是「每走一步问一次」。
 */
internal object FilePolicy {

    /**
     * 覆盖前备份的大小上限（287 派工单：>5MB 不备份，但确认卡上要说明）。
     *
     * 为什么不备份大文件：把 200 MB 的视频复制一份只为"可回滚"，既慢又占空间；
     * 而这类文件本来也不是"改错一个字就毁掉"的形态。**不备份必须说出来**——
     * 静默跳过就是骗用户「我留了退路」。
     */
    const val BACKUP_MAX_BYTES = 5L * 1024 * 1024

    /** 系统区（绝对路径前缀，按**路径组件**比较——同前缀兄弟目录过不来）。 */
    private val SYSTEM_ROOTS = listOf(
        "/system", "/vendor", "/product", "/apex", "/proc", "/sys", "/dev", "/etc",
        "/bin", "/sbin", "/lib", "/lib64", "/root", "/acct", "/init.rc", "/ueventd.rc",
    )

    /** 凭据目录（任一路径段命中即拒：`.ssh` 里是私钥，`keystore` 里是签名密钥）。 */
    private val CRED_SEGMENTS = setOf(".ssh", ".gnupg", ".android", "keystore", ".keystore", "keychains")

    /** 凭据文件后缀（签名密钥/证书）。 */
    private val CRED_SUFFIX = listOf(".keystore", ".jks", ".p12", ".pfx")

    /** 凭据文件名（SSH 私钥）。 */
    private val CRED_NAMES = setOf("id_rsa", "id_dsa", "id_ecdsa", "id_ed25519")

    /** 别的应用的外置数据目录名（`Android/data/<包名>`、`Android/obb/<包名>`）。 */
    private val ANDROID_DATA_KINDS = setOf("data", "obb")

    /**
     * 判一个真实路径能不能碰：null = 可以；非 null = 给模型的拒绝原文（人话）。
     *
     * 为什么**绝对形态与规范形态各判一次**：规范形态（`canonicalPath`）会解析软链——这是必须的，
     * 否则 `ws/link -> /system` 就绕过去了；而绝对形态（未解软链）兜的是「目标还不存在、
     * canonicalize 只能做词法折叠」那一类。两个形态**任一**命中即拒：判定宁可保守，绝不放行。
     *
     * 平台口径：系统区（`/system` 一类）按 **POSIX 绝对路径**判——那是 Android 上 `File`
     * 的真实形态。JVM 单测跑在 Windows 上时，`File("/system/x")` 会被 JDK 解释成
     * `C://system//x`（盘符相对路径），所以这一条由纯函数 [denyReasonOf] 直测钉住，
     * 不靠 `File` 走一圈（那是测试宿主的语义，不是被测行为）。
     */
    fun denyReason(target: File, pkg: String = BuildConfig.APPLICATION_ID): String? =
        denyReasonOf(target.absolutePath, pkg) ?: denyReasonOf(normalize(target), pkg)

    /**
     * 纯判据本体（JVM 直测）：`path` 用 `/` 分隔的绝对路径，`pkg` 是本应用包名。
     *
     * 四条（顺序即优先级，第一条命中就返回）：
     * 1. 系统区（`/system`、`/proc`、`/dev` …）；
     * 2. `/data` 下**非本应用**的部分（`/data/data/<别的包>`、`/data/local/tmp` …）；
     * 3. 别的应用的外置数据目录（`Android/data/<别的包>`、`Android/obb/<别的包>`）；
     * 4. 凭据目录与密钥文件（`.ssh`／`keystore`／`*.jks` …）。
     *
     * 本应用自己的私有目录（`/data/data/<pkg>`、`/data/user/<n>/<pkg>`）**放行**——
     * 人设、记忆、库都在那儿，写自己的家不算越界。
     */
    fun denyReasonOf(path: String, pkg: String): String? {
        val segs = path.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        if (segs.isEmpty()) return null
        systemDeny(segs)?.let { return it }
        if (segs[0] == "data" && !isAppPrivate(segs, pkg)) {
            return "这是系统数据区（/data 下不属于本应用的部分），抱朴不碰。"
        }
        otherAppDeny(segs, pkg)?.let { return it }
        credentialDeny(segs)?.let { return it }
        return null
    }

    /** 规范路径（解析已存在的软链；拿不到就退回绝对路径——判定宁可保守，绝不放行）。 */
    fun normalize(target: File): String =
        runCatching { target.canonicalPath }.getOrDefault(target.absolutePath)

    /**
     * 根的归一写法（原 `WorkspaceGrantRules.rootKey`，287 随授权账本迁到这里）。
     *
     * 为什么留这一个：读侧搜索范围（`SearchScopeRules.rootKey`）要和工作区同一口径归一，
     * 两处各写一份就会出现「同一个目录两种字符串」的老毛病。
     */
    fun rootKey(root: File): String = normalize(root)

    private fun systemDeny(segs: List<String>): String? =
        SYSTEM_ROOTS.firstOrNull { root -> isUnder(segs, root) }
            ?.let { "这是系统目录（$it），抱朴不碰。" }

    /** `/data` 下是否本应用私有（`data/data/<pkg>` 或 `data/user(_de)/<n>/<pkg>`）。 */
    private fun isAppPrivate(segs: List<String>, pkg: String): Boolean = when {
        segs.size >= 3 && segs[1] == "data" && segs[2] == pkg -> true
        segs.size >= 4 && (segs[1] == "user" || segs[1] == "user_de") && segs[3] == pkg -> true
        else -> false
    }

    private fun otherAppDeny(segs: List<String>, pkg: String): String? {
        for (i in 0 until segs.size - 2) {
            if (segs[i] != "Android" || segs[i + 1] !in ANDROID_DATA_KINDS) continue
            val owner = segs[i + 2]
            if (owner != pkg) return "这是别的应用的数据目录（Android/${segs[i + 1]}/$owner），抱朴不碰。"
        }
        return null
    }

    private fun credentialDeny(segs: List<String>): String? {
        segs.firstOrNull { it.lowercase() in CRED_SEGMENTS }
            ?.let { return "「$it」是凭据目录（私钥/签名密钥），抱朴不碰。" }
        val name = segs.last().lowercase()
        if (name in CRED_NAMES) return "「$name」是私钥文件，抱朴不碰。"
        if (CRED_SUFFIX.any { name.endsWith(it) }) return "「$name」是密钥/证书文件，抱朴不碰。"
        return null
    }

    private fun isUnder(segs: List<String>, root: String): Boolean {
        val rootSegs = root.split('/').filter { it.isNotEmpty() }
        if (rootSegs.size > segs.size) return false
        return rootSegs.indices.all { segs[it].equals(rootSegs[it], ignoreCase = true) }
    }
}

/**
 * 给用户看的路径（287 定稿比喻第三条：「工具返回给用户看的位置，一律显示**相对工作区**的路径」）。
 *
 * 工作区内 ⇒ `资料库/设定.md`；工作区外 ⇒ **绝对路径**（相对路径会变成 `../../小说/…`
 * 这种越看越糊的东西，绝对路径才是手机用户能照着找的）。
 */
internal object FilePaths {

    /**
     * 覆盖前备份的**文件名**（287：全盘可写之后，备份名不能再直接拿绝对路径拼）。
     *
     * 工作区内 ⇒ 相对路径扁平化（`产物_设定.md.20261030-101010.bak`，人能看懂是哪个文件）；
     * 工作区外 ⇒ **只取文件名 ＋ 路径哈希**——绝对路径扁平化在 Android 上会长到超过文件名
     * 长度上限（255 字节），在 Windows 上还会带冒号（非法字符）。哈希只负责让不同目录的
     * 同名文件不互相顶掉备份。
     */
    fun backupName(root: File, file: File, stamp: String): String {
        val inside = runCatching { PathGuard.isInside(root, file) }.getOrDefault(false)
        val base = if (inside) {
            display(root, file)
        } else {
            file.name + "-" + Integer.toHexString(file.absolutePath.hashCode())
        }
        return base.replace('/', '_').replace('\\', '_').replace(':', '_') + "." + stamp + ".bak"
    }

    fun display(root: File, file: File): String {
        val inside = runCatching { PathGuard.isInside(root, file) }.getOrDefault(false)
        if (!inside) return FilePolicy.normalize(file)
        val rel = runCatching { root.toPath().relativize(file.toPath()).toString() }.getOrNull()
        return rel?.replace('\\', '/')?.ifBlank { "." } ?: file.name
    }
}
