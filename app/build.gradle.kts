import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Properties
import java.util.zip.ZipFile

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

/** 版本号格式：三段纯数字。Gitee 的 tag 就是 `v` + 它，多一段少一段都会让比较出错。 */
val semanticVersion = Regex("""\d+\.\d+\.\d+""")

/** 首个可发布号；低于它说明 gradle.properties 被写坏或是从旧分支抄来的 */
val minVersionCode = 2

/** 逐段数值比较，缺的段按 0 算 —— 与运行时 UpdateInfo.kt 里的 compareVersions 同一口径 */
fun compareSemver(left: String, right: String): Int {
    val a = left.split('.').map { it.toInt() }
    val b = right.split('.').map { it.toInt() }
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return if (x > y) 1 else -1
    }
    return 0
}

/**
 * 版本号只有 gradle.properties 一个来源（`-P` 可显式覆盖，供 CI 出快照包用）。
 *
 * 这里刻意不给兜底默认值，也不拿 `GITHUB_RUN_NUMBER` 当 versionCode：
 * 前者漏配时会静默发出一个自称旧版本的包，Gitee 的 tag 已指向新版本，于是所有用户
 * 被提示"有更新"、装完号还是没变、更新弹窗从此无限循环 —— 而更新是唯一还能修好
 * 其他 bug 的通道；后者会让 CI 跑号一路涨到比正式发布包还大，CI 产物一旦外流，
 * 之后所有正常版本的包都因"降级"装不上（R5 F-C8 的原诉求只是"CI 产物彼此可覆盖
 * 安装"，那用 `-PVERSION_CODE=12` 显式传就够了）。
 */
val releaseVersionName = (project.findProperty("VERSION_NAME") as String?)?.trim().orEmpty()
    .also {
        require(semanticVersion.matches(it)) {
            "gradle.properties 的 VERSION_NAME 必须是三段数字（当前：\"$it\"）。" +
                "发布请走 `powershell -File release.ps1 X.Y.Z`，它会连 VERSION_CODE 一起改好。"
        }
    }
val releaseVersionCode = (project.findProperty("VERSION_CODE") as String?)?.trim()?.toIntOrNull()
    ?: throw GradleException(
        "gradle.properties 的 VERSION_CODE 必须是整数（当前：" +
            "${project.findProperty("VERSION_CODE")}）。发布请走 `powershell -File release.ps1 X.Y.Z`。",
    )
