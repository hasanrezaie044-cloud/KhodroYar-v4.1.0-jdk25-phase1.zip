package com.khodroyar.app.core.rates

/** Service categories. Wire values match the JS string literals so backups stay compatible. */
enum class ServiceType(val wire: String, val label: String) {
    NIGHT("night", "شب"),
    HOLIDAY("holiday", "تعطیل"),
    REQUEST("request", "درخواستی"),
    /**
     * Display label renamed from "آماده‌باش" to "در اختیار".
     * The wire value stays "available", so every existing record, backup file and
     * report continues to resolve — this is a label change, never a data migration.
     */
    AVAILABLE("available", "در اختیار"),
    FIXED("fixed", "ثابت"),
    MISSION("mission", "مأموریت");

    companion object {
        fun fromWire(v: String?): ServiceType = entries.firstOrNull { it.wire == v } ?: NIGHT
        fun fromWireOrNull(v: String?): ServiceType? = entries.firstOrNull { it.wire == v }
    }
}

enum class CarType(val wire: String, val label: String) {
    SOREN("soren", "سورن"), TARA("tara", "تارا");
    companion object { fun fromWire(v: String?) = entries.firstOrNull { it.wire == v } ?: SOREN }
}

enum class FuelType(val wire: String, val label: String) {
    GOV("gov", "دولتی"), SEMI("semi", "نیمه‌آزاد"), FREE("free", "آزاد");
    companion object { fun fromWire(v: String?) = entries.firstOrNull { it.wire == v } ?: GOV }
}

/**
 * Rate settings. Field-for-field identical to `Rates` in contexts/AppContext.tsx,
 * including the per-year storage model and the legacy v2.2 field aliases.
 */
data class Rates(
    val nightKm: Double = 0.0,
    val holidayKm: Double = 0.0,
    val fixedRequestKm: Double = 0.0,
    val hour: Double = 0.0,
    val fuelPriceGov: Double = 0.0,
    val fuelPriceSemi: Double = 0.0,
    val fuelPriceFree: Double = 0.0,
    val toll: Double = 0.0,
    val defaultCarType: CarType = CarType.SOREN,
    val oilChangeKmInterval: Int = 10000,
    val timingBeltKmInterval: Int = 80000,
    /** Percent uplift on the EXISTING base rates — never a second independent rate. */
    val nightPercent: Double = 0.0,
    val saharPercent: Double = 0.0,
    val nightStartTime: String = "17:30",
    val saharStartTime: String = "20:00",
    val saharEndTime: String = "06:00",
    val timeBonusServiceTypes: List<ServiceType> = listOf(ServiceType.NIGHT, ServiceType.FIXED),
) {
    companion object {
        val DEFAULT = Rates()
    }

    /** Converts the stored "HH:MM" strings to minute boundaries, falling back to the JS defaults. */
    fun boundaries(): TimeRates.Boundaries = TimeRates.Boundaries(
        nightStartMin = TimeRates.timeToMinutes(nightStartTime) ?: TimeRates.DEFAULT_NIGHT_START_MIN,
        nightEndMin = TimeRates.timeToMinutes(saharStartTime) ?: TimeRates.DEFAULT_NIGHT_END_MIN,
        saharEndMin = TimeRates.timeToMinutes(saharEndTime) ?: TimeRates.DEFAULT_SAHAR_END_MIN,
    )

    fun kmRateFor(type: ServiceType): Double = when (type) {
        ServiceType.NIGHT -> nightKm
        ServiceType.HOLIDAY -> holidayKm
        ServiceType.REQUEST -> fixedRequestKm
        ServiceType.FIXED -> fixedRequestKm
        ServiceType.MISSION -> fixedRequestKm
        ServiceType.AVAILABLE -> 0.0
    }

    fun fuelPriceFor(type: FuelType): Double = when (type) {
        FuelType.GOV -> fuelPriceGov
        FuelType.SEMI -> fuelPriceSemi
        FuelType.FREE -> fuelPriceFree
    }
}

/**
 * Per-Jalali-year rate map. Editing rates in Settings only ever touches the active
 * year; historical pricing for previous years is left untouched. A year with no
 * entry of its own inherits the nearest earlier year that has one, then the
 * defaults — identical to `pickRatesForYear` in the JS app.
 */
class RatesByYear(private val map: Map<Int, Rates>) {

    fun forYear(year: Int): Rates {
        map[year]?.let { return it }
        val earlier = map.keys.filter { it <= year }.maxOrNull()
        return earlier?.let { map[it] } ?: Rates.DEFAULT
    }

    fun with(year: Int, rates: Rates) = RatesByYear(map + (year to rates))
    fun asMap(): Map<Int, Rates> = map
    fun years(): List<Int> = map.keys.sorted()

    companion object { fun empty() = RatesByYear(emptyMap()) }
}
