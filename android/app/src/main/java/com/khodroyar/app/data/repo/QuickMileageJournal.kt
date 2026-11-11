package com.khodroyar.app.data.repo

import com.khodroyar.app.data.db.AppDatabase
import com.khodroyar.app.data.prefs.AppSettings
import com.khodroyar.app.data.prefs.SettingsStore
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Small write-ahead journal for the Room/preferences boundary; no Room schema change.
 * Stage synchronously BEFORE Room. On recovery, only a matching committed row (or
 * confirmed deletion) may advance mileage. Mileage + journal removal share one
 * preferences commit. A manual odometer correction is never overwritten.
 * Call only under RecordMutations' gate. Receipts themselves remain session-only.
 */
internal data class QuickMileageJournal(
    val kind: QuickKind,
    val id: String,
    val beforeRow: String?,
    val afterRow: String?,
    val beforeKm: Double?,
    val afterKm: Double?,
    val tracked: TrackedChange?,
) {
    enum class Recovery { NONE, APPLIED, NOT_COMMITTED, CONFLICT }

    fun beforeMatches(s: AppSettings): Boolean =
        (beforeKm == null || canonical(s.vehicleCurrentKm) == beforeKm) &&
            (tracked?.let { canonical(s.currentKmFor(it.wire)) == it.beforeCurrent && canonical(s.nextKmFor(it.wire)) == it.beforeNext } != false)

    private fun afterMatches(s: AppSettings): Boolean =
        (afterKm == null || canonical(s.vehicleCurrentKm) == afterKm) &&
            (tracked?.let { canonical(s.currentKmFor(it.wire)) == it.afterCurrent && canonical(s.nextKmFor(it.wire)) == it.afterNext } != false)

    private fun apply(s: AppSettings): AppSettings {
        var next = s
        afterKm?.let { next = next.copy(vehicleCurrentKm = it) }
        tracked?.let { next = next.withTrackedKm(it.wire, it.afterCurrent, it.afterNext) }
        return next
    }

    fun encode(): String = JSONObject().apply {
        put("version",1); put("kind",kind.name); put("id",id)
        put("beforeRow",beforeRow ?: JSONObject.NULL); put("afterRow",afterRow ?: JSONObject.NULL)
        put("beforeKm",beforeKm ?: JSONObject.NULL); put("afterKm",afterKm ?: JSONObject.NULL)
        tracked?.let { t -> put("tracked",JSONObject().apply {
            put("wire",t.wire);put("beforeCurrent",t.beforeCurrent);put("beforeNext",t.beforeNext)
            put("afterCurrent",t.afterCurrent);put("afterNext",t.afterNext)
        }) }
    }.toString()

    companion object {
        private fun canonical(value: Double): Double = value.toFloat().toDouble()
        fun fingerprint(row: Any?): String? = row?.let {
            MessageDigest.getInstance("SHA-256").digest(it.toString().toByteArray(Charsets.UTF_8)).joinToString("") { byte -> "%02x".format(byte) }
        }
        fun plan(kind: QuickKind, id: String, beforeRow: Any?, afterRow: Any?, before: AppSettings,
            after: AppSettings, odometer: Boolean, wire: String?): QuickMileageJournal? {
            if (!odometer && wire == null) return null
            return QuickMileageJournal(kind,id,fingerprint(beforeRow),fingerprint(afterRow),
                if (odometer) canonical(before.vehicleCurrentKm) else null, if (odometer) canonical(after.vehicleCurrentKm) else null,
                wire?.let { TrackedChange(it,canonical(before.currentKmFor(it)),canonical(before.nextKmFor(it)),canonical(after.currentKmFor(it)),canonical(after.nextKmFor(it))) })
        }
        private fun decode(value: String): QuickMileageJournal {
            val j = JSONObject(value); require(j.getInt("version") == 1)
            val t=j.optJSONObject("tracked")
            return QuickMileageJournal(QuickKind.valueOf(j.getString("kind")),j.getString("id"),
                if (j.isNull("beforeRow")) null else j.getString("beforeRow"), if (j.isNull("afterRow")) null else j.getString("afterRow"),
                if (j.isNull("beforeKm")) null else j.getDouble("beforeKm"), if (j.isNull("afterKm")) null else j.getDouble("afterKm"),
                t?.let { TrackedChange(it.getString("wire"),it.getDouble("beforeCurrent"),it.getDouble("beforeNext"),it.getDouble("afterCurrent"),it.getDouble("afterNext")) })
        }

        suspend fun recover(db: AppDatabase, settings: SettingsStore): Recovery {
            val encoded=settings.pendingQuickMileage
            if (encoded.isNullOrBlank()) return Recovery.NONE
            val journal=decode(encoded)
            val row: Any?=when(journal.kind) {
                QuickKind.SERVICE -> db.services().byId(journal.id)
                QuickKind.EXPENSE -> db.insights().expense(journal.id)
                QuickKind.FUEL -> db.insights().fuel(journal.id)
                QuickKind.MAINTENANCE -> db.insights().maintenance(journal.id)
            }
            val actual=fingerprint(row)
            return when {
                actual == journal.afterRow -> {
                    val applied=settings.finishQuickMileage({ journal.beforeMatches(it) || journal.afterMatches(it) }) { journal.apply(it) }
                    if (applied) Recovery.APPLIED else {
                        settings.finishQuickMileage({ true }, "ثبت در پایگاه‌داده انجام شد؛ کیلومترِ دستی حفظ شد. کیلومتر خودرو و موعد سرویس را بررسی کن.") { it }
                        QuickEntries.invalidate()
                        Recovery.CONFLICT
                    }
                }
                actual == journal.beforeRow -> {
                    settings.finishQuickMileage({ true }) { it }; Recovery.NOT_COMMITTED
                }
                else -> {
                    settings.finishQuickMileage({ true }, "ثبت سریع تغییر کرده است؛ کیلومتر خودکار تغییر نکرد. رکورد و کیلومتر خودرو را بررسی کن.") { it }
                    QuickEntries.invalidate(); Recovery.CONFLICT
                }
            }
        }
    }
}
