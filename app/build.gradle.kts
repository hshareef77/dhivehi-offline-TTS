plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.hussainshareef.dhivehiofflinetts"
    compileSdk = 35
    defaultConfig { applicationId = "com.hussainshareef.dhivehiofflinetts"; minSdk = 26; targetSdk = 35; versionCode = 5; versionName = "0.5-dhivehi-phonemes" }

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
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}


dependencies {
    testImplementation("junit:junit:4.13.2")
}


/**
 * Optional direct OpenEVV JNI build. The checked-in source reference deliberately
 * omits language/acoustic data. To build a working native engine, supply those
 * files locally under third_party/eloquence-reference/native/openevv before
 * enabling -PnativeEloquence=true. Do not commit private voice resources.
 */
val nativeEloquence = providers.gradleProperty("nativeEloquence")
    .map { it.equals("true", ignoreCase = true) }.orElse(false)
val nativeOutput = layout.buildDirectory.dir("nativeEloquence/jniLibs")
android.sourceSets.getByName("main").jniLibs.srcDir(nativeOutput)

if (nativeEloquence.get()) {
    val nativeRoot = rootProject.file("third_party/eloquence-reference/native")
    val buildScript = nativeRoot.resolve("build-native.sh")
    val languageModule = nativeRoot.resolve("openevv/lang/enus")
    require(buildScript.exists() && languageModule.exists()) {
        "Native Eloquence source and language assets are required for native build."
    }
    val buildEloquence by tasks.registering(Exec::class) {
        group = "build"
        description = "Compile actual Eloquence JNI synthesis engine for arm64 Android"
        commandLine("bash", buildScript.absolutePath)
        environment("ABIS", "arm64-v8a")
        environment("RULES", "c")
        environment("LANGS", "lang/enus")
        environment("OUT", nativeOutput.get().asFile.absolutePath)
        environment("ANDROID_NDK_HOME",
            System.getenv("ANDROID_NDK_HOME")
                ?: "${System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")}/ndk/26.1.10909125")
        outputs.dir(nativeOutput)
    }
    tasks.named("preBuild").configure { dependsOn(buildEloquence) }
}
