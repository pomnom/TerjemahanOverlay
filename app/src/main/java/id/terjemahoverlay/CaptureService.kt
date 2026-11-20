package id.terjemahoverlay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import kotlin.concurrent.thread

/**
 * Alur kerja (semua di HP, tanpa internet):
 *  1. MediaProjection + AudioPlaybackCapture → suara yang sedang diputar app lain (PCM 16 kHz mono)
 *  2. Vosk (model Jepang) → teks Jepang, per kalimat
 *  3. ML Kit Translate → teks Indonesia
 *  4. OverlayController → kotak subtitle di atas aplikasi lain
 */
class CaptureService : Service() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "id.terjemahoverlay.STOP"

        @Volatile
        var running = false
            private set

        /** Dipasang MainActivity agar tombol Mulai/Berhenti ikut berubah. */
        var onStateChanged: (() -> Unit)? = null

        private const val TAG = "TerjemahOverlay"
        private const val CHANNEL = "capture"
        private const val NOTIF_ID = 1
        private const val RATE = 16_000

        /** Teks sementara tidak berubah selama ini → anggap kalimat selesai (berguna bila ada musik latar). */
        private const val STABLE_MS = 1_500L
    }

    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var overlay: OverlayController? = null
    private var translator: Translator? = null
    private var worker: Thread? = null

    @Volatile private var active = false
    @Volatile private var record: AudioRecord? = null

    private var seq = 0
    private var shownSeq = 0

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != Prefs.POS_X && key != Prefs.POS_Y) overlay?.applySettings()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        }
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Android mewajibkan service jadi foreground (tipe mediaProjection) sebelum MediaProjection diambil.
        startAsForeground()

        val mp = try {
            getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection gagal", e)
            null
        }
        if (mp == null) {
            toast("Tidak bisa memulai perekaman suara")
            stopSelf()
            return START_NOT_STICKY
        }
        val cb = object : MediaProjection.Callback() {
            // Pengguna menghentikan lewat ikon/chip "merekam" milik sistem.
            override fun onStop() { main.post { stopSelf() } }
        }
        mp.registerCallback(cb, main)
        projection = mp
        projectionCallback = cb

        running = true
        onStateChanged?.invoke()

        overlay = OverlayController(this) { stopSelf() }.also {
            it.show()
            it.showStatus("Memuat model suara…")
        }
        Prefs.get(this).registerOnSharedPreferenceChangeListener(prefListener)
        translator = Translation.getClient(Models.translatorOptions())

        active = true
        worker = thread(name = "pengenal-suara") { recognizeLoop(mp) }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.onScreenChanged()
    }

    override fun onDestroy() {
        active = false
        try { record?.stop() } catch (_: Exception) { }
        try { worker?.join(1_500) } catch (_: InterruptedException) { }
        worker = null

        Prefs.get(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        overlay?.hide()
        overlay = null
        translator?.close()
        translator = null
        projectionCallback?.let { projection?.unregisterCallback(it) }
        projection?.stop()
        projection = null

        running = false
        onStateChanged?.invoke()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- pengenalan suara (thread latar)

    private fun recognizeLoop(mp: MediaProjection) {
        var model: Model? = null
        var rec: Recognizer? = null
        var ar: AudioRecord? = null
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            model = Model(Models.voskDir(this).absolutePath)
            if (!active) return

            val opened = openRecord(mp) ?: throw IllegalStateException("perekam suara sistem tidak bisa dibuka")
            ar = opened.first
            record = ar
            val factor = opened.second / RATE // 1 (16 kHz) atau 3 (48 kHz → diturunkan ke 16 kHz)

            rec = Recognizer(model, RATE.toFloat())
            // Detik: diam di awal, jeda akhir kalimat, panjang maksimum satu kalimat.
            rec.setEndpointerDelays(5f, 0.6f, 8f)

            val buf = ShortArray(opened.second / 10) // 100 ms
            val small = ShortArray(buf.size / factor)
            ar.startRecording()
            post { overlay?.showStatus("Mendengarkan…") }

            var lastPartial = ""
            var lastChange = SystemClock.elapsedRealtime()
            while (active) {
                val n = ar.read(buf, 0, buf.size)
                if (n < 0) throw IllegalStateException("perekam suara berhenti (kode $n)")
                if (n == 0) continue

                val data: ShortArray
                val len: Int
                if (factor == 1) {
                    data = buf; len = n
                } else {
                    len = n / factor
                    for (i in 0 until len) {
                        var s = 0
                        for (k in 0 until factor) s += buf[i * factor + k]
                        small[i] = (s / factor).toShort()
                    }
                    data = small
                }

                val now = SystemClock.elapsedRealtime()
                if (rec.acceptWaveForm(data, len)) {
                    deliver(field(rec.result, "text"))
                    lastPartial = ""; lastChange = now
                } else {
                    val p = field(rec.partialResult, "partial")
                    if (p != lastPartial) {
                        lastPartial = p; lastChange = now
                        val ja = clean(p)
                        post { overlay?.showPartial(ja) }
                    } else if (p.isNotBlank() && now - lastChange > STABLE_MS) {
                        deliver(field(rec.finalResult, "text"))
                        lastPartial = ""; lastChange = now
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "pengenalan suara gagal", e)
            if (active) post {
                toast("Berhenti: ${e.message ?: e.javaClass.simpleName}")
                stopSelf()
            }
        } finally {
            record = null
            try { ar?.stop() } catch (_: Exception) { }
            ar?.release()
            rec?.close()
            model?.close()
        }
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO diminta MainActivity sebelum service dimulai
    private fun openRecord(mp: MediaProjection): Pair<AudioRecord, Int>? {
        val config = AudioPlaybackCaptureConfiguration.Builder(mp)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        for (rate in intArrayOf(RATE, 48_000)) {
            try {
                val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val format = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
                val r = AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(maxOf(min, rate * 2)) // ±1 detik
                    .setAudioPlaybackCaptureConfig(config)
                    .build()
                if (r.state == AudioRecord.STATE_INITIALIZED) return r to rate
                r.release()
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord $rate Hz gagal", e)
            }
        }
        return null
    }

    /** Dari thread latar: kirim satu kalimat Jepang untuk diterjemahkan lalu ditampilkan. */
    private fun deliver(raw: String) {
        val ja = clean(raw)
        if (ja.length < 2) return
        post {
            val t = translator ?: return@post
            val my = ++seq
            overlay?.showPartial(ja)
            t.translate(ja)
                .addOnSuccessListener { id ->
                    if (my > shownSeq) {
                        shownSeq = my
                        overlay?.showTranslation(ja, id)
                    }
                }
                .addOnFailureListener { e -> Log.w(TAG, "terjemahan gagal", e) }
        }
    }

    /** Vosk memisahkan kata Jepang dengan spasi; bahasa Jepang ditulis tanpa spasi. */
    private fun clean(s: String) = s.replace(" ", "").trim()

    private fun field(json: String, key: String): String =
        try { JSONObject(json).optString(key, "") } catch (_: Exception) { "" }

    // ---------------------------------------------------------------- notifikasi & bantuan

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Terjemahan berjalan", NotificationManager.IMPORTANCE_LOW)
        )
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP), flags
        )
        val openPi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val notif = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Menerjemahkan suara Jepang")
            .setContentText("Subtitle tampil di atas aplikasi lain")
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_notif), "Berhenti", stopPi
                ).build()
            )
            .build()
        val type = if (Build.VERSION.SDK_INT >= 30) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        startForeground(NOTIF_ID, notif, type)
    }

    private fun post(block: () -> Unit) {
        main.post { block() }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
