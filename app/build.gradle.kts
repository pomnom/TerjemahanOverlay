plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "id.terjemahoverlay"
    compileSdk = 35

    defaultConfig {
        applicationId = "id.terjemahoverlay"
        // Android 10+: syarat API perekaman suara sistem (AudioPlaybackCapture).
        minSdk = 29
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
        // Hanya HP ARM (hampir semua HP Android) supaya APK tidak membengkak.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // Kunci tanda tangan tetap dari GitHub Secrets (TO_KEYSTORE_B64, TO_STORE_PASSWORD), supaya APK baru
    // bisa dipasang di atas versi lama. Tanpa Secrets, APK ditandatangani kunci debug sementara.
    val signPass: String? = System.getenv("TO_STORE_PASSWORD")
    signingConfigs {
        create("fixed") {
            storeFile = file(System.getenv("TO_KEYSTORE_FILE") ?: "terjemah.keystore")
            storePassword = signPass
            keyAlias = System.getenv("TO_KEY_ALIAS") ?: "terjemah"
            keyPassword = System.getenv("TO_KEY_PASSWORD") ?: signPass
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (signPass != null) signingConfigs.getByName("fixed")
            else signingConfigs.getByName("debug")
        }
        debug {
            if (signPass != null) signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    // Pengenal ucapan offline (Vosk) — model Jepang diunduh app saat pertama kali dipakai.
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    // Terjemahan offline di HP (Google ML Kit).
    implementation("com.google.mlkit:translate:17.0.3")
}
