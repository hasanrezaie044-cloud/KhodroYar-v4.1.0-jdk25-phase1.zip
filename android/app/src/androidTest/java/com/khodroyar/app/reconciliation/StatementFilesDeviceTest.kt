package com.khodroyar.app.reconciliation

import android.graphics.*
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Platform import and preview tests on a disposable emulator, never production user records. */
@RunWith(AndroidJUnit4::class)
class StatementFilesDeviceTest {
    @Test fun importsEveryPdfPageAndKeepsDistinctPageIds() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val files = StatementFiles(context)
        val pdf = File(context.cacheDir, "exports/test-statement.pdf").apply { parentFile!!.mkdirs() }
        val document = PdfDocument()
        repeat(3) { index ->
            val page = document.startPage(PdfDocument.PageInfo.Builder(600, 900, index + 1).create())
            page.canvas.drawColor(Color.WHITE)
            page.canvas.drawText("1405/01/1${index + 1}    160    10", 50f, 180f, Paint().apply { color = Color.BLACK; textSize = 30f })
            document.finishPage(page)
        }
        pdf.outputStream().use { document.writeTo(it) }; document.close()
        val pages = files.import(files.uri(pdf), 40)
        try {
            assertEquals(3, pages.size); assertEquals(3, pages.map { it.id }.distinct().size)
            assertTrue(pages.all { files.image(it).isFile })
            assertFalse(pages.any { it.reviewed || it.processed })
            val result = files.prepare(pages[0].copy(rotation = 90))
            val bitmap = BitmapFactory.decodeFile(result.absolutePath)
            assertTrue(bitmap.width > bitmap.height); bitmap.recycle()
            assertNotEquals(files.fingerprint(pages[0]), files.fingerprint(pages[1]))
        } finally { pages.forEach { files.remove(it) }; pdf.delete() }
    }

    @Test fun repeatedImageImportsHaveSameFingerprintButIndependentIds() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val files = StatementFiles(context)
        val image = File(context.cacheDir, "exports/test-statement.png").apply { parentFile!!.mkdirs() }
        val bitmap = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val a = files.import(files.uri(image), 40).single()
        val b = files.import(files.uri(image), 39).single()
        try { assertNotEquals(a.id, b.id); assertEquals(files.fingerprint(a), files.fingerprint(b)) }
        finally { files.remove(a); files.remove(b); image.delete() }
    }
}
