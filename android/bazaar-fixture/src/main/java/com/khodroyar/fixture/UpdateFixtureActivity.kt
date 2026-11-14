package com.khodroyar.fixture

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView

class UpdateFixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("fixture", 0)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 80, 24, 24)
            listOf("SYNTHETIC BAZAAR — NOT THE REAL MARKETPLACE", prefs.getString("method", "none"),
                prefs.getString("queriedPackage", "none"), intent.dataString.orEmpty()).forEach { value ->
                addView(TextView(this@UpdateFixtureActivity).apply { text = value; textSize = 18f })
            }
        })
    }
}
