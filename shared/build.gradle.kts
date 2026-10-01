plugins {
  kotlin("multiplatform")
  id("com.android.library")
  id("org.jetbrains.kotlin.plugin.compose")
  id("org.jetbrains.compose")
  kotlin("plugin.serialization")
}

kotlin {
  androidTarget()

  sourceSets {
    val commonMain by getting {
      dependencies {
        implementation(compose.runtime)
        implementation(compose.foundation)
        implementation(compose.material)
        implementation(compose.material3)
        implementation(compose.materialIconsExtended)
        implementation(compose.ui)

        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")

        val ktorVersion = "2.3.12"
        implementation("io.ktor:ktor-client-core:$ktorVersion")
        implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
        implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

        // 毛玻璃（glassmorphism）效果：底栏浮岛的背景模糊。0.7.3 是兼容 CMP 1.6.x 的最新版。
        val hazeVersion = "0.7.3"
        implementation("dev.chrisbanes.haze:haze:$hazeVersion")
        implementation("dev.chrisbanes.haze:haze-materials:$hazeVersion")
      }
    }

    val commonTest by getting {
      dependencies {
        implementation(kotlin("test"))
      }
    }

    val androidMain by getting {
      dependencies {
        val ktorVersion = "2.3.12"
        implementation("io.ktor:ktor-client-cio:$ktorVersion")
        implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
        implementation("io.ktor:ktor-client-logging:$ktorVersion")
        implementation("io.ktor:ktor-client-websockets:$ktorVersion")
        implementation("io.ktor:ktor-server-core:$ktorVersion")
        implementation("io.ktor:ktor-server-cio:$ktorVersion")
        implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
        implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
        implementation("org.mozilla:rhino:1.7.14")
        implementation("com.squareup.okhttp3:okhttp:4.12.0")
        implementation("io.coil-kt:coil-compose:2.7.0")
        implementation("com.google.zxing:core:3.5.4")
        implementation("com.journeyapps:zxing-android-embedded:4.3.0")
        implementation("app.cash.quickjs:quickjs-android:0.9.2")
        // 受 AGP 8.5.2 限制：core-ktx 1.16.0+ 要求 AGP 8.7+、1.17.0 要求 8.9.1，
        // 故锁定 1.15.0（项目基线，稳定且与 AGP 8.5.2 兼容）。
        implementation("androidx.core:core-ktx:1.15.0")
        implementation("androidx.activity:activity-compose:1.10.1")

        // 暂不升级：media3-ui 1.11.1 的 Gradle module 元数据里带 kotlin-stdlib:2.2.10，
        // 而本工程 Kotlin 编译器是 2.0.21，会直接报
        // "Module was compiled with an incompatible version of Kotlin ... metadata is 2.2.0"。
        // 1.10.1 依赖的是 kotlin-stdlib:2.0.20，与本工程一致，故锁定 1.10.1。
        // 结论：media3 想升 1.11.x 必须先整体升级 Kotlin/CMP/AGP 工具链。
        val media3Version = "1.10.1"
        implementation("androidx.media3:media3-exoplayer:$media3Version")
        implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
        implementation("androidx.media3:media3-datasource:$media3Version")
        implementation("androidx.media3:media3-ui:$media3Version")
      }
    }
  }
}

android {
  namespace = "dtv.mobile.shared"
  compileSdk = 36
  buildToolsVersion = "36.1.0"

  defaultConfig {
    minSdk = 24
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    // minSdk 低于 26 时 java.time 等 API 需要脱糖
    isCoreLibraryDesugaringEnabled = true
  }
}

dependencies {
  coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
