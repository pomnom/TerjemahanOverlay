package id.terjemahoverlay

import android.content.Context
import android.content.SharedPreferences

/** Pengaturan tampilan subtitle. Service ikut memperbarui overlay saat nilainya berubah. */
object Prefs {
    const val FONT_SIZE = "font_size"          // sp, 14..34
    const val BG_ALPHA = "bg_alpha"            // persen kegelapan latar, 30..95 (kelipatan 5)
    const val SHOW_JAPANESE = "show_japanese"  // tampilkan teks Jepang hasil dengar
    const val PASS_THROUGH = "pass_through"    // sentuhan tembus ke aplikasi di bawahnya
    const val POS_X = "pos_x"                  // posisi kotak, pecahan lebar layar
    const val POS_Y = "pos_y"                  // posisi kotak, pecahan tinggi layar

    const val DEFAULT_FONT = 20
    const val DEFAULT_BG = 70

    fun get(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
}
