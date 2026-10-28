package com.example.zhengdao.baopu.inspect

internal data class Budget(
    val perRoundMax: Int = 1,
    val perDayRoyalMax: Int = 3,
    val emergencyExempt: Boolean = true,
) {
    init {
        require(perRoundMax in 0..1) { "perRoundMax must not exceed 1" }
        require(perDayRoyalMax in 0..3) { "perDayRoyalMax must not exceed 3" }
    }

    fun exemptionFor(opinion: Opinion, whyNoApplicableLaw: String): EmergencyExemption? =
        if (emergencyExempt && opinion.severity == Severity.CRITICAL && opinion.lawRef == LawRef.L21) {
            EmergencyExemption(opinion, whyNoApplicableLaw)
        } else {
            null
        }
}

internal data class EmergencyExemption(
    val opinion: Opinion,
    val whyNoApplicableLaw: String,
) {
    init {
        require(opinion.severity == Severity.CRITICAL && opinion.lawRef == LawRef.L21) {
            "emergency exemption requires CRITICAL and L21"
        }
        require(whyNoApplicableLaw.isNotBlank()) { "emergency exemption requires a reason" }
    }
}
