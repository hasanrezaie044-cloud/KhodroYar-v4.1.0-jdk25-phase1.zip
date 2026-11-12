package com.khodroyar.ocr

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.googlecode.tesseract.android.TessBaseAPI
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** A bounded synthetic PRINTED Persian smoke fixture, not a claim about real payroll accuracy. */
@RunWith(AndroidJUnit4::class)
class PersianOcrDeviceTest {
    @Test fun officialPersianModelRunsLocallyWithoutNetworkPermission() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission("android.permission.INTERNET"))
        val data = File(PersianData.root(context), "tessdata/fas.traineddata").apply { parentFile!!.mkdirs() }
        instrumentation.context.assets.open("fas.traineddata").use { input -> data.outputStream().use { input.copyTo(it) } }
        assertTrue(PersianData.ready(context))
        val bitmap = Bitmap.createBitmap(1800, 560, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); canvas.drawColor(Color.WHITE)
        val paint = TextPaint().apply { color = Color.BLACK; textSize = 78f; isAntiAlias = true }
        val text = "تاریخ کارکرد خودرو\n۱۴۰۵/۰۱/۱۲\nکیلومتر ۱۶۰ ساعت ۱۰"
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, 1600).setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(true).build()
        canvas.save(); canvas.translate(100f, 70f); layout.draw(canvas); canvas.restore()
        val tess = TessBaseAPI()
        try {
            assertTrue(tess.init(PersianData.root(context).absolutePath, "fas", TessBaseAPI.OEM_LSTM_ONLY))
            tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK)
            tess.setImage(bitmap)
            val result = tess.getUTF8Text()
            assertNotNull(result); assertTrue(result, Regex("[آ-ی]").containsMatchIn(result))
            assertTrue(result, Regex("[0-9۰-۹٠-٩]").containsMatchIn(result))
            val iterator = tess.resultIterator
            assertNotNull(iterator)
            iterator?.delete()
            println("Verified native offline fas OCR produced Persian letters and numbers; all statement values still require human review.")
        } finally { tess.recycle(); bitmap.recycle() }
    }
}
