plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun envOrNull(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }
fun envOrDefault(name: String, fallback: String): String = envOrNull(name) ?: fallback

val signingVars = listOf(
    "ANDROID_KEYSTORE_PATH",
    "ANDROID_KEYSTORE_PASSWORD",
    "ANDROID_KEY_ALIAS",
    "ANDROID_KEY_PASSWORD"
)
val hasReleaseSigning = signingVars.all { envOrNull(it) != null }

android {
    namespace = "com.maverock24.pimobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.maverock24.pimobile"
        minSdk = 26
        targetSdk = 35
        versionCode = envOrDefault("ANDROID_VERSION_CODE", "1").toIntOrNull() ?: 1
        versionName = envOrDefault("ANDROID_VERSION_NAME", "0.0.1")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(envOrNull("ANDROID_KEYSTORE_PATH")!!)
                storePassword = envOrNull("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = envOrNull("ANDROID_KEY_ALIAS")
                keyPassword = envOrNull("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // The BouncyCastle jars each carry the same OSGi manifest under the
        // versioned directory, and the packager refuses to choose one. It is
        // OSGi metadata and is not used on Android.
        resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Talk playback. media3-exoplayer decodes the MP3 and media3-session pairs it
    // with a MediaSessionService, which is what keeps a 30 minute talk playing
    // with the screen off. Apache-2.0, so nothing GPL enters the app.
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    // QR scanning for pairing. ZXing only: no Google Play services component, and the
    // camera is used solely to decode the code /pair draws.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    // Web access: the phone serves one page to another computer on the WLAN.
    // Ktor CIO is the HTTP layer (Kotlin-first, coroutine-based, maintained).
    // The CIO engine refuses a TLS connector of its own, so the WLAN face
    // terminates TLS in WebTlsFront (JSSE) and hands the decrypted bytes to CIO
    // on loopback. 3.0.2 is the newest Ktor built with this app's Kotlin 2.0.21;
    // Ktor 3.1 and later need a newer compiler.
    implementation("io.ktor:ktor-server-cio:3.0.2")
    // Assembles the self-signed X.509 certificate that the Android keystore key
    // signs. The platform has no public certificate-builder API, so this one
    // dependency earns its place. bcprov is pulled in transitively but is named
    // here because the assembly code uses its ASN.1 types directly.
    implementation("org.bouncycastle:bcpkix-jdk18on:1.81.1")
    implementation("org.bouncycastle:bcprov-jdk18on:1.81.1")
    // Unit tests only; nothing here reaches the APK.
    testImplementation("junit:junit:4.13.2")
}