require(releaseVersionCode >= minVersionCode) {
    "VERSION_CODE=$releaseVersionCode 低得可疑：同 versionCode 的覆盖安装不算升级，" +
        "首个数到 2 为止（图标缓存那条史见 gradle.properties 注释）"
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
        // 版本号只来自 gradle.properties（读法与校验见本文件顶部）。
        // versionCode 必须逐次递增：启动器（HyperOS 的 com.miui.home）按
        // package+versionCode 缓存应用图标，同 versionCode 的 `install -r` 不算升级，
        // 换了 ic_launcher 也仍然显示旧图标。
        versionName = releaseVersionName
        versionCode = releaseVersionCode

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 只投中/英两种语言的资源。本应用自己没有 values-* 目录，
        // 带多语言的资源全部来自 AndroidX / Compose（material3 日期选择器、
        // work、emoji2 等），上百个语言里用户真正能触发的只有系统语言那一种；
        // 其他语言走默认（英文）回退，不会因为过滤器而找不到资源。
        androidResources {
            localeFilters += listOf("zh", "en")
        }
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

    packaging {
        resources {
            // 下面每一项都是**编译期/工具链元数据**，运行时没有任何读取方：
            // kotlin 的 builtins 表只有 kotlin-reflect 会读（本工程没有这个依赖），
            // .kotlin_module 给编译器做跨模块内联，.version 是每个依赖 7 字节的版本标记，
            // DebugProbesKt.bin 是协程调试 agent 的探针表。
            // 依赖许可证（META-INF 下各 androidx 包的 LICENSE.txt）一个都不动 ——
            // Kyant0 液态玻璃走的 Apache-2.0 归因要靠它。
            excludes += setOf(
                "kotlin/**",
                "kotlin-tooling-metadata.json",
                "META-INF/*.kotlin_module",
                "META-INF/*.version",
                "DebugProbesKt.bin",
            )
        }
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
    // okhttp 已移除：全部网络请求走 HttpURLConnection（BuaaApi）与页面内 fetch
    // （BuaaInPageFetcher），R8 早把它整个剥掉，留在包里只剩 41KB 的 publicsuffixes.gz。
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    debugImplementation(libs.androidx.compose.ui.tooling.pinned)
}

// ---------- 发布 ----------
//
// 两步走：`gradle :app:stampVersion -PreleaseVersion=0.2.0` 落盘版本号，
// 然后**新起一次**构建去 assembleRelease（同一趟配置阶段读不到刚写进去的值）。
// 日常只该用 `powershell -File release.ps1 X.Y.Z`，它按顺序把这两步和门禁一起跑完。

val releasePropsFile = rootProject.file("gradle.properties")

/** 与应用内 `RELEASES_PAGE_URL` 同源的发布页，只给脚本打印步骤用 */
val giteeReleasesUrl = "https://gitee.com/alyssumira/buaa-schedule/releases"

/** 上面那个页面的接口形态：创建发布与上传附件都打这里 */
val giteeApiReleasesUrl = "https://gitee.com/api/v5/repos/alyssumira/buaa-schedule/releases"

// 下面这几个值是发布任务的"配置期快照"。之所以要在配置期一次性取好：`doLast` 里再碰
// `project` / `rootProject` / `project.layout` 就是 "Invocation of Task.project at execution
// time"，Gradle 8 只是警告，Gradle 10 直接报错。发布脚本是修好其他 bug 的唯一通道，
// 不能挂在一个"未来某次升级 Gradle 就静默失效"的 API 上。
val releaseLog = project.logger
val releaseBuildDir = project.layout.buildDirectory.get().asFile
val releaseApkOutputDir = File(releaseBuildDir, "outputs/apk/release")
val releaseWorkDir = File(releaseBuildDir, "release-work")
val releaseDistDir = File(rootProject.projectDir, "dist")
val releaseDebugSign = project.findProperty("debugSign") != null
val releaseStampVersion = (project.findProperty("releaseVersion") as String?)?.trim().orEmpty()

/** build-tools 根目录；本机没有 SDK 时为 null（发布脚本会降级跳过 aapt2 核对） */
val releaseBuildToolsDir: File? = run {
    val props = Properties().apply {
        rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    val sdk = props.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME")
    sdk?.let { File(it, "build-tools") }?.takeIf { it.isDirectory }
}

/** 取 build-tools 里版本号最高的那个工具 */
fun buildToolsTool(name: String): File? =
    releaseBuildToolsDir?.listFiles()?.asSequence()
        ?.map { File(it, name) }
        ?.filter { it.isFile }
        ?.sortedByDescending { it.parentFile.name }
        ?.firstOrNull()

fun apksignerTool(): File? = buildToolsTool("apksigner.bat")

fun aapt2Tool(): File? = buildToolsTool("aapt2.exe") ?: buildToolsTool("aapt2")

/**
 * 写回 gradle.properties 里的两个版本号键，其余内容（含注释）原样保留。
 *
 * 只做行前缀替换、不重排：这个文件里每条属性都挂着"为什么是这个值"的注释，
 * 用 Properties.store 写回会把注释全部丢掉。
 */
tasks.register("stampVersion") {
    group = "release"
    description = "把 -PreleaseVersion=X.Y.Z 写进 gradle.properties，VERSION_CODE 自动 +1"
    notCompatibleWithConfigurationCache("执行期改写 gradle.properties")
    outputs.upToDateWhen { false }
    doLast {
        val requested = releaseStampVersion
        require(semanticVersion.matches(requested)) {
            "必须给 -PreleaseVersion=X.Y.Z（三段数字），当前是 \"$requested\""
        }
        val lines = releasePropsFile.readLines()
        fun currentValue(key: String): String? =
            lines.firstOrNull { it.startsWith("$key=") }?.substringAfter('=')?.trim()

        val currentName = currentValue("VERSION_NAME")
        val currentCode = currentValue("VERSION_CODE")?.toIntOrNull()
        require(currentName != null && currentCode != null) {
            "gradle.properties 里缺 VERSION_NAME 或 VERSION_CODE，先把它补回来再发布"
        }
        require(compareSemver(requested, currentName) > 0) {
            "新版本 $requested 必须高于当前 $currentName：号不涨就等于没发版 —— " +
                "Gitee 的 tag 会指着一个与已装版本相同的包，用户被提示\"有更新\"、装完还是旧号，" +
                "之后每次检查都会重复弹窗。"
        }
        // 只增不减，所以直接 +1；跨多次本地试发布也不会出现两个同号的包
        val nextCode = currentCode + 1
        val rewritten = lines.map { line ->
            when {
                line.startsWith("VERSION_NAME=") -> "VERSION_NAME=$requested"
                line.startsWith("VERSION_CODE=") -> "VERSION_CODE=$nextCode"
                else -> line
            }
        }
        // CRLF：这个文件本来就是这个换行风格，整体改成 LF 会把 diff 变成全文件重排
        releasePropsFile.writeText(rewritten.joinToString("\r\n") + "\r\n")
        logger.lifecycle("✅ gradle.properties：$currentName/$currentCode → $requested/$nextCode")
        logger.lifecycle("下一步构建才会读到新值；发布完成后请把 gradle.properties 与 tag v$requested 一起提交。")
    }
}

tasks.register("releasePackage") {
    group = "release"
    description = "把 release 产物收进 dist/buaa-schedule-<版本>.apk，并打印 Gitee 发布步骤"
    dependsOn("assembleRelease")
    outputs.upToDateWhen { false }
    doLast {
        val outputDir = releaseApkOutputDir
        val signed = File(outputDir, "app-release.apk")
        val unsigned = File(outputDir, "app-release-unsigned.apk")
        // 文件名就是签名结论：AGP 只在配齐签名配置时才产出 app-release.apk
        val source = when {
            signed.isFile -> signed
            unsigned.isFile && releaseDebugSign -> signWithDebugKey(unsigned)
            unsigned.isFile -> throw GradleException(
                "产物是 unsigned APK，装不上也发不出去。二选一：" +
                    "\n  · 正式发布：在 local.properties 配 buaa.keystore.path/.password/.alias/.keyPassword" +
                    "（四要素齐全后本任务会自动签名）" +
                    "\n  · 只想装机自测：加 -PdebugSign 用调试密钥自签（**绝不能发到 Gitee**，" +
                    "换回正式密钥时用户必须先卸载，课表会一起没）",
            )
            else -> throw GradleException("assembleRelease 没有产出 APK，检查 $outputDir")
        }
        val distDir = releaseDistDir.apply { mkdirs() }
        val artifact = File(distDir, "buaa-schedule-$releaseVersionName.apk")
        // 先把别的版本清掉：dist 里同时躺着 0.1.0 和 0.2.0 时，上传附件那一步
        // 拿错文件的概率几乎是必然，而且错了也不会红。
        distDir.listFiles()
            ?.filter {
                it.isFile && it.name.startsWith("buaa-schedule-") && it.name.endsWith(".apk") &&
                    it.name != artifact.name
            }
            ?.forEach { stale ->
                stale.delete()
                releaseLog.lifecycle("   已删掉上一轮产物 ${stale.name}（本轮版本是 $releaseVersionName）")
            }
        source.copyTo(artifact, overwrite = true)
        releaseLog.lifecycle("📦 ${artifact.path}（${artifact.length() / 1024} KB）")
        assertArtifactVersion(artifact)
        reportSigners(artifact)
        printPublishSteps(artifact)
    }
}

/**
 * 用本机调试密钥给 unsigned 产物自签。
 *
 * 存在的唯一理由：CI 与本地默认都没有发布密钥（不能入库），而 R8 之后的 release 包
 * 只能签名才装得上。所以这条路只服务"我自己要在真机上验一遍"，产物不得发布。
 */
fun signWithDebugKey(unsigned: File): File {
    val apksigner = apksignerTool()
        ?: throw GradleException("找不到 build-tools 里的 apksigner.bat，检查 local.properties 的 sdk.dir")
    val debugKeystore = File(System.getProperty("user.home"), ".android/debug.keystore")
    if (!debugKeystore.isFile) {
        throw GradleException("本机没有调试密钥 $debugKeystore（用 Android Studio 跑过一次任何 debug 包就会生成）")
    }
    // 产物写进 build/release-work，不写 outputs/apk/release：那是 AGP 独占的产物目录，
    // 它对里面文件的处理不由我们决定（今天一个手工签出来的残留就在下一轮构建后不见了）。
    // 中间产物放在那里，等于让"上一轮的文件还在不在"决定这一轮看起来成没成功。
    val workDir = releaseWorkDir.apply { mkdirs() }
    val target = File(workDir, "app-release-debugsigned.apk")
    target.delete()
    releaseLog.lifecycle("⚠️ 用调试密钥自签 $target：这份包只能自己装机验证，不得发布")
    runChecked(
        listOf(
            apksigner.absolutePath, "sign",
            "--ks", debugKeystore.absolutePath,
            "--ks-key-alias", "androiddebugkey",
            "--ks-pass", "pass:android",
            "--key-pass", "pass:android",
            "--out", target.absolutePath,
            unsigned.absolutePath,
        ),
        "apksigner 自签",
    )
    if (!target.isFile || target.length() == 0L) {
        throw GradleException(
            "apksigner 退出码为 0 但没有产出 $target —— 别信这一步：命令很可能根本没跑，" +
                "而 copyTo 会从产物目录里拿走上一轮的残留文件（providers.exec 就是惰性的）。",
        )
    }
    return target
}

/**
 * 同步跑一个外部命令并把输出交回；非 0 退出码直接把 stderr 摊开发异常。
 *
 * 不用 `project.exec`（8.11 起弃用、且 `@Suppress("DEPRECATION")` 压不掉它的提示），
 * 也不用 `providers.exec`（惰性：不查结果就**不会执行**，签名那步已经因此静默跳过过）。
 */
fun runChecked(command: List<String>, what: String): String {
    val process = ProcessBuilder(command).redirectErrorStream(false).start()
    val stdout = process.inputStream.bufferedReader().readText()
    val stderr = process.errorStream.bufferedReader().readText()
    val exit = process.waitFor()
    if (exit != 0) {
        throw GradleException(
            "$what 失败（退出码 $exit）：\n  命令：${command.joinToString(" ")}" +
                "\n  stdout：${stdout.trim().ifEmpty { "(空)" }}" +
                "\n  stderr：${stderr.trim().ifEmpty { "(空)" }}",
        )
    }
    return stdout
}

/**
 * 核对**包内**的版本号，而不是核对脚本以为自己写进去的那个。
 *
 * 触发它加进来的是一次红掉的构建：签名步骤（`providers.exec`）惰性、根本没执行，
 * 于是产物路径不存在，`copyTo` 抛 `NoSuchFileException`。这暴露了一个更普遍的问题 ——
 * 整条链路都在按"文件名对不对"判断成败，而文件名匹配不代表内容来自本轮构建。
 * APK 自己带的 `versionName` / `versionCode` 是唯一算数的证据，所以复制完立刻读它。
 */
fun assertArtifactVersion(apk: File) {
    val aapt2 = aapt2Tool()
    if (aapt2 == null) {
        releaseLog.warn("   未找到 aapt2，跳过包内版本核对（本机没有 build-tools 时才会这样）")
        return
    }
    val badging = runChecked(
        listOf(aapt2.absolutePath, "dump", "badging", apk.absolutePath),
        "aapt2 读取包信息",
    )
    val inApk = Regex("""versionName='([^']*)'""").find(badging)?.groupValues?.get(1)
    val codeInApk = Regex("""versionCode='(\d+)'""").find(badging)?.groupValues?.get(1)?.toIntOrNull()
    if (inApk != releaseVersionName || codeInApk != releaseVersionCode) {
        throw GradleException(
            "产物 ${apk.name} 里写的是 $inApk (code $codeInApk)，而本轮要求的版本是 " +
                "$releaseVersionName (code $releaseVersionCode)：复制到了一个不属于本轮构建的文件。" +
                "先把 build/release-work 与 dist 清空再重跑。",
        )
    }
    releaseLog.lifecycle("   包内版本核对通过：$inApk (code $codeInApk)")
}

/**
 * 打出包里的签名证书指纹：发出去的包与本机已装的是不是同一把密钥，只有这一行能一次看清。
 *
 * 不叫 `apksigner verify` —— 这里只想知道"有哪几张证书"，直接读 APK 里的 PKCS#7 签名块就行，
 * 少起一个进程也不受本机有没有 build-tools 影响。JDK 的 X.509 CertificateFactory 认 PKCS#7。
 * 只有 v2/v3 签名（没有 v1 的 META-INF 块）时这里看不到证书，属正常。
 */
fun reportSigners(apk: File) {
    ZipFile(apk).use { zip ->
        val blocks = zip.entries().asSequence()
            .filter { it.name.startsWith("META-INF/") && it.name.substringAfterLast('.', "") in SIGNER_BLOCKS }
            .toList()
        if (blocks.isEmpty()) {
            releaseLog.lifecycle("   包里无 v1（JAR）签名块：只有 v2/v3 时这里读不到证书，属正常")
            return
        }
        val factory = CertificateFactory.getInstance("X.509")
        for (block in blocks) {
            zip.getInputStream(block).use { input ->
                for (certificate in factory.generateCertificates(input)) {
                    val x509 = certificate as X509Certificate
                    val sha256 = MessageDigest.getInstance("SHA-256").digest(x509.encoded)
                        .joinToString("") { byte -> "%02X".format(byte) }
                    releaseLog.lifecycle("   签名证书 DN=${x509.subjectX500Principal.name} SHA-256=$sha256")
                }
            }
        }
    }
}

val SIGNER_BLOCKS = setOf("RSA", "DSA", "EC")

fun printPublishSteps(artifact: File) {
    val tag = "v$releaseVersionName"
    releaseLog.lifecycle(
        """
        |
        |—— 把 $tag 发出去（顺序别乱，最后一步之前用户都可能拿到半截链路）——
        |  1. 打 tag：git tag $tag && git push origin $tag
        |     用**轻量** tag，别加 -a：本仓库首个 commit 的 committer 是 Gitee 的
        |     noreply@gitee.com，附注 tag 会新建一个对象、逼服务端把整条历史翻一遍，
        |     「author 必须等于 committer」的钩子当场拒推（2026-09-16 实测 hook declined）
        |  2. Gitee 新建仓库发布：tag 选 $tag，标题写 $releaseVersionName，正文即更新说明
        |     （不想点网页就走接口：POST $giteeApiReleasesUrl，参数 tag_name/name/body，
        |     鉴权用带 projects 域的私人令牌。**target_commitish 也必须传**，填 $tag 所指 commit 的 sha；
        |     2026-09-16 实测：tag 已存在时少传它照样 400 "target_commitish is missing"）
        |  3. 上传附件：${artifact.name}
        |     接口：POST $giteeApiReleasesUrl/{release_id}/attach_files（multipart 字段名 file，
        |     成功回 201，附件 id 在响应里）
        |     要额外挂调试包自测的话，名字里必须含 debug（客户端选附件的顺序见 UpdateInfo.pickApkAsset）
        |     附件必须「公开可下载」——私有仓库或需登录时，接口给的是 HTML 登录页，
        |     应用会把它挡在安装器之外（校验见 UpdateInfo.apkIntegrityProblem）
        |  4. 自查发布结果（不需要装，命令行就能验）：
        |     curl -sI "$giteeReleasesUrl/download/$tag/${artifact.name}" | grep -i -E "^(HTTP|location)"
        |     curl -sL "$giteeReleasesUrl/download/$tag/${artifact.name}" -o t.apk && head -c 4 t.apk | xxd
        |     最后一跳应是 200，文件头应是 50 4B 03 04（zip）
        |  5. 在旧版本上真点一次「检查更新 → 下载 → 安装」，这是唯一能证明链路通的手段
        |
        |提醒：VERSION_NAME=$releaseVersionName / VERSION_CODE=$releaseVersionCode 已写进 gradle.properties，
        |      把这一行的改动与 tag 一起提交；漏提交会让下一次发布从旧号起步。
        """.trimMargin(),
    )
}
