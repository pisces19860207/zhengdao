// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.voice

/**
 * 模拟抱朴输入框：语音只经 [onVoiceText] 写回；「发送」是另一个独立动作 [clickSend]。
 * 判据③靠这个 spy 断言「转完 sendCalls 仍是 0」，并用 [clickSend] 做正例对照，
 * 免得「永远是 0」变成恒绿断言。
 */
internal class VoiceHostFixture(initial: String) {
    val before: String = initial

    var text: String = initial
        private set

    var sendCalls = 0
        private set

    fun onVoiceText(value: String) {
        text = value
    }

    /** 模拟 OutlinedTextField.onValueChange（用户输入）。 */
    fun onUserEdit(value: String) {
        text = value
    }

    fun clickSend() {
        if (text.isNotBlank()) sendCalls++
    }
}
