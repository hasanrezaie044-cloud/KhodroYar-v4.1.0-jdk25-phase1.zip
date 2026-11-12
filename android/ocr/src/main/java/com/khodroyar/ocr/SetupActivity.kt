package com.khodroyar.ocr

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

/** SAF provisioning can be completed entirely offline from a transferred file. */
class SetupActivity : Activity() {
    private lateinit var status: TextView
    private val worker = Executors.newSingleThreadExecutor()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 64, 32, 32); layoutDirection = View.LAYOUT_DIRECTION_RTL }
        status = TextView(this).apply { textSize = 18f; text = "افزونهٔ OCR فارسی خودرویار\n\nدادهٔ رسمی fas.traineddata را وارد کنید. حجم فایل ۴۳۱٬۵۰۰ بایت است. این افزونه هیچ مجوز اینترنتی ندارد. کیفیت متن و اعداد باید در خودرویار بازبینی شود." }
        column.addView(status)
        column.addView(Button(this).apply {
            text = "انتخاب فایل زبان فارسی"
            setOnClickListener { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE) }, 1) }
        })
        setContentView(column)
        worker.execute { if (PersianData.ready(this)) runOnUiThread { status.append("\n\nدادهٔ زبان تأیید شد؛ اکنون به خودرویار برگردید.") } }
    }
    @Deprecated("Activity result compatible with API 24")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 1 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        status.text = "در حال بررسی فایل زبان…"
        worker.execute {
            val result = runCatching { PersianData.import(this, uri) }
            runOnUiThread { status.text = if (result.isSuccess) "دادهٔ زبان فارسی تأیید و نصب شد. به مغایرت‌گیری خودرویار برگردید.\n\nTesseract 5.5.1 · fas · Apache-2.0" else result.exceptionOrNull()?.message ?: "نصب انجام نشد" }
        }
    }
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
}
