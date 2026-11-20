// Pure Kotlin/JVM speech engine shared by the Android app and desktop/CI tests.
// It uses the ONNX Runtime Java API (package ai.onnxruntime); the Android app supplies
// onnxruntime-android at runtime, tests use the desktop onnxruntime artifact.
plugins {
    kotlin("jvm") version "2.0.21"
}

group = "mv.dhivehi.tts"
version = "1.0"

val ortVersion = "1.31.1"

java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    compileOnly("com.microsoft.onnxruntime:onnxruntime:$ortVersion")
    compileOnly("org.json:json:20240303") // provided by the Android platform
    testImplementation("com.microsoft.onnxruntime:onnxruntime:$ortVersion")
    testImplementation("org.json:json:20240303")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "3g"
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    // Paths to a converted model package and reference data, provided by CI or locally.
    listOf("csm.model.dir", "csm.reference.dir", "csm.tokenizer.cases", "csm.output.dir", "omni.model.dir", "omni.reference.dir").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}
