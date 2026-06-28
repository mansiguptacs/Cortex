plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.echowalk"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.echowalk"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // ExecuTorch + QNN ship native libs; we run on the device's arm64 NPU.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        viewBinding = true
    }
    // Keep assets uncompressed so ExecuTorch can mmap them (Team B VLM models).
    androidResources {
        noCompress += listOf("pte", "bin", "json", "model", "jinja")
    }
    // QNN .so libraries go in app/src/main/jniLibs/arm64-v8a/.
    // The Hexagon DSP loads libQnnHtpV79Skel.so via FastRPC — must be extracted to disk.
    packaging {
        jniLibs.useLegacyPackaging = true
        jniLibs.pickFirsts += listOf(
            "**/libexecutorch.so",
            "**/libqnn_executorch_backend.so",
            "**/libc++_shared.so",
        )
    }
}

dependencies {
    // The integration module depends on the shared foundation + all three team modules.
    implementation(project(":shared"))
    implementation(project(":teama"))
    implementation(project(":teamb"))
    implementation(project(":teamc"))

    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.2")
    // ExecuTorch AAR (LlmModule + Module) native deps — must init SoLoader in EchoWalkApplication.
    implementation("com.facebook.soloader:soloader:0.10.5")
    implementation("com.facebook.fbjni:fbjni:0.6.0")
    // CameraX, ExecuTorch and coroutines come transitively from :shared (declared `api`).
}
