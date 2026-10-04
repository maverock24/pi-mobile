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
    // QR scanning for pairing. ZXing only: no Google Play services component, and the
    // camera is used solely to decode the code /pair draws.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
