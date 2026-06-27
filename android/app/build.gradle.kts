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
    // QNN .so libraries go in app/src/main/jniLibs/arm64-v8a/.
    // .pte model files go in app/src/main/assets/ (or are pushed to /data/local/tmp during dev).
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
    // CameraX, ExecuTorch and coroutines come transitively from :shared (declared `api`).
}
