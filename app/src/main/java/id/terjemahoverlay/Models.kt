package id.terjemahoverlay

import android.content.Context
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Dua model offline:
 *  - Vosk "small-ja" (±48 MB): suara Jepang → teks Jepang.
 *  - Google ML Kit Jepang & Indonesia: teks Jepang → teks Indonesia.
 * Keduanya diunduh sekali, sesudah itu app bekerja tanpa internet.
 */
object Models {
    private const val VOSK_URL = "https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip"
    private const val READY_MARK = ".siap"

    fun voskDir(ctx: Context) = File(ctx.filesDir, "vosk-ja")

    fun voskReady(ctx: Context) = File(voskDir(ctx), READY_MARK).exists()

    /**
     * Unduh & ekstrak model Vosk. Memblokir — panggil dari thread latar.
     * onProgress: 0..100 saat mengunduh, -1 saat mengekstrak.
     */
    fun downloadVosk(ctx: Context, onProgress: (Int) -> Unit) {
        val zip = File(ctx.cacheDir, "vosk-ja.zip")
        val conn = URL(VOSK_URL).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            if (conn.responseCode !in 200..299) throw IOException("server menjawab HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                zip.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val p = (done * 100 / total).toInt()
                            if (p != last) { last = p; onProgress(p) }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }

        onProgress(-1)
        val tmp = File(ctx.filesDir, "vosk-tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        val base = tmp.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            var e = zin.nextEntry
            while (e != null) {
                val out = File(tmp, e.name)
                if (!out.canonicalPath.startsWith(base)) throw IOException("isi zip tidak valid")
                if (e.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zin.copyTo(it) }
                }
                zin.closeEntry()
                e = zin.nextEntry
            }
        }
        zip.delete()

        // Zip berisi satu folder "vosk-model-small-ja-0.22/" (am, conf, graph, ivector).
        val inner = tmp.listFiles()?.singleOrNull { it.isDirectory }
        val root = if (inner != null && File(inner, "conf").exists()) inner else tmp
        if (!File(root, "conf").exists()) throw IOException("isi model tidak dikenali")
        val dest = voskDir(ctx)
        dest.deleteRecursively()
        if (!root.renameTo(dest)) throw IOException("gagal memindahkan model")
        tmp.deleteRecursively()
        File(dest, READY_MARK).writeText("ok")
    }

    fun translatorOptions(): TranslatorOptions = TranslatorOptions.Builder()
        .setSourceLanguage(TranslateLanguage.JAPANESE)
        .setTargetLanguage(TranslateLanguage.INDONESIAN)
        .build()

    /** Cek apakah model terjemahan Jepang & Indonesia sudah ada di HP. */
    fun checkTranslateReady(cb: (Boolean) -> Unit) {
        RemoteModelManager.getInstance()
            .getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnSuccessListener { models ->
                val langs = models.map { it.language }.toSet()
                cb(TranslateLanguage.JAPANESE in langs && TranslateLanguage.INDONESIAN in langs)
            }
            .addOnFailureListener { cb(false) }
    }

    fun downloadTranslate(onDone: () -> Unit, onFail: (Exception) -> Unit) {
        val t = Translation.getClient(translatorOptions())
        t.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener { t.close(); onDone() }
            .addOnFailureListener { e -> t.close(); onFail(e) }
    }
}
