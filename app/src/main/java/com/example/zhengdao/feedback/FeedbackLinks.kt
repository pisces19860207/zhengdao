// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.feedback

/**
 * 反馈外链的**字符串构造**（第四百五十二批）。
 *
 * 这里只拼 URL，不发起任何请求：真正的「打开」发生在设置页的点击回调里，
 * 由系统浏览器或邮件应用接手。本文件零 Android 依赖、零 java.net、零网络 API。
 * 百分号编码自己写，就是为了不在反馈这条链上引入网络栈。
 */
internal object FeedbackLinks {

    const val REPO = "pisces19860207/zhengdao"
    const val ISSUES_NEW = "https://github.com/" + REPO + "/issues/new"
    const val BUG_TEMPLATE = "bug.yml"
    const val CONTACT = "putaopanpan@gmail.com"
    const val BUG_TITLE = "[问题] 证道反馈"
    const val MAIL_SUBJECT = "证道反馈"

    private const val HEX = "0123456789ABCDEF"

    /**
     * 新建问题页：带上模板、标题与诊断块。Issue Form 未必吃 `body` 预填，
     * 所以设置页的文案同时写明「没自动带入就粘贴剪贴板里的诊断块」。
     */
    fun issueUrl(title: String, body: String): String =
        "$ISSUES_NEW?template=$BUG_TEMPLATE&title=${encode(title)}&body=${encode(body)}"

    /** 邮件预填同一块诊断文本。 */
    fun mailtoUrl(subject: String, body: String): String =
        "mailto:$CONTACT?subject=${encode(subject)}&body=${encode(body)}"

    /** UTF-8 百分号编码：只留 unreserved 字符，其余按字节转 %HH。 */
    fun encode(text: String): String {
        val out = StringBuilder()
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            val value = byte.toInt() and 0xFF
            val ch = value.toChar()
            val safe = ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' ||
                ch == '-' || ch == '.' || ch == '_' || ch == '~'
            if (safe) {
                out.append(ch)
            } else {
                out.append('%').append(HEX[value ushr 4]).append(HEX[value and 0x0F])
            }
        }
        return out.toString()
    }
}
