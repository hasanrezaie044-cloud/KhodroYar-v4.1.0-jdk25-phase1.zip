package com.khodroyar.app.ai

/** Text approval is accepted ONLY for the currently visible non-destructive app-built card. */
object LocalApprovalInput {
    enum class Decision { CONFIRM, CANCEL, REVISE, REQUIRE_BUTTON }
    fun classify(raw: String, destructive: Boolean): Decision {
        val answer = LocalDialogueEngine.normalize(raw).trim(' ', '!', '؟', '.', '?')
        if (answer in setOf("نه", "لغو", "بیخیال", "بی خیال", "کنسل", "انصراف")) return Decision.CANCEL
        if (destructive) return Decision.REQUIRE_BUTTON
        if (answer in setOf("بله", "آره", "اره", "تایید", "تأیید", "انجام بده", "فعال کن", "اوکی", "باشه")) return Decision.CONFIRM
        return Decision.REVISE
    }
}
