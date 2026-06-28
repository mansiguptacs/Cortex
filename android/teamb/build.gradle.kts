plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.echowalk.teamb"
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
    implementation(project(":shared"))
    implementation("androidx.appcompat:appcompat:1.7.0") // for the isolation harness Activity
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4") // lifecycleScope for async describe
    // Team B VLM glue goes here; .pte + tokenizer live in the app's assets/.

    // Fast iteration: pure-Kotlin logic is unit-tested on the JVM (no device, sub-second).
    testImplementation("junit:junit:4.13.2")
}
