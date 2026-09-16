import java.util.Base64
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// 评审 P2：固定 Kotlin/Java 编译工具链为 JDK 21。
// 不固定时「用哪个 JDK 编译」取决于谁启动了 Gradle —— Android Studio 新版自带
// JBR 是 Java 25，而 Gradle 8.14.3 不支持在 Java 25 上运行（实测 daemon 直接失败，
// 只报一行 "25.0.2"）。固定后编译统一落在 JDK 21，字节码目标仍为 17（见下方
// compileOptions / jvmTarget）。settings.gradle.kts 未引入 foojay resolver，
// 工具链只做本机探测、不会联网下载，--offline 构建不受影响；
// CI 与本地开发均显式使用 JDK 21（见 .github/workflows/android.yml 与 README）。
kotlin {
    jvmToolchain(21)
}

android {
    namespace = "com.buaa.schedule"
    compileSdk = 36

    // 发布签名：优先 local.properties，其次环境变量（CI secrets）。
    // 四要素齐全且 keystore 文件存在才启用；否则 release 保持 unsigned，
    // 本地 `assembleRelease` 与 CI 的构建校验都不会因此失败。
    val signingProps = Properties().apply {
        rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    fun signingSecret(propKey: String, envKey: String): String? =
        signingProps.getProperty(propKey) ?: System.getenv(envKey)

    val keystoreBase64 = signingSecret("buaa.keystore.base64", "BUAA_KEYSTORE_BASE64")
    // CI 的 secrets 存不了二进制：给 base64 就解到 build/ 下再签（R5 F-C8）。
    // 只在配置阶段做一次，且仅在 base64 非空时触发；build/ 已被 .gitignore 覆盖。
    val keystorePath = keystoreBase64?.let { encoded ->
        val target = layout.buildDirectory.file("keystore/release.keystore").get().asFile
        target.parentFile.mkdirs()
        // 每次都覆写：跳过会让换掉的 secret 继续用旧 keystore 签名
        target.writeBytes(Base64.getMimeDecoder().decode(encoded))
        target.absolutePath
    } ?: signingSecret("buaa.keystore.path", "BUAA_KEYSTORE_PATH")
    val keystorePassword = signingSecret("buaa.keystore.password", "BUAA_KEYSTORE_PASSWORD")
    val keystoreAlias = signingSecret("buaa.keystore.alias", "BUAA_KEYSTORE_ALIAS")
    val keystoreKeyPassword = signingSecret("buaa.keystore.keyPassword", "BUAA_KEYSTORE_KEY_PASSWORD")
    val hasReleaseSigning = listOf(
        keystorePath, keystorePassword, keystoreAlias, keystoreKeyPassword,
    ).all { !it.isNullOrBlank() } && file(keystorePath!!).exists()

    if (!hasReleaseSigning) {
        logger.lifecycle(
            "⚠️ 未配置发布签名，release 产物将为 unsigned。" +
                "在 local.properties 配置 buaa.keystore.path/.password/.alias/.keyPassword，" +
                "或设置 BUAA_KEYSTORE_PATH 等环境变量即可启用；" +
                "CI 可用 BUAA_KEYSTORE_BASE64 直接传 keystore（R5 F-C8）。"
        )
    }

    defaultConfig {
        applicationId = "com.buaa.schedule"
        minSdk = 26
        targetSdk = 36
        // 版本号优先级：-PversionCode=12（手动覆盖）> GITHUB_RUN_NUMBER（CI 自增）> 1。
        // 此前默认恒为 1，CI 每次产出的 APK 版本号都一样，无法覆盖安装（R5 F-C8）。
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull()
            ?: System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
            ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = keystoreAlias
                keyPassword = keystoreKeyPassword
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            // R5 F-C6：本工程没有任何 `getIdentifier` / 按名字反射取资源的代码（已全仓核对），
            // 资源都经 R.* 或 XML 引用，因此可以安全地跟着 R8 一起收缩。
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
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
        buildConfig = true
    }

    sourceSets {
        // Room schema 历史版本作为迁移测试的资产
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    lint {
        // CI 会跑 lintDebug。这里不引入 baseline：把 error 级问题真正修掉，
        // 让 lint 保持"新引入的 error 一定拦得住"的信号强度。
        checkDependencies = false
        abortOnError = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":kyant-backdrop"))
    implementation(platform(libs.androidx.compose.bom))
    // R5 F-C1：整棵 Compose 运行时树显式钉到 composePinned。
    // 此前只有 ui / foundation 两个模块钉了版本，runtime / animation / ui-* 全靠
    // Gradle「取高版本」被顺带抬上来 —— BOM 或 activity-compose 任一升级都会
    // 悄悄改变实际参与编译的版本，出问题时报的栈也对不上。
    constraints {
        implementation(libs.androidx.compose.runtime.pinned)
        implementation(libs.androidx.compose.runtime.saveable.pinned)
        implementation(libs.androidx.compose.animation.pinned)
        implementation(libs.androidx.compose.animation.core.pinned)
        implementation(libs.androidx.compose.foundation.layout.pinned)
        implementation(libs.androidx.compose.ui.graphics.pinned)
        implementation(libs.androidx.compose.ui.text.pinned)
        implementation(libs.androidx.compose.ui.unit.pinned)
        implementation(libs.androidx.compose.ui.util.pinned)
        implementation(libs.androidx.compose.ui.geometry.pinned)
        implementation(libs.androidx.compose.ui.tooling.pinned)
        implementation(libs.androidx.compose.ui.tooling.preview.pinned)
        // material3 / material-ripple 是照 Compose 1.7.x 编译的那一族，离线仓库里没有
        // 配套 1.11.3 的版本；这里钉住是故意让"混搭"写在图上，而不是让人猜。
        implementation(libs.androidx.compose.material3)
        implementation(libs.androidx.compose.material.ripple)
    }
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui.pinned)
    implementation(libs.androidx.compose.foundation.pinned)
    implementation(libs.androidx.compose.ui.graphics.pinned)
    implementation(libs.androidx.compose.ui.tooling.preview.pinned)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    debugImplementation(libs.androidx.compose.ui.tooling.pinned)
}
