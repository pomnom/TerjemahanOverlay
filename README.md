# Terjemah Overlay

App Android yang mendengarkan suara dari HP (video, anime, game) lalu menampilkan terjemahan
**Jepang → Indonesia** sebagai subtitle melayang di atas aplikasi lain. Semua diproses di HP (offline).

## Cara kerja

1. **Tangkap suara sistem** — MediaProjection + AudioPlaybackCapture (Android 10+), 16 kHz mono.
2. **Suara → teks Jepang** — [Vosk](https://alphacephei.com/vosk/) model `vosk-model-small-ja-0.22` (±48 MB).
3. **Jepang → Indonesia** — Google ML Kit Translate, on-device (±60 MB untuk dua bahasa).
4. **Overlay** — jendela `TYPE_APPLICATION_OVERLAY` yang bisa digeser.

Model diunduh sekali dari dalam app; sesudah itu tidak perlu internet.

## Pemakaian

1. Pasang APK dari halaman **Releases**.
2. Buka app → **Unduh model** → izinkan **Tampil di atas aplikasi lain**.
3. **Mulai menerjemahkan** → izinkan rekam audio → pilih **Mulai sekarang**.
4. Buka video/game berbahasa Jepang. Geser kotak subtitle sesuai selera, ✕ untuk berhenti.

## Batasan

- Butuh Android 10 ke atas.
- Suara telepon/panggilan tidak bisa ditangkap (aturan Android); sebagian app memblokir perekaman suaranya.
- Model offline kecil: akurasi turun saat ada musik latar keras atau banyak orang bicara bersamaan.

## Build

Push ke `main` (atau upload `TerjemahOverlay.zip`) → GitHub Actions membuat APK di Releases.
Opsional: isi Secrets `TO_KEYSTORE_B64` (keystore base64, alias `terjemah`) dan `TO_STORE_PASSWORD`
agar APK baru bisa dipasang di atas versi lama tanpa uninstall.
