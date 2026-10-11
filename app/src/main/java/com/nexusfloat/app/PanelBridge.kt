package com.nexusfloat.app

import android.content.Context
import android.os.Build
import android.webkit.JavascriptInterface
import org.json.JSONObject

/**
 * Jembatan JS <-> native. Hanya dimuat oleh halaman lokal (file:///android_asset).
 * Semua method dipanggil dari thread bridge WebView, bukan main thread.
 */
class PanelBridge(context: Context, @Volatile var host: Host?) {

    interface Host {
        fun onPanelClose()
        fun onStopService()
        fun onDataSaved(key: String)
        fun onInputMode(on: Boolean)
    }

    private val app = context.applicationContext

    @JavascriptInterface
    fun load(key: String): String =
        if (key in Prefs.KEYS) Prefs.get(app, key) else ""

    @JavascriptInterface
    fun save(key: String, value: String): Boolean {
        if (key !in Prefs.KEYS || value.length > Prefs.MAX_VALUE_CHARS) return false
        val ok = try { Prefs.putSync(app, key, value) } catch (e: Exception) { false }
        if (ok) host?.onDataSaved(key)
        return ok
    }

    @JavascriptInterface
    fun close() { host?.onPanelClose() }

    @JavascriptInterface
    fun stopService() { host?.onStopService() }

    @JavascriptInterface
    fun inputMode(on: Boolean) { host?.onInputMode(on) }

    @JavascriptInterface
    fun info(): String = JSONObject()
        .put("sdk", Build.VERSION.SDK_INT)
        .put("release", Build.VERSION.RELEASE)
        .toString()
}
