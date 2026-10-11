package com.nexusfloat.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import org.json.JSONObject
import kotlin.math.hypot

/**
 * Foreground service yang memegang dua jendela overlay:
 *  - bubble (view native ringan, selalu ada selama service hidup)
 *  - panel (WebView, dibuat saat dibuka dan DIHANCURKAN saat ditutup agar RAM hemat)
 */
class FloatingService : Service(), PanelBridge.Host {

    companion object {
        const val ACTION_STOP = "com.nexusfloat.app.action.STOP"
        private const val CHANNEL_ID = "nexus_float"
        private const val NOTIF_ID = 1001
        private const val PANEL_URL = "file:///android_asset/www/index.html"
        private const val JS_NAME = "NexusBridge"

        @Volatile
        var running: Boolean = false
            private set

        @Volatile
        var listener: (() -> Unit)? = null
    }

    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var touchSlop = 0

    private var bubble: BubbleView? = null
    private var bubbleLp: WindowManager.LayoutParams? = null

    private var panelRoot: FrameLayout? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var webView: WebView? = null
    private var bridge: PanelBridge? = null

    private var settings = OverlaySettings()
    private var xFrac = 1f
    private var yFrac = 0.35f

    private var snapAnim: ValueAnimator? = null
    private var timerTask: Runnable? = null

    // state drag
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false
    private var dragBounds = android.graphics.Rect()

