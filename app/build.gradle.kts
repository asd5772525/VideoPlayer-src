plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// 版本号与远程地址均可由 CI 通过 -P 参数注入（见 .github/workflows/build.yml）
fun prop(name: String): String? = (project.findProperty(name) as String?)?.takeIf { it.isNotBlank() }

val appVersionCode = prop("versionCode")?.toIntOrNull() ?: 1
val appVersionName = prop("versionName") ?: "1.0.0"
// update.json 地址，多个用英文逗号分隔，按顺序作为镜像兜底（CI 自动注入 raw.githubusercontent + jsDelivr）
val updateUrls = prop("updateUrls") ?: ""

val keystoreFile = System.getenv("KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "com.vplayer.app"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.vplayer.app"
        minSdk = 24
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "UPDATE_URL", "\"$updateUrls\"")
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile = keystoreFile
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystoreFile != null) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    // 按 CPU 架构分包 + 通用包（universal）。v1.2 起带了 IJK 内核（FFmpeg），每个 ABI 约 5~6MB native 库，
    // 分包（arm64 约 8.4MB）比通用包（约 19.5MB）小很多，在线更新时 App 会自动下载匹配自己 CPU 的分包。
    // 本地调试想只出一个包：./gradlew assembleDebug -Pvp.abiSplit=false
    splits {
        abi {
            isEnable = prop("vp.abiSplit") != "false"
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    // CI 正式包跳过 lintVital（-Pvp.skipLintVital=true），本地默认仍然检查
    lint {
        checkReleaseBuilds = prop("vp.skipLintVital") != "true"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.all {
            // 模拟服务器地址（scripts/ 外部的 mock_maccms.py），未启动时相关测试自动跳过
            it.systemProperty("mock.base", System.getenv("MOCK_BASE") ?: "")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // v1.2：IJK 的 .so 压缩存放（安装时解压）。arm64 包约 8MB；不压缩约 16MB（安装后少占约 13MB 空间、首次加载略快）。
        // 用户主要通过 GitHub 在线更新，下载体积更重要，所以默认压缩；想要不压缩：-Pvp.compressNative=false
        jniLibs {
            useLegacyPackaging = prop("vp.compressNative") != "false"
        }
    }
}

// ---- 构建时把仓库根目录的 config.json 打包进 assets（运行时不再联网拉取配置） ----
val configAssetsDir = layout.buildDirectory.dir("generated/configAssets")
val copyAppConfig by tasks.registering(Copy::class) {
    from(rootProject.file("config.json"))
    into(configAssetsDir)
}
android.sourceSets.getByName("main").assets.srcDir(configAssetsDir)
tasks.named("preBuild") { dependsOn(copyAppConfig) }

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    // 安装时按 src/main/baseline-prof.txt 预编译热点代码（手写规则，见该文件说明）
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-database:$media3")

    // v1.2 备选内核 IJK（ijkplayer + FFmpeg）：GSYVideoPlayer 发布在 Maven Central 的构建，只取 Java 接口 + 3 个 ABI 的 .so
    // （每个 ABI 压缩后约 5.3~6.2MB；x86 32 位 / armv5 不带，这些设备会自动退回系统播放器内核）
    val gsy = "14.1.0"
    implementation("io.github.carguo:gsyijkjava:1.0.0")
    implementation("io.github.carguo:gsyvideoplayer-arm64:$gsy")
    implementation("io.github.carguo:gsyvideoplayer-armv7a:$gsy")
    implementation("io.github.carguo:gsyvideoplayer-x64:$gsy")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    val room = "2.6.1"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    implementation("androidx.room:room-paging:$room")
    ksp("androidx.room:room-compiler:$room")

    implementation("androidx.paging:paging-runtime-ktx:3.3.5")
    implementation("androidx.paging:paging-compose:3.3.5")

    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    testImplementation("junit:junit:4.13.2")
    // 本地 JVM 测试用的 XmlPullParser 实现（android.util.Xml 在本地测试中不可用）
    testImplementation("net.sf.kxml:kxml2:2.3.0")
}
