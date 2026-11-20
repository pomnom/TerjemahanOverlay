package id.terjemahoverlay

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var tvVosk: TextView
    private lateinit var tvTranslate: TextView
    private lateinit var bar: ProgressBar
    private lateinit var tvProgress: TextView
    private lateinit var btnDownload: MaterialButton
    private lateinit var tvOverlay: TextView
    private lateinit var btnOverlay: MaterialButton
    private lateinit var btnStart: MaterialButton

    private var voskReady = false
    private var translateReady = false
    private var downloading = false

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val data = res.data
            if (res.resultCode == Activity.RESULT_OK && data != null) {
                val i = Intent(this, CaptureService::class.java)
                    .putExtra(CaptureService.EXTRA_CODE, res.resultCode)
                    .putExtra(CaptureService.EXTRA_DATA, data)
                ContextCompat.startForegroundService(this, i)
                toast("Mulai. Buka video/game berbahasa Jepang.")
            } else {
                toast("Izin dibatalkan, terjemahan tidak dimulai")
            }
        }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasPerm(Manifest.permission.RECORD_AUDIO)) askProjection()
            else toast("Izin \"rekam audio\" diperlukan untuk mendengar suara dari HP")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs.get(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(32))
        }
        col.addView(text("Terjemah Overlay", 24f, bold = true))
        col.addView(text(
            "Suara Jepang dari HP → subtitle bahasa Indonesia. Semua diproses di HP, tanpa internet.", 14f
        ).apply { alpha = 0.75f; setPadding(0, dp(4), 0, dp(4)) })

        // 1. Model
        val c1 = card(col, "1. Unduh model (sekali saja, ±110 MB)")
        tvVosk = text("", 14f)
        c1.addView(tvVosk)
        tvTranslate = text("", 14f)
        c1.addView(tvTranslate)
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        c1.addView(bar, lp(top = 8))
        tvProgress = text("", 13f).apply { visibility = View.GONE; alpha = 0.8f }
        c1.addView(tvProgress)
        btnDownload = button("Unduh model") { downloadModels() }
        c1.addView(btnDownload, lp(top = 8))

        // 2. Izin overlay
        val c2 = card(col, "2. Izin tampil di atas aplikasi lain")
        tvOverlay = text("", 14f)
        c2.addView(tvOverlay)
        btnOverlay = button("Buka pengaturan izin") { openOverlaySettings() }
        c2.addView(btnOverlay, lp(top = 8))

        // 3. Mulai
        btnStart = button("Mulai menerjemahkan") { onStartClick() }.apply {
            textSize = 17f
            minimumHeight = dp(56)
        }
        col.addView(btnStart, lp(top = 18))
        col.addView(text(
            "Saat diminta izin, pilih \"Mulai sekarang\". App ini hanya mengambil suaranya, bukan gambar layar.",
            13f
        ).apply { alpha = 0.7f; setPadding(0, dp(6), 0, 0) })

        // Tampilan subtitle
        val c3 = card(col, "Tampilan subtitle")
        val tvSize = text("", 14f)
        c3.addView(tvSize)
        val font = prefs.getInt(Prefs.FONT_SIZE, Prefs.DEFAULT_FONT).coerceIn(14, 34)
        tvSize.text = "Ukuran huruf: $font"
        c3.addView(Slider(this).apply {
            valueFrom = 14f; valueTo = 34f; stepSize = 1f
            value = font.toFloat()
            addOnChangeListener { _, v, _ ->
                tvSize.text = "Ukuran huruf: ${v.toInt()}"
                prefs.edit().putInt(Prefs.FONT_SIZE, v.toInt()).apply()
            }
        })

        val tvBg = text("", 14f)
        c3.addView(tvBg)
        val dark = (prefs.getInt(Prefs.BG_ALPHA, Prefs.DEFAULT_BG) / 5 * 5).coerceIn(30, 95)
        tvBg.text = "Kegelapan latar: $dark%"
        c3.addView(Slider(this).apply {
            valueFrom = 30f; valueTo = 95f; stepSize = 5f
            value = dark.toFloat()
            addOnChangeListener { _, v, _ ->
                tvBg.text = "Kegelapan latar: ${v.toInt()}%"
                prefs.edit().putInt(Prefs.BG_ALPHA, v.toInt()).apply()
            }
        })

        c3.addView(MaterialSwitch(this).apply {
            text = "Tampilkan teks Jepang yang terdengar"
            isChecked = prefs.getBoolean(Prefs.SHOW_JAPANESE, true)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(Prefs.SHOW_JAPANESE, on).apply() }
        }, lp(top = 4))
        c3.addView(MaterialSwitch(this).apply {
            text = "Tembus sentuhan (subtitle tidak menghalangi tombol game)"
            isChecked = prefs.getBoolean(Prefs.PASS_THROUGH, false)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(Prefs.PASS_THROUGH, on).apply() }
        })
        c3.addView(text(
            "Bila tembus sentuhan aktif, kotak tidak bisa digeser; hentikan lewat notifikasi \"Berhenti\".", 12f
        ).apply { alpha = 0.65f })
        c3.addView(button("Kembalikan posisi kotak ke tengah") {
            prefs.edit().remove(Prefs.POS_X).remove(Prefs.POS_Y).apply()
            toast("Berlaku saat terjemahan dimulai lagi")
        }.apply { alpha = 0.9f }, lp(top = 6))

        // Catatan
        val c4 = card(col, "Perlu diketahui")
        c4.addView(text(
            "• Geser kotak subtitle untuk memindahkannya, ✕ untuk berhenti.\n" +
                "• Subtitle muncul ±1–2 detik setelah ucapan.\n" +
                "• Suara telepon/panggilan tidak bisa ditangkap, dan sebagian app memblokir perekaman suaranya.\n" +
                "• Akurasi turun bila musik latar keras atau banyak orang bicara bersamaan. Mode offline memang " +
                "kurang akurat dibanding layanan online.",
            13f
        ).apply { setLineSpacing(0f, 1.15f) })

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        CaptureService.onStateChanged = { runOnUiThread { if (!isDestroyed) updateButtons() } }
        refresh()
    }

    override fun onPause() {
        super.onPause()
        CaptureService.onStateChanged = null
    }

    private fun refresh() {
        voskReady = Models.voskReady(this)
        tvVosk.text = mark(voskReady) + "Pengenal suara Jepang (±48 MB)" + if (voskReady) " — siap" else ""
        Models.checkTranslateReady { ok ->
            if (isDestroyed) return@checkTranslateReady
            translateReady = ok
            tvTranslate.text = mark(ok) + "Terjemahan Jepang → Indonesia (±60 MB)" + if (ok) " — siap" else ""
            updateButtons()
        }
        val ov = Settings.canDrawOverlays(this)
        tvOverlay.text = if (ov) "✓ Sudah diizinkan" else "• Belum diizinkan"
        btnOverlay.visibility = if (ov) View.GONE else View.VISIBLE
        updateButtons()
    }

    private fun updateButtons() {
        btnDownload.visibility = if (voskReady && translateReady && !downloading) View.GONE else View.VISIBLE
        btnDownload.isEnabled = !downloading
        btnStart.text = if (CaptureService.running) "Berhenti" else "Mulai menerjemahkan"
    }

    private fun onStartClick() {
        if (CaptureService.running) {
            stopService(Intent(this, CaptureService::class.java))
            return
        }
        if (!voskReady || !translateReady) {
            toast("Unduh model dulu (langkah 1)")
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("Izinkan tampil di atas aplikasi lain dulu (langkah 2)")
            openOverlaySettings()
            return
        }
        val need = mutableListOf<String>()
        if (!hasPerm(Manifest.permission.RECORD_AUDIO)) need += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 && !hasPerm(Manifest.permission.POST_NOTIFICATIONS)) {
            need += Manifest.permission.POST_NOTIFICATIONS
        }
        if (need.isEmpty()) askProjection() else permLauncher.launch(need.toTypedArray())
    }

    private fun askProjection() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        // Android 14+: langsung "seluruh layar" (pilihan satu aplikasi bisa membuat suara tidak tertangkap).
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        projectionLauncher.launch(intent)
    }

    private fun openOverlaySettings() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun downloadModels() {
        if (downloading) return
        downloading = true
        updateButtons()
        bar.visibility = View.VISIBLE
        tvProgress.visibility = View.VISIBLE
        val app = applicationContext
        thread(name = "unduh-model") {
            try {
                if (!Models.voskReady(app)) {
                    runOnUiThread {
                        bar.isIndeterminate = false
                        bar.progress = 0
                        tvProgress.text = "Mengunduh pengenal suara Jepang…"
                    }
                    Models.downloadVosk(app) { p ->
                        runOnUiThread {
                            if (p < 0) {
                                bar.isIndeterminate = true
                                tvProgress.text = "Menyiapkan model suara…"
                            } else {
                                bar.progress = p
                                tvProgress.text = "Mengunduh pengenal suara Jepang… $p%"
                            }
                        }
                    }
                }
                runOnUiThread { downloadTranslate() }
            } catch (e: Exception) {
                runOnUiThread { downloadFailed("Gagal mengunduh model suara: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    private fun downloadTranslate() {
        if (isDestroyed) return
        voskReady = Models.voskReady(this)
        tvVosk.text = mark(voskReady) + "Pengenal suara Jepang (±48 MB)" + if (voskReady) " — siap" else ""
        bar.isIndeterminate = true
        tvProgress.text = "Mengunduh model terjemahan Jepang–Indonesia…"
        Models.downloadTranslate(
            onDone = {
                downloading = false
                if (!isDestroyed) {
                    bar.visibility = View.GONE
                    tvProgress.visibility = View.GONE
                    toast("Model siap dipakai")
                    refresh()
                }
            },
            onFail = { e -> downloadFailed("Gagal mengunduh model terjemahan: ${e.message ?: e.javaClass.simpleName}") }
        )
    }

    private fun downloadFailed(msg: String) {
        downloading = false
        if (isDestroyed) return
        bar.visibility = View.GONE
        tvProgress.visibility = View.VISIBLE
        tvProgress.text = "$msg\nPeriksa internet lalu coba lagi."
        updateButtons()
    }

    // ---------------------------------------------------------------- bantuan tampilan

    private fun mark(ok: Boolean) = if (ok) "✓ " else "• "

    private fun hasPerm(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun text(s: String, sizeSp: Float, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = sizeSp
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun button(label: String, onClick: () -> Unit) = MaterialButton(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun card(parent: LinearLayout, title: String): LinearLayout {
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        inner.addView(text(title, 16f, bold = true).apply { setPadding(0, 0, 0, dp(6)) })
        val card = MaterialCardView(this).apply {
            radius = dp(14).toFloat()
            addView(inner)
        }
        parent.addView(card, lp(top = 14))
        return inner
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
