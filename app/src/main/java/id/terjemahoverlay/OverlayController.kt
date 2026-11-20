package id.terjemahoverlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Kotak subtitle yang melayang di atas aplikasi lain.
 *  Baris kecil atas : teks Jepang yang sedang didengar (opsional)
 *  Baris tengah     : terjemahan sebelumnya (redup)
 *  Baris besar      : terjemahan terbaru
 * Geser kotak untuk memindahkan; posisi disimpan.
 */
class OverlayController(private val ctx: Context, private val onClose: () -> Unit) {

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val prefs = Prefs.get(ctx)
    private val bg = GradientDrawable().apply { cornerRadius = dp(14).toFloat() }
    private val root = LinearLayout(ctx)
    private val tvJa = TextView(ctx)
    private val tvPrev = TextView(ctx)
    private val tvMain = TextView(ctx)
    private val btnClose = TextView(ctx)
    private val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        baseFlags,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private var attached = false
    private var showJa = true
    private var hasTranslation = false
    private val hidePrev = Runnable { tvPrev.visibility = View.GONE }

    init {
        root.orientation = LinearLayout.VERTICAL
        root.background = bg
        root.setPadding(dp(14), dp(6), dp(6), dp(10))

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tvJa.setTextColor(Color.rgb(205, 205, 205))
        tvJa.maxLines = 2
        header.addView(tvJa, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        btnClose.apply {
            text = "✕"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(12), dp(4), dp(8), dp(4))
            setOnClickListener { onClose() }
        }
        header.addView(btnClose)
        root.addView(header)

        tvPrev.setTextColor(Color.argb(165, 255, 255, 255))
        tvPrev.visibility = View.GONE
        root.addView(tvPrev, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            rightMargin = dp(8)
        })

        tvMain.setTextColor(Color.WHITE)
        tvMain.typeface = Typeface.DEFAULT_BOLD
        tvMain.setShadowLayer(4f, 0f, 1f, Color.BLACK)
        root.addView(tvMain, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(2)
            rightMargin = dp(8)
        })

        root.setOnTouchListener(DragListener())
        applySettings()
    }

    fun show() {
        if (attached) return
        layoutForScreen()
        wm.addView(root, params)
        attached = true
    }

    fun hide() {
        root.removeCallbacks(hidePrev)
        if (!attached) return
        try { wm.removeView(root) } catch (_: Exception) { }
        attached = false
    }

    /** Dipanggil saat layar diputar. */
    fun onScreenChanged() {
        if (!attached) return
        layoutForScreen()
        wm.updateViewLayout(root, params)
    }

    fun applySettings() {
        val size = prefs.getInt(Prefs.FONT_SIZE, Prefs.DEFAULT_FONT).toFloat()
        tvMain.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        tvPrev.setTextSize(TypedValue.COMPLEX_UNIT_SP, size * 0.8f)
        tvJa.setTextSize(TypedValue.COMPLEX_UNIT_SP, (size * 0.65f).coerceAtLeast(11f))

        showJa = prefs.getBoolean(Prefs.SHOW_JAPANESE, true)
        if (!showJa) tvJa.text = ""

        val dark = prefs.getInt(Prefs.BG_ALPHA, Prefs.DEFAULT_BG).coerceIn(0, 100)
        bg.setColor(Color.argb(dark * 255 / 100, 0, 0, 0))

        // Mode tembus sentuhan: Android 12+ hanya meneruskan sentuhan bila jendela overlay
        // tidak lebih pekat dari 80%, jadi alpha jendela diturunkan ke 0.8.
        val pass = prefs.getBoolean(Prefs.PASS_THROUGH, false)
        params.flags = if (pass) baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else baseFlags
        params.alpha = if (pass) 0.8f else 1f
        btnClose.visibility = if (pass) View.GONE else View.VISIBLE
        if (attached) wm.updateViewLayout(root, params)
    }

    fun showStatus(msg: String) {
        tvMain.text = msg
        hasTranslation = false
        tvPrev.visibility = View.GONE
    }

    fun showPartial(ja: String) {
        if (showJa) tvJa.text = ja
    }

    fun showTranslation(ja: String, translated: String) {
        val old = tvMain.text?.toString().orEmpty()
        if (hasTranslation && old.isNotBlank()) {
            tvPrev.text = old
            tvPrev.visibility = View.VISIBLE
        }
        tvMain.text = translated
        hasTranslation = true
        if (showJa) tvJa.text = ja
        root.removeCallbacks(hidePrev)
        root.postDelayed(hidePrev, 15_000)
    }

    private fun layoutForScreen() {
        val dm = ctx.resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        params.width = minOf((w * 0.94f).toInt(), dp(720))
        val xf = prefs.getFloat(Prefs.POS_X, -1f)
        val yf = prefs.getFloat(Prefs.POS_Y, 0.62f)
        params.x = if (xf < 0f) (w - params.width) / 2 else (xf * w).toInt()
        params.y = (yf * h).toInt()
        clamp()
    }

    private fun clamp() {
        val dm = ctx.resources.displayMetrics
        params.x = params.x.coerceIn(0, maxOf(0, dm.widthPixels - params.width))
        params.y = params.y.coerceIn(0, maxOf(0, dm.heightPixels - dp(60)))
    }

    private fun savePosition() {
        val dm = ctx.resources.displayMetrics
        prefs.edit()
            .putFloat(Prefs.POS_X, params.x.toFloat() / dm.widthPixels)
            .putFloat(Prefs.POS_Y, params.y.toFloat() / dm.heightPixels)
            .apply()
    }

    private inner class DragListener : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    startX = params.x; startY = params.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (!moved && abs(dx) + abs(dy) > dp(6)) moved = true
                    if (moved && attached) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        clamp()
                        wm.updateViewLayout(root, params)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (moved) savePosition()
            }
            return true
        }
    }

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
}
