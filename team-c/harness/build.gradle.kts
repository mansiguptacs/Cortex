plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.echowalk.harness"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.echowalk.harness"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-fallback"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        viewBinding = false
    }
}

dependencies {
    implementation(project(":core"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")

    // CameraX: preview + low-rate frame analysis. The YUV Y-plane is grayscale, which feeds
    // the CPU fallback embedder directly (no model / ExecuTorch needed).
    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
}
