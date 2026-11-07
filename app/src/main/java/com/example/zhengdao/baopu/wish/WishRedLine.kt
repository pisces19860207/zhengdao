// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百一十批 · 许愿流水线 §二①：三条硬红线之「生成期就拦」。
// 草稿含删原件/转账/外发/改系统目录 ⇒ 拒生成（不是生成后警告）。
// 纯函数，无 IO；与 SkillWritePolicy.forbiddenFindings 互补（那个拦代码块/权限词，
// 这个拦危险操作意图）。判据⑦：三类危险草稿各拒一条。
package com.example.zhengdao.baopu.wish

/** 四类危险操作（派工单 §二①）。 */
enum class DangerKind(val label: String) {
    DELETE_ORIGINAL("删原件（无回收站）"),
    TRANSFER("转账/付费"),
    EXTERNAL_SEND("对外发送"),
    SYSTEM_DIR("改系统目录/凭据"),
}

/** 生成期红线闸（纯函数；空列表＝安全，非空＝拒生成）。 */
internal object WishRedLine {

    private val DELETE_PATTERNS = listOf(
        "删原件", "删除原件", "删除原文件", "删掉原文件", "永久删除", "彻底删除",
        "rm -rf", "rm -f", "del /f", "格式化磁盘",
    )

    private val TRANSFER_PATTERNS = listOf(
        "转账", "付费", "支付", "汇款", "打款", "扣款", "充值",
    )

    private val SEND_PATTERNS = listOf(
        "外发", "对外发送", "发送到外部", "上传到服务器", "发邮件给",
        "发到网上", "公开发布", "post to", "upload to",
    )

    private val SYSTEM_PATTERNS = listOf(
        "系统目录", "修改凭据", "改密码", "改系统设置", "/system/",
        "credentials", "注册表", "改hosts",
    )

    /** 扫描正文里的危险操作意图（纯函数；命中即拒，不是警告）。 */
    fun scan(body: String): List<DangerKind> {
        val out = mutableListOf<DangerKind>()
        if (DELETE_PATTERNS.any { body.contains(it, ignoreCase = true) }) out += DangerKind.DELETE_ORIGINAL
        if (TRANSFER_PATTERNS.any { body.contains(it) }) out += DangerKind.TRANSFER
        if (SEND_PATTERNS.any { body.contains(it, ignoreCase = true) }) out += DangerKind.EXTERNAL_SEND
        if (SYSTEM_PATTERNS.any { body.contains(it, ignoreCase = true) }) out += DangerKind.SYSTEM_DIR
        return out
    }

    /** 拒生成理由（null＝安全可以生成；非 null＝拒生成并说明）。 */
    fun refuseReason(body: String): String? {
        val hits = scan(body)
        if (hits.isEmpty()) return null
        return "拒生成：草稿含危险操作——" + hits.joinToString("、") { it.label }
    }
}