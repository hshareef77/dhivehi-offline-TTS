plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.hussainshareef.dhivehiofflinetts"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.hussainshareef.dhivehiofflinetts"
        minSdk = 26
        targetSdk = 35
        // CI sets VERSION_CODE to the run number so every new APK installs over the previous one.
        versionCode = (System.getenv("VERSION_CODE") ?: "2").toInt()
        versionName = "2.0." + (System.getenv("VERSION_CODE") ?: "0")
        // Where the converted voice model package (manifest.json + files) is published.
        buildConfigField("String", "MODEL_BASE_URL",
            "\"" + (System.getenv("MODEL_BASE_URL") ?: "https://huggingface.co/Hshareef77/csm-1b-dhivehi-5spk-onnx/resolve/main") + "\"")
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    buildFeatures { buildConfig = true }

    signingConfigs {
        // Fixed debug key committed to the repository so every CI debug build has the same
        // signature and can update the installed app without uninstalling.
        getByName("debug") {
            storeFile = file("debug-stable.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    val keyStorePath = System.getenv("ANDROID_KEYSTORE_PATH")
    if (!keyStorePath.isNullOrBlank()) {
        signingConfigs {
            create("permanentRelease") {
                storeFile = file(keyStorePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS") ?: "release"
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
        buildTypes {
            getByName("release") { signingConfig = signingConfigs.getByName("permanentRelease") }
        }
    }
    buildTypes { getByName("debug") { signingConfig = signingConfigs.getByName("debug") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs { useLegacyPackaging = false } }
}

dependencies {
    implementation("mv.dhivehi.tts:engine:1.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.1")
}
