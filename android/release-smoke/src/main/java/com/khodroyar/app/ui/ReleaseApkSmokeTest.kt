package com.khodroyar.app.ui

import android.content.ContentValues
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.ConnectivityManager
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.regex.Pattern

/**
 * Accessibility-only interaction with the exact originally signed R8 APK.
 * Tests online-only consent/cancel, explicit synthetic TEXT entry + review,
 * and the genuine marketplace IPC/deep link against a disposable store fixture.
 * Never calls a live OCR provider and is NOT live Bazaar publishing proof.
 */
class ReleaseApkSmokeTest {
    @Test(timeout = 900000) fun signedOnlineOnlyReviewAndBazaarIpcDoNotNeedAnOfflineOcrEngine() {
        check(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.context
        val targetPackage = "com.khodroyar.app"
        assertEquals(context.packageName, instrumentation.targetContext.packageName)
        assertNotEquals(targetPackage, context.packageName)
        val targetInfo = context.packageManager.getApplicationInfo(targetPackage, 0)
        assertNotEquals(targetInfo.uid, android.os.Process.myUid())
        assertEquals(0, targetInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        Configurator.getInstance().waitForIdleTimeout = 1000
        val device = UiDevice.getInstance(instrumentation)
        assertEquals(2, android.provider.Settings.Secure.getInt(context.contentResolver, "navigation_mode", -1))
        val stages = mutableListOf<String>()
        fun stage(value: String) {
            stages += value
            println("Signed-release stage: $value")
            publishSyntheticEvidence(context, "KhodroYar-Release-Smoke", "stage-${stages.size.toString().padStart(2, '0')}.json",
                "application/json", JSONObject().put("stages", JSONArray(stages)).toString().toByteArray())
        }
        fun find(selector: BySelector, timeout: Long = 30000): UiObject2 = device.wait(Until.findObject(selector), timeout)
            ?: throw AssertionError("UI element not found: $selector; stages=$stages")
        fun tagged(value: String, timeout: Long = 30000) = find(By.res(value), timeout)
        fun scrollTo(selector: BySelector, container: String): UiObject2 {
            repeat(18) {
                device.findObject(selector)?.takeIf { it.visibleBounds.height() > 0 && it.visibleBounds.width() > 0 }?.let { return it }
                tagged(container).scroll(Direction.DOWN, .65f)
            }
            throw AssertionError("Cannot scroll to $selector; stages=$stages")
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "khodroyar-online-review-fixture.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/KhodroYarTest")
        }
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        val bitmap = Bitmap.createBitmap(1800, 650, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            val paint = Paint().apply { color = Color.BLACK; textSize = 40f }
            drawText("SYNTHETIC REVIEW FIXTURE — NOT LIVE OCR", 60f, 100f, paint)
            listOf("7  1405/05/10  CityA CityB  40  0", "8  1405/05/10  CityB CityA  95  0", "9  1405/05/11  CityA CityB  80  .")
                .forEachIndexed { i, text -> drawText(text, 60f, 220f + 100 * i, paint) }
        }
        context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val firstRow = JSONObject()
        val review = JSONObject()
        val bazaar = JSONObject()
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
            throw AssertionError("Emulator is not offline for local-text review: $state")
        }
        var passed = false
        try {
            stage("independent UIAutomator process; original non-debuggable target; NO live OCR or real Bazaar")
            device.executeShellCommand("pm grant com.khodroyar.app android.permission.POST_NOTIFICATIONS")
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
            find(By.text("به‌روزرسانی خودرویار"))
            // An actual explicit-package Binder call must have supplied a newer
            // code before the production app can show this entry prompt.
            tagged("bazaar-update-now").click()
            find(By.text("SYNTHETIC BAZAAR — NOT THE REAL MARKETPLACE"))
            find(By.text("getRemoteVersionCode"))
            find(By.text(targetPackage))
            find(By.text("bazaar://details/modal?id=com.khodroyar.app"))
            bazaar.put("passed", true).put("source", "synthetic IPC fixture; NOT real Bazaar")
                .put("automatic_entry_prompt", true).put("method", "getRemoteVersionCode")
                .put("queried_package", targetPackage).put("update_uri", "bazaar://details/modal?id=com.khodroyar.app")
            stage("production entry prompt, explicit Bazaar IPC and user-triggered modal deep link passed against simulated store")
            device.pressBack()
            tagged("nav-REPORTS").click()
            scrollTo(By.res("open-work-report"), "report-list").click()
            tagged("report-import").click()
            stage("real SAF picker opened")
            find(By.textContains("khodroyar-online-review-fixture"), 20000).click()
            device.waitForIdle(2000)
            device.findObject(By.res(Pattern.compile(".*:id/action_menu_select")))?.click()
            scrollTo(By.res("report-online-assist"), "work-report-list").click()
            scrollTo(By.res("report-fastocr-host"), "report-online-consent-list")
            assertFalse("No API key, no consent to upload", tagged("report-online-send").isEnabled)
            tagged("report-online-cancel").click()
            stage("FastOCR destination/explicit consent shown, upload cancelled; no live provider invoked")
            assertNull(device.findObject(By.res("report-ocr")))
            scrollTo(By.res("report-import-text"), "work-report-list").click()
            val syntheticText = "71405/05/10 شهر الف شهر ب 40 0\n81405/05/10 شهر ب شهر الف 95 0\n91405/05/11 شهر الف شهر ب 80 ."
            tagged("report-text-input").apply { click(); text = syntheticText }
            device.pressBack() // Hide the actual software IME, not the full-screen editor.
            tagged("report-text-apply").click()
            assertFalse("Review is mandatory even for explicitly entered text", tagged("report-compare").isEnabled)
            scrollTo(By.res("report-date-compare-1405/05/10"), "work-report-list")
            find(By.text("شرکت 2 سرویس"))
            scrollTo(By.res("report-date-compare-1405/05/11"), "work-report-list")
            find(By.text("شرکت 1 سرویس"))
            val dateSelector = By.res(Pattern.compile("report-cell-.*-date"))
            scrollTo(dateSelector, "work-report-list")
            fun normalized(text: String) = buildString {
                text.forEach { c -> append(when (c) { in '۰'..'۹' -> '0' + (c - '۰'); in '٠'..'٩' -> '0' + (c - '٠'); '٫' -> '.'; else -> c }) }
            }
            fun value(field: String, record: JSONObject = firstRow): String {
                val selector = By.res(Pattern.compile("report-cell-.*-$field"))
                repeat(5) {
                    device.findObject(selector)?.takeIf { it.visibleBounds.width() > 0 }?.let { node ->
                        val value = normalized(node.text.orEmpty())
                        record.put(field, value)
                        return value
                    }
                    find(By.res(Pattern.compile("report-row-scroll-.*"))).scroll(Direction.LEFT, .65f)
                }
                throw AssertionError("Editable $field cell was not reachable; firstRow=$firstRow")
            }
            assertEquals("1405/05/10", value("date")); assertEquals("1", value("count"))
            assertEquals(40.0, value("km").toDouble(), .000001); assertEquals(0.0, value("hours").toDouble(), .000001)
            scrollTo(By.res("report-review-confirm"), "work-report-list").click()
            tagged("report-compare").click()
            find(By.text("شرکت: 3 سرویس · 215 کیلومتر · ساعت نامشخص"))
            find(By.text("1405/05/01 تا 1405/05/31"))
            review.put("passed", true).put("text_source", "user-entered synthetic fixture; NOT image OCR")
                .put("rows", 3).put("count_per_row", "1").put("period_start", "1405/05/01").put("period_end", "1405/05/31")
                .put("km", "215").put("hours", "unknown").put("first", firstRow)
                .put("day10_count", 2).put("day11_count", 1).put("mandatory_review", true).put("consent_cancelled", true)
            offline.put("after", verifyOffline())
            stage("local synthetic text parsing, date counts, mandatory review and unknown-hour comparison passed in signed R8 APK")
            val screenshot = File(context.cacheDir, "signed-release-online-only-review.png")
            assertTrue(device.takeScreenshot(screenshot))
            publishSyntheticEvidence(context, "KhodroYar-Release-Smoke", screenshot.name, "image/png", screenshot.readBytes())
            screenshot.delete()
            passed = true
        } finally {
            val trace = JSONObject().put("environment", "API35 emulator; exact originally signed non-debuggable R8 APK; synthetic text and simulated Bazaar; NOT live OCR/marketplace")
                .put("passed", passed).put("stages", JSONArray(stages)).put("review", review).put("bazaar", bazaar)
                .put("ocr_mode", "online-only").put("live_ocr_exercised", false).put("offline_review", offline)
                .put("target_package", targetPackage).put("target_debuggable", false).put("instrumentation_package", context.packageName)
                .put("target_uid", targetInfo.uid).put("instrumentation_uid", android.os.Process.myUid())
            publishSyntheticEvidence(context, "KhodroYar-Release-Smoke", "runtime.json", "application/json", trace.toString(2).toByteArray())
            val hierarchy = ByteArrayOutputStream(); device.dumpWindowHierarchy(hierarchy)
            publishSyntheticEvidence(context, "KhodroYar-Release-Smoke", "last-window.xml", "text/xml", hierarchy.toByteArray())
            if (!passed) {
                val failureImage = File(context.cacheDir, "signed-release-failure.png")
                if (device.takeScreenshot(failureImage)) publishSyntheticEvidence(context, "KhodroYar-Release-Smoke", failureImage.name, "image/png", failureImage.readBytes())
                failureImage.delete()
            }
            device.executeShellCommand("cmd connectivity airplane-mode ${if (initiallyAirplaneMode) "enable" else "disable"}")
            device.executeShellCommand("svc wifi enable"); device.executeShellCommand("svc data enable")
            context.contentResolver.delete(uri, null, null)
        }
    }
}
