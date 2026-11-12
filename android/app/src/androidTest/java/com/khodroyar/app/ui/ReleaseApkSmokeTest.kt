package com.khodroyar.app.ui

import android.app.Activity
import android.content.ContentValues
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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.regex.Pattern

/**
 * ONLY the signed, non-debuggable, R8 release, on a fresh disposable emulator.
 * Drives the actual launcher, terms/profile, system SAF picker and native OCR UI.
 * No direct references to optimised app implementation classes or debug test host.
 */
class ReleaseApkSmokeTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun signedMinifiedApkLaunchesAndReadsPersianReportWithRadiosDisabled() {
        check(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        assertEquals("Gesture navigation must really be enabled", 2, android.provider.Settings.Secure.getInt(context.contentResolver, "navigation_mode", -1))
        assertEquals("com.khodroyar.app", context.packageName)
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
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
        device.executeShellCommand("svc wifi disable")
        device.executeShellCommand("svc data disable")
        val launch = requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        try {
            ActivityScenario.launch<Activity>(launch).use {
                compose.waitUntil(30000) { compose.onAllNodesWithText("متن بالا را خوانده‌ام و می‌پذیرم").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("متن بالا را خوانده‌ام و می‌پذیرم").performClick()
                compose.onNodeWithText("می‌پذیرم و ادامه").performClick()
                compose.onNode(hasText("نام") and hasSetTextAction()).performTextInput("آزمایشی")
                compose.onNode(hasText("نام خانوادگی") and hasSetTextAction()).performTextInput("امولاتور")
                device.pressBack()
                compose.onNodeWithText("شروع مسیر").performScrollTo().performClick()
                compose.waitUntil(30000) { compose.onAllNodesWithTag("nav-REPORTS").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("nav-REPORTS").performClick()
                compose.onNodeWithTag("report-list").performScrollToNode(hasTestTag("open-work-report"))
                compose.onNode(hasText("مقایسهٔ کارکرد شرکت") and hasAnyAncestor(hasTestTag("open-work-report"))).performClick()
                compose.onNodeWithTag("report-import").performClick()
                val document = device.wait(Until.findObject(By.textContains("khodroyar-release-ocr-fixture")), 20000)
                    ?: throw AssertionError("Synthetic image was not shown in the real SAF picker")
                document.click()
                device.waitForIdle()
                device.findObject(By.res(Pattern.compile(".*:id/action_menu_select")))?.click()
                compose.waitUntil(20000) { compose.onAllNodesWithTag("report-ocr").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("work-report-list").performScrollToNode(hasTestTag("report-ocr"))
                compose.onNodeWithTag("report-ocr").performClick()
                compose.waitUntil(120000) { compose.onAllNodesWithTag("report-compare").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("report-compare").assertIsNotEnabled()
                // Capture actual extracted cell contents, normalising scripts only in the assertion.
                val list = compose.onNodeWithTag("work-report-list")
                list.performScrollToNode(hasSetTextAction() and hasText("تعداد"))
                val cells = compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().filter {
                    it.config.contains(SemanticsProperties.TestTag) && it.config[SemanticsProperties.TestTag].startsWith("report-cell-")
                }
                fun normalized(text: String) = buildString {
                    text.forEach { c -> append(when (c) { in '۰'..'۹' -> '0' + (c - '۰'); in '٠'..'٩' -> '0' + (c - '٠'); '٫' -> '.'; else -> c }) }
                }
                fun value(suffix: String): String {
                    val node = cells.single { it.config[SemanticsProperties.TestTag].endsWith(suffix) }
                    return normalized(node.config[SemanticsProperties.EditableText].text)
                }
                assertEquals("1403/01/01", value("-date")); assertEquals("2", value("-count"))
                assertEquals(120.5, value("-km").toDouble(), .000001); assertEquals(3.5, value("-hours").toDouble(), .000001)
                list.performScrollToNode(hasTestTag("report-review-confirm"))
                compose.onNodeWithTag("report-review-confirm").performClick()
                compose.onNodeWithTag("report-compare").performClick()
                compose.waitUntil(30000) { compose.onAllNodesWithTag("report-comparison").fetchSemanticsNodes().isNotEmpty() }
                compose.onAllNodesWithText("شرکت: 2 سرویس · 120.5 کیلومتر · 3.5 ساعت").onFirst().assertIsDisplayed()
                val directory = File(context.getExternalFilesDir(null), "release-smoke").also { it.mkdirs() }
                assertTrue(device.takeScreenshot(File(directory, "signed-release-offline-ocr.png")))
                println("Signed non-debuggable R8 APK: real SAF import, bundled Persian OCR, review gate and comparison passed with radios disabled.")
            }
        } finally {
            device.executeShellCommand("svc wifi enable"); device.executeShellCommand("svc data enable")
            context.contentResolver.delete(uri, null, null)
        }
    }
}