    // ---------------------------------------------------------------- lifecycle

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val n = buildNotification()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            toast(e.message ?: "Tidak dapat memulai foreground service")
            stopSelf()
            return START_NOT_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            toast(getString(R.string.need_perm))
            stopSelf()
            return START_NOT_STICKY
        }
        if (bubble == null) {
            if (!addBubble()) {
                stopSelf()
                return START_NOT_STICKY
            }
            rescheduleTimer()
        }
        running = true
        listener?.invoke()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        snapAnim?.cancel()
        snapAnim = null
        closePanelNow()
        bubble?.let { try { wm.removeViewImmediate(it) } catch (e: Exception) { } }
        bubble = null
        bubbleLp = null
        listener?.invoke()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // beri waktu sistem memperbarui metrik layar sebelum menghitung ulang
        handler.removeCallbacks(relayout)
        handler.postDelayed(relayout, 150)
    }

    private val relayout = Runnable {
        if (bubble == null) return@Runnable
        applyBubbleStyle()
        applyBubblePosition()
        safeUpdate(bubble, bubbleLp)
        if (panelRoot != null) {
            layoutPanel()
            safeUpdate(panelRoot, panelLp)
        }
    }

    // ------------------------------------------------------------ notification

    private fun createChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, FloatingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val action = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_stat_nexus),
            getString(R.string.notif_stop), stop
        ).build()
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_nexus)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(action)
            .build()
    }

    // ------------------------------------------------------------------ bubble

    private fun dp(v: Int): Int = Math.round(v * resources.displayMetrics.density)

    @SuppressLint("ClickableViewAccessibility")
    private fun addBubble(): Boolean {
        settings = OverlaySettings.parse(Prefs.get(this, Prefs.SETTINGS))
        val pos = Prefs.loadPosition(this)
        xFrac = pos.first
        yFrac = pos.second

        val size = dp(settings.bubbleSize)
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START

        val v = BubbleView(this)
        v.contentDescription = getString(R.string.bubble_desc)
        v.setOnTouchListener { view, e -> onBubbleTouch(view, e) }

        bubble = v
        bubbleLp = lp
        applyBubbleStyle()
        applyBubblePosition()

        return try {
            wm.addView(v, lp)
            true
        } catch (e: Exception) {
            bubble = null
            bubbleLp = null
            toast(e.message ?: "Gagal menampilkan bubble")
            false
        }
    }

    private fun applyBubbleStyle() {
        val v = bubble ?: return
        val lp = bubbleLp ?: return
        val size = dp(settings.bubbleSize)
        lp.width = size
        lp.height = size
        v.accent = settings.bubbleColor
        v.alpha = settings.bubbleOpacity
    }

    /** Hitung x,y dari posisi relatif tersimpan; aman untuk rotasi/perubahan ukuran layar. */
    private fun applyBubblePosition() {
        val lp = bubbleLp ?: return
        val b = ScreenBounds.usable(this, wm)
        val rx = (b.width() - lp.width).coerceAtLeast(0)
        val ry = (b.height() - lp.height).coerceAtLeast(0)
        lp.x = b.left + (xFrac * rx).toInt()
        lp.y = b.top + (yFrac * ry).toInt()
    }

    private fun persistPosition() {
        val lp = bubbleLp ?: return
        val b = ScreenBounds.usable(this, wm)
        val rx = (b.width() - lp.width).coerceAtLeast(1)
        val ry = (b.height() - lp.height).coerceAtLeast(1)
        xFrac = ((lp.x - b.left).toFloat() / rx).coerceIn(0f, 1f)
        yFrac = ((lp.y - b.top).toFloat() / ry).coerceIn(0f, 1f)
        Prefs.savePosition(this, xFrac, yFrac)
    }

    private fun clamp(v: Int, lo: Int, hi: Int): Int = if (hi < lo) lo else minOf(maxOf(v, lo), hi)

    private fun onBubbleTouch(view: View, e: MotionEvent): Boolean {
        val lp = bubbleLp ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                snapAnim?.cancel()
                downRawX = e.rawX
                downRawY = e.rawY
                startX = lp.x
                startY = lp.y
                dragging = false
                dragBounds = ScreenBounds.usable(this, wm)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downRawX
                val dy = e.rawY - downRawY
                if (!dragging && hypot(dx, dy) > touchSlop) dragging = true
                if (dragging) {
                    lp.x = clamp(startX + dx.toInt(), dragBounds.left, dragBounds.right - lp.width)
                    lp.y = clamp(startY + dy.toInt(), dragBounds.top, dragBounds.bottom - lp.height)
                    safeUpdate(view, lp)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) endDrag() else {
                    view.performClick()
                    onBubbleTap()
                }
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) endDrag()
                dragging = false
            }
        }
        return true
    }

    private fun endDrag() {
        val lp = bubbleLp ?: return
        val b = ScreenBounds.usable(this, wm)
        if (settings.autoSnap) {
            val centerX = lp.x + lp.width / 2
            val target = if (centerX < (b.left + b.right) / 2) b.left else b.right - lp.width
            animateX(target)
        } else {
            persistPosition()
            if (panelRoot != null) { layoutPanel(); safeUpdate(panelRoot, panelLp) }
        }
    }

    private fun animateX(target: Int) {
        val lp = bubbleLp ?: return
        val v = bubble ?: return
        snapAnim?.cancel()
        snapAnim = ValueAnimator.ofInt(lp.x, target).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener { a ->
                lp.x = a.animatedValue as Int
                safeUpdate(v, lp)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (bubble == null) return
                    persistPosition()
                    if (panelRoot != null) { layoutPanel(); safeUpdate(panelRoot, panelLp) }
                }
            })
            start()
        }
    }

    private fun onBubbleTap() {
        bubble?.alert = false
        if (panelRoot == null) openPanel() else requestClosePanel()
    }

    private fun restScale(): Float = if (panelRoot != null) 0.88f else 1f

    private fun animateBubbleScale() {
        bubble?.animate()?.scaleX(restScale())?.scaleY(restScale())?.setDuration(120)?.start()
    }

    private fun safeUpdate(v: View?, lp: WindowManager.LayoutParams?) {
        if (v == null || lp == null) return
        try { wm.updateViewLayout(v, lp) } catch (e: Exception) { }
    }

    // ------------------------------------------------------------------- panel

    @SuppressLint("SetJavaScriptEnabled")
    private fun openPanel() {
        if (panelRoot != null) return
        val themed = ContextThemeWrapper(applicationContext, R.style.Theme_Nexus)

        val root = object : FrameLayout(themed) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) requestClosePanel()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }

        val wv = try {
            WebView(themed)
        } catch (e: Exception) {
            toast("WebView tidak tersedia: ${e.message}")
            return
        }
        wv.setBackgroundColor(Color.TRANSPARENT)
        wv.overScrollMode = View.OVER_SCROLL_NEVER
        wv.isVerticalScrollBarEnabled = false
        wv.isHorizontalScrollBarEnabled = false
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false      // penyimpanan lewat bridge native
            allowFileAccess = false        // file:///android_asset tetap bisa dibaca
            allowContentAccess = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100
        }
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                handler.post { closePanelNow() }
                return true
            }
        }
        val br = PanelBridge(this, this)
        wv.addJavascriptInterface(br, JS_NAME)
        root.addView(wv, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        val lp = WindowManager.LayoutParams(
            dp(320), dp(420),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_FOCUSABLE: game tetap menerima fokus/tombol; diubah sementara hanya saat mengetik.
            // NOT_TOUCH_MODAL: sentuhan di luar panel diteruskan ke game.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN

        panelRoot = root
        panelLp = lp
        webView = wv
        bridge = br
        layoutPanel()

        try {
            wm.addView(root, lp)
        } catch (e: Exception) {
            toast(e.message ?: "Gagal membuka panel")
            closePanelNow()
            return
        }
        wv.loadUrl(PANEL_URL)
        animateBubbleScale()
    }

    /** Minta JS menjalankan animasi tutup; cadangan: tutup paksa bila JS tidak merespons. */
    private fun requestClosePanel() {
        val root = panelRoot ?: return
        val wv = webView
        try {
            wv?.evaluateJavascript("window.NexusUI&&window.NexusUI.requestClose()", null)
        } catch (e: Exception) {
            closePanelNow()
            return
        }
        handler.postDelayed({ if (panelRoot === root) closePanelNow() }, 500)
    }

    private fun closePanelNow() {
        val root = panelRoot ?: return
        panelRoot = null
        panelLp = null
        bridge?.host = null
        bridge = null
        try {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(root.windowToken, 0)
        } catch (e: Exception) { }
        try { wm.removeViewImmediate(root) } catch (e: Exception) { }
        webView?.let { wv ->
            try {
                wv.removeJavascriptInterface(JS_NAME)
                wv.stopLoading()
                wv.webViewClient = WebViewClient()
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.destroy()
            } catch (e: Exception) { }
        }
        webView = null
        root.removeAllViews()
        animateBubbleScale()
    }

    /** Tempatkan panel di dekat bubble tanpa menutupinya dan tanpa keluar dari area aman. */
    private fun layoutPanel() {
        val lp = panelLp ?: return
        val bl = bubbleLp ?: return
        val b = ScreenBounds.usable(this, wm)
        val m = dp(8)
        val bs = bl.width

        val availW = (b.width() - 2 * m).coerceAtLeast(dp(200))
        val minH = dp(300)
        val maxH = dp(440)
        val maxW = dp(340)

        val spaceAbove = bl.y - b.top - m
        val spaceBelow = b.bottom - (bl.y + bs) - m
        val bubbleOnLeft = (bl.x + bs / 2) < (b.left + b.right) / 2

        val w: Int
        val h: Int
        val x: Int
        val y: Int
        if (maxOf(spaceAbove, spaceBelow) >= minH) {
            val below = spaceBelow >= minH
            w = minOf(maxW, availW)
            h = minOf(maxH, if (below) spaceBelow else spaceAbove)
            x = if (bubbleOnLeft) b.left + m else b.right - m - w
            y = if (below) bl.y + bs + m / 2 else bl.y - h - m / 2
        } else {
            // layar pendek (landscape): panel di samping bubble
            w = minOf(maxW, maxOf(dp(240), b.width() - bs - 3 * m)).coerceAtMost(availW)
            h = minOf(maxH, (b.height() - 2 * m).coerceAtLeast(dp(200)))
            x = if (bubbleOnLeft) bl.x + bs + m else bl.x - w - m
            y = bl.y + bs / 2 - h / 2
        }
        lp.width = w
        lp.height = h
        lp.x = clamp(x, b.left + m, b.right - m - w)
        lp.y = clamp(y, b.top + m, b.bottom - m - h)
    }

    // ------------------------------------------------------ PanelBridge.Host

    override fun onPanelClose() {
        handler.post { closePanelNow() }
    }

    override fun onStopService() {
        handler.post { stopSelf() }
    }

    override fun onDataSaved(key: String) {
        handler.post {
            when (key) {
                Prefs.SETTINGS -> {
                    settings = OverlaySettings.parse(Prefs.get(this, Prefs.SETTINGS))
                    applyBubbleStyle()
                    applyBubblePosition()
                    safeUpdate(bubble, bubbleLp)
                    if (panelRoot != null) { layoutPanel(); safeUpdate(panelRoot, panelLp) }
                }
                Prefs.CLOCK -> rescheduleTimer()
            }
        }
    }

    override fun onInputMode(on: Boolean) {
        handler.post {
            val root = panelRoot ?: return@post
            val lp = panelLp ?: return@post
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            if (on) {
                lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                safeUpdate(root, lp)
                webView?.requestFocus()
                handler.postDelayed({
                    webView?.let { imm.showSoftInput(it, InputMethodManager.SHOW_IMPLICIT) }
                }, 100)
            } else {
                imm.hideSoftInputFromWindow(root.windowToken, 0)
                lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                safeUpdate(root, lp)
            }
        }
    }

    // ------------------------------------------------------------------- timer

    /** Satu Runnable saja; selalu dibatalkan sebelum dijadwalkan ulang (tidak ada timer ganda). */
    private fun rescheduleTimer() {
        timerTask?.let { handler.removeCallbacks(it) }
        timerTask = null
        try {
            val tm = JSONObject(Prefs.get(this, Prefs.CLOCK)).optJSONObject("tm") ?: return
            if (!tm.optBoolean("running", false)) return
            val delay = tm.optLong("endAt", 0L) - System.currentTimeMillis()
            if (delay <= 0L) return
            val r = Runnable { timerTask = null; onTimerDone() }
            timerTask = r
            handler.postDelayed(r, delay)
        } catch (e: Exception) {
            // data belum ada / rusak: abaikan
        }
    }

    private fun onTimerDone() {
        val v = bubble ?: return
        v.alert = true
        v.animate().scaleX(1.25f).scaleY(1.25f).setDuration(160).withEndAction {
            bubble?.animate()?.scaleX(restScale())?.scaleY(restScale())?.setDuration(160)?.start()
        }.start()
        toast(getString(R.string.timer_done))
    }

    private fun toast(msg: String) {
        Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
    }
}
