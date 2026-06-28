plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.echowalk.shared"
    compileSdk = 35

    defaultConfig {
        minSdk = 31
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Exposed as `api` so dependent modules (and the app) get these types transitively.
    api("androidx.core:core-ktx:1.13.1")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // CameraX is owned by the shared FrameProvider; expose PreviewView for the app layout.
    val cameraxVersion = "1.3.4"
    api("androidx.camera:camera-core:$cameraxVersion")
    api("androidx.camera:camera-camera2:$cameraxVersion")
    api("androidx.camera:camera-lifecycle:$cameraxVersion")
    api("androidx.camera:camera-view:$cameraxVersion")

    // ExecuTorch runtime + LlmModule. Prefer the QNN-enabled AAR from Jainil's handoff when present
    // (includes libqnn_executorch_backend.so); otherwise fall back to Maven Central (CPU/XNNPACK only).
    val qnnAar = rootProject.file("libs/executorch-qnn.aar")
    if (qnnAar.exists()) {
        api(files(qnnAar))
    } else {
        api("org.pytorch:executorch-android:0.6.0")
    }
}
