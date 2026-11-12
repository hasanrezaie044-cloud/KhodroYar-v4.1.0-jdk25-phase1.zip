package com.khodroyar.app.reconciliation

import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercise the actual signature-protected IPC/URI protocol, not a mocked OCR provider. */
@RunWith(AndroidJUnit4::class)
class OfflineOcrClientDeviceTest {
    @Test fun readsPersianThroughTheSameSignatureOfflineCompanion() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val client = OfflineOcrClient(context)
        assertTrue("Install the same-debug-certificate OCR companion and provision official fas data first", client.installed())
        val image = File(context.cacheDir, "exports/ocr-ipc-test.jpg").apply { parentFile!!.mkdirs() }
        val bitmap = Bitmap.createBitmap(1800, 560, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); canvas.drawColor(Color.WHITE)
        val paint = TextPaint().apply { color = Color.BLACK; textSize = 78f; isAntiAlias = true }
        val text = "تاریخ کارکرد خودرو\n۱۴۰۵/۰۱/۱۲\nکیلومتر ۱۶۰ ساعت ۱۰"
        canvas.save(); canvas.translate(100f, 70f)
        StaticLayout.Builder.obtain(text, 0, text.length, paint, 1600).setAlignment(Layout.Alignment.ALIGN_CENTER).build().draw(canvas)
        canvas.restore()
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 97, it) }; bitmap.recycle()
        try {
            val page = client.recognize("actual-ipc", image)
            assertEquals("actual-ipc", page.id); assertEquals(1800, page.width); assertEquals(560, page.height)
            assertTrue(page.words.isNotEmpty())
            assertTrue(page.words.any { Regex("[آ-ی]").containsMatchIn(it.text) })
            assertTrue(page.words.all { it.confidence in 0..100 && it.area.left >= 0 && it.area.top >= 0 && it.area.right <= page.width && it.area.bottom <= page.height })
        } finally { image.delete() }
        Unit
    }
}
