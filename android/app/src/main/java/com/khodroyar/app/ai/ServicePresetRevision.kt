package com.khodroyar.app.ai

import com.khodroyar.app.automation.ServiceTemplate
import com.khodroyar.app.core.rates.ServiceType

/** Change only the explicitly supplied category of this app-owned snapshot, never its saved source. */
object ServicePresetRevision {
    val allowedTypes = listOf("night", "holiday", "request", "available", "fixed", "mission")

    fun apply(saved: ServiceTemplate, requestedType: String?): ServiceTemplate {
        if (!saved.valid()) throw AgentUserException("الگوی سرویس کامل نیست؛ مسیر و کیلومتر/ساعت را در تب ثبت سرویس بررسی کن.")
        if (requestedType == null) return saved
        if (requestedType !in allowedTypes) throw AgentUserException("نوع سرویس معتبر نیست؛ از نوع‌های موجود برنامه انتخاب کن.")
        if (ServiceType.fromWireOrNull(saved.type) in ServiceType.FIXED_AMOUNT_TYPES) {
            throw AgentUserException("الگوی ذخیره‌شده برای حقوق یا مرخصی است، نه سفر. برای این سرویس مسیر و کیلومتر/ساعت واقعی را در تب ثبت سرویس ذخیره کن؛ چیزی ثبت نشده.")
        }
        return saved.copy(type = requestedType).also {
            if (!it.valid()) throw AgentUserException("مشخصات الگو برای این نوع سرویس کافی نیست؛ در تب ثبت سرویس بررسی کن. چیزی ثبت نشده.")
        }
    }
}
