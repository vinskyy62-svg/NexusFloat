package com.nexusfloat.app

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowInsets
import android.view.WindowManager

/**
 * Area layar yang aman untuk overlay: di luar status bar, navigation bar, dan cutout.
 * Dengan begitu bubble/panel tidak menutupi tombol navigasi Android.
 */
object ScreenBounds {

    fun usable(ctx: Context, wm: WindowManager): Rect {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val m = wm.currentWindowMetrics
                val i = m.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                val allZero = i.left == 0 && i.top == 0 && i.right == 0 && i.bottom == 0
                val b = m.bounds
                val r = Rect(b.left + i.left, b.top + i.top, b.right - i.right, b.bottom - i.bottom)
                if (!allZero && r.width() > 0 && r.height() > 0) return r
            } catch (e: Exception) {
                // jatuh ke perhitungan lama di bawah
            }
        }
        return legacy(ctx, wm)
    }

    private fun legacy(ctx: Context, wm: WindowManager): Rect {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        val display = wm.defaultDisplay
        @Suppress("DEPRECATION")
        display.getRealMetrics(dm)

        val res = ctx.resources
        fun dimen(name: String): Int {
            val id = res.getIdentifier(name, "dimen", "android")
            return if (id > 0) res.getDimensionPixelSize(id) else 0
        }

        val land = dm.widthPixels > dm.heightPixels
        val status = dimen("status_bar_height")
        val navH = if (land) 0 else dimen("navigation_bar_height")
        val navW = if (land) dimen("navigation_bar_width") else 0
        return Rect(0, status, dm.widthPixels - navW, dm.heightPixels - navH)
    }
}
