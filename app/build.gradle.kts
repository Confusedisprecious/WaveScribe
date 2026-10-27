plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.zanzan.voicetranscribe"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zanzan.voicetranscribe"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.1.0"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 用 debug 签名，方便直接安装体验
            signingConfig = signingConfigs.getByName("debug")
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
    }
}

dependencies {
    // sherpa-onnx 官方安卓 AAR（含各平台 libsherpa-onnx-jni.so）
    implementation(files("libs/sherpa-onnx-2.0.0.aar"))

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // 模型整包为 tar.bz2，需要解压
    implementation("org.apache.commons:commons-compress:1.26.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
