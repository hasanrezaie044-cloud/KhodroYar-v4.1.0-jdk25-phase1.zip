package com.khodroyar.app.core.rates

/**
 * Service income calculation — ported 1:1 from `calculateServiceIncomeWithRates`
 * and `getServiceCalculationBreakdown` in contexts/AppContext.tsx.
 *
 * The base hourly / per-km / fixed / request rates are untouched, and no second
 * independent rate is introduced: the night and dawn percents are only multipliers
 * on the existing configured rates. The uplift applies solely to the service types
 * listed in [Rates.timeBonusServiceTypes] (default: night + fixed), across two
 * separate parts:
 *
 *  1. Hourly part — sliced by the real duration inside each bracket
 *     (see [TimeRates.computeHourlyBreakdown]).
 *  2. Per-km part — business rule: if the service STARTED inside the night or dawn
 *     bracket, the entire km amount takes that bracket's percent. Unlike the hourly
 *     part there is no time-slicing here; only the start instant matters.
 *
 * 240 golden vectors assert byte-identical results against the JS implementation.
 */
object IncomeCalculator {

    data class Breakdown(
        val kmRate: Double,
        val kmPercent: Double,
        val hourRate: Double,
        val kmAmount: Double,
        val hourly: TimeRates.HourlyBreakdown,
        val nightPercent: Double,
        val saharPercent: Double,
        val extrasAmount: Double,
        val finalAmount: Long,
    )

    fun calculate(
        serviceType: ServiceType,
        km: Double,
        hours: Double,
        tollCount: Int,
        missionFood: Double,
        missionToll: Double,
        missionFine: Double,
        rates: Rates,
        startTime: String? = null,
        endTime: String? = null,
    ): Long = breakdown(
        serviceType, km, hours, tollCount, missionFood, missionToll, missionFine,
        startTime, endTime, rates,
    ).finalAmount

    fun breakdown(
        serviceType: ServiceType,
        km: Double,
        hours: Double,
        tollCount: Int,
        missionFood: Double,
        missionToll: Double,
        missionFine: Double,
        startTime: String?,
        endTime: String?,
        rates: Rates,
    ): Breakdown {
        val bonusTypes = rates.timeBonusServiceTypes.ifEmpty { Rates.DEFAULT.timeBonusServiceTypes }
        val applyTimeBonus = bonusTypes.contains(serviceType)
        val b = rates.boundaries()

        val kmRate = rates.kmRateFor(serviceType)
        val kmPercent = if (applyTimeBonus)
            TimeRates.startBracketPercent(startTime, rates.nightPercent, rates.saharPercent, b) else 0.0
        val kmAmount = km * kmRate * (1 + kmPercent / 100)

        val effectiveNight = if (applyTimeBonus) rates.nightPercent else 0.0
        val effectiveSahar = if (applyTimeBonus) rates.saharPercent else 0.0
        val hourly = TimeRates.computeHourlyBreakdown(
            hours, startTime, endTime, rates.hour, effectiveNight, effectiveSahar, b,
        )

        var extras = 0.0
        if (serviceType == ServiceType.MISSION) {
            if (missionFood != 0.0) extras += missionFood
            if (missionToll != 0.0) extras += missionToll
            if (missionFine != 0.0) extras += missionFine
        }
        if (tollCount != 0) extras += tollCount * rates.toll

        return Breakdown(
            kmRate = kmRate,
            kmPercent = kmPercent,
            hourRate = rates.hour,
            kmAmount = kmAmount,
            hourly = hourly,
            nightPercent = effectiveNight,
            saharPercent = effectiveSahar,
            extrasAmount = extras,
            finalAmount = TimeRates.jsRound(kmAmount + hourly.totalAmount + extras),
        )
    }
}
