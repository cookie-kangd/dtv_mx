plugins {
  id("com.android.application")
  kotlin("android")
  id("org.jetbrains.kotlin.plugin.compose")
  id("org.jetbrains.compose")
}

import java.util.Properties

val keystoreProperties = Properties()
val keystorePropertiesFile = file("keystore.properties")
val hasReleaseKeystore = keystorePropertiesFile.exists().also { exists ->
  if (exists) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
  }
}

android {
  namespace = "dtv.mobile.android"
  compileSdk = 36
  buildToolsVersion = "36.1.0"

  defaultConfig {
    applicationId = "dtv.mobile"
    minSdk = 24
    targetSdk = 36
    versionCode = 46
    versionName = "0.2.4"

    // 只打包真机使用的 ARM 架构。抖音签名依赖的 libquickjs.so 原本会打包
    // 4 种 ABI（约 3.05MB），其中 x86 / x86_64 合计 1.73MB 在手机上永远用不到。
    ndk {
      abiFilters += listOf("armeabi-v7a", "arm64-v8a")
    }
  }

  buildFeatures { compose = true }

  packaging {
    resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    // minSdk 低于 26 时 java.time 等 API 需要脱糖
    isCoreLibraryDesugaringEnabled = true
  }

  kotlinOptions {
    jvmTarget = "17"
  }

  signingConfigs {
    create("release") {
      if (hasReleaseKeystore) {
        storeFile = file(keystoreProperties.getProperty("storeFile"))
        storePassword = keystoreProperties.getProperty("storePassword")
        keyAlias = keystoreProperties.getProperty("keyAlias")
        keyPassword = keystoreProperties.getProperty("keyPassword")
      }
    }
  }

  buildTypes {
    getByName("release") {
      // 不开启 R8 压缩：开启后斗鱼直播间出现黑屏 + "N5.0" 白字，
      // 为保证功能稳定，这里保持关闭（体积换稳定性）。
      isMinifyEnabled = false
      isShrinkResources = false
      signingConfig = signingConfigs.getByName("release")
    }
  }
}

dependencies {
  coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

  implementation(project(":shared"))

  implementation("androidx.activity:activity-compose:1.10.1")
  implementation("androidx.core:core-ktx:1.15.0")
  implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
  // 提供 ProcessLifecycleOwner：用于监听整个 App 进入/退出前台，实现「退出时清理缓存」
  implementation("androidx.lifecycle:lifecycle-process:2.9.4")

  // DtvApplication 的全局图片加载器（ImageLoaderFactory）需要 coil 核心
  implementation("io.coil-kt:coil-compose:2.7.0")

  implementation(compose.material3)
  implementation(compose.ui)
  implementation(compose.foundation)
  implementation(compose.runtime)
  implementation(compose.materialIconsExtended)
}
