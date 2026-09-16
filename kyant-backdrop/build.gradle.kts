plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.kyant.backdrop"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // R5 F-C12：版本号统一走版本目录（此前这里是三处硬编码 1.11.3，
    // 与 :app 的 composePinned 各写一套，升级时必然漂移）
    api(libs.androidx.compose.ui.pinned)
    implementation(libs.androidx.compose.ui.graphics.pinned)
    implementation(libs.androidx.compose.foundation.pinned)
}
