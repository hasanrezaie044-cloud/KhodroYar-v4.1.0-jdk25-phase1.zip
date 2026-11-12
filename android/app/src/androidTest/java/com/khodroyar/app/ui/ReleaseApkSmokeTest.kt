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
    @Test(timeout = 600000) fun signedMinifiedApkLaunchesAndReadsPersianReportWithRadiosDisabled() {
        check(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // Orbit has native ongoing animations. Bound the accessibility idle wait
        // rather than spending ten seconds on every lookup. Explicit selector
        // waits and every real OCR/review assertion remain unchanged.
        Configurator.getInstance().waitForIdleTimeout = 1000
        val device = UiDevice.getInstance(instrumentation)
        assertEquals("Gesture navigation must really be enabled", 2, android.provider.Settings.Secure.getInt(context.contentResolver, "navigation_mode", -1))
        assertEquals("com.khodroyar.app", context.packageName)
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
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
        stage("external instrumentation attached to original non-debuggable release")
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
        val extracted = JSONObject()
        var passed = false
        try {
            // Emulator permission only, not a new application permission or a bypass on a phone.
            device.executeShellCommand("pm grant com.khodroyar.app android.permission.POST_NOTIFICATIONS")
            device.executeShellCommand("svc wifi disable"); device.executeShellCommand("svc data disable")
            context.startActivity(requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            stage("real launcher opened, radios disabled")
            tagged("terms-agree").click(); tagged("terms-continue").click()
            stage("terms accepted through UI")
            tagged("profile-first-name").apply { click(); text = "آزمایشی" }
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
            fun value(field: String): String {
                val selector = By.res(Pattern.compile("report-cell-.*-$field"))
                repeat(5) {
                    device.findObject(selector)?.takeIf { it.visibleBounds.width() > 0 }?.let { node ->
                        val value = normalized(node.text.orEmpty())
                        extracted.put(field, value)
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
            stage("mandatory review and actual comparison passed")
            val screenshot = File(context.cacheDir, "signed-release-offline-ocr.png")
            assertTrue(device.takeScreenshot(screenshot))
            publishSyntheticEvidence(context, "KhodroYar-Release-Smoke", screenshot.name, "image/png", screenshot.readBytes())
            screenshot.delete()
            passed = true
        } finally {
            val trace = JSONObject().put("environment", "API35 x86_64 emulator; original signed non-debuggable R8 APK; gesture navigation; not physical Samsung proof")
                .put("passed", passed).put("stages", JSONArray(stages)).put("extracted", extracted)
            publishSyntheticEvidence(context, "KhodroYar-Release-Smoke", "runtime.json", "application/json", trace.toString(2).toByteArray())
            val hierarchy = ByteArrayOutputStream()
            device.dumpWindowHierarchy(hierarchy)
            publishSyntheticEvidence(context, "KhodroYar-Release-Smoke", "last-window.xml", "text/xml", hierarchy.toByteArray())
            device.executeShellCommand("svc wifi enable"); device.executeShellCommand("svc data enable")
            context.contentResolver.delete(uri, null, null)
        }
    }
}
