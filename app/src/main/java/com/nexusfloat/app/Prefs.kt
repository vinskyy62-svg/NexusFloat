package com.nexusfloat.app

import android.content.Context
import android.graphics.Color
import org.json.JSONObject

/** Penyimpanan lokal sederhana (SharedPreferences). */
object Prefs {
    const val SETTINGS = "settings"
    const val NOTES = "notes"
    const val CLOCK = "clock"
    val KEYS = setOf(SETTINGS, NOTES, CLOCK)

    const val MAX_VALUE_CHARS = 512 * 1024

    private const val FILE = "nexus_prefs"
    private const val POS_X = "pos_x"
    private const val POS_Y = "pos_y"

    private fun sp(c: Context) =
        c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun get(c: Context, key: String): String = sp(c).getString(key, "") ?: ""

    /** commit() sinkron; dipanggil dari thread bridge JS (bukan main thread). */
    fun putSync(c: Context, key: String, value: String): Boolean =
        sp(c).edit().putString(key, value).commit()

    fun loadPosition(c: Context): Pair<Float, Float> =
        Pair(sp(c).getFloat(POS_X, 1f), sp(c).getFloat(POS_Y, 0.35f))

    fun savePosition(c: Context, x: Float, y: Float) {
        sp(c).edit().putFloat(POS_X, x).putFloat(POS_Y, y).apply()
    }
}

/** Pengaturan yang dipakai sisi native (bubble). Dikirim dari JS sebagai JSON. */
data class OverlaySettings(
    val bubbleSize: Int = 56,          // dp
    val bubbleColor: Int = DEFAULT_COLOR,
    val bubbleOpacity: Float = 0.9f,
    val autoSnap: Boolean = true
) {
    companion object {
        val DEFAULT_COLOR: Int = Color.parseColor("#A855F7")

        fun parse(json: String): OverlaySettings {
            if (json.isBlank()) return OverlaySettings()
            return try {
                val o = JSONObject(json)
                val color = try {
                    Color.parseColor(o.optString("bubbleColor", "#A855F7"))
                } catch (e: IllegalArgumentException) {
                    DEFAULT_COLOR
                }
                OverlaySettings(
                    bubbleSize = o.optInt("bubbleSize", 56).coerceIn(40, 80),
                    bubbleColor = color,
                    bubbleOpacity = (o.optInt("bubbleOpacity", 90) / 100f).coerceIn(0.3f, 1f),
                    autoSnap = o.optBoolean("autoSnap", true)
                )
            } catch (e: Exception) {
                OverlaySettings()
            }
        }
    }
}
