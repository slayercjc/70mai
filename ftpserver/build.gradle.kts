plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.slayercjc.ftpserver"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.slayercjc.ftpserver"
        // AGP 8.x 最低支持 API 19（Android 4.4）；覆盖绝大多数 4.4+ 老后视镜
        minSdk = 19
        // targetSdk 保持 28：避免 Android 10+ 分区存储限制，同时兼容老系统
        targetSdk = 28
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        // 关键：jvmTarget 1.8 + 不使用 Java 8+ 运行时 API，保证 Android 4 的 Dalvik 能加载 dex
        jvmTarget = "1.8"
    }
}

dependencies {
    // 刻意不引入 AndroidX / 协程等依赖：
    // 1) 老后视镜系统 WebView/框架老旧，AndroidX 会增加兼容风险；
    // 2) 纯 android.app.* + java.net.* 足以实现 FTP Server，APK 更小。
}
