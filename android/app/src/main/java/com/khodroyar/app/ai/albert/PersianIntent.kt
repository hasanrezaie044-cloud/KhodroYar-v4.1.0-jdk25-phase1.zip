package com.khodroyar.app.ai.albert

import kotlin.math.exp

/** Fixed app domains only. A model output can never supply a tool name, ID, amount or approval. */
enum class PersianIntent(val wire: String, val entry: Boolean = false) {
    EXPENSE("expense", true), FUEL("fuel", true), MAINTENANCE("maintenance", true), SERVICE("service", true),
    VEHICLE_STATUS("vehicle_status"), AUTOMATION_HEALTH("automation_health"), COMPARISON("comparison"), OTHER("other");
    companion object { fun fromWire(value: String) = entries.firstOrNull { it.wire == value } }
}

data class PersianIntentPrediction(val intent: PersianIntent, val score: Double, val margin: Double,
                                   val accepted: Boolean, val inferenceMs: Long = 0)

object PersianIntentScores {
    fun decode(labels: List<String>, logits: FloatArray, threshold: Double, minimumMargin: Double): PersianIntentPrediction? {
        if (labels.size != PersianIntent.entries.size || labels.toSet() != PersianIntent.entries.map { it.wire }.toSet() ||
            logits.size != labels.size || logits.any { !it.isFinite() } || threshold !in 0.5..1.0 || minimumMargin !in 0.0..1.0) return null
        val max = logits.maxOrNull()!!.toDouble()
        val values = logits.map { exp(it.toDouble() - max) }
        val total = values.sum()
        if (!total.isFinite() || total <= 0) return null
        val order = values.indices.sortedByDescending { values[it] }
        val first = order[0]
        val score = values[first] / total
        val margin = (values[first] - values[order[1]]) / total
        val intent = PersianIntent.fromWire(labels[first]) ?: return null
        return PersianIntentPrediction(intent, score, margin,
            intent != PersianIntent.OTHER && score >= threshold && margin >= minimumMargin)
    }
}

/** Independent safety boundary: confidence does not override negation, questions or compound writes. */
object PersianHintSafety {
    private val unsafe = Regex("نکن|نزن|نریز|ننویس|ننداز|نذار|نباید|نمی\\s*خوا|نمیخوا|نخواستم|انجام نداد|عوض نکرد|ثبت نش|بدون تایید|بی اجازه|نادیده بگیر|حذف|پاک کن|فرض|مثال|(?<![\\p{L}])(?:اگر|اگه|کاش)(?![\\p{L}])")
    private val readQuestion = Regex("چطور|چگونه|چقدر|چقد|چرا|کدوم|کدام|چیه|چیست|کی باید|چه وقت|نسبت به|مقایسه|گزارش|سوابق|فهرست|لیست|[؟?]")
    fun allow(normalized: String, prediction: PersianIntentPrediction?): PersianIntent? {
        val p = prediction ?: return null
        if (!p.accepted || normalized.length !in 2..600 || unsafe.containsMatchIn(normalized)) return null
        if (p.intent.entry) {
            if (readQuestion.containsMatchIn(normalized)) return null
            val domains = listOf(
                Regex("بنزین|سوخت|باک"), Regex("روغن|تعمیر|لنت|لاستیک|تسمه|شمع|کارواش|مکانیک"),
                Regex("قهوه|ناهار|خوراک|صبحانه|قبض|کرایه|دارو|لباس"), Regex("سفر|رفت و آمد|سرویس شب")
            ).count { it.containsMatchIn(normalized) }
            if (domains > 1 && Regex("(?: و |؛|;)").containsMatchIn(normalized)) return null
        }
        return p.intent
    }
}

fun interface PersianIntentModel {
    suspend fun predict(text: String): PersianIntentPrediction?
}
