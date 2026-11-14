package com.khodroyar.fixture

import android.app.Service
import android.content.Intent
import com.farsitel.bazaar.IUpdateCheckService

/** Local Binder protocol fixture, NOT a real remote-market/version test. */
class UpdateFixtureService : Service() {
    private val binder = object : IUpdateCheckService.Stub() {
        override fun getVersionCode(packageName: String): Long {
            getSharedPreferences("fixture", 0).edit().putString("method", "getVersionCode").putString("queriedPackage", packageName).apply()
            return 1 // Deliberately not an update; the modern method must be used.
        }
        override fun getRemoteVersionCode(packageName: String): Long {
            getSharedPreferences("fixture", 0).edit().putString("method", "getRemoteVersionCode").putString("queriedPackage", packageName).apply()
            return 1_000_000
        }
    }
    override fun onBind(intent: Intent) = binder
}
