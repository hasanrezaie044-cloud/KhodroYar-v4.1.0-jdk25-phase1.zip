package com.khodroyar.app.core.holidays

/**
 * Official Iranian public holidays — fully local, zero network calls.
 *
 * Ported verbatim from utils/iranHolidays.ts. Two independent sources are kept
 * separate and then combined, exactly as before:
 *   1. [FIXED_ANNUAL]  — solar holidays; valid for any year.
 *   2. [LUNAR_BY_YEAR] — lunar/religious holidays, tabulated per Jalali year
 *      because they shift 10-12 days each year and cannot be derived without the
 *      official crescent-sighting announcement.
 *
 * To add a future year, append one entry to [LUNAR_BY_YEAR]. Nothing else changes.
 *
 * Duplicate dates across the two sources are preserved rather than de-duplicated,
 * and [titleFor] resolves to the FIRST match, reproducing the JS behaviour
 * (`[...fixed, ...lunar].find(...)`) exactly.
 */
object IranHolidays {

    data class Holiday(val date: String, val title: String)

    private data class Fixed(val month: Int, val day: Int, val title: String)

    private val FIXED_ANNUAL = listOf(
        Fixed(1, 1, "جشن نوروز"),
        Fixed(1, 2, "عید نوروز"),
        Fixed(1, 3, "عید نوروز"),
        Fixed(1, 4, "عید نوروز"),
        Fixed(1, 12, "روز جمهوری اسلامی ایران"),
        Fixed(1, 13, "سیزده‌به‌در (روز طبیعت)"),
        Fixed(3, 14, "رحلت امام خمینی (ره)"),
        Fixed(3, 15, "قیام ۱۵ خرداد"),
        Fixed(11, 22, "پیروزی انقلاب اسلامی ایران"),
        Fixed(12, 29, "ملی‌شدن صنعت نفت ایران")
    )

    private val LUNAR_BY_YEAR: Map<Int, List<Holiday>> = mapOf(
        1404 to listOf(
            Holiday("1404/01/02", "شهادت حضرت علی (ع) و عید سعید فطر"),
            Holiday("1404/03/16", "عید سعید قربان"),
            Holiday("1404/03/24", "عید سعید غدیر خم"),
            Holiday("1404/04/14", "تاسوعای حسینی"),
            Holiday("1404/04/15", "عاشورای حسینی"),
            Holiday("1404/05/23", "اربعین حسینی"),
            Holiday("1404/05/31", "رحلت رسول اکرم (ص) و شهادت امام حسن مجتبی (ع)"),
            Holiday("1404/06/02", "شهادت امام رضا (ع)"),
            Holiday("1404/06/10", "شهادت امام حسن عسکری (ع)"),
            Holiday("1404/06/19", "ولادت رسول اکرم (ص) و امام جعفر صادق (ع)"),
            Holiday("1404/09/03", "شهادت حضرت فاطمه زهرا (س)"),
            Holiday("1404/10/13", "ولادت امام علی (ع)"),
            Holiday("1404/10/27", "مبعث رسول اکرم (ص)"),
            Holiday("1404/11/15", "نیمه شعبان (ولادت امام زمان عج)"),
            Holiday("1404/12/20", "شهادت حضرت علی (ع)")
        ),
        1405 to listOf(
            Holiday("1405/03/06", "عید سعید قربان"),
            Holiday("1405/03/14", "عید سعید غدیر خم"),
            Holiday("1405/04/04", "تاسوعای حسینی"),
            Holiday("1405/04/05", "عاشورای حسینی"),
            Holiday("1405/05/13", "اربعین حسینی"),
            Holiday("1405/05/22", "شهادت امام رضا (ع)"),
            Holiday("1405/05/30", "شهادت امام حسن عسکری (ع)"),
            Holiday("1405/06/08", "ولادت رسول اکرم (ص) و امام جعفر صادق (ع)"),
            Holiday("1405/08/22", "شهادت حضرت فاطمه زهرا (س)"),
            Holiday("1405/10/02", "ولادت امام علی (ع)"),
            Holiday("1405/10/16", "مبعث رسول اکرم (ص)"),
            Holiday("1405/11/04", "نیمه شعبان (ولادت امام زمان عج)"),
            Holiday("1405/12/09", "شهادت حضرت علی (ع)"),
            Holiday("1405/12/20", "عید سعید فطر")
        )
    )

    private fun pad2(n: Int) = n.toString().padStart(2, '0')

    /**
     * Fixed solar + known lunar holidays for [year], sorted by date. If the lunar
     * table has no entry for that year, the solar holidays (always reliable) are
     * still returned. The sort is stable, so on a shared date the solar entry stays
     * first — matching the JS ordering.
     */
    fun forYear(year: Int): List<Holiday> {
        val fixed = FIXED_ANNUAL.map { Holiday("$year/${pad2(it.month)}/${pad2(it.day)}", it.title) }
        val lunar = LUNAR_BY_YEAR[year].orEmpty()
        return (fixed + lunar).sortedBy { it.date }
    }

    fun hasLunarData(year: Int): Boolean = LUNAR_BY_YEAR.containsKey(year)

    private val titleCache = java.util.concurrent.ConcurrentHashMap<Int, Map<String, String>>()
    private val dateCache = java.util.concurrent.ConcurrentHashMap<Int, Set<String>>()

    /** date -> title for [year]; first occurrence wins, cached. */
    private fun titlesFor(year: Int): Map<String, String> = titleCache.getOrPut(year) {
        val m = LinkedHashMap<String, String>()
        for (h in forYear(year)) if (!m.containsKey(h.date)) m[h.date] = h.title
        m
    }

    /** Fast membership set for [year], cached. */
    fun datesFor(year: Int): Set<String> = dateCache.getOrPut(year) { titlesFor(year).keys.toSet() }

    fun isOfficialHoliday(date: String): Boolean {
        val year = date.substringBefore('/').toIntOrNull() ?: return false
        return datesFor(year).contains(date)
    }

    fun titleFor(date: String): String? {
        val year = date.substringBefore('/').toIntOrNull() ?: return null
        return titlesFor(year)[date]
    }
}
