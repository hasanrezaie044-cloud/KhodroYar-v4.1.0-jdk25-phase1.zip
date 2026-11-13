package com.khodroyar.app.ui

import android.content.ContentValues
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.net.ConnectivityManager
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.regex.Pattern

/**
 * External accessibility interaction with the signed, non-debuggable R8 release.
 * Does not load Compose's test runtime or reflect into the optimised app. Drives
 * the real launcher, terms/profile, system SAF picker and offline numeric review.
 */
class ReleaseApkSmokeTest {
    @Test(timeout = 900000) fun signedMinifiedApkLaunchesAndReadsPersianReportWithRadiosDisabled() {
        check(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.context
        val targetPackage = "com.khodroyar.app"
        // Self-instrumenting test APK: never load the runner/Kotlin runtime in the
        // optimised target. All app interactions below cross accessibility/SAF.
        assertEquals(context.packageName, instrumentation.targetContext.packageName)
        assertNotEquals(targetPackage, context.packageName)
        val targetInfo = context.packageManager.getApplicationInfo(targetPackage, 0)
        assertEquals(targetPackage, targetInfo.packageName)
        assertNotEquals("Test process must not share the target UID", targetInfo.uid, android.os.Process.myUid())
        assertEquals(0, targetInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        // Orbit has native ongoing animations. Bound the accessibility idle wait
        // rather than spending ten seconds on every lookup. Explicit selector
        // waits and every real OCR/review assertion remain unchanged.
        Configurator.getInstance().waitForIdleTimeout = 1000
        val device = UiDevice.getInstance(instrumentation)
        assertEquals("Gesture navigation must really be enabled", 2, android.provider.Settings.Secure.getInt(context.contentResolver, "navigation_mode", -1))
        val stages = mutableListOf<String>()
        fun stage(value: String) {
            stages += value
            println("Signed-release stage: $value")
            // Persist each boundary outside app storage, even if UTP later times
            // out/uninstalls the target. Synthetic emulator data only.
            val trace = JSONObject().put("stages", JSONArray(stages))
            publishSyntheticEvidence(context, "KhodroYar-Release-Smoke", "stage-${stages.size.toString().padStart(2, '0')}.json",
                "application/json", trace.toString().toByteArray())
        }
        stage("independent UIAutomator process; original target verified non-debuggable")
        fun find(selector: BySelector, timeout: Long = 30000): UiObject2 = device.wait(Until.findObject(selector), timeout)
            ?: throw AssertionError("UI element not found: $selector; stages=$stages")
        fun tagged(value: String, timeout: Long = 30000) = find(By.res(value), timeout)
        fun scrollTo(selector: BySelector, container: String): UiObject2 {
            repeat(14) {
                device.findObject(selector)?.takeIf { it.visibleBounds.height() > 0 && it.visibleBounds.width() > 0 }?.let { return it }
                tagged(container).scroll(Direction.DOWN, .65f)
            }
            throw AssertionError("Cannot scroll to $selector; stages=$stages")
        }
        val name = "khodroyar-release-ocr-fixture.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/KhodroYarTest")
        }
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        val bitmap = Bitmap.createBitmap(2000, 650, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 64f; color = Color.BLACK; typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        fun cell(text: String, x: Float, y: Float, width: Int = 300) {
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setTextDirection(TextDirectionHeuristics.LTR).setIncludePad(false).build()
            canvas.save(); canvas.translate(x - width / 2f, y); layout.draw(canvas); canvas.restore()
        }
        listOf("date", "count", "km", "hours").forEachIndexed { n, text -> cell(text, 1600f - n * 400, 100f) }
        listOf("۱۴۰۳/۰۱/۰۱", "۲", "۱۲۰٫۵", "۳٫۵").forEachIndexed { n, text -> cell(text, 1600f - n * 400, 300f, if (n == 0) 460 else 300) }
        context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val fixedValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "khodroyar-fixed-company-fixture.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/KhodroYarTest")
        }
        val fixedUri = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, fixedValues))
        val fixedBitmap = fixedCompanyFixture()
        context.contentResolver.openOutputStream(fixedUri)!!.use { fixedBitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; fixedBitmap.recycle()
        val extracted = JSONObject()
        val fixedCompany = JSONObject()
        val offline = JSONObject()
        val initiallyAirplaneMode = device.executeShellCommand("settings get global airplane_mode_on").trim() == "1"
        val connectivity = requireNotNull(context.getSystemService(ConnectivityManager::class.java))
        fun verifyOffline(): JSONObject {
            val deadline = SystemClock.uptimeMillis() + 15000
            var state = JSONObject()
            do {
                val airplane = device.executeShellCommand("settings get global airplane_mode_on").trim()
                val wifi = device.executeShellCommand("settings get global wifi_on").trim()
                val connected = connectivity.activeNetwork != null
                state = JSONObject().put("airplane_mode", airplane).put("wifi_on", wifi).put("active_network", connected)
                if (airplane == "1" && wifi == "0" && !connected) return state
                SystemClock.sleep(250)
            } while (SystemClock.uptimeMillis() < deadline)
            throw AssertionError("Emulator is not actually offline: $state")
        }
        var passed = false
        try {
            // Emulator permission only, not a new application permission or a bypass on a phone.
            device.executeShellCommand("pm grant com.khodroyar.app android.permission.POST_NOTIFICATIONS")
            // Android can keep the legacy global mobile_data preference at 1
            // while the per-subscription radio is disabled. Verify actual lack of
            // connectivity plus airplane mode/Wi-Fi instead of trusting that flag.
            device.executeShellCommand("cmd connectivity airplane-mode enable")
            device.executeShellCommand("svc wifi disable"); device.executeShellCommand("svc data disable")
            offline.put("before", verifyOffline())
            context.startActivity(requireNotNull(context.packageManager.getLaunchIntentForPackage(targetPackage)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            stage("real launcher opened, radios disabled")
            // Hosted emulators intermittently ANR Pixel Launcher/System UI during cold
            // boot; such a SYSTEM dialog hides the real app window and is unrelated to
            // the target. Dismiss it via "Wait", bounded and evidenced. An ANR of the
            // target itself is NEVER dismissed: it must still fail this test.
            val systemDeadline = SystemClock.uptimeMillis() + 90_000
            var dismissed = 0
            while (SystemClock.uptimeMillis() < systemDeadline &&
                device.findObject(By.res("terms-agree")) == null) {
                val dialog = device.findObject(By.pkg("android").textContains("isn't responding"))
                val title = dialog?.text.orEmpty()
                if (dialog != null && (title.startsWith("Pixel Launcher") || title.startsWith("System UI"))) {
                    device.findObject(By.pkg("android").res("android:id/aerr_wait"))?.click()
                    dismissed++
                }
                SystemClock.sleep(500)
            }
            if (dismissed > 0) stage("dismissed $dismissed unrelated system-app ANR dialog(s); target ANR would still fail")
            tagged("terms-agree").click(); tagged("terms-continue").click()
            stage("terms accepted through UI")
            // Lock is ON by default in the recovered app. A real clean install
            // creates and confirms a PIN before profile setup; never disable or
            // seed the lock/preferences to skip this production path.
            find(By.text("یک رمز عبور ۵ رقمی بسازید"))
            find(By.clazz("android.widget.EditText")).apply { click(); text = "13579" }
            find(By.text("رمز عبور را دوباره وارد کنید"))
            find(By.clazz("android.widget.EditText")).apply { click(); text = "13579" }
            tagged("profile-first-name").apply { click(); text = "آزمایشی" }
            stage("fresh-install PIN created and confirmed through actual UI")
            tagged("profile-last-name").apply { click(); text = "امولاتور" }
            device.pressBack()
            find(By.text("شروع مسیر")).click()
            stage("offline profile saved")
            tagged("nav-REPORTS").click()
            scrollTo(By.res("open-work-report"), "report-list").click()
            tagged("report-import").click()
            stage("real SAF picker opened")
            find(By.textContains("khodroyar-release-ocr-fixture"), 20000).click()
            device.waitForIdle(2000)
            device.findObject(By.res(Pattern.compile(".*:id/action_menu_select")))?.click()
            scrollTo(By.res("report-ocr"), "work-report-list").click()
            stage("bundled OCR processing requested")
            assertFalse("Review is required before any comparison", tagged("report-compare", 120000).isEnabled)
            val dateSelector = By.res(Pattern.compile("report-cell-.*-date"))
            scrollTo(dateSelector, "work-report-list")
            fun normalized(text: String) = buildString {
                text.forEach { c -> append(when (c) { in '۰'..'۹' -> '0' + (c - '۰'); in '٠'..'٩' -> '0' + (c - '٠'); '٫' -> '.'; else -> c }) }
            }
            fun value(field: String, record: JSONObject = extracted): String {
                val selector = By.res(Pattern.compile("report-cell-.*-$field"))
                repeat(5) {
                    device.findObject(selector)?.takeIf { it.visibleBounds.width() > 0 }?.let { node ->
                        val value = normalized(node.text.orEmpty())
                        record.put(field, value)
                        return value
                    }
                    find(By.res(Pattern.compile("report-row-scroll-.*"))).scroll(Direction.LEFT, .65f)
                }
                throw AssertionError("Editable $field cell was not reachable; extracted=$extracted")
            }
            assertEquals("1403/01/01", value("date")); assertEquals("2", value("count"))
            assertEquals(120.5, value("km").toDouble(), .000001); assertEquals(3.5, value("hours").toDouble(), .000001)
            stage("actual extracted date/count/km/hours exactly match the generated image")
            scrollTo(By.res("report-review-confirm"), "work-report-list").click()
            tagged("report-compare").click()
            find(By.text("شرکت: 2 سرویس · 120.5 کیلومتر · 3.5 ساعت"))
            stage("mandatory review and actual generic comparison passed; still offline")
            find(By.text("بازگشت به جدول؛ هیچ سابقه‌ای تغییر نکرد")).click()
            find(By.text("پاک کردن پیش‌نویس")).click(); find(By.text("پاک کردن")).click()
            find(By.res("report-import").enabled(true)).click()
            find(By.textContains("khodroyar-fixed-company-fixture"), 20000).click()
            device.waitForIdle(2000)
            device.findObject(By.res(Pattern.compile(".*:id/action_menu_select")))?.click()
            scrollTo(By.res("report-ocr"), "work-report-list").click()
            stage("real ruled company template requested: ordinal/date/origin/destination/km/hours, NO count column")
            assertFalse("Fixed-form review is mandatory", tagged("report-compare", 120000).isEnabled)
            scrollTo(dateSelector, "work-report-list")
            val fixedReadings = JSONObject()
            assertEquals("1403/04/12", value("date", fixedReadings)); assertEquals("1", value("count", fixedReadings))
            assertEquals(40.0, value("km", fixedReadings).toDouble(), .000001); assertEquals(0.0, value("hours", fixedReadings).toDouble(), .000001)
            scrollTo(By.res("report-review-confirm"), "work-report-list").click(); tagged("report-compare").click()
            find(By.text("شرکت: 2 سرویس · 135 کیلومتر · 0 ساعت"))
            find(By.text("1403/04/01 تا 1403/04/31"))
            find(By.text("0 سرویس فقط در برنامه · 2 ردیف فقط در شرکت · 0 مجموعهٔ مبهم"))
            fixedCompany.put("passed", true).put("rows", 2).put("count_per_row", "1")
                .put("period_start", "1403/04/01").put("period_end", "1403/04/31").put("km", "135").put("hours", "0").put("first", fixedReadings)
            offline.put("after", verifyOffline())
            stage("signed R8 fixed-form service comparison passed; ordinals 7/8 were not counts; full month; still offline")
            val screenshot = File(context.cacheDir, "signed-release-offline-ocr.png")
            assertTrue(device.takeScreenshot(screenshot))
            publishSyntheticEvidence(context, "KhodroYar-Release-Smoke", screenshot.name, "image/png", screenshot.readBytes())
            screenshot.delete()
            passed = true
        } finally {
            val trace = JSONObject().put("environment", "API35 x86_64 emulator; original signed non-debuggable R8 APK; gesture navigation; not physical Samsung proof")
                .put("passed", passed).put("stages", JSONArray(stages)).put("extracted", extracted).put("fixed_company", fixedCompany).put("offline", offline)
                .put("target_package", targetPackage).put("target_debuggable", false)
                .put("instrumentation_package", context.packageName)
                .put("target_uid", targetInfo.uid).put("instrumentation_uid", android.os.Process.myUid())
            publishSyntheticEvidence(context, "KhodroYar-Release-Smoke", "runtime.json", "application/json", trace.toString(2).toByteArray())
            val hierarchy = ByteArrayOutputStream()
            device.dumpWindowHierarchy(hierarchy)
            publishSyntheticEvidence(context, "KhodroYar-Release-Smoke", "last-window.xml", "text/xml", hierarchy.toByteArray())
            if (!passed) {
                val failureImage = File(context.cacheDir, "signed-release-failure.png")
                if (device.takeScreenshot(failureImage)) {
                    publishSyntheticEvidence(context, "KhodroYar-Release-Smoke", failureImage.name, "image/png", failureImage.readBytes())
                }
                failureImage.delete()
            }
            device.executeShellCommand("cmd connectivity airplane-mode ${if (initiallyAirplaneMode) "enable" else "disable"}")
            device.executeShellCommand("svc wifi enable"); device.executeShellCommand("svc data enable")
            context.contentResolver.delete(uri, null, null)
            context.contentResolver.delete(fixedUri, null, null)
        }
    }
    private fun fixedCompanyFixture(): Bitmap {
        val bitmap = Bitmap.createBitmap(2400, 1700, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 40f; color = Color.BLACK; typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
        val xs = listOf(120, 320, 500, 680, 860, 1032, 1152, 1272, 1656, 1944, 2196, 2304)
        val line = Paint().apply { color = Color.BLACK; strokeWidth = 3f }
        xs.forEach { x -> canvas.drawLine(x.toFloat(), 270f, x.toFloat(), 660f, line) }
        listOf(270, 360, 510, 660).forEach { y -> canvas.drawLine(120f, y.toFloat(), 2304f, y.toFloat(), line) }
        fun text(value: String, left: Int, right: Int, y: Float, size: Float = 40f) {
            paint.textSize = size
            val layout = StaticLayout.Builder.obtain(value, 0, value.length, paint, right - left - 8).setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setTextDirection(TextDirectionHeuristics.LTR).setIncludePad(false).build()
            canvas.save(); canvas.translate(left + 4f, y); layout.draw(canvas); canvas.restore()
        }
        listOf("serial", "toll", "food", "hotel", "toll", "hours", "km", "destination", "origin", "date", "ordinal").forEachIndexed { n, label -> text(label, xs[n], xs[n + 1], 295f, 27f) }
        listOf("7" to "40", "8" to "95").forEachIndexed { n, (ordinal, km) ->
            val y = 405f + n * 150
            text(ordinal, 2196, 2304, y); text("1403/04/12", 1944, 2196, y, 38f)
            text("A", 1656, 1944, y); text("B", 1272, 1656, y)
            text(km, 1152, 1272, y); text("0", 1032, 1152, y)
        }
        return bitmap
    }

}
