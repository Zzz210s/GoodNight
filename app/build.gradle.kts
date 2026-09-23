import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.goodnight"
    compileSdk = 35
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.goodnight"
        minSdk = 26
        targetSdk = 35
        versionCode = 67
        versionName = "2.1.0"
    }
    val keystoreProps = rootProject.file("local.properties").let { f ->
        if (f.exists()) Properties().apply { f.inputStream().use { load(it) } } else null
    }
    val storeFilePath = keystoreProps?.getProperty("storeFile")
    signingConfigs {
        if (storeFilePath != null) {
            create("release") {
                storeFile = rootProject.file(storeFilePath)
                storePassword = keystoreProps!!.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        /**
         * v1.12.2:本机诊断用 —— `-PreleaseSignDebug=true` 时 debug 变体使用**正式签名**,
         * 因此可以直接覆盖已安装的正式版而**不丢数据**(debug keystore 与正式不一致,默认必须卸载)。
         * 默认(false)行为不变:debug 仍用 debug 签名。
         */
        debug {
            if (project.hasProperty("releaseSignDebug") && storeFilePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            // v1.11.0:重新启用 R8(minify + 资源收缩)。此前关闭是因为误判
            // (真机闪退实际源于 notification_actions.xml 的 RemoteViews 非法属性,已修);
            // 必要的 keep 规则见 proguard-rules.pro(WorkManager 反射、Room、通知/RemoteViews、服务)。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (storeFilePath != null) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }
    // v1.3 EN 对照:移除 zh-only 资源过滤(values-en 需随包;此前列表过滤导致 en 丢包)
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    // 体积:v1.11.0 额外剔除无用元数据(许可证/版本文件),并只保留真机需要的 ABI
    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/*.txt",
            "META-INF/*.md",
            "META-INF/*.version",
            "DebugProbesKt.bin",
            "kotlin-tooling-metadata.json",
        )
    }
    defaultConfig {
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    testOptions { unitTests { isIncludeAndroidResources = true } }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.documentfile)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core.ktx)
    // v2.1 Task 7 修复轮 2:布局类断言(chip 与大数字分属两行、详情两列等分对齐)需要真实的
    // Compose 测量/放置结果 —— 用 Robolectric 跑 ui-test 的 createComposeRule(),不靠肉眼截图。
    // ui-test-manifest 提供 ComponentActivity(仅 debug 变体合并进清单,不进发布包)。
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
