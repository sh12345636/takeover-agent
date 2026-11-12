plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "takeover.agent"
    compileSdk = 35
    ndkVersion = "27.0.12077973"   // 与 CI sdkmanager 安装版本锁定一致

    defaultConfig {
        applicationId = "takeover.agent"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-p1"

        // 双 ABI：arm64 主线 + v7a 兼容老机
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=none")
                cFlags += "-DALIGN_16KB"
            }
        }
    }

    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // LSPosed 模块编译期只需要 API stub（运行期由框架提供）
    compileOnly("de.robv.android.xposed:api:82")
}
