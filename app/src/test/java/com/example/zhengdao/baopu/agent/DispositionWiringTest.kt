// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百二十七批 ①的行为判据（派工单判据①「造真实场景 ⇒ 目标能力被调用」）：
// 硬拒事实真的进了 DispositionGate.decide——门放行出 REPLY 档处置、门拒了如实记门拒，
// 两头都是**门给的结论**，本文件不加工。
package com.example.zhengdao.baopu.agent
import org.junit.Assert.assertTrue
import org.junit.Test

class DispositionWiringTest {

    private fun capture(): MutableList<String> = mutableListOf()

    @Test
    fun `判据一_矩阵硬拒事实进门_出REPLY档处置`() {
        val log = capture()
        DispositionWiring.onMatrixReject(
            toolName = "delete_path",
            reasonCode = "raw_zone",
            detail = "路径在原件区内",
            log = { log += it },
        )
        assertTrue("硬拒事实没进门（日志里没有 REPLY 档那一行）：$log", log.size == 1)
        assertTrue(log[0].contains("REPLY 档记一行"))
        assertTrue("处置对象没带上：${log[0]}", log[0].contains("delete_path"))
        assertTrue("理由没带上：${log[0]}", log[0].contains("风险矩阵硬拒（raw_zone）"))
    }

    @Test
    fun `判据二_门拒也如实记_圣旨边界真咬`() {
        // 处置对象指向御批账 ⇒ 372 判定的圣旨边界必须拒——日志里记的是**门给的**拒因，
        // 这一条同时证明申请真的穿过了判定链（不是本地编结论）。
        val log = capture()
        DispositionWiring.onMatrixReject(
            toolName = RoyalDecisionLedger.FILE_NAME,
            reasonCode = "raw_zone",
            detail = "路径在原件区内",
            log = { log += it },
        )
        assertTrue("圣旨边界没拦（门没真判）：$log", log.size == 1)
        assertTrue(log[0].contains("门拒（edict_immutable）"))
    }

    @Test
    fun `判据三_证据带结论码与拒因原文`() {
        val log = capture()
        DispositionWiring.onMatrixReject(
            toolName = "write_file",
            reasonCode = "cred_file",
            detail = "目标是凭据目录；拒写",
            log = { log += it },
        )
        assertTrue("实际日志：${log.single()}", log.single().contains("cred_file"))
        assertTrue("实际日志：${log.single()}", log.single().contains("目标是凭据目录；拒写"))
    }
}
